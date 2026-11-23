package scholarchain.services.error

import scholarchain.services.http.RequestContext

/** The typed error model. One code per HTTP status; `reason` refines it. */
enum ErrorCode(val status: Int, val wire: String, val retryable: Boolean):
  case BadRequest extends ErrorCode(400, "BAD_REQUEST", false)
  case Unauthenticated extends ErrorCode(401, "UNAUTHENTICATED", false)
  case Forbidden extends ErrorCode(403, "FORBIDDEN", false)
  case NotFound extends ErrorCode(404, "NOT_FOUND", false)
  case Conflict extends ErrorCode(409, "CONFLICT", false)
  case Unprocessable extends ErrorCode(422, "UNPROCESSABLE", false)
  case RateLimited extends ErrorCode(429, "RATE_LIMITED", true)
  case Internal extends ErrorCode(500, "INTERNAL_ERROR", false)
  case BadGateway extends ErrorCode(502, "DOWNSTREAM_FAILURE", true)
  case Unavailable extends ErrorCode(503, "UNAVAILABLE", true)
  case GatewayTimeout extends ErrorCode(504, "DOWNSTREAM_TIMEOUT", true)

object ErrorCode:
  def fromWire(w: String): Option[ErrorCode] = values.find(_.wire == w)

/** A service error. `message` is a fixed, safe sentence: it never echoes request text, a stack
  * trace, a secret or a downstream exception message.
  */
final case class ApiError(
    code: ErrorCode,
    message: String,
    retryable: Boolean,
    reason: Option[String] = None,
    retryAfterSeconds: Option[Long] = None,
    details: Map[String, ujson.Value] = Map.empty
):
  def status: Int = code.status

object ApiError:
  def of(code: ErrorCode, message: String): ApiError =
    new ApiError(code, message, code.retryable, None, None, Map.empty)

  def of(code: ErrorCode, reason: String, message: String): ApiError =
    new ApiError(code, message, code.retryable, Some(reason), None, Map.empty)

  def withRetryable(e: ApiError, retryable: Boolean): ApiError = e.copy(retryable = retryable)

  /** The JSON body of an error. Every error body has `traceId` and `requestId`. */
  def body(e: ApiError, ctx: RequestContext): ujson.Obj =
    val err = ujson.Obj(
      "code" -> e.code.wire,
      "status" -> e.code.status,
      "message" -> e.message,
      "retryable" -> e.retryable
    )
    e.reason.foreach(r => err("reason") = r)
    e.retryAfterSeconds.foreach(s => err("retryAfterSeconds") = s.toDouble)
    if e.details.nonEmpty then err("details") = ujson.Obj.from(e.details)
    ujson.Obj("error" -> err, "requestId" -> ctx.requestId, "traceId" -> ctx.traceId)

  // ---- constructors used across the services (all messages are fixed text) -------------------
  def badRequest(reason: String, message: String): ApiError =
    of(ErrorCode.BadRequest, reason, message)
  def unprocessable(reason: String, message: String): ApiError =
    of(ErrorCode.Unprocessable, reason, message)
  def notFound(message: String): ApiError = of(ErrorCode.NotFound, message)
  def internal: ApiError =
    of(ErrorCode.Internal, "the service failed unexpectedly")
