package scholarchain.hadoop.jobs

import java.lang.Iterable as JIterable
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import scala.collection.mutable
import scholarchain.core.ids.{ExternalIds, StableIds}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.io.*
import upickle.default.{ReadWriter, read, write}

/** One record carrying an exact identifier (J03 map output value). */
final case class IdRef(ref: String, source: SourceType, workId: Option[String], year: Option[Int])
    derives ReadWriter

/** J03+J04 ExactIdentifierJob (merged): normalized records -> exact-identifier outcomes.
  *
  * Mapper (J03, keyed index) `(offset, WorkRecord | GraphNode line) -> (Text "<ExactIdType> SEP
  * <normalizedId>", Text IdRef-json)`; no combiner; reducer (J04, join) `(id, [IdRef]) ->
  * ExactIdentifierOutcome` in `scholarchain/exact_identifier_outcome`.
  *
  * Outcome rule (producer-defined, see JOBS.md):
  *   - work identifiers (DOI, ARXIV, DBLP_KEY, OPENALEX_WORK): all records from one source ->
  *     DUPLICATE; several sources and more than one distinct non-empty year -> CONFLICT; else MATCH
  *   - researcher identifiers (ORCID, OPENALEX_AUTHOR): two of the records are mentions on the same
  *     work -> CONFLICT; else MATCH
  *   - fewer than two distinct records: no outcome (EXACT_ID_SINGLETONS)
  */
object ExactIdentifierJob:
  val Slug = "j03"
  val Table = "exact_identifier_outcome"

  /** Identifiers of one work record, with the ref they key. */
  def workKeys(w: WorkRecord): Vector[(ExactIdType, String, IdRef)] =
    val wref = IdRef(w.workId, w.source, Some(w.workId), w.year.flatMap(Option(_)))
    def one(t: ExactIdType, raw: Option[String]) =
      raw.flatMap(r => ExternalIds.normalizeExactId(t, r).toOption).map(n => (t, n, wref))
    val workLevel = Vector(
      one(ExactIdType.DOI, w.doi),
      one(ExactIdType.ARXIV, w.arxivId),
      if w.source == SourceType.DBLP then one(ExactIdType.DBLP_KEY, Some(w.sourceRecordId))
      else None,
      if w.source == SourceType.OPENALEX then one(ExactIdType.OPENALEX_WORK, Some(w.sourceRecordId))
      else None
    ).flatten
    val mentionLevel = w.authors.flatMap { m =>
      val mref = IdRef(m.mentionId, w.source, Some(w.workId), w.year.flatMap(Option(_)))
      m.orcid
        .flatMap(o => ExternalIds.normalizeOrcid(o).toOption)
        .map(o => (ExactIdType.ORCID, o, mref)) ++
        (if w.source == SourceType.OPENALEX then
           m.sourceAuthorKey
             .flatMap(a => ExternalIds.normalizeOpenAlex(a, 'A').toOption)
             .map(a => (ExactIdType.OPENALEX_AUTHOR, a, mref))
         else None)
    }
    workLevel ++ mentionLevel

  /** Identifiers of a researcher profile node (ORCID record, OpenAlex author). */
  def nodeKeys(n: GraphNode): Vector[(ExactIdType, String, IdRef)] =
    if n.nodeType != NodeType.Researcher then Vector.empty
    else
      val ref = IdRef(n.nodeId, n.provenance.sourceDataset, None, None)
      Vector(
        n.properties
          .get("orcid")
          .flatMap(o => ExternalIds.normalizeOrcid(o).toOption)
          .map(o => (ExactIdType.ORCID, o, ref)),
        n.properties
          .get("openalexAuthorId")
          .flatMap(a => ExternalIds.normalizeOpenAlex(a, 'A').toOption)
          .map(a => (ExactIdType.OPENALEX_AUTHOR, a, ref))
      ).flatten

  def outcome(idType: ExactIdType, refs: Vector[IdRef]): ExactIdOutcome =
    if idType.identifiesResearchers then
      val works = refs.flatMap(_.workId)
      if works.distinct.size != works.size then ExactIdOutcome.CONFLICT else ExactIdOutcome.MATCH
    else if refs.map(_.source).distinct.size == 1 then ExactIdOutcome.DUPLICATE
    else if refs.flatMap(_.year).distinct.size > 1 then ExactIdOutcome.CONFLICT
    else ExactIdOutcome.MATCH

class ExactIdentifierMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var table: Layout.TableRef = null
  private var sink: TableSink = null
  private var runId: String = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    val path = context.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    table = Layout
      .parseTableRef(path)
      .getOrElse(throw new IllegalStateException(s"$path is not a table part file"))
    sink = new TableSink(context)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    val line = value.toString
    val keys: Either[String, Vector[(ExactIdType, String, IdRef)]] =
      if table.table == "work" then
        WireCodec
          .decode[WorkRecord](line)
          .left
          .map(_.mkString("; "))
          .map(ExactIdentifierJob.workKeys)
      else
        WireCodec
          .decode[GraphNode](line)
          .left
          .map(_.mkString("; "))
          .map(ExactIdentifierJob.nodeKeys)
    keys match
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        sink.reject(
          TableSink.stageRejectsDir(context.getConfiguration),
          Records.reject(
            JobSupport.sourceOfDataset(table.dataset),
            s"${table.dataset}/${table.table}@${key.get}",
            table.snapshot,
            runId,
            ExactIdentifierJob.Slug,
            "SCHEMA_FAILURE",
            s"$errors | $line"
          )
        )
      case Right(ks) =>
        ks.foreach { (t, id, ref) =>
          k.set(JobSupport.compositeKey(t.toString, id))
          v.set(write(ref))
          context.write(k, v)
        }

  override def cleanup(context: Ctx): Unit = sink.close()

class ExactIdentifierReducer extends Reducer[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var sink: TableSink = null
  private var runId: String = null
  private var dir: String = null
  private var seed: String = null

  override def setup(context: Ctx): Unit =
    sink = new TableSink(context)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)
    seed = PipelineConf.required(context.getConfiguration, PipelineConf.SeedSnapshot)
    dir = Layout.tableDir("scholarchain", ExactIdentifierJob.Table, seed, runId)

  override def reduce(key: Text, values: JIterable[Text], context: Ctx): Unit =
    val (typeName, normalized) = JobSupport.splitKey(key.toString)
    val idType = ExactIdType.valueOf(typeName)
    val refs = mutable.TreeMap.empty[String, IdRef]
    values.forEach(t =>
      val r = read[IdRef](t.toString)
      refs.getOrElseUpdate(r.ref, r)
    )
    if refs.size < 2 then context.getCounter(HadoopCounter.EXACT_ID_SINGLETONS).increment(1)
    else
      val grouped = refs.values.toVector
      val result = ExactIdentifierJob.outcome(idType, grouped)
      val outcomeId = StableIds.exactIdOutcomeId(idType, normalized).toOption.get
      val record = ExactIdentifierOutcome(
        schemaVersion = SchemaVersions.ExactIdentifierOutcome,
        outcomeId = outcomeId,
        idType = idType,
        normalizedId = normalized,
        recordRefs = refs.keys.toVector,
        outcome = result,
        provenance =
          Records.provenance(SourceType.SCHOLARCHAIN, outcomeId, seed, runId, refs.keys.toVector)
      )
      Records.json(record) match
        case Right(json) =>
          sink.record(dir, json)
          context.getCounter(HadoopCounter.EXACT_ID_OUTCOMES).increment(1)
          context.getCounter(HadoopCounter.ACCEPTED).increment(1)
          result match
            case ExactIdOutcome.DUPLICATE =>
              context.getCounter(HadoopCounter.DUPLICATE).increment(1)
            case ExactIdOutcome.CONFLICT => context.getCounter(HadoopCounter.CONFLICT).increment(1)
            case ExactIdOutcome.MATCH => ()
        case Left(errors) =>
          context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
          sink.reject(
            TableSink.stageRejectsDir(context.getConfiguration),
            Records.reject(
              SourceType.SCHOLARCHAIN,
              outcomeId,
              seed,
              runId,
              ExactIdentifierJob.Slug,
              "SCHEMA_FAILURE",
              errors
            )
          )

  override def cleanup(context: Ctx): Unit = sink.close()
