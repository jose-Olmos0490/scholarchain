package scholarchain.services.query

import scholarchain.core.ids.StableIds
import scholarchain.core.model.{Provenance, SourceType}
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.rag.workflow.{AnswerRequest, WorkflowFailure}
import scholarchain.services.config.{RuntimeLimits, ServiceSettings}
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.http.{IdGenerator, RequestContext, ServiceClock}
import scholarchain.services.resilience.{BoundedExecutor, CircuitBreaker, Retrier}
import scholarchain.services.security.Principal
import scholarchain.storage.common.StorageError
import scholarchain.storage.dynamo.{DynamoSchemas, QuerySessionRecord, QuerySessions}

/** What a submit did. `replayed` is true when an Idempotency-Key matched an earlier request. */
final case class SubmitOutcome(job: JobRecord, replayed: Boolean, accepted: Boolean)

/** The query use case: validate, record the idempotency session and the job, run the workflow
  * synchronously or asynchronously. It contains no retrieval, ranking or synthesis: the
  * [[QueryEngine]] port is the Graph-RAG runtime.
  */
final class QueryService(
    engine: QueryEngine,
    sessions: QuerySessions,
    jobs: JobStore,
    executor: BoundedExecutor,
    breaker: CircuitBreaker,
    retrier: Retrier,
    settings: ServiceSettings,
    limits: RuntimeLimits,
    clock: ServiceClock,
    ids: IdGenerator
):
  private val KeyChars = "[A-Za-z0-9_.:-]+".r

  def submit(
      ctx: RequestContext,
      principal: Principal,
      idempotencyKey: Option[String],
      body: Array[Byte]
  ): Either[ApiError, SubmitOutcome] =
    for
      key <- checkKey(idempotencyKey)
      request <- QueryRequest.parse(body, settings.http, limits)
      fingerprint = request.fingerprint
      effKey = key.map(k => s"${principal.id}:$k")
      outcome <- effKey.flatMap(k => lookup(k, fingerprint, principal)) match
        case Some(Right(existing)) => resume(ctx, existing, request, effKey.get, fingerprint)
        case Some(Left(e)) => Left(e)
        case None => start(ctx, principal, request, effKey, fingerprint)
    yield outcome

  // ---- idempotency --------------------------------------------------------------------------

  private def checkKey(key: Option[String]): Either[ApiError, Option[String]] = key match
    case None => Right(None)
    case Some(k)
        if k.length >= settings.http.idempotencyKeyMinChars &&
          k.length <= settings.http.idempotencyKeyMaxChars && KeyChars.matches(k) =>
      Right(Some(k))
    case Some(_) =>
      Left(
        ApiError.badRequest(
          "INVALID_IDEMPOTENCY_KEY",
          s"the Idempotency-Key must be ${settings.http.idempotencyKeyMinChars} to ${settings.http.idempotencyKeyMaxChars} characters of A-Z a-z 0-9 _ . : -"
        )
      )

  /** `Some(Right(job))`: the key was used by an identical request. `Some(Left(409))`: it was used
    * by a different one. `None`: the key is new.
    */
  private def lookup(
      effKey: String,
      fingerprint: String,
      principal: Principal
  ): Option[Either[ApiError, JobRecord]] =
    sessions.queryIdForIdempotencyKey(effKey) match
      case Left(e) => Some(Left(storageError(e)))
      case Right(None) => None
      case Right(Some(queryId)) =>
        sessions.get(queryId) match
          case Left(e) => Some(Left(storageError(e)))
          case Right(Some(s)) if s.requestFingerprint == fingerprint =>
            jobs.findByQueryId(queryId) match
              case Some(j) if j.principalId == principal.id => Some(Right(j))
              case _ =>
                Some(
                  Left(
                    ApiError.of(
                      ErrorCode.Conflict,
                      "IDEMPOTENCY_RECORD_UNAVAILABLE",
                      "the earlier request with this Idempotency-Key has no job record here"
                    )
                  )
                )
          case Right(_) =>
            Some(
              Left(
                ApiError.of(
                  ErrorCode.Conflict,
                  "IDEMPOTENCY_KEY_REUSED",
                  "the Idempotency-Key was already used with a different request"
                )
              )
            )

  /** A replay returns the stored job. A failed job whose error is retryable runs again. */
  private def resume(
      ctx: RequestContext,
      job: JobRecord,
      request: QueryRequest,
      effKey: String,
      fingerprint: String
  ): Either[ApiError, SubmitOutcome] =
    if job.state == JobState.Failed && job.error.exists(_.retryable) then
      val restarted = jobs
        .restart(job.jobId)(
          _.copy(
            state = JobState.Queued,
            stage = "queued",
            error = None,
            finishedAtMillis = None,
            updatedAtMillis = clock.nowMillis,
            attempts = job.attempts + 1
          )
        )
        .getOrElse(job)
      run(ctx, restarted, request, Some(effKey), fingerprint).map(o => o.copy(replayed = true))
    else Right(SubmitOutcome(job, replayed = true, accepted = !job.state.terminal))

  // ---- a new query --------------------------------------------------------------------------

  private def start(
      ctx: RequestContext,
      principal: Principal,
      request: QueryRequest,
      effKey: Option[String],
      fingerprint: String
  ): Either[ApiError, SubmitOutcome] =
    for
      queryId <- StableIds
        .queryId(s"${effKey.getOrElse(ids.next())}|$fingerprint")
        .left
        .map(_ => ApiError.internal)
      _ <- createSession(queryId, effKey, fingerprint, principal, request)
      now = clock.nowMillis
      job = JobRecord(
        jobId = s"job-${ids.next()}",
        queryId = queryId,
        principalId = principal.id,
        traceId = ctx.traceId,
        state = JobState.Queued,
        stage = "queued",
        createdAtMillis = now,
        startedAtMillis = None,
        finishedAtMillis = None,
        updatedAtMillis = now,
        attempts = 1,
        error = None,
        result = None,
        trace = Vector.empty
      )
      _ <-
        if jobs.create(job) then Right(())
        else Left(ApiError.internal)
      out <- run(ctx, job, request, effKey, fingerprint)
    yield out

  private def createSession(
      queryId: String,
      effKey: Option[String],
      fingerprint: String,
      principal: Principal,
      request: QueryRequest
  ): Either[ApiError, Unit] =
    val session = QuerySessionRecord(
      DynamoSchemas.QuerySession,
      queryId,
      effKey,
      fingerprint,
      clock.nowMillis / 1000L,
      Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        queryId,
        settings.serving.snapshot,
        settings.serving.runId,
        None,
        None,
        None,
        Vector.empty
      )
    )
    sessions.create(session).left.map {
      case StorageError.DuplicateIdempotencyKey(_, _) =>
        // two requests with one key raced: the loser reports a conflict and retries
        ApiError
          .of(
            ErrorCode.Conflict,
            "IDEMPOTENCY_KEY_IN_USE",
            "another request with this Idempotency-Key is being recorded; retry"
          )
          .copy(retryable = true)
      case other => storageError(other)
    }

  // ---- execution ----------------------------------------------------------------------------

  private def run(
      ctx: RequestContext,
      job: JobRecord,
      request: QueryRequest,
      effKey: Option[String],
      fingerprint: String
  ): Either[ApiError, SubmitOutcome] =
    val answerRequest = AnswerRequest(
      traceId = ctx.traceId,
      queryId = job.queryId,
      runId = settings.serving.runId,
      snapshot = settings.serving.snapshot,
      question = request.question,
      fields = request.fields,
      idempotencyKey = effKey,
      requestFingerprint = fingerprint
    )
    request.mode match
      case QueryMode.Async =>
        if executor.submit(execute(job.jobId, answerRequest)) then
          Right(
            SubmitOutcome(jobs.get(job.jobId).getOrElse(job), replayed = false, accepted = true)
          )
        else
          val busy =
            ApiError.of(ErrorCode.Unavailable, "OVERLOADED", "the service is busy; try again")
          fail(job.jobId, busy, "queued")
          Left(busy.copy(retryAfterSeconds = Some(1L)))
      case QueryMode.Sync =>
        executor.callWithin(limits.synchronousDeadlineMs)(execute(job.jobId, answerRequest)) match
          case Left(e) =>
            fail(job.jobId, e, "workflow")
            Left(e)
          case Right(_) =>
            val done = jobs.get(job.jobId).getOrElse(job)
            done.error match
              case Some(err) => Left(err.toApi)
              case None => Right(SubmitOutcome(done, replayed = false, accepted = false))

  /** Runs the workflow and records the outcome. Returns the failure so a caller can answer it. */
  private def execute(jobId: String, request: AnswerRequest): Either[ApiError, Unit] =
    jobs.update(jobId)(j =>
      j.copy(
        state = JobState.Running,
        stage = "workflow",
        startedAtMillis = Some(clock.nowMillis),
        updatedAtMillis = clock.nowMillis
      )
    )
    var last: Option[WorkflowFailure] = None
    // the breaker wraps the whole retried call: one request is one success or one failure, and
    // an open circuit is never retried
    val result = breaker.guard {
      retrier(limits.maxWorkflowRetries) {
        engine.answer(request) match
          case Right(r) => Right(r)
          case Left(f) =>
            last = Some(f)
            Left(FailureMapper.toApi(f))
      }
    }
    result match
      case Right(r) =>
        jobs.update(jobId)(j =>
          j.copy(
            state = JobState.Succeeded,
            stage = "done",
            finishedAtMillis = Some(clock.nowMillis),
            updatedAtMillis = clock.nowMillis,
            result = Some(ResultView.of(r)),
            trace = r.trace
          )
        )
        Right(())
      case Left(e) =>
        fail(jobId, e, last.map(_.stage).getOrElse("workflow"), last.map(_.trace))
        Left(e)

  private def fail(
      jobId: String,
      e: ApiError,
      stage: String,
      trace: Option[Vector[scholarchain.rag.workflow.StageTrace]] = None
  ): Unit =
    jobs.update(jobId)(j =>
      j.copy(
        state = JobState.Failed,
        stage = stage,
        finishedAtMillis = Some(clock.nowMillis),
        updatedAtMillis = clock.nowMillis,
        error = Some(JobError.of(e)),
        trace = trace.getOrElse(j.trace)
      )
    )
    ()

  private def storageError(e: StorageError): ApiError = StorageErrors.toApi(e)

/** Maps a storage failure to the typed error model; a storage message is never repeated. */
object StorageErrors:
  def toApi(e: StorageError): ApiError = e match
    case _: StorageError.Timeout =>
      ApiError.of(ErrorCode.GatewayTimeout, "STORE_TIMEOUT", "a store did not answer in time")
    case _: StorageError.Transient =>
      ApiError.of(ErrorCode.BadGateway, "STORE_UNAVAILABLE", "a store was unavailable")
    case _: StorageError.NotFound => ApiError.notFound("the record was not found")
    case _: StorageError.LimitExceeded =>
      ApiError.unprocessable("LIMIT_OUT_OF_BOUNDS", "a request bound was exceeded")
    case _: StorageError.InvalidRecord =>
      ApiError.badRequest("INVALID_REQUEST", "the request was refused by the store")
    case _: StorageError.Unsupported =>
      ApiError.unprocessable("UNSUPPORTED", "the stores cannot serve this request")
    case _: StorageError.AlreadyExists | _: StorageError.ConflictingContent |
        _: StorageError.DuplicateIdempotencyKey =>
      ApiError.of(ErrorCode.Conflict, "STORE_CONFLICT", "the record conflicts with a stored one")
    case _ =>
      ApiError(
        ErrorCode.BadGateway,
        "a store failed",
        retryable = false,
        reason = Some("STORE_FAILED")
      )
