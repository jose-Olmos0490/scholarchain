package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.ConfigLoader
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{MatchOutcome, PathEvidence, RunManifest, SemanticFact, WorkRecord}
import upickle.default.{ReadWriter, read}
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.spark
import scholarchain.spark.run.{GraphPipeline, RunResult}

/** Local end-to-end run of the PART A job on files laid out exactly like the Hadoop J09 export
  * and the entity-resolution run directory: inputs located and fingerprinted, run ID from the
  * manifest, Parquet layout, contract check of every written row, and determinism across runs.
  */
/** Mirror of rag `PaperInput` (`work`, `venueName`, `abstractText`), derived with the same uPickle
  * macro as rag's `given ReadWriter[PaperInput] = macroRW`, so the line codec is the one IngestMain
  * decodes with. (The spark module may not depend on rag.)
  */
final case class PaperLine(
    work: WorkRecord,
    venueName: Option[String],
    abstractText: Option[String]
) derives ReadWriter

class PipelineIntegrationSpec extends AnyFunSuite:
  private val runHex = HadoopRunId.stripPrefix("run:")
  private val base: Path = Files.createTempDirectory("sc-spark-it")

  private def put(file: Path, lines: Vector[String]): Unit =
    Files.createDirectories(file.getParent)
    Files.write(
      file,
      lines.mkString("", "\n", if lines.isEmpty then "" else "\n").getBytes(StandardCharsets.UTF_8)
    )

  private val chain = DblpWork("XY", Some(2012), Vector("X", "Y"))
  private val a1 = OpenAlexMention(101, 1, "X. Y. Author", Some("A5001"), None)
  private val t = tables(
    HandCheckable :+ chain,
    Vector(a1),
    Vector(
      decision(chain.mention(1), a1.mentionId, MatchOutcome.ACCEPTED),
      decision(chain.mention(2), a1.mentionId, MatchOutcome.ACCEPTED),
      decision(chain.mention(1), mentionId(oaWorkId(5), 1), MatchOutcome.UNRESOLVED)
    )
  )

  private val hadoopRoot = base.resolve("hadoop")
  private def table(name: String): Path =
    hadoopRoot.resolve(s"scholarchain/$name/snapshot=$Snapshot/run_id=$runHex")

  {
    // Author-paper rows partitioned by year, as J09 writes them.
    t.authorPaper.groupBy(l => """"year":(\d+|null)""".r.findFirstMatchIn(l).get.group(1)).foreach {
      (y, ls) =>
        put(
          table("spark_author_paper")
            .resolve(s"year=${if y == "null" then "unknown" else y}/part-r-00000"),
          ls
        )
    }
    put(table("spark_coauthor_pair").resolve("part-r-00000"), t.coauthorPairs)
    put(table("spark_work").resolve("part-r-00000"), t.works)
    put(table("spark_author_mention").resolve("part-r-00000"), t.authorMentions)
    Files.createDirectories(table("spark_oversize_team")) // empty table: no part file
    Files.write(
      table("spark_oversize_team").resolve("counters.json"),
      "{}".getBytes(StandardCharsets.UTF_8)
    )
    for n <- Vector("spark_venue", "spark_topic", "spark_institution", "spark_graph_edge") do
      put(table(n).resolve("part-r-00000"), Vector.empty)
    val decisions = base.resolve("er/run_id=" + "2" * 64 + "/decisions")
    put(decisions.resolve("accepted.jsonl"), t.decisions.filter(_.contains("\"ACCEPTED\"")))
    put(decisions.resolve("unresolved.jsonl"), t.decisions.filter(_.contains("\"UNRESOLVED\"")))
    put(decisions.resolve("conflict.jsonl"), Vector.empty)
    put(decisions.resolve("rejected.jsonl"), Vector.empty)
  }

  private def settings(out: String): SparkAnalyticsSettings =
    val env = Map(
      "SCHOLARCHAIN_ENV" -> "dev",
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2015,2016-2020",
      "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "coauthor-policy-v1",
      "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "true",
      "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT" -> "2",
      "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION" -> "v1",
      "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE" -> "50",
      "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS" -> "30",
      "SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT" -> hadoopRoot.toString,
      "SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR" -> base.resolve("er/run_id=" + "2" * 64).toString,
      "SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> base.resolve(out).toString,
      "SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS" -> "4",
      "SCHOLARCHAIN_SPARK_CHECKPOINT_DIR" -> base.resolve("ckpt").toString,
      "SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL" -> "2",
      "SCHOLARCHAIN_COMMUNITY_ALGORITHM" -> "label-propagation",
      "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS" -> "20",
      "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS" -> "30",
      "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH" -> "6",
      "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT" -> "2",
      "SCHOLARCHAIN_GRAPH_PATHS_SEED" -> "7",
      "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES" -> "3",
      "SCHOLARCHAIN_GRAPH_DIAMETER_SEED" -> "5",
      "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE" -> "2",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT" -> "3",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT" -> "4"
    )
    ConfigLoader.loadSection(SparkAnalyticsSettings, env).fold(e => fail(e.message), s => s)

  private lazy val first: RunResult =
    GraphPipeline.run(spark, settings("out-1"), "test-code-version")
  private lazy val second: RunResult =
    GraphPipeline.run(spark, settings("out-2"), "test-code-version")

  test("the run ID is the hash of the written manifest and every written row carries it") {
    val manifestText =
      Files.readString(Path.of(first.runDir.stripPrefix("file:"), "run-manifest.json"))
    val manifest = WireCodec.decode[RunManifest](manifestText).fold(e => fail(e.toString), m => m)
    assert(StableIds.runId(manifest) == Right(first.runId))
    assert(manifest.inputs.size == 13) // 9 Hadoop tables + 4 decision files
    assert(manifest.inputs.forall(i => StableIds.isTextHash(i.checksum)))
    first.tables.foreach { tbl =>
      val df = spark.read.parquet(tbl.path).where(col("run_id") === first.runId.stripPrefix("run:"))
      assert(df.where(col("runId") =!= first.runId).count() == 0, tbl.name)
      // ResearcherWindowChange defines its two windows (from/to) instead of one window.
      val windowCols =
        if tbl.name == "researcher_window_change" then
          Set("fromWindowStartYear", "fromWindowEndYear", "toWindowStartYear", "toWindowEndYear")
        else Set("windowStartYear", "windowEndYear")
      assert(
        windowCols.subsetOf(df.columns.toSet) && df.columns.contains("schemaVersion"),
        tbl.name
      )
    }
  }

  test("every written row satisfies its core contract; counts match the fixture") {
    first.tables.foreach(tbl =>
      assert(tbl.contractViolations.forall(_ == 0L), s"${tbl.name}: ${tbl.violationSamples}")
    )
    val rows = first.tables.map(x => x.name -> x.rowCount).toMap
    assert(rows("researcher") == 11) // A..I plus X and Y
    assert(rows("identity_conflict") == 1) // the chain case
    assert(rows("integrity_reject") == 0)
    val all = first.summaries.find(_.allYearsWindow).get
    assert(all.coauthorEdgeCount == 9 && all.researcherCount == 11 && all.componentCount == 4)
    assert(
      first.summaries.map(s => (s.windowStartYear, s.windowEndYear)) == Vector(
        (2000, 2025),
        (2010, 2015),
        (2016, 2020)
      )
    )
  }

  test("layout: graph/<kind>/type=<t>[/window=<s-e>]/run_id=<hex>") {
    val root = base.resolve("out-1")
    val hex = first.runId.stripPrefix("run:")
    assert(
      Files.isDirectory(
        root.resolve(s"graph/edges/type=coauthored_with/window=2000-2025/run_id=$hex")
      )
    )
    assert(
      Files.isDirectory(root.resolve(s"graph/metrics/type=components/window=2010-2015/run_id=$hex"))
    )
    assert(Files.isDirectory(root.resolve(s"graph/nodes/type=researcher/run_id=$hex")))
    assert(Files.exists(root.resolve(s"graph/runs/run_id=$hex/run-summary.json")))
    assert(
      Files
        .readString(root.resolve(s"graph/runs/run_id=$hex/explain-edge-aggregation.txt"))
        .contains("HashAggregate")
    )
  }

  test("determinism: a second run gives the same run ID and identical rows") {
    assert(second.runId == first.runId)
    for name <- Vector(
        "coauthored_with",
        "coauthored_with_support",
        "researcher_collaboration",
        "components",
        "researcher",
        "authored",
        "identity_conflict",
        "community_membership",
        "cross_community_edge",
        "bridge_measures",
        "coreness",
        "path_evidence",
        "semantic_fact",
        "researcher_window_change"
      )
    do
      def rows(r: RunResult) =
        val p = r.tables.find(_.name == name).get.path
        spark.read.parquet(p).collect().map(_.toString).sorted.toVector
      assert(rows(first) == rows(second), name)
  }

  test("PART B tables are written with zero contract violations; sampled paths recorded") {
    val rows = first.tables.map(x => x.name -> x).toMap
    for n <- Vector(
        "community_membership",
        "cross_community_edge",
        "bridge_measures",
        "coreness",
        "path_evidence",
        "semantic_fact",
        "researcher_window_change"
      )
    do
      assert(rows.contains(n), n)
      assert(rows(n).contractViolations == Some(0L), s"$n: ${rows(n).violationSamples}")
    assert(rows("community_membership").rowCount == rows("components").rowCount)
    assert(rows("researcher_window_change").partitionColumns == Vector("windows", "run_id"))
    val hex = first.runId.stripPrefix("run:")
    val summary = ujson.read(
      Files.readString(base.resolve(s"out-1/graph/runs/run_id=$hex/run-summary.json"))
    )
    // No benchmark pairs configured: 2 sampled pairs per window with a largest component >= 2.
    val qs = summary("pathQueries").arr
    assert(qs.nonEmpty && qs.forall(_("selection").str == "SAMPLED"))
    assert(summary("seed")("graph.paths.seed").str == "7")
    assert(summary("analytics")("distanceEstimates").arr.nonEmpty)
    for f <- Vector(
        "explain-lpa-iteration-1.txt",
        "explain-bfs-frontier-step-1.txt",
        "explain-kcore-peel-1.txt"
      )
    do
      assert(
        Files.readString(base.resolve(s"out-1/graph/runs/run_id=$hex/$f")).contains("Exchange"),
        f
      )
  }

  test("one valid load manifest per target with files, checksums and row counts") {
    import scholarchain.core.model.{LoadManifest, LoadTarget}
    val hex = first.runId.stripPrefix("run:")
    val ms = Vector("neptune", "dynamodb", "s3vectors-input", "s3").map { t =>
      val text = Files.readString(
        base.resolve(s"out-1/graph/load-manifests/target=$t/run_id=$hex/load-manifest.json")
      )
      WireCodec.decode[LoadManifest](text).fold(e => fail(e.toString), m => m)
    }
    assert(
      ms.map(_.target) == Vector(
        LoadTarget.NEPTUNE,
        LoadTarget.DYNAMODB,
        LoadTarget.S3VECTORS_INPUT,
        LoadTarget.S3
      )
    )
    ms.foreach(m => assert(WireCodec.validate(m).isRight && m.runId == first.runId))
    assert(ms(2).tables.map(_.tableName) == Vector("semantic_fact"))
    val s3 = ms(3).tables.map(t => t.tableName -> t).toMap
    assert(!s3.contains("wt_invariant")) // not a core record
    assert(
      s3("coauthored_with").rowCount == first.tables.find(_.name == "coauthored_with").get.rowCount
    )
    s3.values.foreach { t =>
      t.files.foreach { f =>
        val bytes = Files.readAllBytes(Path.of(java.net.URI.create(f.path)))
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        assert(f.checksum == "sha256:" + d.map(b => f"${b & 0xff}%02x").mkString, f.path)
        assert(f.sizeBytes == bytes.length.toLong)
      }
    }
  }

  private def jsonl(out: String, name: String): Vector[String] =
    Files
      .readAllLines(base.resolve(s"$out/graph/semantic-jsonl/$name"), StandardCharsets.UTF_8)
      .toArray(new Array[String](0))
      .toVector

  test("semantic JSONL export: the three IngestMain input files, decoded with IngestMain's codec") {
    val _ = first
    val dir = base.resolve("out-1/graph/semantic-jsonl")
    // exact file names of rag IngestionInputs, plus the export manifest (hidden files are the
    // local filesystem's checksum sidecars)
    val names = Files
      .list(dir)
      .toArray
      .map(p => p.asInstanceOf[Path].getFileName.toString)
      .filterNot(_.startsWith("."))
      .sorted
      .toVector
    assert(
      names ==
        Vector("export-manifest.json", "papers.jsonl", "paths.jsonl", "semantic-facts.jsonl")
    )
    val rows = first.tables.map(x => x.name -> x.rowCount).toMap

    val facts = jsonl("out-1", "semantic-facts.jsonl")
    val decodedFacts = facts.map(read[SemanticFact](_))
    assert(facts.size.toLong == rows("semantic_fact") && facts.nonEmpty)
    assert(decodedFacts.forall(f => f.runId == first.runId && f.provenance.runId == first.runId))
    assert(decodedFacts.map(_.factId) == decodedFacts.map(_.factId).sorted) // stable ordering
    assert(decodedFacts.map(_.subjectType.toString).toSet.contains("RESEARCHER"))
    assert(decodedFacts.forall(f => WireCodec.validate(f).isRight))

    val paths = jsonl("out-1", "paths.jsonl")
    val decodedPaths = paths.map(read[PathEvidence](_))
    assert(paths.size.toLong == rows("path_evidence") && paths.nonEmpty)
    assert(decodedPaths.forall(_.runId == first.runId))
    assert(decodedPaths.map(_.pathId) == decodedPaths.map(_.pathId).sorted)

    val papers = jsonl("out-1", "papers.jsonl")
    assert(papers.size.toLong == rows("work") && papers.nonEmpty)
    papers.foreach { l =>
      val j = ujson.read(l).obj
      assert(j.keySet == Set("work", "venueName", "abstractText"), l)
      assert(j("venueName").isNull && j("abstractText").isNull) // uPickle None
    }
    val decodedPapers = papers.map(read[PaperLine](_))
    assert(decodedPapers.map(_.work.workId) == decodedPapers.map(_.work.workId).sorted)
    assert(decodedPapers.forall(p => WireCodec.validate(p.work).isRight))
    assert(
      decodedPapers.map(_.work.workId).toSet ==
        spark.read
          .parquet(first.tables.find(_.name == "work").get.path)
          .select("workId")
          .collect()
          .map(_.getString(0))
          .toSet
    )
    assert(decodedPapers.exists(_.work.authors.nonEmpty))

    // the manifest lists the same line counts
    val manifest = ujson.read(Files.readString(dir.resolve("export-manifest.json")))
    assert(manifest("runId").str == first.runId)
    assert(
      manifest("files").arr.map(f => f("name").str -> f("lines").num.toLong).toMap ==
        Map(
          "semantic-facts.jsonl" -> facts.size.toLong,
          "paths.jsonl" -> paths.size.toLong,
          "papers.jsonl" -> papers.size.toLong
        )
    )
  }

  test("semantic JSONL export is byte-identical across two runs") {
    val _ = (first, second)
    for n <- Vector("semantic-facts.jsonl", "paths.jsonl", "papers.jsonl") do
      assert(
        Files.readAllBytes(base.resolve(s"out-1/graph/semantic-jsonl/$n")).toSeq ==
          Files.readAllBytes(base.resolve(s"out-2/graph/semantic-jsonl/$n")).toSeq,
        n
      )
  }
