package scholarchain.services.api

import scala.util.control.NonFatal
import scholarchain.services.config.ServiceSettings
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.http.*
import scholarchain.services.ops.{AccessLog, AccessRecord, Metrics, OpsService}
import scholarchain.services.query.JobStoreException
import scholarchain.services.security.{
  AuthFailure,
  AuthFailureLimiter,
  Authenticator,
  Principal,
  RateLimiter,
  Scopes,
  SourceIdentity
}

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

  /** An error as a response: the error body, `Retry-After`, and `WWW-Authenticate` for a 401. */
  def error(e: ApiError, ctx: RequestContext): HttpResponse =
    val extra =
      e.retryAfterSeconds.map(s => "retry-after" -> s.toString).toMap ++
        (if e.code == ErrorCode.Unauthenticated then Map("www-authenticate" -> "Bearer")
         else Map.empty)
    response(e.status, ApiError.body(e, ctx), extra)

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
    failures: AuthFailureLimiter,
    mounted: Vector[Route],
    ops: OpsService,
    metrics: Metrics,
    accessLog: AccessLog,
    ids: IdGenerator
):
  /** The probes and metrics every entrypoint has, plus the routes the entrypoint mounts. */
  val routes: Vector[Route] = Vector(
    Route("GET", "/healthz", None, _ => Right(Json.response(200, ops.health))),
    Route("GET", "/readyz", None, _ => ops.ready.map(Json.response(200, _))),
    Route("GET", "/metrics", Some(Scopes.OpsRead), metricsRoute)
  ) ++ mounted

  // ---- entry point --------------------------------------------------------------------------

  def handle(req: HttpRequest): HttpResponse =
    val ctx = RequestContext(req, ids)
    var routeLabel = "unmatched"
    var who: Option[Principal] = None
    val outcome: Either[ApiError, HttpResponse] =
      try dispatch(req, ctx, l => routeLabel = l, p => who = Some(p))
      catch
        case e: JobStoreException =>
          Left(
            if e.retryable then
              ApiError(
                ErrorCode.Unavailable,
                "the job store is unavailable",
                retryable = true,
                reason = Some("JOB_STORE_UNAVAILABLE"),
                retryAfterSeconds = Some(1L)
              )
            else ApiError.internal
          )
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

  def errorResponse(e: ApiError, ctx: RequestContext): HttpResponse = Json.error(e, ctx)

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
    val source = SourceIdentity.of(req, settings.auth.failures.trustedProxyHops)
    // a source that failed too often is refused before its token is looked at
    failures
      .check(source)
      .left
      .map(wait =>
        ApiError(
          ErrorCode.RateLimited,
          "too many failed authentication attempts",
          retryable = true,
          reason = Some("AUTH_FAILURE_LIMIT"),
          retryAfterSeconds = Some(wait)
        )
      )
      .flatMap { _ =>
        authenticator.authenticate(req.header("authorization")).left.map { f =>
          failures.recordFailure(source)
          val reason = f match
            case AuthFailure.Missing => "MISSING_TOKEN"
            case AuthFailure.Malformed => "MALFORMED_TOKEN"
            case AuthFailure.Unknown => "INVALID_TOKEN"
          ApiError.of(ErrorCode.Unauthenticated, reason, "a valid bearer token is required")
        }
      }

  private def metricsRoute(c: RouteCall): Either[ApiError, HttpResponse] =
    Right(HttpResponse(200, Map("content-type" -> "text/plain; version=0.0.4"), ops.metricsText))
