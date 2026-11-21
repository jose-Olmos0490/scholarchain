package scholarchain.hadoop.io

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.{
  FileOutputFormat,
  LazyOutputFormat,
  MultipleOutputs,
  TextOutputFormat
}
import org.apache.hadoop.mapreduce.{Job, JobContext, TaskInputOutputContext}
import scholarchain.core.model.{RejectRecord, SourceType}
import scholarchain.hadoop.HadoopCounter
import upickle.default.write

/** Text output whose existence check is done by the driver instead.
  *
  * Every job writes under the run's output root through MultipleOutputs base paths
  * (`<dataset>/<table>/snapshot=<s>/run_id=<d>/part`). The root itself exists after the first job,
  * so the default "output directory already exists" check is replaced by the driver's per-table
  * idempotency check (`PipelineDriver`), which never deletes anything.
  */
class TableOutputFormat extends TextOutputFormat[NullWritable, Text]:
  override def checkOutputSpecs(job: JobContext): Unit =
    if FileOutputFormat.getOutputPath(job) == null then
      throw new IllegalStateException("output root is not set")

/** Job wiring shared by every ScholarChain MapReduce job. */
object JobSupport:
  /** MultipleOutputs name for table records. */
  val RecordsOutput = "records"

  /** MultipleOutputs name for `RejectRecord` rows (work order B item 13). */
  val RejectsOutput = "rejects"

  /** Separator inside composite shuffle keys (`<tableDir>` SEP `<id>`). Never part of a path or
    * stable ID.
    */
  val Sep = '\u0001'

  def compositeKey(tableDir: String, id: String): String = s"$tableDir$Sep$id"

  def splitKey(key: String): (String, String) =
    val i = key.indexOf(Sep)
    if i < 0 then (key, "") else (key.substring(0, i), key.substring(i + 1))

  /** Output, compression and committer settings common to every job. */
  def configureOutput(
      job: Job,
      outputRoot: Path,
      intermediateCodec: String,
      outputCodec: String
  ): Unit =
    val conf = job.getConfiguration
    LazyOutputFormat.setOutputFormatClass(job, classOf[TableOutputFormat])
    MultipleOutputs.addNamedOutput(
      job,
      RecordsOutput,
      classOf[TableOutputFormat],
      classOf[NullWritable],
      classOf[Text]
    )
    MultipleOutputs.addNamedOutput(
      job,
      RejectsOutput,
      classOf[TableOutputFormat],
      classOf[NullWritable],
      classOf[Text]
    )
    FileOutputFormat.setOutputPath(job, outputRoot)
    FileOutputFormat.setCompressOutput(job, true)
    FileOutputFormat.setOutputCompressorClass(
      job,
      conf.getClassByName(outputCodec).asSubclass(classOf[CompressionCodec])
    )
    // Map-output (intermediate) compression with the configured codec.
    conf.setBoolean("mapreduce.map.output.compress", true)
    conf.set("mapreduce.map.output.compress.codec", intermediateCodec)
    // The driver writes the per-table _SUCCESS markers after the job succeeded.
    conf.setBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", false)

  def datasetDir(source: SourceType): String = source.toString.toLowerCase(java.util.Locale.ROOT)

  def sourceOfDataset(dataset: String): SourceType =
    SourceType.values
      .find(s => datasetDir(s) == dataset)
      .getOrElse(throw new IllegalArgumentException(s"unknown dataset directory '$dataset'"))

/** Writes table records and rejects from a map or reduce task. One instance per task (created in
  * `setup`, closed in `cleanup`); nothing is shared across tasks.
  */
final class TableSink(context: TaskInputOutputContext[?, ?, ?, ?]):
  private val mos =
    new MultipleOutputs[NullWritable, Text](
      context.asInstanceOf[TaskInputOutputContext[?, ?, NullWritable, Text]]
    )
  private val text = new Text()

  def record(tableDir: String, json: String): Unit =
    text.set(json)
    mos.write(JobSupport.RecordsOutput, NullWritable.get, text, Layout.partBase(tableDir))

  def reject(tableDir: String, r: RejectRecord): Unit =
    // A reject that does not validate is still written (never dropped) and counted.
    scholarchain.core.json.WireCodec.encodeValidated(r) match
      case Right(json) => text.set(json)
      case Left(_) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        text.set(write(r))
    mos.write(JobSupport.RejectsOutput, NullWritable.get, text, Layout.partBase(tableDir))
    context.getCounter(HadoopCounter.REJECTED).increment(1)

  def close(): Unit = mos.close()

object TableSink:
  /** Rejects table of one job under the `scholarchain` dataset at the seed snapshot. Used by
    * jobs that read pipeline tables (J03, J05, J06, J09) rather than raw source files.
    */
  def stageRejectsDir(conf: Configuration): String =
    rejectsDir(conf, "scholarchain", PipelineConf.required(conf, PipelineConf.SeedSnapshot))

  /** Rejects table of one job for one dataset and snapshot. */
  def rejectsDir(conf: Configuration, dataset: String, snapshot: String): String =
    Layout.tableDir(
      dataset,
      s"rejects_${PipelineConf.required(conf, PipelineConf.JobSlug)}",
      snapshot,
      PipelineConf.required(conf, PipelineConf.RunId)
    )
