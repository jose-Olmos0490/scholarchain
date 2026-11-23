package scholarchain.services.api

import scala.util.control.NonFatal
import scholarchain.services.config.ServiceSettings
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.evidence.EvidenceService
import scholarchain.services.explain.ExplainService
import scholarchain.services.graph.GraphService
import scholarchain.services.http.*
import scholarchain.services.ops.{AccessLog, AccessRecord, Metrics, OpsService}
import scholarchain.services.query.{JobRecord, JobState, JobStore, QueryService}
import scholarchain.services.security.{AuthFailure, Authenticator, Principal, RateLimiter, Scopes}

/** What a route handler gets. */
final case class RouteCall(
    request: HttpRequest,
    ctx: RequestContext,
    principal: Option[Principal],
    params: Map[String, String]
)

/** A route: method, path template, the scope it needs (none for the public probes) and its
  * handler. Handlers are one-liners over a service.
  */
final case class Route(
    method: String,
    template: String,
    scope: Option[String],
    handler: RouteCall => Either[ApiError, HttpResponse]
):
  private val segments = template.split('/').toVector.filter(_.nonEmpty)

  def matches(path: Vector[String]): Option[Map[String, String]] =
    if path.size != segments.size then None
    else
      val pairs = segments.zip(path)
      if pairs.forall((t, p) => (t.startsWith("{") && p.nonEmpty) || t == p) then
        Some(pairs.collect {
          case (t, p) if t.startsWith("{") => t.drop(1).dropRight(1) -> p
        }.toMap)
      else None

object Json:
  def response(
      status: Int,
      body: ujson.Value,
      headers: Map[String, String] = Map.empty
  ): HttpResponse =
    HttpResponse(status, headers, body.render())

/** The HTTP application: authentication, rate limiting, authorization, routing, the error model
  * and request telemetry, over transport-neutral requests. The cask server is a thin adapter
  * around [[handle]].
  *
  * Every response, success or error, carries `X-Request-Id` and `X-Trace-Id`, and every error
  * body carries `requestId` and `traceId`, including auth failures, unknown routes, malformed
  * JSON, open circuits and the 500 fallback.
  */
final class ApiService(
    settings: ServiceSettings,
    authenticator: Authenticator,
    limiter: RateLimiter,
    queries: QueryService,
    jobs: JobStore,
    evidence: EvidenceService,
    graph: GraphService,
    explain: ExplainService,
    ops: OpsService,
    metrics: Metrics,
    accessLog: AccessLog,
    ids: IdGenerator
):
  val routes: Vector[Route] = Vector(
    Route("GET", "/healthz", None, _ => Right(Json.response(200, ops.health))),
    Route("GET", "/readyz", None, _ => ops.ready.map(Json.response(200, _))),
    Route("GET", "/metrics", Some(Scopes.OpsRead), metricsRoute),
    Route("POST", "/v1/query", Some(Scopes.QueryWrite), postQuery),
    Route("GET", "/v1/jobs/{jobId}", Some(Scopes.QueryRead), getJob),
    Route("GET", "/v1/jobs/{jobId}/result", Some(Scopes.QueryRead), getJobResult),
    Route(
      "GET",
      "/v1/evidence/{evidenceId}",
      Some(Scopes.EvidenceRead),
      c => evidence.get(c.params("evidenceId")).map(Json.response(200, _))
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}",
      Some(Scopes.GraphRead),
      c => graph.node(c.params("nodeId")).map(Json.response(200, _))
    ),
    Route(
      "GET",
      "/v1/graph/nodes/{nodeId}/neighbors",
      Some(Scopes.GraphRead),
      c => graph.neighbors(c.params("nodeId"), c.request.query).map(Json.response(200, _))
    ),
    Route(
      "GET",
      "/v1/explain/{queryId}",
      Some(Scopes.QueryRead),
      c => explain.explain(c.principal.get, c.params("queryId")).map(Json.response(200, _))
    )
  )

  // ---- entry point --------------------------------------------------------------------------

  def handle(req: HttpRequest): HttpResponse =
    val ctx = RequestContext(req, ids)
    var routeLabel = "unmatched"
    var who: Option[Principal] = None
    val outcome: Either[ApiError, HttpResponse] =
      try dispatch(req, ctx, l => routeLabel = l, p => who = Some(p))
      catch
        case NonFatal(_) | _: StackOverflowError | _: OutOfMemoryError =>
          Left(ApiError.internal) // the cause is neither returned nor logged: it may hold data
    val response = outcome match
      case Right(r) => r
      case Left(e) => errorResponse(e, ctx)
    val finished = response.copy(headers =
      response.headers ++ Map(
        "x-request-id" -> ctx.requestId,
        "x-trace-id" -> ctx.traceId,
        "cache-control" -> "no-store",
        "x-content-type-options" -> "nosniff"
      ) ++ (if response.headers.contains("content-type") then Map.empty
            else Map("content-type" -> "application/json"))
    )
    val millis = (System.nanoTime() - ctx.startedNanos) / 1_000_000L
    metrics.observe(routeLabel, finished.status, millis)
    try
      accessLog.record(
        AccessRecord(
          req.method,
          routeLabel,
          finished.status,
          ctx.requestId,
          ctx.traceId,
          millis,
          who.map(_.id),
          outcome.left.toOption.map(e => e.reason.getOrElse(e.code.wire))
        )
      )
    catch case NonFatal(_) => ()
    finished

  def errorResponse(e: ApiError, ctx: RequestContext): HttpResponse =
    val extra =
      e.retryAfterSeconds.map(s => "retry-after" -> s.toString).toMap ++
        (if e.code == ErrorCode.Unauthenticated then Map("www-authenticate" -> "Bearer")
         else Map.empty)
    Json.response(e.status, ApiError.body(e, ctx), extra)

  // ---- pipeline -----------------------------------------------------------------------------

  private def dispatch(
      req: HttpRequest,
      ctx: RequestContext,
      label: String => Unit,
      authenticated: Principal => Unit
  ): Either[ApiError, HttpResponse] =
    val path = req.path.split('/').toVector.filter(_.nonEmpty)
    val found = routes.iterator.flatMap(r => r.matches(path).map(p => (r, p))).toVector
    val hit = found.find(_._1.method == req.method)
    hit.foreach((r, _) => label(s"${r.method} ${r.template}"))
    val isPublic = hit.exists(_._1.scope.isEmpty)
    if isPublic then hit.get._1.handler(RouteCall(req, ctx, None, hit.get._2))
    else
      for
        principal <- authenticate(req)
        _ = authenticated(principal)
        _ <- limiter
          .tryAcquire(principal.id)
          .left
          .map(wait =>
            ApiError(
              ErrorCode.RateLimited,
              "the request rate limit was reached",
              retryable = true,
              reason = Some("RATE_LIMIT"),
              retryAfterSeconds = Some(wait)
            )
          )
        selected <- hit.toRight(ApiError.notFound("no such route"))
        (route, params) = selected
        scope = route.scope.get
        _ <-
          if principal.has(scope) then Right(())
          else
            Left(
              ApiError.of(
                ErrorCode.Forbidden,
                "MISSING_SCOPE",
                "the key lacks the scope this route needs"
              )
            )
        _ <- metricsAllowed(route)
        _ <-
          if req.body.length <= settings.http.maxBodyBytes then Right(())
          else Left(ApiError.badRequest("BODY_TOO_LARGE", "the request body is too large"))
        response <- route.handler(RouteCall(req, ctx, Some(principal), params))
      yield response

  private def metricsAllowed(r: Route): Either[ApiError, Unit] =
    if r.template == "/metrics" && !ops.metricsEnabled then Left(ApiError.notFound("no such route"))
    else Right(())

  private def authenticate(req: HttpRequest): Either[ApiError, Principal] =
    authenticator.authenticate(req.header("authorization")).left.map { f =>
      val reason = f match
        case AuthFailure.Missing => "MISSING_TOKEN"
        case AuthFailure.Malformed => "MALFORMED_TOKEN"
        case AuthFailure.Unknown => "INVALID_TOKEN"
      ApiError.of(ErrorCode.Unauthenticated, reason, "a valid bearer token is required")
    }

  // ---- handlers -----------------------------------------------------------------------------

  private def metricsRoute(c: RouteCall): Either[ApiError, HttpResponse] =
    Right(HttpResponse(200, Map("content-type" -> "text/plain; version=0.0.4"), ops.metricsText))

  private val JsonType = "(?i)application/json(;.*)?".r

  private def postQuery(c: RouteCall): Either[ApiError, HttpResponse] =
    for
      _ <-
        if c.request.header("content-type").exists(JsonType.matches) then Right(())
        else
          Left(
            ApiError
              .badRequest("CONTENT_TYPE_NOT_JSON", "the Content-Type must be application/json")
          )
      out <- queries.submit(
        c.ctx,
        c.principal.get,
        c.request.header("idempotency-key"),
        c.request.body
      )
    yield
      val replay = if out.replayed then Map("idempotent-replayed" -> "true") else Map.empty
      val job = out.job
      if out.accepted then
        Json.response(202, JobViews.of(job), replay + ("location" -> s"/v1/jobs/${job.jobId}"))
      else
        job.state match
          case JobState.Succeeded =>
            val body = job.result.collect { case o: ujson.Obj => o }.getOrElse(ujson.Obj())
            body("jobId") = job.jobId
            body("state") = "SUCCEEDED"
            Json.response(200, body, replay)
          case _ =>
            // a replay of a failed, non-retryable job: the stored error, with this request's IDs
            errorResponse(job.error.map(_.toApi).getOrElse(ApiError.internal), c.ctx)

  private def ownedJob(c: RouteCall): Either[ApiError, JobRecord] =
    val id = c.params("jobId")
    if !JobViews.JobId.matches(id) then
      Left(ApiError.badRequest("INVALID_ID", "the job ID is not valid"))
    else
      jobs
        .get(id)
        .filter(_.principalId == c.principal.get.id)
        .toRight(ApiError.notFound("the job was not found"))

  private def getJob(c: RouteCall): Either[ApiError, HttpResponse] =
    ownedJob(c).map(j => Json.response(200, JobViews.of(j)))

  private def getJobResult(c: RouteCall): Either[ApiError, HttpResponse] =
    ownedJob(c).flatMap { j =>
      j.state match
        case JobState.Succeeded =>
          val body = j.result.collect { case o: ujson.Obj => o }.getOrElse(ujson.Obj())
          body("jobId") = j.jobId
          body("state") = "SUCCEEDED"
          Right(Json.response(200, body))
        case JobState.Failed => Left(j.error.map(_.toApi).getOrElse(ApiError.internal))
        case s =>
          Left(
            ApiError(
              ErrorCode.Conflict,
              "the job has not finished",
              retryable = true,
              reason = Some("JOB_NOT_FINISHED"),
              retryAfterSeconds = Some(1L),
              details = Map("state" -> ujson.Str(s.toString.toUpperCase))
            )
          )
    }

object JobViews:
  val JobId = "job-[0-9a-f]{32}".r

  private def iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString

  def of(j: JobRecord): ujson.Obj =
    val out = ujson.Obj(
      "jobId" -> j.jobId,
      "queryId" -> j.queryId,
      "state" -> j.state.toString.toUpperCase,
      "stage" -> j.stage,
      "traceId" -> j.traceId,
      "attempts" -> j.attempts,
      "createdAt" -> iso(j.createdAtMillis),
      "updatedAt" -> iso(j.updatedAtMillis),
      "links" -> ujson.Obj(
        "self" -> s"/v1/jobs/${j.jobId}",
        "explain" -> s"/v1/explain/${j.queryId}"
      )
    )
    j.startedAtMillis.foreach(s => out("startedAt") = iso(s))
    j.finishedAtMillis.foreach(s => out("finishedAt") = iso(s))
    j.error.foreach { e =>
      out("error") = ujson.Obj(
        "code" -> e.code.wire,
        "status" -> e.code.status,
        "reason" -> e.reason.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "message" -> e.message,
        "retryable" -> e.retryable
      )
    }
    if j.state == JobState.Succeeded then out("links")("result") = s"/v1/jobs/${j.jobId}/result"
    out
