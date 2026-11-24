package scholarchain.storage.queue

import scala.collection.mutable
import scholarchain.storage.common.*

/** One received message. `receiveCount` is the queue's own count of deliveries (SQS
  * `ApproximateReceiveCount`), so a poison message is recognised across consumers and restarts.
  */
final case class QueueMessage(
    messageId: String,
    receiptHandle: String,
    body: String,
    receiveCount: Int
)

/** The narrow queue seam of the embedding work queue (at-least-once delivery). The queue holds
  * pointers to work (run ids), never document text.
  */
trait WorkQueue:
  def send(body: String): Result[String]

  /** At most `max` messages, hidden from other consumers for `visibilityTimeoutSeconds`. */
  def receive(
      max: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int
  ): Result[Vector[QueueMessage]]

  /** Acknowledges: removes the message. */
  def delete(receiptHandle: String): Result[Unit]

  /** Makes a received message visible again after `seconds` (0 = at once). */
  def changeVisibility(receiptHandle: String, seconds: Int): Result[Unit]

/** Local fake with SQS semantics: a received message is invisible until its visibility timeout
  * passes, redelivery increments the receive count, and a stale receipt handle cannot delete.
  */
final class InMemoryWorkQueue(nowMillis: () => Long = () => System.currentTimeMillis())
    extends WorkQueue:
  final private class Entry(val id: String, val body: String):
    var visibleAt: Long = 0L
    var receives: Int = 0
    var handle: String = ""

  private val entries = mutable.LinkedHashMap.empty[String, Entry]
  private var seq = 0L

  def send(body: String): Result[String] = synchronized {
    seq += 1
    val id = s"m-$seq"
    entries(id) = new Entry(id, body)
    Right(id)
  }

  def receive(
      max: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int
  ): Result[Vector[QueueMessage]] = synchronized {
    val now = nowMillis()
    val picked = entries.values.filter(_.visibleAt <= now).take(max).toVector
    Right(picked.map { e =>
      e.receives += 1
      seq += 1
      e.handle = s"h-${e.id}-${e.receives}-$seq"
      e.visibleAt = now + visibilityTimeoutSeconds * 1000L
      QueueMessage(e.id, e.handle, e.body, e.receives)
    })
  }

  def delete(receiptHandle: String): Result[Unit] = synchronized {
    entries.values.find(_.handle == receiptHandle) match
      case Some(e) => entries.remove(e.id); Right(())
      case None => Left(StorageError.NotFound(receiptHandle))
  }

  def changeVisibility(receiptHandle: String, seconds: Int): Result[Unit] = synchronized {
    entries.values.find(_.handle == receiptHandle) match
      case Some(e) => e.visibleAt = nowMillis() + seconds * 1000L; Right(())
      case None => Left(StorageError.NotFound(receiptHandle))
  }

  /** Test inspection: bodies still on the queue, visible or not. */
  def pendingBodies: Vector[String] = synchronized(entries.values.map(_.body).toVector)
  def size: Int = synchronized(entries.size)
