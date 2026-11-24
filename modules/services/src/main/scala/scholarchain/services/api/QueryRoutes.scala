package scholarchain.services.api

import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.explain.ExplainService
import scholarchain.services.http.HttpResponse
import scholarchain.services.query.{JobRecord, JobState, JobStore, QueryService}
import scholarchain.services.security.Scopes

/** The routes of the query API: submit, jobs, explain. Each handler is a one-liner over a
  * service; the query API entrypoint is the only place that mounts them.
  */
final class QueryRoutes(queries: QueryService, jobs: JobStore, explain: ExplainService):
  val routes: Vector[Route] = Vector(
    Route("POST", "/v1/query", Some(Scopes.QueryWrite), postQuery),
    Route("GET", "/v1/jobs/{jobId}", Some(Scopes.QueryRead), getJob),
    Route("GET", "/v1/jobs/{jobId}/result", Some(Scopes.QueryRead), getJobResult),
    Route(
      "GET",
      "/v1/explain/{queryId}",
      Some(Scopes.QueryRead),
      c => explain.explain(c.principal.get, c.params("queryId")).map(Json.response(200, _))
    )
  )

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
            Json.error(job.error.map(_.toApi).getOrElse(ApiError.internal), c.ctx)

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
