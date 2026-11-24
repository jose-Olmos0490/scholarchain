package scholarchain.services.security

import scala.collection.mutable
import scholarchain.services.config.AuthFailureSettings
import scholarchain.services.http.{HttpRequest, ServiceClock}

/** Which network source a request is attributed to, for limiting failed authentication.
  *
  * Identity used: the TCP peer address the transport reports (`HttpRequest.remoteAddress`). A
  * request header is client-controlled, so `X-Forwarded-For` is read only when the deployment
  * says how many trusted proxies append to it (`auth.failures.trustedProxyHops`, normally 1 behind
  * the ALB). Then the source is the entry that many positions from the RIGHT: the entries a proxy
  * appended are trustworthy, everything to their left was typed by the client and is ignored, so a
  * caller cannot pick its own bucket by sending a forged header. With 0 hops the header is never
  * read. A header with fewer entries than hops, or an entry that is not an IP literal, falls
  * back to the peer address. A request with no peer address (not possible over the socket
  * transport) shares the bucket `unknown`.
  */
object SourceIdentity:
  private val IpLiteral = "[0-9A-Fa-f:.]{2,45}".r
  val Unknown = "unknown"

  def of(req: HttpRequest, trustedProxyHops: Int): String =
    val peer = req.remoteAddress.map(_.trim).filter(IpLiteral.matches).map(_.toLowerCase)
    val forwarded =
      if trustedProxyHops <= 0 then None
      else
        req
          .header("x-forwarded-for")
          .map(_.split(',').toVector.map(_.trim))
          .filter(_.size >= trustedProxyHops)
          .map(parts => parts(parts.size - trustedProxyHops))
          .filter(IpLiteral.matches)
          .map(_.toLowerCase)
    forwarded.orElse(peer).getOrElse(Unknown)

/** Counts failed authentication attempts per source in a fixed window and refuses a source that
  * made too many.
  *
  *   - Bounded memory: at most `maxTrackedSources` sources are remembered. When full, windows
  *     that have ended are dropped first; if none has ended, the source whose window started
  *     earliest is evicted (a source needs a fresh set of failures to be blocked again).
  *   - A blocked source is refused before its token is looked at, so a limit cannot be used to
  *     test guesses. A success does not reset the count, so one valid key does not give an
  *     attacker unlimited guesses from the same address.
  *   - Time comes from the injected clock. Nothing but the source key and a counter is kept: no
  *     token, header, or key digest is stored or logged.
  */
final class AuthFailureLimiter(settings: AuthFailureSettings, clock: ServiceClock):
  final private class Window(val startMillis: Long, var failures: Int)

  private val windows = mutable.LinkedHashMap.empty[String, Window]
  private val lengthMillis = settings.windowSeconds * 1000L

  /** `Left(retryAfterSeconds)` when the source already has `maxFailures` failures this window. */
  def check(source: String): Either[Long, Unit] = synchronized {
    val now = clock.nowMillis
    windows.get(source) match
      case Some(w) if now - w.startMillis < lengthMillis && w.failures >= settings.maxFailures =>
        Left(math.max(1L, (w.startMillis + lengthMillis - now + 999) / 1000))
      case _ => Right(())
  }

  def recordFailure(source: String): Unit = synchronized {
    val now = clock.nowMillis
    windows.get(source) match
      case Some(w) if now - w.startMillis < lengthMillis => w.failures += 1
      case _ =>
        windows.remove(source)
        makeRoom(now)
        windows.put(source, new Window(now, 1))
    ()
  }

  def trackedSources: Int = synchronized(windows.size)

  private def makeRoom(now: Long): Unit =
    if windows.size >= settings.maxTrackedSources then
      val ended = windows.collect {
        case (k, w) if now - w.startMillis >= lengthMillis => k
      }.toVector
      ended.foreach(windows.remove)
      if windows.size >= settings.maxTrackedSources then
        // insertion order is window-start order, so the head is the oldest window
        windows.keysIterator.nextOption().foreach(windows.remove)
