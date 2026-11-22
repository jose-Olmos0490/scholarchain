package scholarchain.storage.common

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer
import upickle.default.*

/** A record a loader refused to write, with a stable reason code and context. */
final case class StoreReject(
    store: String,
    key: String,
    reasonCode: String,
    detail: String,
    runId: String
) derives ReadWriter

/** Destination of rejects. Loaders record every reject and keep going; a reject never vanishes. */
trait RejectSink:
  def record(reject: StoreReject): Unit
  def all: Vector[StoreReject]

final class InMemoryRejectSink extends RejectSink:
  private val buf = ArrayBuffer.empty[StoreReject]
  def record(reject: StoreReject): Unit = synchronized { buf += reject; () }
  def all: Vector[StoreReject] = synchronized(buf.toVector)

/** Appends one JSON object per line to a file (`rejected-<store>.jsonl`). */
final class JsonLinesRejectSink(path: Path) extends RejectSink:
  private val mem = new InMemoryRejectSink
  def record(reject: StoreReject): Unit = synchronized {
    mem.record(reject)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(
      path,
      (write(reject) + "\n").getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
    ()
  }
  def all: Vector[StoreReject] = mem.all
