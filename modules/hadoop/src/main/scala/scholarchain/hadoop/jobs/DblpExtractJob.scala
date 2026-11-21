package scholarchain.hadoop.jobs

import java.lang.Iterable as JIterable
import java.net.URI
import java.nio.charset.{Charset, StandardCharsets}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.hadoop.io.{BytesWritable, LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.{Mapper, Reducer, TaskInputOutputContext}
import scholarchain.core.model.SourceType
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.dblp.*
import scholarchain.hadoop.io.*
import upickle.default.{read, write}

/** J01 DblpExtractJob: DBLP XML -> normalized DBLP works (`dblp/work_extracted`).
  *
  * Mapper `(LongWritable recordOffset, BytesWritable recordBytes) -> (Text workId, Text
  * DblpWork-json)`. Reducer `(workId, [DblpWork-json]) -> work_extracted row`, plus a per-reducer
  * work-ID bucket histogram (`dblp/cap_histogram`) for the deterministic maxWorks cap. See
  * modules/hadoop/JOBS.md.
  */
object DblpExtractJob:
  val Slug = "j01"
  val WorkExtractedTable = "work_extracted"
  val HistogramTable = "cap_histogram"

  /** Number of cap buckets: the first four hex digits of a work ID digest. */
  val Buckets = 65536

  /** Cap bucket of a work ID `work:<ns>:<64 hex>`: its first four digest hex digits. */
  def bucket(workId: String): Int =
    val digest = workId.substring(workId.lastIndexOf(':') + 1)
    Integer.parseInt(digest.substring(0, 4), 16)

  /** Reads a distributed-cache file: the localized symlink when present, else the cached URI. */
  def readCacheFile(context: TaskInputOutputContext[?, ?, ?, ?], name: String): Array[Byte] =
    val local = new java.io.File(name)
    if local.isFile then java.nio.file.Files.readAllBytes(local.toPath)
    else
      val uris = Option(context.getCacheFiles).map(_.toVector).getOrElse(Vector.empty)
      val uri = uris
        .find(u => u.getFragment == name)
        .getOrElse(
          throw new IllegalStateException(
            s"distributed-cache file '$name' is missing (cache: ${uris.mkString(", ")})"
          )
        )
      val plain = new URI(uri.getScheme, uri.getAuthority, uri.getPath, null, null)
      val path = new Path(plain)
      val in = path.getFileSystem(context.getConfiguration).open(path)
      try in.readAllBytes()
      finally in.close()

  /** Charset declared by the XML prolog at the start of `file` (UTF-8 if none). */
  def fileEncoding(conf: Configuration, file: Path): Charset =
    val fs = file.getFileSystem(conf)
    val raw = fs.open(file)
    val codec = new CompressionCodecFactory(conf).getCodec(file)
    val in = if codec == null then raw else codec.createInputStream(raw)
    try
      val head = new Array[Byte](1024)
      var n = 0
      var r = 0
      while n < head.length && { r = in.read(head, n, head.length - n); r > 0 } do n += r
      Records.xmlEncoding(head, n)
    finally in.close()

  private val KeyAttr = """\bkey\s*=\s*"([^"]*)"""".r

  /** Best locator of a raw record: its key attribute, else `<file>@<offset>`. */
  def recordRef(raw: String, file: String, offset: Long): String =
    KeyAttr
      .findFirstMatchIn(raw)
      .map(_.group(1).trim)
      .filter(_.nonEmpty)
      .getOrElse(s"$file@$offset")

class DblpExtractMapper extends Mapper[LongWritable, BytesWritable, Text, Text]:
  type Ctx = Mapper[LongWritable, BytesWritable, Text, Text]#Context
  private var sink: TableSink = null
  private var entities: DtdEntities = null
  private var charset: Charset = StandardCharsets.UTF_8
  private var ctx: DblpContext = null
  private var input: InputFile = null
  private var rejectsDir: String = null
  private val outKey = new Text()
  private val outValue = new Text()

  override def setup(context: Ctx): Unit =
    val conf = context.getConfiguration
    sink = new TableSink(context)
    val split = context.getInputSplit.asInstanceOf[FileSplit]
    input = PipelineConf
      .inputFor(conf, split.getPath.toString)
      .getOrElse(throw new IllegalStateException(s"split ${split.getPath} is not a run input"))
    entities = DtdEntities.load(
      DblpExtractJob.readCacheFile(context, PipelineConf.required(conf, PipelineConf.DtdCacheName))
    )
    charset = DblpExtractJob.fileEncoding(conf, split.getPath)
    ctx = DblpContext(
      recordTypes = DblpInputFormat.recordTypes(conf).toSet,
      yearFrom = PipelineConf.requiredInt(conf, PipelineConf.YearFrom),
      yearTo = PipelineConf.requiredInt(conf, PipelineConf.YearTo),
      snapshot = input.snapshot,
      runId = PipelineConf.required(conf, PipelineConf.RunId)
    )
    rejectsDir = TableSink.rejectsDir(conf, "dblp", input.snapshot)

  private def reject(code: String, raw: String, offset: Long): Unit =
    sink.reject(
      rejectsDir,
      Records.reject(
        SourceType.DBLP,
        DblpExtractJob.recordRef(raw, input.relative, offset),
        ctx.snapshot,
        ctx.runId,
        DblpExtractJob.Slug,
        code,
        raw
      )
    )

  override def map(key: LongWritable, value: BytesWritable, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    // Lenient decoding only for the reject excerpt; parsing uses strict decoding, so bytes that
    // are illegal in the declared encoding are MALFORMED_XML and never silently become U+FFFD.
    val raw = new String(value.getBytes, 0, value.getLength, charset)
    DblpRecordParser.parse(value.getBytes, value.getLength, charset, entities) match
      case Left(DblpParseFailure.UnknownEntity(name)) =>
        context.getCounter(HadoopCounter.UNKNOWN_ENTITY).increment(1)
        reject("UNKNOWN_ENTITY", s"&$name; | $raw", key.get)
      case Left(DblpParseFailure.Malformed(msg)) =>
        reject("MALFORMED_XML", s"$msg | $raw", key.get)
      case Right(rec) =>
        DblpNormalizer.normalize(rec, ctx) match
          case DblpOutcome.OutOfSubset(_) =>
            context.getCounter(HadoopCounter.OUT_OF_SUBSET).increment(1)
          case DblpOutcome.Rejected(code, detail) =>
            if code == "MISSING_KEY" then context.getCounter(HadoopCounter.MISSING_KEY).increment(1)
            reject(code, s"$detail | $raw", key.get)
          case DblpOutcome.Accepted(dw) =>
            Records.json(dw.work) match
              case Left(errors) =>
                context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
                reject("SCHEMA_FAILURE", s"$errors | $raw", key.get)
              case Right(_) =>
                outKey.set(dw.work.workId)
                outValue.set(write(dw))
                context.write(outKey, outValue)

  override def cleanup(context: Ctx): Unit = sink.close()

/** Keeps one DblpWork per work ID and builds the cap histogram (per-task state, written once in
  * `cleanup`).
  */
class DblpExtractReducer extends Reducer[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var sink: TableSink = null
  private var counts: Array[Long] = null
  private var snapshot: String = null
  private var runId: String = null

  override def setup(context: Ctx): Unit =
    sink = new TableSink(context)
    counts = new Array[Long](DblpExtractJob.Buckets)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)
    snapshot = PipelineConf.required(context.getConfiguration, PipelineConf.SeedSnapshot)

  override def reduce(key: Text, values: JIterable[Text], context: Ctx): Unit =
    val distinct = scala.collection.mutable.TreeMap.empty[String, Long]
    values.forEach(v => distinct.updateWith(v.toString)(o => Some(o.getOrElse(0L) + 1)))
    val total = distinct.values.sum
    if total > distinct.size then
      context.getCounter(HadoopCounter.DUPLICATE).increment(total - distinct.size)
    val kept = read[DblpWork](distinct.head._1)
    if distinct.size > 1 then
      context.getCounter(HadoopCounter.CONFLICT).increment(1)
      distinct.keysIterator.drop(1).foreach { other =>
        val w = read[DblpWork](other).work
        sink.reject(
          TableSink.rejectsDir(context.getConfiguration, "dblp", w.provenance.snapshot),
          Records.reject(
            SourceType.DBLP,
            w.sourceRecordId,
            w.provenance.snapshot,
            runId,
            DblpExtractJob.Slug,
            "CONFLICTING_RECORD",
            other
          )
        )
      }
    sink.record(
      Layout
        .tableDir("dblp", DblpExtractJob.WorkExtractedTable, kept.work.provenance.snapshot, runId),
      distinct.head._1
    )
    context.getCounter(HadoopCounter.ACCEPTED).increment(1)
    counts(DblpExtractJob.bucket(key.toString)) += 1

  override def cleanup(context: Ctx): Unit =
    val dir = Layout.tableDir("dblp", DblpExtractJob.HistogramTable, snapshot, runId)
    var i = 0
    while i < counts.length do
      if counts(i) > 0 then
        sink.record(dir, write(CapBucket(i, counts(i), runId, snapshot)))
        context.getCounter(HadoopCounter.SIDE_OUTPUT_RECORDS).increment(1)
      i += 1
    sink.close()

/** One histogram row: number of distinct DBLP works whose work-ID bucket is `bucket`. */
final case class CapBucket(bucket: Int, works: Long, runId: String, snapshot: String)
    derives upickle.default.ReadWriter
