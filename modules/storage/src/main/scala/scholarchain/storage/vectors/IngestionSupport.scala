package scholarchain.storage.vectors

import scala.collection.mutable
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.storage.common.*

/** Source of monotonic time (nanoseconds) for the rate limiter. */
trait MonotonicClock:
  def nanos: Long

object MonotonicClock:
  val System: MonotonicClock = new MonotonicClock:
    def nanos: Long = java.lang.System.nanoTime()

/** Spaces PutVectors requests per index: at most `rate` requests per second, evenly spread (one
  * request every `1/rate` seconds). Time and sleeping are injected so tests never wait.
  */
final class RateLimiter(ratesPerSecond: Map[String, Int], clock: MonotonicClock, sleeper: Sleeper):
  private val nextFree = mutable.Map.empty[String, Long]

  def hasRate(index: String): Boolean = ratesPerSecond.contains(index)

  /** Blocks until a request to `index` may be sent; returns the milliseconds waited. */
  def acquire(index: String): Long = synchronized {
    val rate = ratesPerSecond.getOrElse(
      index,
      throw new IllegalStateException(s"no rate limit configured for index $index")
    )
    val interval = 1_000_000_000L / rate
    val now = clock.nanos
    val at = math.max(now, nextFree.getOrElse(index, now))
    nextFree(index) = at + interval
    val waitNanos = at - now
    if waitNanos > 0 then
      val ms = (waitNanos + 999_999L) / 1_000_000L
      sleeper.sleep(ms)
      ms
    else 0L
  }

/** Bounded exponential backoff with full jitter over a typed result. Only
  * [[StorageError.Transient]] (throttling, timeouts, 5xx that survived the adapter) is retried; every
  * other error is returned at once. Delay before attempt `n + 1` is uniform in
  * `[0, min(maxDelayMs, baseDelayMs * 2^(n-1))]`.
  */
object Backoff:
  def delayBoundMs(policy: RetryPolicyConfig, failedAttempt: Int): Long =
    val shift = math.min(failedAttempt - 1, 30)
    math.min(policy.maxDelayMs.toLong, policy.baseDelayMs.toLong << shift)

  /** The result and the number of attempts made. */
  def run[A](policy: RetryPolicyConfig, sleeper: Sleeper, jitter: Jitter)(
      attempt: => Result[A]
  ): (Result[A], Int) =
    var n = 1
    var out = attempt
    while isTransient(out) && n < policy.maxAttempts do
      sleeper.sleep(jitter.upTo(delayBoundMs(policy, n)))
      n += 1
      out = attempt
    (out, n)

  private def isTransient(r: Result[?]): Boolean = r match
    case Left(_: StorageError.Transient) => true
    case _ => false
