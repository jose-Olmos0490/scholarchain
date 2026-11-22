package scholarchain.storage.common

/** Typed failure of a storage operation. Ports return `Either[StorageError, A]`; no port throws for
  * an expected failure. `message` is a complete, human-readable reason.
  */
sealed trait StorageError:
  def message: String

object StorageError:
  /** A file's SHA-256 differs from its load-manifest entry. The load fails. */
  final case class ChecksumMismatch(path: String, expected: String, actual: String)
      extends StorageError:
    def message = s"checksum mismatch for $path: manifest says $expected, file is $actual"

  final case class SizeMismatch(path: String, expected: Long, actual: Long) extends StorageError:
    def message = s"size mismatch for $path: manifest says $expected bytes, file has $actual"

  final case class RowCountMismatch(table: String, expected: Long, actual: Long)
      extends StorageError:
    def message =
      s"row count mismatch for table $table: manifest says $expected, files hold $actual"

  final case class MissingFile(path: String) extends StorageError:
    def message = s"file listed in the load manifest does not exist: $path"

  final case class InvalidManifest(reason: String) extends StorageError:
    def message = s"invalid load manifest: $reason"

  /** Same key, different content. Never overwritten. */
  final case class ConflictingContent(key: String, existing: String, incoming: String)
      extends StorageError:
    def message = s"key $key already holds different content ($existing), refusing $incoming"

  /** A conditional create found the key already present. */
  final case class AlreadyExists(key: String) extends StorageError:
    def message = s"item already exists: $key"

  /** A QuerySession create carried an Idempotency-Key already used by `existingQueryId`. */
  final case class DuplicateIdempotencyKey(idempotencyKey: String, existingQueryId: String)
      extends StorageError:
    def message = s"Idempotency-Key $idempotencyKey was already used by query $existingQueryId"

  final case class NotFound(key: String) extends StorageError:
    def message = s"not found: $key"

  final case class LimitExceeded(what: String, limit: Long, actual: Long) extends StorageError:
    def message = s"$what exceeds the limit: $actual > $limit"

  final case class InvalidRecord(reason: String) extends StorageError:
    def message = s"invalid record: $reason"

  final case class ValidationFailed(problems: Vector[String]) extends StorageError:
    def message =
      s"validation failed with ${problems.size} problem(s): ${problems.take(20).mkString("; ")}"

  final case class Misconfigured(reason: String) extends StorageError:
    def message = s"misconfigured: $reason"

  /** A transient failure that survived the bounded retries. */
  final case class Transient(operation: String, attempts: Int, cause: String) extends StorageError:
    def message = s"$operation failed transiently after $attempts attempt(s): $cause"

  /** A failure that is not retried. */
  final case class Permanent(operation: String, cause: String) extends StorageError:
    def message = s"$operation failed: $cause"

type Result[+A] = Either[StorageError, A]

/** Carries a [[StorageError]] through code that cannot return it (iterator internals). */
final class StorageException(val error: StorageError) extends RuntimeException(error.message)
