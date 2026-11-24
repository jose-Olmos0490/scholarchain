package scholarchain.services.http

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** A transport-neutral HTTP request. `remoteAddress` is the TCP peer as the transport saw it (never
  * a header). Header names are lower-cased by [[HttpRequest.of]].
  */
final case class HttpRequest(
    method: String,
    path: String,
    query: Map[String, Vector[String]],
    headers: Map[String, String],
    body: Array[Byte],
    remoteAddress: Option[String] = None
):
  def header(name: String): Option[String] = headers.get(name.toLowerCase)

object HttpRequest:
  def of(
      method: String,
      path: String,
      query: Map[String, Vector[String]] = Map.empty,
      headers: Map[String, String] = Map.empty,
      body: Array[Byte] = Array.emptyByteArray,
      remoteAddress: Option[String] = None
  ): HttpRequest =
    HttpRequest(
      method.toUpperCase,
      path,
      query,
      headers.map((k, v) => k.toLowerCase -> v),
      body,
      remoteAddress
    )

/** A transport-neutral HTTP response. */
final case class HttpResponse(status: Int, headers: Map[String, String], body: String)

object QueryString:
  /** Parses a raw query string; malformed percent-encoding yields `None` (the caller answers 400). */
  def parse(raw: String): Option[Map[String, Vector[String]]] =
    if raw == null || raw.isEmpty then Some(Map.empty)
    else
      try
        val pairs = raw.split('&').toVector.filter(_.nonEmpty).map { p =>
          val i = p.indexOf('=')
          val (k, v) = if i < 0 then (p, "") else (p.substring(0, i), p.substring(i + 1))
          URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder
            .decode(v, StandardCharsets.UTF_8)
        }
        Some(pairs.groupMap(_._1)(_._2))
      catch case _: IllegalArgumentException => None

/** Time, injectable so tests need no sleeping. */
trait ServiceClock:
  def nowMillis: Long
  def now: Instant = Instant.ofEpochMilli(nowMillis)

object SystemServiceClock extends ServiceClock:
  def nowMillis: Long = System.currentTimeMillis()

/** Source of request, trace, job and nonce identifiers. */
trait IdGenerator:
  def next(): String

object RandomIdGenerator extends IdGenerator:
  def next(): String = UUID.randomUUID().toString.replace("-", "")

/** Per-request identifiers. Every response, success or error, carries both. */
final case class RequestContext(requestId: String, traceId: String, startedNanos: Long)

object RequestContext:
  /** The trace ID is accepted from the caller only in a safe form; anything else is replaced. */
  val SafeId = "[A-Za-z0-9._:-]{1,128}".r
  private val TraceParent = "00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}".r

  def apply(req: HttpRequest, ids: IdGenerator): RequestContext =
    val requestId = s"req-${ids.next()}"
    val fromHeader = req
      .header("x-trace-id")
      .filter(SafeId.matches)
      .orElse(req.header("traceparent").collect { case TraceParent(t) => t })
    RequestContext(requestId, fromHeader.getOrElse(s"trace-${ids.next()}"), System.nanoTime())
