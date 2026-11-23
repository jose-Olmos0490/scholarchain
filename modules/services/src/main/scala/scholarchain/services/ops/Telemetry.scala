package scholarchain.services.ops

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*
import scholarchain.services.resilience.CircuitBreaker

/** One finished request, as it is logged. It holds no header, body, token or query text. */
final case class AccessRecord(
    method: String,
    route: String,
    status: Int,
    requestId: String,
    traceId: String,
    latencyMillis: Long,
    principal: Option[String],
    errorCode: Option[String]
)

trait AccessLog:
  def record(r: AccessRecord): Unit

object Slf4jAccessLog extends AccessLog:
  private val log = LoggerFactory.getLogger("scholarchain.services.access")
  def record(r: AccessRecord): Unit =
    log.info(
      "request method={} route={} status={} requestId={} traceId={} latencyMs={} principal={} error={}",
      r.method,
      r.route,
      Int.box(r.status),
      r.requestId,
      r.traceId,
      Long.box(r.latencyMillis),
      r.principal.getOrElse("-"),
      r.errorCode.getOrElse("-")
    )

/** In-process counters, keyed by route template (never by raw path, so no ID is a label). */
final class Metrics:
  private val counts = new ConcurrentHashMap[(String, Int), AtomicLong]()
  private val latency = new ConcurrentHashMap[String, AtomicLong]()

  def observe(route: String, status: Int, latencyMillis: Long): Unit =
    counts.computeIfAbsent((route, status), _ => new AtomicLong()).incrementAndGet()
    latency.computeIfAbsent(route, _ => new AtomicLong()).addAndGet(latencyMillis)

  def count(route: String, status: Int): Long =
    Option(counts.get((route, status))).map(_.get()).getOrElse(0L)

  /** Prometheus text exposition. Carries no ID, secret or host name. */
  def render(breakers: Seq[CircuitBreaker]): String =
    val sb = new StringBuilder
    sb.append("# TYPE scholarchain_http_requests_total counter\n")
    counts.asScala.toVector
      .sortBy((k, _) => (k._1, k._2))
      .foreach((k, v) =>
        sb.append(
          s"""scholarchain_http_requests_total{route="${k._1}",status="${k._2}"} ${v.get()}\n"""
        )
      )
    sb.append("# TYPE scholarchain_http_latency_millis_total counter\n")
    latency.asScala.toVector
      .sortBy(_._1)
      .foreach((k, v) =>
        sb.append(s"""scholarchain_http_latency_millis_total{route="$k"} ${v.get()}\n""")
      )
    sb.append("# TYPE scholarchain_circuit_state gauge\n")
    breakers.foreach(b =>
      sb.append(s"""scholarchain_circuit_state{dependency="${b.name}",state="${b.current}"} 1\n""")
    )
    sb.result()
