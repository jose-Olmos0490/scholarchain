package scholarchain.rag.provider

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.{ExecutionException, TimeUnit, TimeoutException}
import scala.util.control.NonFatal
import scholarchain.rag.common.BoundedJson
import scholarchain.rag.llm.Backoff

/** Delays between attempts of one provider call. No default: the values come from the caller's
  * retry configuration. The delay before retry `n` (1-based) is
  * `min(maxDelayMs, baseDelayMs * 2^(n-1))`; a `retry-after` answer may lengthen it, never past
  * `maxDelayMs`.
  */
final case class RetryDelays(baseDelayMs: Long, maxDelayMs: Long):
  require(baseDelayMs >= 0, "baseDelayMs must not be negative")
  require(maxDelayMs >= baseDelayMs, "maxDelayMs must be at least baseDelayMs")

  def beforeRetry(retry: Int, retryAfterSeconds: Option[Long]): Long =
    val shift = math.min(math.max(retry - 1, 0), 30)
    val exponential = math.min(maxDelayMs, baseDelayMs * (1L << shift))
    val asked = retryAfterSeconds.fold(0L)(s => math.min(math.max(s, 0L), maxDelayMs) * 1000L)
    math.min(maxDelayMs, math.max(exponential, asked))

enum ProviderFailureKind:
  /** HTTP 429 or 529. */
  case Overloaded

  /** A client-side deadline (connect or request). No HTTP status is classified as this. */
  case TimedOut

  /** Any other HTTP 5xx, or a connection failure. */
  case Transient

  /** Any other HTTP status, or no usable API key. Never retried. */
  case Permanent

/** Why a provider call failed. `detail` holds only fixed text, numbers and allowlisted error type
  * names: never a provider message, a response body, a header or a key.
  *
  * @param status
  *   the HTTP status, when the failure is an HTTP answer
  */
final case class ProviderFailure(
    kind: ProviderFailureKind,
    detail: String,
    status: Option[Int] = None
)

/** One `POST` with a JSON body to a model provider, with bounded retries.
  *
  * At most `maxRetries + 1` attempts. Each attempt has the deadline `requestTimeoutMs`, which
  * covers the response body too. Redirects are never followed, so the key header cannot travel
  * to another host. The key is read from the supplier once per call, is only placed in the
  * request header and is never part of any message or exception.
  */
final private[rag] class ProviderHttp(
    connectTimeoutMs: Int,
    requestTimeoutMs: Int,
    maxRetries: Int,
    delays: RetryDelays,
    backoff: Backoff
):
  require(connectTimeoutMs >= 1 && requestTimeoutMs >= 1 && maxRetries >= 0)

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofMillis(connectTimeoutMs.toLong))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  /** The body of an HTTP 200 answer. `headers` receives the API key. */
  def postJson(
      url: String,
      apiKey: () => String,
      headers: String => Seq[(String, String)],
      body: String
  ): Either[ProviderFailure, String] =
    ProviderHttp.key(apiKey).flatMap { key =>
      request(url, headers(key), body).flatMap(send)
    }

  private def request(
      url: String,
      headers: Seq[(String, String)],
      body: String
  ): Either[ProviderFailure, HttpRequest] =
    try
      val b = HttpRequest
        .newBuilder(URI.create(url))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      headers.foreach((name, value) => b.header(name, value))
      Right(b.build())
    catch
      // the exception message can quote a header value, so it is dropped
      case NonFatal(_) =>
        Left(ProviderFailure(ProviderFailureKind.Permanent, "the request could not be built"))

  private def send(req: HttpRequest): Either[ProviderFailure, String] =
    var attempt = 0
    var outcome: Option[Either[ProviderFailure, String]] = None
    while outcome.isEmpty do
      // an interrupted thread (a stage deadline) starts nothing: a request could still be billed
      if Thread.currentThread().isInterrupted then
        outcome = Some(
          Left(
            ProviderFailure(
              ProviderFailureKind.Transient,
              s"the call was interrupted after $attempt attempt(s); no further request was sent"
            )
          )
        )
      else
        attempt += 1
        once(req) match
          case Right(ok) => outcome = Some(Right(ok))
          case Left((failure, retryAfter, interrupted)) =>
            if interrupted || failure.kind == ProviderFailureKind.Permanent || attempt > maxRetries
            then outcome = Some(Left(counted(failure, attempt)))
            else
              try backoff.pause(delays.beforeRetry(attempt, retryAfter))
              catch
                case _: InterruptedException =>
                  Thread.currentThread().interrupt()
                  outcome = Some(Left(counted(failure, attempt)))
    outcome.get

  private def counted(f: ProviderFailure, attempts: Int): ProviderFailure =
    f.copy(detail = s"${f.detail} after $attempts attempt(s)")

  /** One attempt: the body, or the failure, its `retry-after` and whether it was interrupted. */
  private def once(
      req: HttpRequest
  ): Either[(ProviderFailure, Option[Long], Boolean), String] =
    def failed(kind: ProviderFailureKind, detail: String, interrupted: Boolean = false) =
      Left((ProviderFailure(kind, detail), None, interrupted))
    try
      val future =
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      try
        val r = future.get(requestTimeoutMs.toLong, TimeUnit.MILLISECONDS)
        if r.statusCode() == 200 then Right(r.body())
        else
          val retryAfter = Option(r.headers().firstValue("retry-after").orElse(null))
            .flatMap(_.trim.toLongOption)
          Left((ProviderHttp.classify(r.statusCode(), r.body()), retryAfter, false))
      catch
        case _: TimeoutException =>
          future.cancel(true)
          failed(ProviderFailureKind.TimedOut, "the request deadline passed")
        case _: InterruptedException =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          failed(ProviderFailureKind.Transient, "the call was interrupted", interrupted = true)
        case e: ExecutionException =>
          e.getCause match
            case _: HttpTimeoutException =>
              failed(ProviderFailureKind.TimedOut, "the connection deadline passed")
            case _ => failed(ProviderFailureKind.Transient, "the connection failed")
    catch case NonFatal(_) => failed(ProviderFailureKind.Transient, "the connection failed")

private[rag] object ProviderHttp:
  /** Error type names that may be repeated; anything else in an error body is dropped. */
  private val ErrorTypes = Set(
    "invalid_request_error",
    "authentication_error",
    "billing_error",
    "permission_error",
    "not_found_error",
    "request_too_large",
    "rate_limit_error",
    "api_error",
    "timeout_error",
    "overloaded_error"
  )

  def classify(status: Int, body: String): ProviderFailure =
    val kind = status match
      case 429 | 529 => ProviderFailureKind.Overloaded
      case s if s >= 500 => ProviderFailureKind.Transient
      case _ => ProviderFailureKind.Permanent
    val errorType =
      try
        BoundedJson
          .read(body)
          .objOpt
          .flatMap(_.get("error"))
          .flatMap(_.objOpt)
          .flatMap(_.get("type"))
          .flatMap(_.strOpt)
          .filter(ErrorTypes)
      catch case NonFatal(_) => None
    ProviderFailure(kind, s"HTTP $status${errorType.fold("")(t => s" ($t)")}", Some(status))

  /** The trimmed key, or a permanent failure. A key with a character that cannot be a header
    * value is refused here, because the HTTP library would quote it in its exception.
    */
  def key(supplier: () => String): Either[ProviderFailure, String] =
    def refused(why: String) = Left(ProviderFailure(ProviderFailureKind.Permanent, why))
    try
      Option(supplier()).map(_.trim).filter(_.nonEmpty) match
        case None => refused("no API key is available")
        case Some(k) if !k.forall(c => c > ' ' && c < 127) =>
          refused("the API key is not a valid header value")
        case Some(k) => Right(k)
    catch case NonFatal(_) => refused("the API key could not be read")
