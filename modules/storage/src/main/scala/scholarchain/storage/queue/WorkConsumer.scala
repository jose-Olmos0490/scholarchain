package scholarchain.storage.queue

import scholarchain.storage.common.*

/** @param batchSize
  *   messages per receive (SQS allows 1..10)
  * @param visibilityTimeoutSeconds
  *   must exceed the longest handler run, or a message is redelivered while still processed
  * @param maxReceiveCount
  *   a message received this many times without an acknowledgement is dead-lettered
  * @param maxMessagesPerRun
  *   hard bound on the messages one [[WorkConsumer.drain]] call may take
  * @param retryBackoffSeconds
  *   visibility delay applied to a message whose handler failed transiently
  */
final case class ConsumerSettings(
    batchSize: Int,
    visibilityTimeoutSeconds: Int,
    waitSeconds: Int,
    maxReceiveCount: Int,
    maxMessagesPerRun: Int,
    retryBackoffSeconds: Int
):
  require(batchSize >= 1 && batchSize <= 10, "batchSize must be 1..10")
  require(visibilityTimeoutSeconds >= 1, "visibilityTimeoutSeconds must be >= 1")
  require(waitSeconds >= 0, "waitSeconds must be >= 0")
  require(maxReceiveCount >= 1, "maxReceiveCount must be >= 1")
  require(maxMessagesPerRun >= 1, "maxMessagesPerRun must be >= 1")
  require(retryBackoffSeconds >= 0, "retryBackoffSeconds must be >= 0")

enum MessageOutcome:
  /** Handled and deleted. */
  case Acknowledged

  /** Handler failed transiently; the message stays on the queue for redelivery. */
  case Retry(reason: String)

  /** Undecodable, permanently failing or out of receives; copied to the DLQ and deleted. */
  case DeadLettered(reason: String)

  /** Handled (or dead-lettered) but the queue operation after it failed. The message will be
    * redelivered; handlers are idempotent so the repeat is harmless.
    */
  case QueueFault(reason: String)

final case class ConsumerReport(received: Int, outcomes: Vector[(String, MessageOutcome)]):
  def count(f: MessageOutcome => Boolean): Int = outcomes.count(o => f(o._2))
  def acknowledged: Int = count(_ == MessageOutcome.Acknowledged)
  def deadLettered: Int = count(_.isInstanceOf[MessageOutcome.DeadLettered])
  def retried: Int = count(_.isInstanceOf[MessageOutcome.Retry])

/** A bounded, idempotent consumer loop over a [[WorkQueue]].
  *
  * Per message: decode, then handle. Success deletes (acknowledges) it. A transient handler failure
  * leaves it for redelivery after `retryBackoffSeconds`, unless it has been received
  * `maxReceiveCount` times. A decode failure, a permanent handler failure or exhausted receives copy
  * the message to the dead-letter queue (with the reason as a reject) and only then delete it: if
  * the DLQ send fails the message stays where it is. Delivery is at least once, so `handle` must be
  * idempotent.
  */
final class WorkConsumer[A](
    queue: WorkQueue,
    deadLetter: WorkQueue,
    decode: String => Either[String, A],
    handle: A => Result[Unit],
    settings: ConsumerSettings,
    rejects: RejectSink,
    store: String = "embedding-queue"
):
  private def reject(m: QueueMessage, code: String, detail: String): Unit =
    rejects.record(StoreReject(store, m.messageId, code, detail, runId = ""))

  private def bury(m: QueueMessage, code: String, reason: String): MessageOutcome =
    reject(m, code, reason)
    deadLetter.send(m.body) match
      case Left(e) => MessageOutcome.QueueFault(s"dead-letter send failed: ${e.message}")
      case Right(_) =>
        queue.delete(m.receiptHandle) match
          case Left(e) =>
            MessageOutcome.QueueFault(s"delete after dead-letter failed: ${e.message}")
          case Right(_) => MessageOutcome.DeadLettered(reason)

  private def process(m: QueueMessage): MessageOutcome =
    decode(m.body) match
      case Left(why) => bury(m, "UNDECODABLE_MESSAGE", why)
      case Right(a) =>
        val handled =
          try handle(a)
          catch
            case scala.util.control.NonFatal(t) =>
              Left(StorageError.Transient("handler", 1, t.getClass.getSimpleName))
        handled match
          case Right(_) =>
            queue.delete(m.receiptHandle) match
              case Right(_) => MessageOutcome.Acknowledged
              case Left(e) => MessageOutcome.QueueFault(s"ack failed: ${e.message}")
          case Left(e) if !e.retryable => bury(m, "PERMANENT_FAILURE", e.message)
          case Left(e) if m.receiveCount >= settings.maxReceiveCount =>
            bury(m, "RECEIVES_EXHAUSTED", s"after ${m.receiveCount} receives: ${e.message}")
          case Left(e) =>
            reject(m, "TRANSIENT_FAILURE", e.message)
            queue.changeVisibility(m.receiptHandle, settings.retryBackoffSeconds) match
              case Left(f) => MessageOutcome.QueueFault(s"backoff failed: ${f.message}")
              case Right(_) => MessageOutcome.Retry(e.message)

  /** One receive and the handling of what it returned. */
  def runOnce(max: Int = settings.batchSize): Result[ConsumerReport] =
    queue
      .receive(max.min(settings.batchSize), settings.visibilityTimeoutSeconds, settings.waitSeconds)
      .map(ms => ConsumerReport(ms.size, ms.map(m => m.messageId -> process(m))))

  /** Receives until the queue returns nothing or `maxMessagesPerRun` messages were taken. */
  def drain(): Result[ConsumerReport] =
    var taken = 0
    var all = Vector.empty[(String, MessageOutcome)]
    var failure: Option[StorageError] = None
    var more = true
    while more && failure.isEmpty && taken < settings.maxMessagesPerRun do
      runOnce(settings.maxMessagesPerRun - taken) match
        case Left(e) => failure = Some(e)
        case Right(r) =>
          taken += r.received
          all ++= r.outcomes
          more = r.received > 0
    failure.toLeft(ConsumerReport(taken, all))
