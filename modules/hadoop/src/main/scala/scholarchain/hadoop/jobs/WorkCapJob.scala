package scholarchain.hadoop.jobs

import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import scholarchain.core.model.{GraphNode, NodeType, SourceType, Venue}
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.dblp.DblpWork
import scholarchain.hadoop.io.*
import upickle.default.read

/** J01C WorkCapJob: `dblp/work_extracted` -> capped `dblp/work`, `dblp/author_mention`,
  * `dblp/venue`, `dblp/work_node`.
  *
  * Cap rule (deterministic, `datasets.dblpSubset.maxWorks`): a work is kept iff the bucket of its
  * work ID (first four digest hex digits, 0..65535) is below `capBucketLimit`. The driver sets the
  * limit from the J01 histogram to the largest value whose kept total is <= maxWorks (65536, i.e.
  * keep everything, when the subset already fits). The kept set depends only on the set of work
  * IDs, never on input order or split layout.
  *
  * Mapper `(offset, work_extracted line) -> (Text "<tableDir> SEP <id>", Text counted-json)`;
  * combiner [[DedupCombiner]]; reducer [[DedupReducer]].
  */
object WorkCapJob:
  val Slug = "j01c"

class WorkCapMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var limit = 0
  private var runId: String = null
  private var sink: TableSink = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    limit = PipelineConf.requiredInt(context.getConfiguration, PipelineConf.CapBucketLimit)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)
    sink = new TableSink(context)

  private def emit(context: Ctx, table: String, snapshot: String, id: String, json: String): Unit =
    k.set(JobSupport.compositeKey(Layout.tableDir("dblp", table, snapshot, runId), id))
    v.set(Counted.encode(1, json))
    context.write(k, v)

  private def emitValidated[
      A: upickle.default.ReadWriter: scholarchain.core.validation.RecordContract
  ](
      context: Ctx,
      table: String,
      snapshot: String,
      id: String,
      a: A
  ): Unit =
    Records.json(a) match
      case Right(json) => emit(context, table, snapshot, id, json)
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        sink.reject(
          TableSink.rejectsDir(context.getConfiguration, "dblp", snapshot),
          Records.reject(
            SourceType.DBLP,
            id,
            snapshot,
            runId,
            WorkCapJob.Slug,
            "SCHEMA_FAILURE",
            errors
          )
        )

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    val dw = read[DblpWork](value.toString)
    val w = dw.work
    if DblpExtractJob.bucket(w.workId) >= limit then
      context.getCounter(HadoopCounter.CAPPED_WORKS).increment(1)
    else
      val snap = w.provenance.snapshot
      emitValidated(context, "work", snap, w.workId, w)
      w.authors.foreach(m => emitValidated(context, "author_mention", snap, m.mentionId, m))
      for
        vk <- dw.venueKey
        vid <- w.venueId
        name <- dw.venueName
      do
        val venue = Venue(
          schemaVersion = SchemaVersions.Venue,
          venueId = vid,
          name = name,
          venueType = Some(vk.takeWhile(_ != '/')),
          sourceVenueKey = Some(vk),
          issns = Vector.empty,
          provenance = Records.provenance(SourceType.DBLP, vk, snap, runId)
        )
        emitValidated(context, "venue", snap, vid, venue)
      val node = GraphNode(
        schemaVersion = SchemaVersions.GraphNode,
        nodeId = w.workId,
        nodeType = NodeType.Work,
        label = w.title,
        properties = Records.sorted(dw.properties + ("dblpKey" -> w.sourceRecordId)),
        provenance = w.provenance
      )
      emitValidated(context, "work_node", snap, w.workId, node)

  override def cleanup(context: Ctx): Unit = sink.close()
