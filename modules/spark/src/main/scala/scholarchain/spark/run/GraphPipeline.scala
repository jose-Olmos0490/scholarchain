package scholarchain.spark.run

import java.nio.charset.StandardCharsets
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, Encoders, Row, SaveMode, SparkSession}
import scala.collection.immutable.TreeMap
import scholarchain.core.config.ConfigLoader
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{RunInput, RunManifest, SourceType, WindowGraphSummary}
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.graph.*
import scholarchain.spark.io.{InputCatalog, InputTable}
import scholarchain.spark.{
  AnalysisWindow,
  CoreRecords,
  Schemas,
  SparkAnalyticsSettings,
  Versions,
  Windows
}
import upickle.default.writeJs

/** Command line of the PART A graph job.
  *
  * @param codeVersion
  *   recorded in the run manifest (pass the Git SHA of the packaged commit)
  * @param local
  *   run with a local master (`local[*]`) when no master is configured
  */
final case class PipelineArgs(codeVersion: String, local: Boolean)

object PipelineArgs:
  val Usage = "usage: GraphPipeline [--local] [--code-version <git-sha>]"

  def parse(args: Seq[String]): Either[String, PipelineArgs] =
    def loop(rest: List[String], acc: PipelineArgs): Either[String, PipelineArgs] = rest match
      case Nil => Right(acc)
      case "--local" :: t => loop(t, acc.copy(local = true))
      case "--code-version" :: v :: t if v.trim.nonEmpty => loop(t, acc.copy(codeVersion = v.trim))
      case other :: _ => Left(s"unknown or incomplete argument '$other'\n$Usage")
    loop(args.toList, PipelineArgs(Versions.DefaultCodeVersion, local = false))

/** One written output table. */
final case class WrittenTable(
    name: String,
    path: String,
    recordType: String,
    schemaVersion: String,
    windowed: Boolean,
    partitionColumns: Vector[String],
    rowCount: Long,
    fileCount: Int,
    contractViolations: Option[Long],
    violationSamples: Vector[String]
)

/** Result of one run (the report is what `main` prints). */
final case class RunResult(
    runId: String,
    runDir: String,
    tables: Vector[WrittenTable],
    summaries: Vector[WindowGraphSummary],
    report: String
)

/** Main class of Phase 06 (PART A and PART B): canonical tables, temporal weighted coauthorship
  * graph, collaboration metrics, connected components, communities, bridge measures, k-core,
  * shortest paths, distance estimate, temporal comparison, semantic facts and per-target load
  * manifests, written under `spark.outputRoot`.
  *
  * {{{
  * sbt "sparkAnalytics/runMain scholarchain.spark.run.GraphPipeline --local --code-version <sha>"
  * }}}
  * Configuration comes only from the core loader (environment variables bound in core's
  * env-overrides.conf; see modules/spark/config/local-run.properties for the local values).
  */
object GraphPipeline:

  def main(argv: Array[String]): Unit =
    val args = PipelineArgs.parse(argv.toSeq) match
      case Left(e) =>
        System.err.println(e)
        sys.exit(2)
      case Right(a) => a
    val settings = ConfigLoader.loadSection(SparkAnalyticsSettings, sys.env) match
      case Left(e) =>
        System.err.println(s"GraphPipeline: ${e.message}")
        sys.exit(2)
      case Right(s) => s
    val spark = session(settings, args.local)
    try
      val result = run(spark, settings, args.codeVersion)
      println(result.report)
    finally spark.stop()

  /** A session with the configured shuffle partitions and the job's fixed SQL settings. */
  def session(settings: SparkAnalyticsSettings, local: Boolean): SparkSession =
    val b = SparkSession.builder().appName("scholarchain-spark-graph-analytics")
    if local then b.master("local[*]").config("spark.ui.enabled", "false")
    configure(b, settings.spark.shufflePartitions).getOrCreate()

  /** SQL settings every run uses (also applied by the tests). */
  def configure(b: SparkSession.Builder, shufflePartitions: Int): SparkSession.Builder =
    b.config("spark.sql.shuffle.partitions", shufflePartitions.toString)
      // Keep the configured partition count (AQE would coalesce small shuffles to 1 partition).
      .config("spark.sql.adaptive.coalescePartitions.enabled", "false")
      .config("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.cleaner.referenceTracking.cleanCheckpoints", "true")

  private def fs(p: Path, conf: Configuration): FileSystem = p.getFileSystem(conf)

  private def writeText(path: Path, text: String, conf: Configuration): Unit =
    val out = fs(path, conf).create(path, true)
    try out.write(text.getBytes(StandardCharsets.UTF_8))
    finally out.close()

  /** The formatted physical plan of `df` (Dataset.explain prints to Console.out). */
  def explain(df: DataFrame): String =
    val buf = new java.io.ByteArrayOutputStream()
    val ps = new java.io.PrintStream(buf, true, "UTF-8")
    Console.withOut(ps)(df.explain("formatted"))
    ps.flush()
    buf.toString("UTF-8")

  /** Executes the job. */
  def run(spark: SparkSession, settings: SparkAnalyticsSettings, codeVersion: String): RunResult =
    spark.sparkContext.setLogLevel("WARN")
    val listener = new StageMetricsListener
    spark.sparkContext.addSparkListener(listener)
    val conf = spark.sparkContext.hadoopConfiguration
    val sc = settings.spark

    // ------------------------------------------------------------------ inputs and run ID
    val hadoopRun = InputCatalog.selectRun(sc.hadoopInputRoot, sc.hadoopRunId, conf)
    val hadoopTables = InputCatalog.hadoopTables(sc.hadoopInputRoot, hadoopRun, conf)
    val decisionTables = InputCatalog.decisionTables(sc.matchDecisionsDir, conf)
    val byName = (hadoopTables ++ decisionTables).map(t => t.name -> t).toMap
    def hadoop(name: String): DataFrame =
      InputCatalog.read(spark, byName(name), InputCatalog.HadoopTables.toMap.apply(name))
    val decisions = decisionTables
      .map(t => InputCatalog.read(spark, t, Schemas.MatchDecision))
      .reduce(_.unionByName(_))
    // Bounded collect: the distinct ER policy versions (a handful).
    val erPolicies = decisions
      .select("policyVersion")
      .distinct()
      .collect()
      .map(r => String.valueOf(r.getString(0)))
      .sorted
      .toVector
    val snapshot = hadoopRun.snapshot
    val manifest = RunManifest(
      schemaVersion = SchemaVersions.RunManifest,
      pipelineStage = Versions.PipelineStage,
      codeVersion = codeVersion,
      inputs = (hadoopTables ++ decisionTables).map(t =>
        RunInput(SourceType.SCHOLARCHAIN, snapshot, t.location, t.checksum)
      ),
      parameters = TreeMap.from(settings.logicalParameters),
      policyVersions = TreeMap(
        "coauthorPolicyVersion" -> settings.graph.coauthorPolicyVersion,
        "collaborationMetricVersion" -> Versions.CollaborationMetrics,
        "componentsMetricVersion" -> Versions.Components,
        "identityRule" -> Versions.IdentityRule,
        "identityVersion" -> settings.identity.version,
        "hasTopicPolicy" -> Versions.HasTopicPolicy,
        "windowSummaryVersion" -> Versions.WindowSummary,
        "communitiesMetricVersion" -> Versions.Communities,
        "bridgeMetricVersion" -> Versions.Bridges,
        "kcoreMetricVersion" -> Versions.KCore,
        "pathAlgorithmVersion" -> Versions.Paths,
        "distanceEstimateVersion" -> Versions.DistanceEstimate,
        "temporalVersion" -> Versions.Temporal,
        "semanticFactsVersion" -> Versions.SemanticFacts,
        "entityResolutionPolicyVersions" -> erPolicies.mkString(","),
        "hadoopRunId" -> s"run:${hadoopRun.runHex}"
      )
    )
    val manifestJson =
      WireCodec
        .encodeValidated(manifest)
        .fold(e => sys.error(s"invalid run manifest: $e"), Predef.identity)
    val runId = StableIds.runId(manifest).fold(e => sys.error(e), Predef.identity)
    val runHex = runId.stripPrefix("run:")

    val checkpointing = sc.checkpointDir.nonEmpty
    spark.sparkContext.setCheckpointDir(new Path(sc.checkpointDir, runHex).toString)

    val inputs = GraphInputs(
      authorPaper = hadoop("spark_author_paper"),
      coauthorPairs = hadoop("spark_coauthor_pair"),
      works = hadoop("spark_work"),
      authorMentions = hadoop("spark_author_mention"),
      oversizeTeams = hadoop("spark_oversize_team"),
      venues = hadoop("spark_venue"),
      topics = hadoop("spark_topic"),
      institutions = hadoop("spark_institution"),
      graphEdges = hadoop("spark_graph_edge"),
      decisions = decisions
    )
    val inputCounts: Vector[(InputTable, Long)] =
      hadoopTables.map(t => t -> hadoop(t.name).count()) ++
        decisionTables.map(t => t -> InputCatalog.read(spark, t, Schemas.MatchDecision).count())

    val scope = AnalysisWindow(settings.subsetYears.startYear, settings.subsetYears.endYear, false)
    val params = BuildParams(
      runId = runId,
      snapshot = snapshot,
      windows = settings.windows,
      scope = scope,
      coauthorPolicyVersion = settings.graph.coauthorPolicyVersion,
      representativeWorkLimit = settings.graph.representativeWorkLimit,
      identityVersion = settings.identity.version,
      maxClusterSize = settings.identity.maxClusterSize,
      componentsMaxIterations = settings.components.maxIterations,
      checkpointInterval = sc.checkpointInterval,
      checkpoint = checkpointing
    )

    // ------------------------------------------------------------------ build
    val out = GraphBuilder.build(spark, inputs, params)
    val edgePlan = explain(out.edgeAggregationPlan)
    val componentPlan = explain(out.componentRun.firstIterationPlan)

    // ------------------------------------------------------------------ PART B
    val aparams = AnalyticsParams(
      communityMaxIterations = settings.community.maxIterations,
      kcoreMaxIterations = settings.kcore.maxIterations,
      pathMaxDepth = settings.paths.maxDepth,
      benchmarkPairs = settings.paths.benchmarkPairs,
      samplePairCount = settings.paths.samplePairCount,
      pathSeed = settings.paths.seed,
      diameterSampleSources = settings.diameter.sampleSources,
      diameterSeed = settings.diameter.seed,
      diameterMinComponentSize = settings.diameter.minComponentSize,
      distanceDepthBound = settings.components.maxIterations,
      topEntityLimit = settings.semanticFacts.topEntityLimit,
      memberLimit = settings.semanticFacts.memberLimit
    )
    val an = Analytics.build(spark, out, params, aparams)
    val lpaPlan = explain(an.lpaPlan)
    val kcorePlan = explain(an.kcore.firstRoundPlan)
    val bfsPlan =
      Option(an.pathRun.bfs.firstStepPlan).fold("(no path query: no BFS step)\n")(explain)

    // ------------------------------------------------------------------ write
    val root = new Path(sc.outputRoot)
    def write(
        name: String,
        rel: String,
        df: DataFrame,
        windowed: Boolean,
        recordType: String,
        schemaVersion: String,
        kind: Option[CoreRecords.Kind],
        customLeaves: Option[(String, Vector[(String, org.apache.spark.sql.Column)])] = None
    ): WrittenTable =
      val path = new Path(root, rel)
      // One leaf directory per window (window=<s>-<e>/run_id=<hex>), or run_id=<hex>. Writing each
      // leaf separately keeps an empty window readable (Spark writes a schema-only file for an
      // empty write) and replaces only this run's own leaves on a rerun of the same run ID.
      val leaves: Vector[(Path, DataFrame)] = customLeaves match
        case Some((_, ls)) =>
          ls.map((dir, cond) => new Path(path, s"$dir/run_id=$runHex") -> df.where(cond))
        case None =>
          if windowed then
            settings.windows.map(w =>
              new Path(path, s"window=${w.label}/run_id=$runHex") ->
                df.where(col(Windows.Start) === w.startYear && col(Windows.End) === w.endYear)
            )
          else Vector(new Path(path, s"run_id=$runHex") -> df)
      val partitionColumns = customLeaves match
        case Some((name, _)) => Vector(name, "run_id")
        case None => if windowed then Vector("window", "run_id") else Vector("run_id")
      leaves.foreach((leaf, d) => d.write.mode(SaveMode.Overwrite).parquet(leaf.toString))
      val back = spark.read.parquet(leaves.map(_._1.toString)*)
      val rows = back.count()
      val files = countFiles(path, runHex, conf)
      val (violations, samples) = kind match
        case None => (None, Vector.empty)
        case Some(k) =>
          val checked =
            CoreRecords.withViolations(back, k).where(col("contractViolation").isNotNull)
          // Bounded collect: at most 5 sample messages.
          val s =
            checked.select("contractViolation").limit(5).collect().map(_.getString(0)).toVector
          (Some(checked.count()), s)
      WrittenTable(
        name,
        path.toString,
        recordType,
        schemaVersion,
        windowed,
        partitionColumns,
        rows,
        files,
        violations,
        samples
      )

    import CoreRecords.Kind
    val written = Vector(
      write(
        "researcher",
        "graph/nodes/type=researcher",
        out.researchers,
        false,
        "CanonicalResearcher",
        SchemaVersions.CanonicalResearcher,
        Some(Kind.Researcher)
      ),
      write(
        "work",
        "graph/nodes/type=work",
        out.works,
        false,
        "WorkRecord",
        SchemaVersions.WorkRecord,
        None
      ),
      write(
        "venue",
        "graph/nodes/type=venue",
        out.venues,
        false,
        "Venue",
        SchemaVersions.Venue,
        None
      ),
      write(
        "topic",
        "graph/nodes/type=topic",
        out.topics,
        false,
        "Topic",
        SchemaVersions.Topic,
        None
      ),
      write(
        "institution",
        "graph/nodes/type=institution",
        out.institutions,
        false,
        "Institution",
        SchemaVersions.Institution,
        None
      ),
      write(
        "authored",
        "graph/edges/type=authored",
        out.authored,
        false,
        "AuthorshipRecord",
        SchemaVersions.AuthorshipRecord,
        Some(Kind.Authorship)
      ),
      write(
        "has_topic",
        "graph/edges/type=has_topic",
        out.hasTopic,
        false,
        "GraphEdge",
        SchemaVersions.GraphEdge,
        Some(Kind.HasTopic)
      ),
      write(
        "coauthored_with",
        "graph/edges/type=coauthored_with",
        out.edges,
        true,
        "CoauthorEdge",
        SchemaVersions.CoauthorEdge,
        Some(Kind.Edge)
      ),
      write(
        "coauthored_with_support",
        "graph/edges/type=coauthored_with_support",
        out.edgeSupport,
        true,
        "CoauthorEdgeSupport",
        SchemaVersions.CoauthorEdgeSupport,
        Some(Kind.EdgeSupport)
      ),
      write(
        "researcher_collaboration",
        "graph/metrics/type=researcher_collaboration",
        an.metrics,
        true,
        "ResearcherMetrics",
        SchemaVersions.ResearcherMetrics,
        Some(Kind.Metrics)
      ),
      write(
        "components",
        "graph/metrics/type=components",
        out.components,
        true,
        "ComponentMembership",
        SchemaVersions.ComponentMembership,
        Some(Kind.Component)
      ),
      write(
        "wt_invariant",
        "graph/metrics/type=wt_invariant",
        out.wtInvariant,
        true,
        "WtInvariant",
        "wt-invariant-v1",
        None
      ),
      write(
        "community_membership",
        "graph/metrics/type=communities",
        an.communities,
        true,
        "CommunityMembership",
        SchemaVersions.CommunityMembership,
        Some(Kind.Community)
      ),
      write(
        "cross_community_edge",
        "graph/edges/type=cross_community",
        an.crossEdges,
        true,
        "CrossCommunityEdge",
        SchemaVersions.CrossCommunityEdge,
        Some(Kind.CrossEdge)
      ),
      write(
        "bridge_measures",
        "graph/metrics/type=bridges",
        an.bridges,
        true,
        "BridgeMeasures",
        SchemaVersions.BridgeMeasures,
        Some(Kind.Bridge)
      ),
      write(
        "coreness",
        "graph/metrics/type=coreness",
        an.coreness,
        true,
        "CorenessRecord",
        SchemaVersions.CorenessRecord,
        Some(Kind.Coreness)
      ),
      write(
        "path_evidence",
        "graph/paths/type=path_evidence",
        an.paths,
        true,
        "PathEvidence",
        SchemaVersions.PathEvidence,
        Some(Kind.Path)
      ),
      write(
        "semantic_fact",
        "graph/semantic/type=semantic_fact",
        an.semanticFacts,
        true,
        "SemanticFact",
        SchemaVersions.SemanticFact,
        Some(Kind.Fact)
      )
    ) ++ an.windowChanges.toVector.map(df =>
      write(
        "researcher_window_change",
        "graph/temporal/type=researcher_window_change",
        df,
        false,
        "ResearcherWindowChange",
        SchemaVersions.ResearcherWindowChange,
        Some(Kind.WindowChange),
        Some(
          "windows" -> Temporal
            .pairs(settings.windows)
            .map((f, t) =>
              s"windows=${f.label}_${t.label}" -> (col("fromWindowStartYear") === f.startYear &&
                col("toWindowStartYear") === t.startYear)
            )
        )
      )
    ) ++ Vector(
      write(
        "identity_conflict",
        "graph/identity/type=identity_conflict",
        out.identity.conflicts,
        false,
        "IdentityConflict",
        SchemaVersions.IdentityConflict,
        Some(Kind.Conflict)
      ),
      write(
        "integrity_reject",
        "graph/rejects/type=integrity",
        out.rejects,
        false,
        "RejectRecord",
        SchemaVersions.RejectRecord,
        Some(Kind.Reject)
      )
    )

    val summaries = WindowSummaries.compute(
      out,
      params,
      settings.components.maxIterations,
      Some(
        WindowSummaries.PartB(an, settings.community.maxIterations, settings.kcore.maxIterations)
      )
    )
    val summaryJson = summaries.map(s =>
      WireCodec
        .encodeValidated(s)
        .fold(e => sys.error(s"invalid window summary: $e"), Predef.identity)
    )
    val summaryDf =
      spark.read.json(spark.createDataset(java.util.Arrays.asList(summaryJson*))(Encoders.STRING))
    val summaryTable = write(
      "window_summary",
      "graph/metrics/type=window_summary",
      summaryDf,
      true,
      "WindowGraphSummary",
      SchemaVersions.WindowGraphSummary,
      None
    )

    // ------------------------------------------------------------------ bounded statistics
    val stats = RunStatistics.compute(out, params)
    val astats = AnalyticsStatistics.compute(an, params, summaries, 10)

    listener.awaitQuiet()
    val stages = listener.snapshot
    spark.sparkContext.removeSparkListener(listener)

    // ------------------------------------------------------------------ manifest and summary
    val runDir = new Path(root, s"graph/runs/run_id=$runHex")
    writeText(new Path(runDir, "run-manifest.json"), manifestJson, conf)
    writeText(new Path(runDir, "explain-edge-aggregation.txt"), edgePlan, conf)
    writeText(new Path(runDir, "explain-component-iteration-1.txt"), componentPlan, conf)
    writeText(new Path(runDir, "explain-lpa-iteration-1.txt"), lpaPlan, conf)
    writeText(new Path(runDir, "explain-bfs-frontier-step-1.txt"), bfsPlan, conf)
    writeText(new Path(runDir, "explain-kcore-peel-1.txt"), kcorePlan, conf)
    val tables = written :+ summaryTable
    // Flat JSONL input of the rag IngestMain, rendered from this run's semantic_fact,
    // path_evidence, work and venue tables (see SemanticJsonlExport).
    val jsonl = SemanticJsonlExport.exportRun(spark, sc.outputRoot, runHex, runId, conf)
    val loadManifests = LoadManifests.manifests(tables, runId, snapshot, conf)
    val manifestPaths = loadManifests.map { m =>
      val mp = new Path(root, LoadManifests.relativePath(m.target, runHex))
      writeText(mp, LoadManifests.json(m), conf)
      m.target.toString -> mp.toString
    }
    val summary = ujson.Obj(
      "format" -> Versions.RunSummaryFormat,
      "runId" -> runId,
      "codeVersion" -> codeVersion,
      "pipelineStage" -> Versions.PipelineStage,
      "manifest" -> new Path(runDir, "run-manifest.json").toString,
      "hadoopRun" -> ujson.Obj("snapshot" -> snapshot, "runId" -> s"run:${hadoopRun.runHex}"),
      "seed" -> ujson.Obj(
        "graph.paths.seed" -> settings.paths.seed.toString,
        "graph.diameter.seed" -> settings.diameter.seed.toString
      ),
      "pathQueries" -> ujson.Arr.from(
        an.queries.map(q =>
          ujson.Obj(
            "pathId" -> q.pathId,
            "window" -> s"${q.windowStartYear}-${q.windowEndYear}",
            "source" -> q.source,
            "target" -> q.target,
            "selection" -> q.selection.toString
          )
        )
      ),
      "semanticJsonl" -> ujson.Obj(
        "dir" -> SemanticJsonlExport.outputDir(sc.outputRoot).toString,
        "files" -> ujson.Arr.from(
          jsonl.map(f => ujson.Obj("name" -> f.name, "lines" -> f.lines.toDouble))
        )
      ),
      "loadManifests" -> ujson.Obj.from(manifestPaths.map((t, p) => t -> ujson.Str(p))),
      "analytics" -> astats.json,
      "logicalParameters" -> ujson.Obj.from(
        TreeMap.from(settings.logicalParameters).map((k, v) => k -> ujson.Str(v))
      ),
      "executionParameters" -> ujson.Obj.from(
        TreeMap.from(settings.executionParameters).map((k, v) => k -> ujson.Str(v))
      ),
      "inputs" -> ujson.Arr.from(
        inputCounts.map((t, n) =>
          ujson.Obj(
            "name" -> t.name,
            "location" -> t.location,
            "directory" -> t.directory,
            "files" -> t.files.size,
            "rows" -> n,
            "checksum" -> t.checksum
          )
        )
      ),
      "outputs" -> ujson.Arr.from(
        tables.map(t =>
          ujson.Obj(
            "name" -> t.name,
            "path" -> t.path,
            "recordType" -> t.recordType,
            "schemaVersion" -> t.schemaVersion,
            "windowed" -> t.windowed,
            "partitionColumns" -> ujson.Arr.from(t.partitionColumns.map(ujson.Str(_))),
            "rows" -> t.rowCount,
            "files" -> t.fileCount,
            "contractViolations" -> t.contractViolations.fold[ujson.Value](ujson.Null)(n =>
              ujson.Num(n.toDouble)
            ),
            "violationSamples" -> ujson.Arr.from(t.violationSamples.map(ujson.Str(_)))
          )
        )
      ),
      "windows" -> ujson.Arr.from(summaries.map(s => writeJs(s))),
      "statistics" -> stats.json,
      "identityIterations" -> ujson.Obj(
        "identityClusters" -> ujson.Obj(
          "iterations" -> out.identity.identityIterations.iterations,
          "converged" -> out.identity.identityIterations.converged
        )
      ),
      "explain" -> ujson.Obj(
        "edgeAggregation" -> new Path(runDir, "explain-edge-aggregation.txt").toString,
        "componentIteration1" -> new Path(runDir, "explain-component-iteration-1.txt").toString,
        "lpaIteration1" -> new Path(runDir, "explain-lpa-iteration-1.txt").toString,
        "bfsFrontierStep1" -> new Path(runDir, "explain-bfs-frontier-step-1.txt").toString,
        "kcorePeel1" -> new Path(runDir, "explain-kcore-peel-1.txt").toString
      ),
      "stages" -> ujson.Arr.from(
        stages.map(s =>
          ujson.Obj(
            "stageId" -> s.stageId,
            "attempt" -> s.attempt,
            "name" -> s.name,
            "tasks" -> s.numTasks,
            "shuffleReadBytes" -> s.shuffleReadBytes.toDouble,
            "shuffleReadRecords" -> s.shuffleReadRecords.toDouble,
            "shuffleWriteBytes" -> s.shuffleWriteBytes.toDouble,
            "shuffleWriteRecords" -> s.shuffleWriteRecords.toDouble
          )
        )
      )
    )
    writeText(new Path(runDir, "run-summary.json"), ujson.write(summary, indent = 2), conf)

    val text =
      report(
        runId,
        runDir,
        tables,
        summaries,
        stats,
        stages,
        edgePlan,
        componentPlan,
        inputCounts
      ) +
        astats.lines.mkString("", "\n", "\n") +
        manifestPaths.map((t, p) => s"LOAD-MANIFEST $t $p").mkString("", "\n", "\n") +
        "EXPLAIN LPA iteration 1 (formatted physical plan):\n" + lpaPlan +
        "EXPLAIN BFS frontier step 1 (formatted physical plan):\n" + bfsPlan +
        "EXPLAIN k-core peel round 1 (formatted physical plan):\n" + kcorePlan
    RunResult(runId, runDir.toString, tables, summaries, text)

  /** Parquet files of one run of one table (below `window=*` when windowed). */
  private def countFiles(path: Path, runHex: String, conf: Configuration): Int =
    val f = fs(path, conf)
    if !f.exists(path) then 0
    else
      val it = f.listFiles(path, true)
      var n = 0
      while it.hasNext do
        val p = it.next().getPath
        if p.getName.endsWith(".parquet") && p.getParent.getName == s"run_id=$runHex" then n += 1
      n

  private def report(
      runId: String,
      runDir: Path,
      tables: Vector[WrittenTable],
      summaries: Vector[WindowGraphSummary],
      stats: RunStatistics,
      stages: Vector[StageMetrics],
      edgePlan: String,
      componentPlan: String,
      inputs: Vector[(InputTable, Long)]
  ): String =
    val sb = new StringBuilder
    def line(s: String): Unit = sb.append(s).append('\n')
    line(s"RUN $runId")
    line(s"RUN DIR $runDir")
    inputs.foreach((t, n) =>
      line(f"INPUT ${t.name}%-26s rows=$n%-6d files=${t.files.size}%-3d ${t.checksum}")
    )
    tables.foreach(t =>
      line(
        f"OUTPUT ${t.name}%-26s rows=${t.rowCount}%-6d files=${t.fileCount}%-3d contractViolations=${t.contractViolations.fold("n/a")(_.toString)} ${t.path}"
      )
    )
    summaries.foreach { s =>
      val frac =
        if s.researcherCount == 0 then 0.0 else s.largestComponentSize.toDouble / s.researcherCount
      val it = s.iterations.head
      line(
        f"WINDOW ${s.windowStartYear}-${s.windowEndYear}%s allYears=${s.allYearsWindow} researchers=${s.researcherCount} works=${s.workCount} yearless=${s.yearlessWorkCount} edges=${s.coauthorEdgeCount} selfPairsDropped=${s.selfPairsDropped} oversizeWorks=${s.oversizeTeamWorkCount} components=${s.componentCount} largest=${s.largestComponentSize} largestFraction=$frac%.4f singletons=${s.singletonComponentCount} iterations=${it.iterations} converged=${it.converged}"
      )
    }
    stats.lines.foreach(line)
    line(
      s"STAGES ${stages.size} totalShuffleReadBytes=${stages.map(_.shuffleReadBytes).sum} totalShuffleWriteBytes=${stages.map(_.shuffleWriteBytes).sum}"
    )
    line("EXPLAIN edge aggregation (formatted physical plan):")
    sb.append(edgePlan)
    line("EXPLAIN component iteration 1 (formatted physical plan):")
    sb.append(componentPlan)
    sb.toString
