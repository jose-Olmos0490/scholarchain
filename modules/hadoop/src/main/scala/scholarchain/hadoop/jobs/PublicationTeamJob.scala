package scholarchain.hadoop.jobs

import java.lang.Iterable as JIterable
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.io.*
import scholarchain.hadoop.pairs.TeamExpansion
import upickle.default.write

/** J07+J08 PublicationTeamJob (merged with MultipleOutputs): `dblp/work` -> publication teams,
  * author-paper rows, coauthor pair candidates (v2), large and oversize team side outputs.
  *
  * Mapper `(offset, WorkRecord line) -> (Text workId, Text WorkRecord-json)`; no combiner;
  * reducer `(workId, [WorkRecord-json]) -> publication_team, author_paper, coauthor_pair,
  * large_team, oversize_team, stats_j07`.
  */
object PublicationTeamJob:
  val Slug = "j07"
  val Tables: Vector[String] =
    Vector("publication_team", "author_paper", "coauthor_pair", "large_team", "oversize_team")
  val StatsTable = "stats_j07"

class PublicationTeamMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var table: Layout.TableRef = null
  private var sink: TableSink = null
  private var runId: String = null
  private val k = new Text()

  override def setup(context: Ctx): Unit =
    val path = context.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    table = Layout.parseTableRef(path).getOrElse(throw new IllegalStateException(path))
    sink = new TableSink(context)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    WireCodec.decode[WorkRecord](value.toString) match
      case Right(w) =>
        k.set(w.workId)
        context.write(k, value)
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        sink.reject(
          TableSink.rejectsDir(context.getConfiguration, table.dataset, table.snapshot),
          Records.reject(
            JobSupport.sourceOfDataset(table.dataset),
            s"${table.dataset}/${table.table}@${key.get}",
            table.snapshot,
            runId,
            PublicationTeamJob.Slug,
            "SCHEMA_FAILURE",
            s"${errors.mkString("; ")} | $value"
          )
        )

  override def cleanup(context: Ctx): Unit = sink.close()

class PublicationTeamReducer extends Reducer[Text, Text, NullWritable, Text]:
  type Ctx = Reducer[Text, Text, NullWritable, Text]#Context
  private var sink: TableSink = null
  private var runId: String = null
  private var largeThreshold = 0
  private var maxExpansion = 0
  private val maxTeam = new MaxTracker
  private val maxExpansionSeen = new MaxTracker
  private var statsSnapshot: String = null

  override def setup(context: Ctx): Unit =
    val conf = context.getConfiguration
    sink = new TableSink(context)
    runId = PipelineConf.required(conf, PipelineConf.RunId)
    largeThreshold = PipelineConf.requiredInt(conf, PipelineConf.LargeTeamThreshold)
    maxExpansion = PipelineConf.requiredInt(conf, PipelineConf.MaxPairExpansionTeamSize)
    statsSnapshot = PipelineConf.required(conf, PipelineConf.SeedSnapshot)

  private def dir(work: WorkRecord, table: String): String =
    Layout.tableDir(
      JobSupport.datasetDir(work.provenance.sourceDataset),
      table,
      work.provenance.snapshot,
      runId
    )

  private def emit[A: upickle.default.ReadWriter: scholarchain.core.validation.RecordContract](
      work: WorkRecord,
      table: String,
      a: A,
      ref: String,
      context: Ctx
  ): Boolean =
    Records.json(a) match
      case Right(json) =>
        sink.record(dir(work, table), json)
        true
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        rejectWork(work, ref, "SCHEMA_FAILURE", errors, context)
        false

  private def rejectWork(
      work: WorkRecord,
      ref: String,
      code: String,
      detail: String,
      context: Ctx
  ): Unit =
    val dataset = JobSupport.datasetDir(work.provenance.sourceDataset)
    sink.reject(
      TableSink.rejectsDir(context.getConfiguration, dataset, work.provenance.snapshot),
      Records.reject(
        work.provenance.sourceDataset,
        ref,
        work.provenance.snapshot,
        runId,
        PublicationTeamJob.Slug,
        code,
        detail
      )
    )

  override def reduce(key: Text, values: JIterable[Text], context: Ctx): Unit =
    val distinct = scala.collection.mutable.TreeMap.empty[String, Long]
    values.forEach(v => distinct.updateWith(v.toString)(o => Some(o.getOrElse(0L) + 1)))
    val total = distinct.values.sum
    if total > distinct.size then
      context.getCounter(HadoopCounter.DUPLICATE).increment(total - distinct.size)
    if distinct.size > 1 then context.getCounter(HadoopCounter.CONFLICT).increment(1)
    val work = WireCodec.decode[WorkRecord](distinct.head._1).toOption.get
    distinct.keysIterator
      .drop(1)
      .foreach(other => rejectWork(work, work.sourceRecordId, "CONFLICTING_RECORD", other, context))
    val team = TeamExpansion.team(work)
    if team.removedDuplicates > 0 then
      context.getCounter(HadoopCounter.DUPLICATE_AUTHOR_IN_TEAM).increment(team.removedDuplicates)
    if team.size == 0 then
      // A work without authors has no team (PublicationTeam needs teamSize >= 1) and no pairs.
      ()
    else
      val pt = TeamExpansion.publicationTeam(team, runId)
      if emit(work, "publication_team", pt, work.workId, context) then
        context.getCounter(HadoopCounter.ACCEPTED).increment(1)
      TeamExpansion
        .authorPapers(team, runId)
        .foreach(ap => emit(work, "author_paper", ap, ap.mentionId, context))
      maxTeam.offer(team.size.toLong, work.workId)
      TeamExpansion.route(team.size, largeThreshold, maxExpansion) match
        case TeamExpansion.Route.Oversize =>
          context.getCounter(HadoopCounter.OVERSIZE_AUTHOR_TEAM).increment(1)
          context.getCounter(HadoopCounter.LARGE_AUTHOR_TEAM).increment(1)
          emit(work, "large_team", pt, work.workId, context)
          emit(work, "oversize_team", pt, work.workId, context)
        case TeamExpansion.Route.Expand(large) =>
          if large then
            context.getCounter(HadoopCounter.LARGE_AUTHOR_TEAM).increment(1)
            emit(work, "large_team", pt, work.workId, context)
          if team.size >= 2 then
            work.year.flatMap(Option(_)) match
              case None =>
                context.getCounter(HadoopCounter.MISSING_KEY).increment(1)
                rejectWork(work, work.sourceRecordId, "MISSING_YEAR", "pairs need a year", context)
              case Some(year) =>
                maxExpansionSeen.offer(TeamExpansion.pairCount(team.size), work.workId)
                var emitted = 0L
                TeamExpansion.pairs(team, year, runId).foreach {
                  case Right(p) =>
                    if emit(work, "coauthor_pair", p, p.candidateId, context) then emitted += 1
                  case Left(reason) =>
                    context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
                    rejectWork(work, work.sourceRecordId, "SCHEMA_FAILURE", reason, context)
                }
                context.getCounter(HadoopCounter.EMITTED_PAIRS).increment(emitted)

  override def cleanup(context: Ctx): Unit =
    val dir = Layout.tableDir("scholarchain", PublicationTeamJob.StatsTable, statsSnapshot, runId)
    if maxTeam.value >= 0 then
      sink.record(
        dir,
        write(StatRow(PublicationTeamJob.Slug, "maxTeamSize", maxTeam.value, maxTeam.key, runId))
      )
    if maxExpansionSeen.value >= 0 then
      sink.record(
        dir,
        write(
          StatRow(
            PublicationTeamJob.Slug,
            "largestPairExpansion",
            maxExpansionSeen.value,
            maxExpansionSeen.key,
            runId
          )
        )
      )
    sink.close()
