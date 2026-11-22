package scholarchain.storage.common

import java.util.concurrent.atomic.AtomicLong
import scholarchain.core.config.RetryPolicyConfig

trait Sleeper:
  def sleep(millis: Long): Unit

object Sleeper:
  val Real: Sleeper = ms => Thread.sleep(ms)

/** Source of jitter: a uniform value in `[0, boundInclusive]`. */
trait Jitter:
  def upTo(boundInclusive: Long): Long

object Jitter:
  def random(seed: Option[Long] = None): Jitter =
    val r = seed.fold(new java.util.Random())(new java.util.Random(_))
    bound => if bound <= 0 then 0L else (r.nextDouble() * (bound + 1)).toLong.min(bound)

/** Counters for retries. One collector is shared by an adapter; reports snapshot it. */
final class RetryStats:
  private val ops = new AtomicLong
  private val retried = new AtomicLong
  private val gaveUp = new AtomicLong
  private val permanent = new AtomicLong
  def recordOperation(): Unit = ops.incrementAndGet()
  def recordRetry(): Unit = retried.incrementAndGet()
  def recordGaveUp(): Unit = gaveUp.incrementAndGet()
  def recordPermanent(): Unit = permanent.incrementAndGet()
  def snapshot: RetrySnapshot =
    RetrySnapshot(ops.get, retried.get, gaveUp.get, permanent.get)

final case class RetrySnapshot(
    operations: Long,
    retries: Long,
    exhausted: Long,
    permanentFailures: Long
)

/** Bounded exponential backoff with full jitter. Only failures the classifier calls transient are
  * retried; every other failure is returned at once as a permanent error. Delay before attempt
  * `n + 1` is a uniform value in `[0, min(maxDelayMs, baseDelayMs * 2^(n-1))]`.
  */
final class Retrier(
    policy: RetryPolicyConfig,
    isTransient: Throwable => Boolean,
    sleeper: Sleeper = Sleeper.Real,
    jitter: Jitter = Jitter.random(),
    val stats: RetryStats = new RetryStats
):
  def delayBoundMs(failedAttempt: Int): Long =
    val shift = math.min(failedAttempt - 1, 30)
    math.min(policy.maxDelayMs.toLong, policy.baseDelayMs.toLong << shift)

  def run[A](operation: String)(body: => A): Result[A] =
    stats.recordOperation()
    var attempt = 1
    var result: Option[Result[A]] = None
    while result.isEmpty do
      try result = Some(Right(body))
      catch
        case e: StorageException => result = Some(Left(e.error))
        case e: Throwable if isTransient(e) =>
          if attempt >= policy.maxAttempts then
            stats.recordGaveUp()
            result = Some(Left(StorageError.Transient(operation, attempt, describe(e))))
          else
            stats.recordRetry()
            sleeper.sleep(jitter.upTo(delayBoundMs(attempt)))
            attempt += 1
        case e: Throwable =>
          if scala.util.control.NonFatal(e) then
            stats.recordPermanent()
            result = Some(Left(StorageError.Permanent(operation, describe(e))))
          else throw e
    result.get

  /** Like [[run]] for bodies that already return a typed result. */
  def runEither[A](operation: String)(body: => Result[A]): Result[A] =
    run(operation)(body.fold(e => throw new StorageException(e), identity))

  private def describe(e: Throwable): String =
    s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
