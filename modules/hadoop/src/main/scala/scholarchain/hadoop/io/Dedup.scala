package scholarchain.hadoop.io

import java.lang.Iterable as JIterable
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.Reducer
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.hadoop.HadoopCounter

/** Counted values: `<occurrences> TAB <json>`. A serialized JSON line never contains a raw tab
  * (uPickle escapes control characters), so the first tab is the separator.
  */
object Counted:
  def encode(n: Long, json: String): String = s"$n\t$json"

  def decode(v: String): (Long, String) =
    val i = v.indexOf('\t')
    (v.substring(0, i).toLong, v.substring(i + 1))

  /** Occurrences per distinct JSON value, sorted by JSON (deterministic). */
  def tally(values: JIterable[Text]): mutable.TreeMap[String, Long] =
    val m = mutable.TreeMap.empty[String, Long]
    values.asScala.foreach { t =>
      val (n, j) = decode(t.toString)
      m.updateWith(j)(o => Some(o.getOrElse(0L) + n))
    }
    m

/** Combiner for counted values: merges occurrences of identical values. Safe to run zero or more
  * times because it only sums counts per distinct value.
  */
class DedupCombiner extends Reducer[Text, Text, Text, Text]:
  private val out = new Text()

  override def reduce(
      key: Text,
      values: JIterable[Text],
      context: Reducer[Text, Text, Text, Text]#Context
  ): Unit =
    Counted.tally(values).foreach { (json, n) =>
      out.set(Counted.encode(n, json))
      context.write(key, out)
    }

/** Generic de-duplicating reducer.
  *
  * Input key: `<tableDir> SEP <stableId>` ([[JobSupport.compositeKey]]); input values: counted
  * JSON lines. Output: one JSON line per key in `<tableDir>/part-r-NNNNN`.
  *
  *   - one distinct value: written; extra identical occurrences add to DUPLICATE, except in
  *     dimension tables (`venue`, `institution`, `topic`), where repetition is expected
  *   - several distinct values: CONFLICT; the lexicographically smallest JSON is written and the
  *     others go to the job's rejects table with reason `CONFLICTING_RECORD` (dimension tables:
  *     the others are dropped after counting, because they describe the same entity)
  */
class DedupReducer extends Reducer[Text, Text, NullWritable, Text]:
  private var sink: TableSink = null

  override def setup(context: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    sink = new TableSink(context)

  override def reduce(
      key: Text,
      values: JIterable[Text],
      context: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit =
    val (tableDir, id) = JobSupport.splitKey(key.toString)
    val ref = Layout.parseTableRef(tableDir + "/x")
    val dimension = ref.exists(r => DedupReducer.DimensionTables.contains(r.table))
    val tally = Counted.tally(values)
    val (kept, n) = tally.head
    val total = tally.values.sum
    if !dimension && total > tally.size then
      context.getCounter(HadoopCounter.DUPLICATE).increment(total - tally.size)
    if tally.size > 1 then
      context.getCounter(HadoopCounter.CONFLICT).increment(1)
      if !dimension then
        ref.foreach { r =>
          tally.keysIterator.drop(1).foreach { other =>
            sink.reject(
              TableSink.rejectsDir(context.getConfiguration, r.dataset, r.snapshot),
              Records.reject(
                JobSupport.sourceOfDataset(r.dataset),
                id,
                r.snapshot,
                PipelineConf.required(context.getConfiguration, PipelineConf.RunId),
                PipelineConf.required(context.getConfiguration, PipelineConf.JobSlug),
                "CONFLICTING_RECORD",
                other
              )
            )
          }
        }
    sink.record(tableDir, kept)
    context.getCounter(HadoopCounter.ACCEPTED).increment(1)

  override def cleanup(context: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    sink.close()

object DedupReducer:
  val DimensionTables: Set[String] = Set("venue", "institution", "topic")
