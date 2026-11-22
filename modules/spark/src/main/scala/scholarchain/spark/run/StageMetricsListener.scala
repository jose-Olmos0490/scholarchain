package scholarchain.spark.run

import java.util.concurrent.ConcurrentLinkedQueue
import org.apache.spark.scheduler.{SparkListener, SparkListenerStageCompleted}
import scala.jdk.CollectionConverters.*

/** Per-stage task and shuffle metrics of one run, recorded on the driver from listener events
  * (no task-side state). One entry per completed stage attempt, so the list is bounded by the
  * number of stages the run executes.
  */
final case class StageMetrics(
    stageId: Int,
    attempt: Int,
    name: String,
    numTasks: Int,
    shuffleReadBytes: Long,
    shuffleReadRecords: Long,
    shuffleWriteBytes: Long,
    shuffleWriteRecords: Long,
    inputRecords: Long,
    outputRecords: Long
)

final class StageMetricsListener extends SparkListener:
  private val stages = new ConcurrentLinkedQueue[StageMetrics]()

  override def onStageCompleted(event: SparkListenerStageCompleted): Unit =
    val info = event.stageInfo
    val m = info.taskMetrics
    if m != null then
      stages.add(
        StageMetrics(
          info.stageId,
          info.attemptNumber(),
          info.name,
          info.numTasks,
          m.shuffleReadMetrics.totalBytesRead,
          m.shuffleReadMetrics.recordsRead,
          m.shuffleWriteMetrics.bytesWritten,
          m.shuffleWriteMetrics.recordsWritten,
          m.inputMetrics.recordsRead,
          m.outputMetrics.recordsWritten
        )
      )

  /** The stages recorded so far, by stage ID and attempt. */
  def snapshot: Vector[StageMetrics] =
    stages.asScala.toVector.sortBy(s => (s.stageId, s.attempt))

  /** Waits until no new stage event has arrived for `quietMs` (listener events are asynchronous),
    * at most `maxMs`.
    */
  def awaitQuiet(quietMs: Long = 500, maxMs: Long = 10000): Unit =
    val deadline = System.currentTimeMillis() + maxMs
    var last = stages.size
    var stableSince = System.currentTimeMillis()
    while System.currentTimeMillis() < deadline &&
      System.currentTimeMillis() - stableSince < quietMs
    do
      Thread.sleep(50)
      val now = stages.size
      if now != last then
        last = now
        stableSince = System.currentTimeMillis()
