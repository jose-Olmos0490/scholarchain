package scholarchain.hadoop.jobs

import java.nio.charset.StandardCharsets
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.{CompressionCodecFactory, SplittableCompressionCodec}
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit, LineRecordReader}
import org.apache.hadoop.mapreduce.{
  InputSplit,
  JobContext,
  Mapper,
  RecordReader,
  TaskAttemptContext
}
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.io.*
import scholarchain.hadoop.sources.*

/** Input format for external sources: JSON Lines files are read line by line (splittable when
  * uncompressed or bzip2); XML files (`*.xml`, one ORCID record each) are read whole, one value
  * per file, never split.
  */
class SourceInputFormat extends FileInputFormat[LongWritable, Text]:
  private def isXml(p: Path): Boolean =
    val n = p.getName.toLowerCase(java.util.Locale.ROOT)
    n.endsWith(".xml") || n.endsWith(".xml.gz") || n.endsWith(".xml.bz2")

  override protected def isSplitable(context: JobContext, file: Path): Boolean =
    if isXml(file) then false
    else
      val codec = new CompressionCodecFactory(context.getConfiguration).getCodec(file)
      codec == null || codec.isInstanceOf[SplittableCompressionCodec]

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, Text] =
    if isXml(split.asInstanceOf[FileSplit].getPath) then new WholeFileRecordReader
    else new LineRecordReader()

/** One value per file: the whole decoded file (charset from the XML declaration). */
class WholeFileRecordReader extends RecordReader[LongWritable, Text]:
  private var path: Path = null
  private var context: TaskAttemptContext = null
  private var done = false
  private val key = new LongWritable(0)
  private val value = new Text()

  override def initialize(split: InputSplit, ctx: TaskAttemptContext): Unit =
    path = split.asInstanceOf[FileSplit].getPath
    context = ctx

  override def nextKeyValue(): Boolean =
    if done then false
    else
      val conf = context.getConfiguration
      val raw = path.getFileSystem(conf).open(path)
      val codec = new CompressionCodecFactory(conf).getCodec(path)
      val in = if codec == null then raw else codec.createInputStream(raw)
      val bytes =
        try in.readAllBytes()
        finally in.close()
      val charset = Records.xmlEncoding(bytes, math.min(bytes.length, 1024))
      value.set(new String(bytes, charset).getBytes(StandardCharsets.UTF_8))
      done = true
      true

  override def getCurrentKey: LongWritable = key
  override def getCurrentValue: Text = value
  override def getProgress: Float = if done then 1f else 0f
  override def close(): Unit = ()

/** J02 SourceNormalizeJob: OpenAlex, Crossref and ORCID raw files -> normalized source tables.
  *
  * Mapper `(LongWritable offset, Text line-or-file) -> (Text "<tableDir> SEP <id>", Text
  * counted-json)`, dispatching on the input kind of its split; combiner [[DedupCombiner]];
  * reducer [[DedupReducer]].
  */
object SourceNormalizeJob:
  val Slug = "j02"

class SourceNormalizeMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var sink: TableSink = null
  private var input: InputFile = null
  private var sctx: SourceContext = null
  private var runId: String = null
  private var dataset: String = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    val conf = context.getConfiguration
    sink = new TableSink(context)
    val split = context.getInputSplit.asInstanceOf[FileSplit]
    input = PipelineConf
      .inputFor(conf, split.getPath.toString)
      .getOrElse(throw new IllegalStateException(s"split ${split.getPath} is not a run input"))
    runId = PipelineConf.required(conf, PipelineConf.RunId)
    sctx = SourceContext(input.snapshot, runId, input.relative)
    dataset = JobSupport.datasetDir(input.dataset)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    val line = value.toString
    if line.trim.nonEmpty then
      context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
      val emitted = input.kind match
        case InputKind.OpenAlexWorks => OpenAlexNormalizer.works(line, sctx)
        case InputKind.OpenAlexAuthors => OpenAlexNormalizer.authors(line, sctx)
        case InputKind.CrossrefWorks => CrossrefNormalizer.works(line, sctx)
        case InputKind.OrcidRecord => OrcidNormalizer.record(line, sctx)
        case InputKind.DblpXml =>
          throw new IllegalStateException(s"${input.relative} is DBLP XML; J01 reads it")
      emitted.foreach {
        case Emitted.Row(table, id, json) =>
          k.set(JobSupport.compositeKey(Layout.tableDir(dataset, table, input.snapshot, runId), id))
          v.set(Counted.encode(1, json))
          context.write(k, v)
        case Emitted.Reject(code, ref, detail) =>
          code match
            case "SCHEMA_FAILURE" => context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
            case "MISSING_KEY" => context.getCounter(HadoopCounter.MISSING_KEY).increment(1)
            case _ => ()
          val located = if ref == input.relative then s"${input.relative}@${key.get}" else ref
          sink.reject(
            TableSink.rejectsDir(context.getConfiguration, dataset, input.snapshot),
            Records.reject(
              input.dataset,
              located,
              input.snapshot,
              runId,
              SourceNormalizeJob.Slug,
              code,
              detail
            )
          )
      }

  override def cleanup(context: Ctx): Unit = sink.close()
