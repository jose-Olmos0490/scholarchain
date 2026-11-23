package scholarchain.services.resilience

import scholarchain.services.config.RetrySettings
import scholarchain.services.error.ApiError
import scholarchain.services.query.StorageErrors
import scholarchain.storage.common.Result

/** One downstream (a store family) behind its own breaker, bounded retries and deadline. Every
  * read through a port goes through here; a call never runs unbounded.
  */
final class Downstream(
    val breaker: CircuitBreaker,
    retrier: Retrier,
    executor: BoundedExecutor,
    settings: RetrySettings
):
  /** Runs an idempotent read: deadline per attempt, bounded retries, then the breaker. */
  def read[A](
      timeoutMillis: Long = settings.readTimeoutMillis,
      mapError: scholarchain.storage.common.StorageError => ApiError = StorageErrors.toApi
  )(op: => Result[A]): Either[ApiError, A] =
    breaker.guard(
      retrier.reads(executor.callWithin(timeoutMillis)(op.left.map(mapError)))
    )
