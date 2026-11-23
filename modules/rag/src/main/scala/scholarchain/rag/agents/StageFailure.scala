package scholarchain.rag.agents

import scholarchain.storage.common.StorageError

/** Typed failure of a runtime stage. A stage never throws for an expected failure and never
  * works around a refusal: a storage refusal that the store cannot serve surfaces as
  * [[StageFailure.Unsupported]], a time-out as [[StageFailure.Retryable]].
  *
  * `detail` is a readable reason. It never contains raw model output, retrieved text or a user
  * question.
  */
sealed trait StageFailure:
  def code: String
  def detail: String
  def retryable: Boolean
  def message: String = s"$code: $detail"

object StageFailure:
  /** The store holds no data for the request (unloaded window, bridge ranking, venue filter). */
  final case class Unsupported(detail: String) extends StageFailure:
    val code = "UNSUPPORTED"
    val retryable = false

  /** A time-out or a transient store failure. The same request may succeed if repeated. */
  final case class Retryable(operation: String, detail: String) extends StageFailure:
    val code = "RETRYABLE"
    val retryable = true

  /** The request, a plan field, a model output or a retrieved item was refused. */
  final case class Rejected(code: String, detail: String) extends StageFailure:
    val retryable = false

  /** The query ID already has an audit record that describes a different answer. A replay must
    * not return an answer that the audit record does not describe; a service maps this to a
    * conflict (HTTP 409).
    */
  final case class IdempotencyConflict(detail: String) extends StageFailure:
    val code = "IDEMPOTENCY_CONFLICT"
    val retryable = false

  final case class NotFound(detail: String) extends StageFailure:
    val code = "NOT_FOUND"
    val retryable = false

  /** Any other failure that is not worth repeating. */
  final case class Failed(operation: String, detail: String) extends StageFailure:
    val code = "FAILED"
    val retryable = false

  def fromStorage(operation: String, e: StorageError): StageFailure = e match
    case StorageError.Unsupported(what) => Unsupported(what)
    case t: StorageError.Timeout => Retryable(operation, t.message)
    case t: StorageError.Transient => Retryable(operation, t.message)
    case StorageError.NotFound(key) => NotFound(key)
    case l: StorageError.LimitExceeded => Rejected("LIMIT_EXCEEDED", l.message)
    case i: StorageError.InvalidRecord => Rejected("INVALID_REQUEST", i.message)
    case other => Failed(operation, other.message)

type SResult[+A] = Either[StageFailure, A]
