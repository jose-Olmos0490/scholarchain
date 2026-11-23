package scholarchain.services.resilience

import java.util.concurrent.{
  ArrayBlockingQueue,
  Callable,
  ExecutionException,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import scala.util.control.NonFatal
import scholarchain.services.config.{BreakerSettings, ExecutionSettings, RetrySettings}
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.http.ServiceClock

enum BreakerState:
  case Closed, Open, HalfOpen

/** A circuit breaker for one downstream. Only downstream failures (502, 503, 504) count; a
  * caller's mistake (4xx) never opens it. After `openMillis` one trial call is let through.
  */
final class CircuitBreaker(val name: String, settings: BreakerSettings, clock: ServiceClock):
  private var state = BreakerState.Closed
  private var failures = 0
  private var openedAt = 0L
  private var trialInFlight = false

  def current: BreakerState = synchronized {
    if state == BreakerState.Open && clock.nowMillis - openedAt >= settings.openMillis then
      BreakerState.HalfOpen
    else state
  }

  private def permit(): Boolean = synchronized {
    current match
      case BreakerState.Closed => true
      case BreakerState.Open => false
      case BreakerState.HalfOpen =>
        if trialInFlight then false
        else
          state = BreakerState.HalfOpen
          trialInFlight = true
          true
  }

  private def onSuccess(): Unit = synchronized {
    state = BreakerState.Closed
    failures = 0
    trialInFlight = false
  }

  private def onFailure(): Unit = synchronized {
    trialInFlight = false
    failures += 1
    if state == BreakerState.HalfOpen || failures >= settings.failureThreshold then
      state = BreakerState.Open
      openedAt = clock.nowMillis
  }

  private def countsAsFailure(e: ApiError): Boolean =
    e.code == ErrorCode.BadGateway || e.code == ErrorCode.GatewayTimeout ||
      e.code == ErrorCode.Unavailable

  /** Runs `op` unless the breaker is open; an open breaker answers 503 at once. */
  def guard[A](op: => Either[ApiError, A]): Either[ApiError, A] =
    if !permit() then
      Left(
        ApiError(
          ErrorCode.Unavailable,
          "the downstream is temporarily unavailable",
          retryable = true,
          reason = Some("CIRCUIT_OPEN"),
          retryAfterSeconds = Some(math.max(1L, (settings.openMillis + 999) / 1000)),
          details = Map("dependency" -> ujson.Str(name))
        )
      )
    else
      val result =
        try op
        catch
          case NonFatal(_) =>
            Left(ApiError.of(ErrorCode.BadGateway, "DOWNSTREAM_EXCEPTION", "a downstream failed"))
      result match
        case Left(e) if countsAsFailure(e) => onFailure()
        case _ => onSuccess()
      result

/** Sleeps between retries; replaced in tests so they never wait. */
trait Sleeper:
  def sleep(millis: Long): Unit

object ThreadSleeper extends Sleeper:
  def sleep(millis: Long): Unit = if millis > 0 then Thread.sleep(millis)

/** Bounded retry for idempotent operations. Only an error marked retryable is retried, and never
  * a rate-limit or an open circuit (the caller must back off instead).
  */
final class Retrier(settings: RetrySettings, sleeper: Sleeper):
  def apply[A](retries: Int)(op: => Either[ApiError, A]): Either[ApiError, A] =
    def loop(attempt: Int): Either[ApiError, A] =
      op match
        case Left(e) if e.retryable && attempt < retries && retryableHere(e) =>
          sleeper.sleep(settings.backoffMillis * (attempt + 1))
          loop(attempt + 1)
        case other => other
    loop(0)

  def reads[A](op: => Either[ApiError, A]): Either[ApiError, A] = apply(settings.readRetries)(op)

  private def retryableHere(e: ApiError): Boolean =
    e.code != ErrorCode.RateLimited && !e.reason.contains("CIRCUIT_OPEN")

/** A bounded pool with a bounded queue: the bulkhead and the deadline of every downstream call.
  * A call that runs past its deadline is cancelled and answers 504; a full pool answers 503.
  */
final class BoundedExecutor(settings: ExecutionSettings):
  private val pool = new ThreadPoolExecutor(
    settings.workerThreads,
    settings.workerThreads,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](settings.queueCapacity),
    new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, "services-worker")
        t.setDaemon(true)
        t
  )

  private def overloaded: ApiError =
    ApiError(
      ErrorCode.Unavailable,
      "the service is busy; try again",
      retryable = true,
      reason = Some("OVERLOADED"),
      retryAfterSeconds = Some(1L)
    )

  /** Runs `body` on the pool and waits up to `timeoutMillis`. An exception thrown by `body` is a
    * downstream failure; its text is never kept.
    */
  def callWithin[A](timeoutMillis: Long)(body: => Either[ApiError, A]): Either[ApiError, A] =
    try
      val future = pool.submit(new Callable[Either[ApiError, A]]:
        def call(): Either[ApiError, A] = body)
      try future.get(timeoutMillis, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          future.cancel(true)
          Left(
            ApiError.of(
              ErrorCode.GatewayTimeout,
              "TIMEOUT",
              s"the operation did not finish within its time limit"
            )
          )
        case _: ExecutionException =>
          Left(ApiError.of(ErrorCode.BadGateway, "DOWNSTREAM_EXCEPTION", "a downstream failed"))
        case _: InterruptedException =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          Left(ApiError.internal)
    catch case _: RejectedExecutionException => Left(overloaded)

  /** Starts `body` without waiting. `false` when the pool and queue are full. */
  def submit(body: => Unit): Boolean =
    try
      pool.execute(() =>
        try body
        catch case NonFatal(_) => ()
      )
      true
    catch case _: RejectedExecutionException => false

  def shutdown(): Unit = pool.shutdownNow()
