package scholarchain.storage

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.storage.parquet.ParquetRows
import upickle.default.ReadWriter

/** Manual check, not a test: streams the Parquet files that a real Spark run wrote and decodes every
  * row into its core record.
  *
  * `sbt "storage/Test/runMain scholarchain.storage.RealRunSmoke <graph-output-dir>"`
  *
  * Spark's own files exercise what the fixture writer only imitates (its list and map encodings,
  * its snappy pages, its column order).
  */
object RealRunSmoke:
  private def check[A: ReadWriter: RecordContract](graph: Path, rel: String): Unit =
    val dir = graph.resolve(rel)
    if !Files.isDirectory(dir) then println(f"$rel%-48s (absent)")
    else
      val files =
        Files.walk(dir).iterator().asScala.filter(_.toString.endsWith(".parquet")).toVector.sorted
      var rows = 0L
      var bad = 0L
      var firstBad = ""
      files.foreach { f =>
        ParquetRows.foreach(f) { row =>
          rows += 1
          WireCodec.decodeValue[A](row) match
            case Right(_) => ()
            case Left(errors) =>
              bad += 1
              if firstBad.isEmpty then firstBad = errors.take(2).mkString("; ")
        }
      }
      println(f"$rel%-48s files=${files.size}%3d rows=$rows%6d undecodable=$bad%3d $firstBad")

  /** Builds a NEPTUNE manifest over the files on disk (the way Spark's LoadManifests would),
    * then verifies it, exports it to CSV and validates the CSV. Uses the first `run_id=` directory
    * name found for each table.
    */
  private def exportReal(graph: Path): Unit =
    import scholarchain.core.schema.SchemaVersions as SV
    import scholarchain.storage.common.*
    import scholarchain.storage.graph.*
    import scholarchain.storage.manifest.*
    val tables = Vector(
      ("researcher", "nodes/type=researcher", "CanonicalResearcher", SV.CanonicalResearcher),
      ("work", "nodes/type=work", "WorkRecord", SV.WorkRecord),
      ("venue", "nodes/type=venue", "Venue", SV.Venue),
      ("topic", "nodes/type=topic", "Topic", SV.Topic),
      ("institution", "nodes/type=institution", "Institution", SV.Institution),
      ("authored", "edges/type=authored", "AuthorshipRecord", SV.AuthorshipRecord),
      ("has_topic", "edges/type=has_topic", "GraphEdge", SV.GraphEdge),
      ("coauthored_with", "edges/type=coauthored_with", "CoauthorEdge", SV.CoauthorEdge),
      (
        "researcher_collaboration",
        "metrics/type=researcher_collaboration",
        "ResearcherMetrics",
        SV.ResearcherMetrics
      )
    )
    val RunDir = "run_id=([0-9a-f]{64})".r
    var runHex = ""
    val loadTables = tables.map { (name, rel, recordType, schema) =>
      val dir = graph.resolve(rel)
      val files =
        Files.walk(dir).iterator().asScala.filter(_.toString.endsWith(".parquet")).toVector.sorted
      if runHex.isEmpty then
        runHex = RunDir.findFirstMatchIn(files.head.toString).map(_.group(1)).getOrElse("")
      val mine = files.filter(_.toString.contains(s"run_id=$runHex"))
      LoadTable(
        name,
        recordType,
        schema,
        dir.toString,
        "parquet",
        Vector("run_id"),
        mine.map(ParquetRows.rowCount).sum,
        mine.map(f => LoadFile(f.toString, Files.size(f), Sha256.ofFile(f)))
      )
    }
    val runId = s"run:$runHex"
    val manifest = LoadManifest(
      SV.LoadManifest,
      LoadTarget.NEPTUNE,
      runId,
      loadTables,
      Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        runId,
        "2026-11-01",
        runId,
        None,
        None,
        None,
        Vector.empty
      )
    )
    val rejects = new InMemoryRejectSink
    val out = Files.createTempDirectory("real-export-")
    val result = for
      v <- LoadManifests.verify(manifest, LoadTarget.NEPTUNE, new LocalFileStager(Paths.get("/")))
      m <- new NeptuneCsvExporter(rejects).exportRun(v, out)
      s <- NeptuneCsvValidator.validate(out, m, 10)
    yield (m, s)
    result match
      case Right((m, s)) =>
        println(
          s"export OK: node window ${m.nodeWindow}, rejected rows ${m.rejectedRows}, validation $s"
        )
        m.files.foreach(f => println(f"  ${f.file}%-40s ${f.role}%-13s rows=${f.rowCount}%6d"))
        println(s"  rejects recorded: ${rejects.all.size}")
      case Left(e) => println(s"export FAILED: ${e.message}")

  def main(args: Array[String]): Unit =
    val graph = Paths.get(args.headOption.getOrElse("modules/spark/local-run/graph"))
    if args.contains("--export") then exportReal(graph.toAbsolutePath) else decodeAll(graph)

  private def decodeAll(graph: Path): Unit =
    check[CanonicalResearcher](graph, "nodes/type=researcher")
    check[WorkRecord](graph, "nodes/type=work")
    check[Venue](graph, "nodes/type=venue")
    check[Topic](graph, "nodes/type=topic")
    check[Institution](graph, "nodes/type=institution")
    check[AuthorshipRecord](graph, "edges/type=authored")
    check[GraphEdge](graph, "edges/type=has_topic")
    check[CoauthorEdge](graph, "edges/type=coauthored_with")
    check[CoauthorEdgeSupport](graph, "edges/type=coauthored_with_support")
    check[ResearcherMetrics](graph, "metrics/type=researcher_collaboration")
    check[ComponentMembership](graph, "metrics/type=components")
