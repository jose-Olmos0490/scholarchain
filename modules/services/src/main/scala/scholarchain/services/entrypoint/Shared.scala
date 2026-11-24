package scholarchain.services.entrypoint

import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.services.config.BindSettings
import scholarchain.services.http.ServiceClock
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.runtime.ServiceRuntime
import scholarchain.services.security.{ApiKeyRecord, ApiKeySource}
import scholarchain.services.server.HttpEndpoint

/** A started HTTP entrypoint: the runtime and its listener. */
final class RunningHttp(val runtime: ServiceRuntime, endpoint: HttpEndpoint):
  /** The port actually bound (differs from the configured one only when that was 0). */
  def port: Int = endpoint.boundPort

  def stop(): Unit =
    try endpoint.stop()
    finally runtime.close()

object RunningHttp:
  def start(runtime: ServiceRuntime, maxBodyBytes: Int, bind: BindSettings): RunningHttp =
    new RunningHttp(runtime, HttpEndpoint.start(runtime.api, maxBodyBytes, bind.host, bind.port))

/** The API-key digests, loaded from a source at start-up and re-read on a schedule so a rotation
  * takes effect without a restart. Start-up fails closed: no keys, no service. When a later refresh
  * fails, the keys already loaded stay in force. Only digests are held; nothing is logged.
  */
final class RefreshingApiKeys private (
    source: ApiKeySource,
    refreshMillis: Long,
    clock: ServiceClock,
    first: Vector[ApiKeyRecord]
):
  private var keys = first
  private var nextRefreshAt = clock.nowMillis + refreshMillis

  def current: Vector[ApiKeyRecord] =
    val due = synchronized {
      val now = clock.nowMillis
      if now >= nextRefreshAt then
        nextRefreshAt = now + refreshMillis
        true
      else false
    }
    if due then
      try source.load().foreach(fresh => if fresh.nonEmpty then synchronized { keys = fresh })
      catch case NonFatal(_) => ()
    synchronized(keys)

  def loaded: Boolean = synchronized(keys.nonEmpty)

object RefreshingApiKeys:
  def load(
      source: ApiKeySource,
      refreshSeconds: Int,
      clock: ServiceClock
  ): Either[String, RefreshingApiKeys] =
    source
      .load()
      .flatMap(ks =>
        if ks.isEmpty then Left("the API key source holds no keys")
        else Right(new RefreshingApiKeys(source, refreshSeconds * 1000L, clock, ks))
      )

/** Cheap readiness checks. A probe reports only ok or failing; it never keeps an error text. */
object Probes:
  def of(name: String)(check: => Boolean): ReadinessProbe =
    ReadinessProbe(
      name,
      () =>
        try check
        catch case NonFatal(_) => false
    )

  def failing(name: String): ReadinessProbe = ReadinessProbe(name, () => false)

/** Writes stage timings (no question, no text) to the application log. */
object Slf4jTraceSink extends scholarchain.rag.workflow.TraceSink:
  private val log = LoggerFactory.getLogger("scholarchain.services.workflow")
  def record(t: scholarchain.rag.workflow.StageTrace): Unit =
    try
      log.info(
        "stage traceId={} stage={} latencyMs={} outcome={}",
        t.traceId,
        t.stage,
        Long.box(t.latencyMillis),
        t.outcome
      )
    catch case NonFatal(_) => ()

/** A reject sink that does not grow: it logs the reason code and key (never a document) and
  * counts. The ingestion report carries the durable reject location.
  */
final class LoggingRejectSink extends scholarchain.storage.common.RejectSink:
  private val log = LoggerFactory.getLogger("scholarchain.services.worker.rejects")
  private val counter = new java.util.concurrent.atomic.AtomicLong(0)
  def record(r: scholarchain.storage.common.StoreReject): Unit =
    counter.incrementAndGet()
    try log.warn("reject store={} key={} code={}", r.store, r.key, r.reasonCode)
    catch case NonFatal(_) => ()
  def count: Long = counter.get()
  def all: Vector[scholarchain.storage.common.StoreReject] = Vector.empty
