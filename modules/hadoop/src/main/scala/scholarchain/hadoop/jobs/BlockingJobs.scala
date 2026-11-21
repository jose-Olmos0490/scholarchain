package scholarchain.hadoop.jobs

import java.lang.Iterable as JIterable
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import scala.collection.mutable
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.blocking.*
import scholarchain.hadoop.io.*
import upickle.default.{read, write}

/** Per-task statistic row (written once per reduce task in `cleanup`). `key` is the record or
  * block key that holds the maximum; ties go to the smallest key.
  */
final case class StatRow(job: String, metric: String, value: Long, key: String, runId: String)
    derives upickle.default.ReadWriter

/** Running maximum with a deterministic tie-break (smallest key). Per-task state. */
final class MaxTracker:
  var value: Long = -1
  var key: String = ""
  def offer(v: Long, k: String): Unit =
    if v > value || (v == value && k.compareTo(key) < 0) then
      value = v
      key = k

/** J05 BlockingKeyJob / J05b FallbackBlockingJob / J06 CandidateJoinJob. See JOBS.md. */
object BlockingJobs:
  val PrimarySlug = "j05"
  val FallbackSlug = "j05b"
  val CandidateSlug = "j06"
  val BlockTable = "blocking_block"
  val FallbackBlockTable = "blocking_block_fallback"
  val RekeyedTable = "blocking_rekeyed"
  val OversizeTable = "oversize_block"
  val CandidateTable = "match_candidate"
  val StatsPrimary = "stats_j05"
  val StatsFallback = "stats_j05b"

/** J05 mapper: `(offset, WorkRecord line) -> (Text primaryBlockKey, Text BlockMember-json)`. */
class BlockingKeyMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var table: Layout.TableRef = null
  private var sink: TableSink = null
  private var runId: String = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    val path = context.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    table = Layout.parseTableRef(path).getOrElse(throw new IllegalStateException(s"$path"))
    sink = new TableSink(context)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    WireCodec.decode[WorkRecord](value.toString) match
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        sink.reject(
          TableSink.stageRejectsDir(context.getConfiguration),
          Records.reject(
            JobSupport.sourceOfDataset(table.dataset),
            s"${table.dataset}/${table.table}@${key.get}",
            table.snapshot,
            runId,
            BlockingJobs.PrimarySlug,
            "SCHEMA_FAILURE",
            s"${errors.mkString("; ")} | $value"
          )
        )
      case Right(w) =>
        BlockingKeys.members(w).foreach { m =>
          BlockingKeys.primary(m) match
            case None => context.getCounter(HadoopCounter.MISSING_KEY).increment(1)
            case Some(bk) =>
              k.set(bk)
              v.set(write(m))
              context.write(k, v)
        }

  override def cleanup(context: Ctx): Unit = sink.close()

/** Shared bounded-group logic of J05 and J05b reducers.
  *
  * Values are buffered only up to `maxBlockSize`; the first member beyond the bound switches the
  * group to overflow mode, in which the buffered members and every remaining member are streamed
  * to `overflow` one by one. Memory per group is therefore bounded by `maxBlockSize`.
  */
abstract class BoundedBlockReducer extends Reducer[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  protected var sink: TableSink = null
  protected var runId: String = null
  protected var seed: String = null
  protected var maxBlockSize = 0
  protected val largest = new MaxTracker

  protected def slug: String
  protected def blockTable: String
  protected def statsTable: String
  protected def overflow(blockKey: String, m: BlockMember, context: Ctx): Unit
  protected def overflowGroupDone(blockKey: String, size: Long, context: Ctx): Unit

  override def setup(context: Ctx): Unit =
    val conf = context.getConfiguration
    sink = new TableSink(context)
    runId = PipelineConf.required(conf, PipelineConf.RunId)
    seed = PipelineConf.required(conf, PipelineConf.SeedSnapshot)
    maxBlockSize = PipelineConf.requiredInt(conf, PipelineConf.MaxBlockSize)

  protected def member(t: Text): BlockMember

  override def reduce(key: Text, values: JIterable[Text], context: Ctx): Unit =
    val blockKey = key.toString
    val buffer = mutable.ArrayBuffer.empty[BlockMember]
    var size = 0L
    var overflowing = false
    val it = values.iterator()
    while it.hasNext do
      val m = member(it.next())
      size += 1
      if overflowing then overflow(blockKey, m, context)
      else if buffer.size < maxBlockSize then buffer += m
      else
        overflowing = true
        buffer.foreach(overflow(blockKey, _, context))
        buffer.clear()
        overflow(blockKey, m, context)
    largest.offer(size, blockKey)
    if overflowing then overflowGroupDone(blockKey, size, context)
    else if buffer.size >= 2 then
      val block = Block(blockKey, buffer.head.entityType, buffer.sortBy(_.ref).toVector)
      sink.record(Layout.tableDir("scholarchain", blockTable, seed, runId), write(block))
      context.getCounter(HadoopCounter.BLOCKS_EMITTED).increment(1)
      context.getCounter(HadoopCounter.ACCEPTED).increment(1)

  override def cleanup(context: Ctx): Unit =
    if largest.value >= 0 then
      sink.record(
        Layout.tableDir("scholarchain", statsTable, seed, runId),
        write(StatRow(slug, "largestBlockingGroup", largest.value, largest.key, runId))
      )
      context.getCounter(HadoopCounter.SIDE_OUTPUT_RECORDS).increment(1)
    sink.close()

/** J05 reducer: `(primaryKey, [BlockMember]) -> blocking_block | blocking_rekeyed`. */
class BlockingKeyReducer extends BoundedBlockReducer:
  protected def slug = BlockingJobs.PrimarySlug
  protected def blockTable = BlockingJobs.BlockTable
  protected def statsTable = BlockingJobs.StatsPrimary
  protected def member(t: Text): BlockMember = read[BlockMember](t.toString)

  protected def overflow(blockKey: String, m: BlockMember, context: Ctx): Unit =
    BlockingKeys.fallback(m) match
      case Some(fk) =>
        sink.record(
          Layout.tableDir("scholarchain", BlockingJobs.RekeyedTable, seed, runId),
          write(Rekeyed(fk, m))
        )
        context.getCounter(HadoopCounter.REKEYED_MEMBERS).increment(1)
      case None => context.getCounter(HadoopCounter.MISSING_KEY).increment(1)

  protected def overflowGroupDone(blockKey: String, size: Long, context: Ctx): Unit = ()

/** J05b mapper: `(offset, Rekeyed line) -> (Text fallbackKey, Text BlockMember-json)`. */
class FallbackBlockingMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private val k = new Text()
  private val v = new Text()

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    val r = read[Rekeyed](value.toString)
    k.set(r.fallbackKey)
    v.set(write(r.member))
    context.write(k, v)

/** J05b reducer: `(fallbackKey, [BlockMember]) -> blocking_block_fallback | oversize_block`.
  * Oversize members are written as `RejectRecord` rows (reason OVERSIZE_BLOCK) to the
  * `scholarchain/oversize_block` table: never truncated, never silently dropped.
  */
class FallbackBlockingReducer extends BoundedBlockReducer:
  protected def slug = BlockingJobs.FallbackSlug
  protected def blockTable = BlockingJobs.FallbackBlockTable
  protected def statsTable = BlockingJobs.StatsFallback
  protected def member(t: Text): BlockMember = read[BlockMember](t.toString)

  protected def overflow(blockKey: String, m: BlockMember, context: Ctx): Unit =
    val r = Records.reject(
      m.source,
      m.ref,
      m.provenance.snapshot,
      runId,
      BlockingJobs.FallbackSlug,
      "OVERSIZE_BLOCK",
      s"fallbackKey=$blockKey exceeds maxBlockSize=$maxBlockSize"
    )
    Records.json(r) match
      case Right(json) =>
        sink.record(Layout.tableDir("scholarchain", BlockingJobs.OversizeTable, seed, runId), json)
        context.getCounter(HadoopCounter.OVERSIZE_BLOCK_MEMBERS).increment(1)
      case Left(_) => context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)

  protected def overflowGroupDone(blockKey: String, size: Long, context: Ctx): Unit =
    context.getCounter(HadoopCounter.OVERSIZE_BLOCK).increment(1)

/** J06 mapper: `(offset, Block line) -> (Text candidateId, Text counted MatchCandidate-json)` for
  * every pair of members in the block.
  */
class CandidateJoinMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var runId: String = null
  private var seed: String = null
  private var sink: TableSink = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)
    seed = PipelineConf.required(context.getConfiguration, PipelineConf.SeedSnapshot)
    sink = new TableSink(context)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    val block = read[Block](value.toString)
    val ms = block.members
    var i = 0
    while i < ms.size do
      var j = i + 1
      while j < ms.size do
        MatchFeatures.candidate(block.blockKey, ms(i), ms(j), seed, runId).foreach { c =>
          Records.json(c) match
            case Right(json) =>
              k.set(c.candidateId)
              v.set(Counted.encode(1, json))
              context.write(k, v)
            case Left(errors) =>
              context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
              sink.reject(
                TableSink.stageRejectsDir(context.getConfiguration),
                Records.reject(
                  SourceType.SCHOLARCHAIN,
                  c.candidateId,
                  seed,
                  runId,
                  BlockingJobs.CandidateSlug,
                  "SCHEMA_FAILURE",
                  errors
                )
              )
        }
        j += 1
      i += 1

  override def cleanup(context: Ctx): Unit = sink.close()

/** J06 combiner: keeps the smallest JSON per candidate ID and sums occurrences. */
class CandidateMinCombiner extends Reducer[Text, Text, Text, Text]:
  private val out = new Text()
  override def reduce(
      key: Text,
      values: JIterable[Text],
      context: Reducer[Text, Text, Text, Text]#Context
  ): Unit =
    val t = Counted.tally(values)
    out.set(Counted.encode(t.values.sum, t.head._1))
    context.write(key, out)

/** J06 reducer: `(candidateId, [counted json]) -> match_candidate` (one row per candidate; the
  * row from the smallest block key wins because the JSON with the smallest blockKey sorts first
  * among otherwise equal rows).
  */
class CandidateJoinReducer extends Reducer[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var sink: TableSink = null
  private var dir: String = null

  override def setup(context: Ctx): Unit =
    sink = new TableSink(context)
    val conf = context.getConfiguration
    dir = Layout.tableDir(
      "scholarchain",
      BlockingJobs.CandidateTable,
      PipelineConf.required(conf, PipelineConf.SeedSnapshot),
      PipelineConf.required(conf, PipelineConf.RunId)
    )

  override def reduce(key: Text, values: JIterable[Text], context: Ctx): Unit =
    val t = Counted.tally(values)
    val total = t.values.sum
    if total > 1 then context.getCounter(HadoopCounter.DUPLICATE).increment(total - 1)
    sink.record(dir, t.head._1)
    context.getCounter(HadoopCounter.EMITTED_MATCH_CANDIDATES).increment(1)
    context.getCounter(HadoopCounter.ACCEPTED).increment(1)

  override def cleanup(context: Ctx): Unit = sink.close()
