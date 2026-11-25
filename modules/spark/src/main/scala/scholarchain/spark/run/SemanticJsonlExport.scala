package scholarchain.spark.run

import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.{col, struct, udf}
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import scholarchain.core.config.ConfigLoader
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{AuthorMention, SourceType, WorkRecord}
import scholarchain.spark.{CoreRecords, SparkAnalyticsSettings, Versions}
import upickle.default.writeJs

/** One exported file. */
final case class ExportedFile(name: String, path: String, lines: Long)

/** Flat JSONL export of the semantic facts, shortest paths and papers of one run, in the exact
  * layout that the rag module's `IngestMain --input-dir` reads (rag `IngestionInputs`):
  *
  *   - `semantic-facts.jsonl`: one core `SemanticFact` per line (`sc-semantic-facts-v1` rows of
  *     the `semantic_fact` table)
  *   - `paths.jsonl`: one core `PathEvidence` per line (every row of `path_evidence`, FOUND and
  *     not-found alike; the renderer decides)
  *   - `papers.jsonl`: one `{"work": WorkRecord, "venueName": .., "abstractText": ..}` per line.
  *     The two Option fields are written through `writeJs`, so they use the same uPickle default
  *     encoding (`null` or the string) as rag's `macroRW` codec of `PaperInput`. `abstractText`
  *     is always `null`: the pipeline carries no abstract text (only
  *     `WorkRecord.abstractTextRef`, a pointer).
  *
  * Source tables: the run's own Parquet leaves under `spark.outputRoot` (written by
  * [[GraphPipeline]]), so the export is a pure function of one run's outputs and carries their
  * provenance (`runId`, `metricVersion`, `provenance`) unchanged. Lines are validated against the
  * core record contracts before they are written.
  *
  * Execution (see modules/spark/JOBS.md, "Semantic JSONL export"):
  *   - rows are rendered to JSON by an executor-side UDF (no driver collect);
  *   - `repartition(1)` is the only shuffle: one hash exchange into a single partition, followed
  *     by `sortWithinPartitions(key, json)`, so line order is deterministic (stable ID, then the
  *     JSON text);
  *   - the single `part-*` file is renamed to the target name with the Hadoop FileSystem; the
  *     driver never holds the data;
  *   - no caching, no checkpoint, no broadcast (the paper/venue join is a plain left join that
  *     Spark may broadcast by its own size rules).
  *
  * Limitation: one task writes each file, so the file size is bounded by one executor's sort
  * spill capacity; it suits the bounded run, a full-corpus export should write sharded files.
  */
object SemanticJsonlExport:
  val FactsFile = "semantic-facts.jsonl"
  val PathsFile = "paths.jsonl"
  val PapersFile = "papers.jsonl"
  val ManifestFile = "export-manifest.json"

  /** Directory below `spark.outputRoot` that holds the three files (an `IngestMain` input dir). */
  val OutputDir = "graph/semantic-jsonl"

  val Usage = "usage: SemanticJsonlExport [--local] [--run-id run:<64 hex>]"

  def outputDir(root: String): Path = new Path(new Path(root), OutputDir)

  private enum Kind:
    case Fact, PathEv, Paper

  // ------------------------------------------------------------------ row -> record mappers
  private def str(r: Row, f: String): String = r.getAs[String](f)
  private def opt(r: Row, f: String): Option[String] = Option(r.getAs[String](f))
  private def optInt(r: Row, f: String): Option[Int] =
    Option(r.getAs[java.lang.Integer](f)).map(_.intValue)

  private def authorMention(r: Row): AuthorMention = AuthorMention(
    schemaVersion = str(r, "schemaVersion"),
    mentionId = str(r, "mentionId"),
    workId = str(r, "workId"),
    position = r.getAs[java.lang.Integer]("position").intValue,
    rawName = str(r, "rawName"),
    normalizedName = str(r, "normalizedName"),
    sourceAuthorKey = opt(r, "sourceAuthorKey"),
    orcid = opt(r, "orcid"),
    provenance = CoreRecords.provenance(r.getAs[Row]("provenance"))
  )

  /** A `work` node row as a core `WorkRecord`. */
  def workRecord(r: Row): WorkRecord = WorkRecord(
    schemaVersion = str(r, "schemaVersion"),
    workId = str(r, "workId"),
    source = SourceType.valueOf(str(r, "source")),
    sourceRecordId = str(r, "sourceRecordId"),
    recordType = str(r, "recordType"),
    title = str(r, "title"),
    normalizedTitle = str(r, "normalizedTitle"),
    year = optInt(r, "year"),
    venueId = opt(r, "venueId"),
    doi = opt(r, "doi"),
    arxivId = opt(r, "arxivId"),
    authors = Option(r.getAs[scala.collection.Seq[Row]]("authors"))
      .map(_.toVector)
      .getOrElse(Vector.empty)
      .map(authorMention),
    abstractTextRef = opt(r, "abstractTextRef"),
    provenance = CoreRecords.provenance(r.getAs[Row]("provenance"))
  )

  private def valid[A](a: A, what: String)(using
      c: scholarchain.core.validation.RecordContract[A]
  ): A =
    WireCodec.validate(a).fold(e => sys.error(s"$what violates its core contract: $e"), x => x)

  /** One JSON line of `kind` for `r` (fails the task on a contract violation). */
  private def line(kind: Kind, r: Row): String = kind match
    case Kind.Fact =>
      upickle.default.write(valid(CoreRecords.semanticFact(r), "semantic fact"))
    case Kind.PathEv =>
      upickle.default.write(valid(CoreRecords.pathEvidence(r), "path evidence"))
    case Kind.Paper =>
      val work = valid(workRecord(r), "work")
      ujson.write(
        ujson.Obj(
          "work" -> writeJs(work),
          "venueName" -> writeJs(opt(r, "venueName").filter(_.trim.nonEmpty)),
          "abstractText" -> writeJs(Option.empty[String])
        )
      )

  private def lineUdf(kind: Kind): UserDefinedFunction =
    val name = kind.toString
    udf(
      new UDF1[Row, String]:
        def call(r: Row): String = line(Kind.valueOf(name), r)
      ,
      StringType
    )

  // ------------------------------------------------------------------ job
  /** Reads the leaf directories matching `glob`, listed explicitly (Spark warns on glob paths).
    */
  private def readLeaves(spark: SparkSession, glob: Path, conf: Configuration): DataFrame =
    val leaves = Option(glob.getFileSystem(conf).globStatus(glob))
      .map(_.toVector.map(_.getPath.toString).sorted)
      .getOrElse(Vector.empty)
    if leaves.isEmpty then sys.error(s"no run output matches $glob")
    spark.read.parquet(leaves*)

  /** Renders `df` to one JSON line per row, sorted by (`key`, line), into `target`. */
  private def writeOne(
      spark: SparkSession,
      df: DataFrame,
      kind: Kind,
      key: String,
      dir: Path,
      target: String,
      conf: Configuration
  ): ExportedFile =
    val fs: FileSystem = dir.getFileSystem(conf)
    val tmp = new Path(dir, s"_tmp-$target")
    fs.delete(tmp, true)
    val rendered = df
      .select(col(key).as("key"), lineUdf(kind)(struct(df.columns.toSeq.map(col)*)).as("json"))
      .repartition(1)
      .sortWithinPartitions(col("key"), col("json"))
      .select(col("json"))
    rendered.write.option("compression", "none").text(tmp.toString)
    val dest = new Path(dir, target)
    fs.delete(dest, false)
    val parts =
      fs.listStatus(tmp).map(_.getPath).filter(_.getName.startsWith("part-")).sortBy(_.getName)
    parts.toVector match
      case Vector(one) => require(fs.rename(one, dest), s"cannot rename $one to $dest")
      case Vector() =>
        fs.create(dest, true).close()
      case many =>
        sys.error(s"expected one part file for $target, found ${many.size}")
    fs.delete(tmp, true)
    ExportedFile(target, dest.toString, spark.read.text(dest.toString).count())

  /** Exports the three files of run `runHex` found under `root` into [[outputDir]]. Returns the
    * files in the order facts, paths, papers.
    */
  def exportRun(
      spark: SparkSession,
      root: String,
      runHex: String,
      runId: String,
      conf: Configuration
  ): Vector[ExportedFile] =
    val base = new Path(root)
    val dir = outputDir(root)
    dir.getFileSystem(conf).mkdirs(dir)
    def windowed(rel: String) =
      readLeaves(spark, new Path(base, s"$rel/window=*/run_id=$runHex"), conf)
    def flat(rel: String) = readLeaves(spark, new Path(base, s"$rel/run_id=$runHex"), conf)
    val facts = windowed("graph/semantic/type=semantic_fact")
    val paths = windowed("graph/paths/type=path_evidence")
    val venues = flat("graph/nodes/type=venue").select(col("venueId"), col("name").as("venueName"))
    val papers = flat("graph/nodes/type=work").join(venues, Seq("venueId"), "left")
    val files = Vector(
      writeOne(spark, facts, Kind.Fact, "factId", dir, FactsFile, conf),
      writeOne(spark, paths, Kind.PathEv, "pathId", dir, PathsFile, conf),
      writeOne(spark, papers, Kind.Paper, "workId", dir, PapersFile, conf)
    )
    val manifest = ujson.Obj(
      "format" -> "sc-semantic-jsonl-export-v1",
      "runId" -> runId,
      "metricVersion" -> Versions.SemanticFacts,
      "inputDir" -> dir.toString,
      "files" -> ujson.Arr.from(
        files.map(f => ujson.Obj("name" -> f.name, "lines" -> f.lines.toDouble))
      )
    )
    val out = dir.getFileSystem(conf).create(new Path(dir, ManifestFile), true)
    try out.write(ujson.write(manifest, indent = 2).getBytes(StandardCharsets.UTF_8))
    finally out.close()
    files

  /** The only run below `root`, or the requested one. */
  private def selectRun(root: String, requested: Option[String], conf: Configuration): String =
    val runs = new Path(root, "graph/runs")
    val fs = runs.getFileSystem(conf)
    val found = fs
      .listStatus(runs)
      .map(_.getPath.getName)
      .filter(_.startsWith("run_id="))
      .map(_.stripPrefix("run_id="))
      .sorted
      .toVector
    requested.map(_.stripPrefix("run:")) match
      case Some(r) if found.contains(r) => r
      case Some(r) => sys.error(s"run $r not found under $runs (found: ${found.mkString(", ")})")
      case None if found.size == 1 => found.head
      case None => sys.error(s"pass --run-id: ${found.size} runs under $runs")

  def main(argv: Array[String]): Unit =
    def parse(
        rest: List[String],
        local: Boolean,
        run: Option[String]
    ): Either[String, (Boolean, Option[String])] =
      rest match
        case Nil => Right((local, run))
        case "--local" :: t => parse(t, true, run)
        case "--run-id" :: v :: t if v.trim.nonEmpty => parse(t, local, Some(v.trim))
        case other :: _ => Left(s"unknown or incomplete argument '$other'\n$Usage")
    val (local, run) = parse(argv.toList, false, None) match
      case Left(e) => System.err.println(e); sys.exit(2)
      case Right(v) => v
    val settings = ConfigLoader.loadSection(SparkAnalyticsSettings, sys.env) match
      case Left(e) => System.err.println(s"SemanticJsonlExport: ${e.message}"); sys.exit(2)
      case Right(s) => s
    val spark = GraphPipeline.session(settings, local)
    try
      val conf = spark.sparkContext.hadoopConfiguration
      val root = settings.spark.outputRoot
      val hex = selectRun(root, run, conf)
      val files = exportRun(spark, root, hex, s"run:$hex", conf)
      files.foreach(f => println(s"EXPORT ${f.name} lines=${f.lines} ${f.path}"))
    finally spark.stop()
