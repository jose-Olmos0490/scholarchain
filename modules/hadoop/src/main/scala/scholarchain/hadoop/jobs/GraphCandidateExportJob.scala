package scholarchain.hadoop.jobs

import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.mapreduce.Mapper
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.HadoopCounter
import scholarchain.hadoop.io.*

/** J09 GraphCandidateExportJob: every primary table of the run -> Spark-ready, de-duplicated,
  * year-partitioned collaboration inputs under `scholarchain/spark_<table>/`.
  *
  * Mapper `(offset, table line) -> (Text "<exportDir>[/year=<y>] SEP <stableId>", Text
  * counted-json)`; it re-validates every row with its core contract (SCHEMA_FAILURE otherwise).
  * Combiner [[DedupCombiner]], reducer [[DedupReducer]]. Year-partitioned tables: `work`,
  * `publication_team`, `author_paper`, `coauthor_pair` (`year=unknown` when absent).
  */
object GraphCandidateExportJob:
  val Slug = "j09"

  /** Source table name -> export table name. Tables of every dataset with the same name merge. */
  val Exported: Map[String, String] = Map(
    "work" -> "spark_work",
    "author_mention" -> "spark_author_mention",
    "publication_team" -> "spark_publication_team",
    "author_paper" -> "spark_author_paper",
    "coauthor_pair" -> "spark_coauthor_pair",
    "large_team" -> "spark_large_team",
    "oversize_team" -> "spark_oversize_team",
    "venue" -> "spark_venue",
    "institution" -> "spark_institution",
    "topic" -> "spark_topic",
    "graph_edge" -> "spark_graph_edge",
    "work_node" -> "spark_graph_node",
    "mention_node" -> "spark_graph_node",
    "researcher_node" -> "spark_graph_node",
    "exact_identifier_outcome" -> "spark_exact_identifier_outcome",
    "match_candidate" -> "spark_match_candidate"
  )

  val YearPartitioned: Set[String] =
    Set("spark_work", "spark_publication_team", "spark_author_paper", "spark_coauthor_pair")

  private def errs(e: Vector[scholarchain.core.validation.ValidationError]) = e.mkString("; ")

  /** Validates a row of `table` and returns its stable ID and year. */
  def idAndYear(table: String, json: String): Either[String, (String, Option[Int])] =
    table match
      case "work" => WireCodec.decode[WorkRecord](json).left.map(errs).map(w => (w.workId, w.year))
      case "author_mention" =>
        WireCodec.decode[AuthorMention](json).left.map(errs).map(m => (m.mentionId, None))
      case "publication_team" | "large_team" | "oversize_team" =>
        WireCodec.decode[PublicationTeam](json).left.map(errs).map(t => (t.workId, t.year))
      case "author_paper" =>
        WireCodec.decode[AuthorPaperRecord](json).left.map(errs).map(a => (a.mentionId, a.year))
      case "coauthor_pair" =>
        WireCodec
          .decode[CoauthorPairCandidate](json)
          .left
          .map(errs)
          .map(p => (p.candidateId, Some(p.year)))
      case "venue" => WireCodec.decode[Venue](json).left.map(errs).map(v => (v.venueId, None))
      case "institution" =>
        WireCodec.decode[Institution](json).left.map(errs).map(i => (i.institutionId, None))
      case "topic" => WireCodec.decode[Topic](json).left.map(errs).map(t => (t.topicId, None))
      case "graph_edge" =>
        WireCodec.decode[GraphEdge](json).left.map(errs).map(e => (e.edgeId, None))
      case "work_node" | "mention_node" | "researcher_node" =>
        WireCodec.decode[GraphNode](json).left.map(errs).map(n => (n.nodeId, None))
      case "exact_identifier_outcome" =>
        WireCodec.decode[ExactIdentifierOutcome](json).left.map(errs).map(o => (o.outcomeId, None))
      case "match_candidate" =>
        WireCodec.decode[MatchCandidate](json).left.map(errs).map(c => (c.candidateId, None))
      case other => Left(s"table '$other' is not exported")

class GraphCandidateExportMapper extends Mapper[LongWritable, Text, Text, Text]:
  type Ctx = Mapper[LongWritable, Text, Text, Text]#Context
  private var table: Layout.TableRef = null
  private var exportTable: String = null
  private var sink: TableSink = null
  private var runId: String = null
  private var seed: String = null
  private val k = new Text()
  private val v = new Text()

  override def setup(context: Ctx): Unit =
    val path = context.getInputSplit.asInstanceOf[FileSplit].getPath.toString
    table = Layout.parseTableRef(path).getOrElse(throw new IllegalStateException(path))
    exportTable = GraphCandidateExportJob.Exported.getOrElse(
      table.table,
      throw new IllegalStateException(s"table ${table.table} is not exported")
    )
    sink = new TableSink(context)
    runId = PipelineConf.required(context.getConfiguration, PipelineConf.RunId)
    seed = PipelineConf.required(context.getConfiguration, PipelineConf.SeedSnapshot)

  override def map(key: LongWritable, value: Text, context: Ctx): Unit =
    context.getCounter(HadoopCounter.INPUT_RECORDS).increment(1)
    val json = value.toString
    GraphCandidateExportJob.idAndYear(table.table, json) match
      case Left(errors) =>
        context.getCounter(HadoopCounter.SCHEMA_FAILURE).increment(1)
        sink.reject(
          TableSink.stageRejectsDir(context.getConfiguration),
          Records.reject(
            JobSupport.sourceOfDataset(table.dataset),
            s"${table.dataset}/${table.table}@${key.get}",
            table.snapshot,
            runId,
            GraphCandidateExportJob.Slug,
            "SCHEMA_FAILURE",
            s"$errors | $json"
          )
        )
      case Right((id, year)) =>
        val base = Layout.tableDir("scholarchain", exportTable, seed, runId)
        val dir =
          if GraphCandidateExportJob.YearPartitioned.contains(exportTable) then
            s"$base/year=${year.map(_.toString).getOrElse("unknown")}"
          else base
        k.set(JobSupport.compositeKey(dir, id))
        v.set(Counted.encode(1, json))
        context.write(k, v)

  override def cleanup(context: Ctx): Unit = sink.close()
