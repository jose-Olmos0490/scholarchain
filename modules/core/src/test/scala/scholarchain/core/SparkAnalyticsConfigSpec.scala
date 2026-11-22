package scholarchain.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.*
import scholarchain.core.ids.StableIds

/** Phase 6 Spark analytics configuration: the all-years window, Spark I/O and execution keys,
  * identity, components, k-core, paths, diameter and semantic-fact bounds. The process
  * environment is never read: every test passes its own map.
  */
class SparkAnalyticsConfigSpec extends AnyFunSuite with Matchers:

  private def env(name: String, vars: (String, String)*): Map[String, String] =
    Map(ConfigLoader.EnvironmentVariable -> name) ++ vars

  private def w(a: Int, b: Int): YearWindow = YearWindow.of(a, b).toOption.get

  private def rid(anchor: String): String =
    StableIds.researcherIdScholarChain("v1", anchor).toOption.get

  private val graphVars: Map[String, String] = Map(
    "DBLP_SUBSET_START_YEAR" -> "2000",
    "DBLP_SUBSET_END_YEAR" -> "2009",
    "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2004,2005-2009",
    "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "p",
    "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "true",
    "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT" -> "3"
  )

  private val sparkVars: Map[String, String] = Map(
    "SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT" -> "target/phase04/run-b4",
    "SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR" -> "er/run",
    "SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> "local-run/",
    "SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS" -> "8",
    "SCHOLARCHAIN_SPARK_CHECKPOINT_DIR" -> "local-run-checkpoints",
    "SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL" -> "2"
  )

  private val analyticsVars: Map[String, String] = Map(
    "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION" -> "v1",
    "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE" -> "10",
    "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS" -> "20",
    "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS" -> "30",
    "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH" -> "6",
    "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT" -> "4",
    "SCHOLARCHAIN_GRAPH_PATHS_SEED" -> "42",
    "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES" -> "3",
    "SCHOLARCHAIN_GRAPH_DIAMETER_SEED" -> "-7",
    "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE" -> "2",
    "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT" -> "5",
    "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT" -> "10"
  )

  private def section[A](s: ConfigSection[A], vars: Map[String, String]) =
    ConfigLoader.loadSection(s, env("empty-test") ++ vars)

  private def loaded[A](s: ConfigSection[A], vars: Map[String, String]): A =
    section(s, vars).fold(e => fail(e.message), identity)

  private def invalid[A](s: ConfigSection[A], vars: Map[String, String]): Vector[InvalidSetting] =
    section(s, vars) match
      case Left(ConfigError.Invalid(missing, inv)) =>
        missing shouldBe empty
        inv
      case other => fail(s"expected ConfigError.Invalid, got $other")

  // --- all-years window -----------------------------------------------------------------------

  test("all-years window: spans the DBLP subset years and coexists with the fixed windows") {
    val g = loaded(GraphConfig, graphVars)
    g.allYearsWindow shouldBe Some(w(2000, 2009))
    g.windows shouldBe Vector(w(2000, 2004), w(2005, 2009))
    g.analysisWindows shouldBe Vector(w(2000, 2009), w(2000, 2004), w(2005, 2009))
    g.representativeWorkLimit shouldBe 3
    // The same window in the fixed list is still rejected by the non-overlap rule.
    YearWindow.seriesViolations(
      Vector(w(2000, 2009), w(2000, 2004)),
      Some(w(2000, 2009))
    ) shouldBe Vector("windows 2000-2009 and 2000-2004 overlap")
  }

  test("window membership: a year-less work belongs only to the all-years window") {
    val g = loaded(GraphConfig, graphVars)
    g.windowsFor(None) shouldBe Vector(w(2000, 2009))
    g.windowsFor(Some(2004)) shouldBe Vector(w(2000, 2009), w(2000, 2004))
    g.windowsFor(Some(2005)) shouldBe Vector(w(2000, 2009), w(2005, 2009))
    g.windowsFor(Some(2010)) shouldBe empty
    g.isAllYearsWindow(w(2000, 2009)) shouldBe true
    g.isAllYearsWindow(w(2000, 2004)) shouldBe false
  }

  test("window membership: with the all-years window disabled, a year-less work is in none") {
    val g =
      loaded(GraphConfig, graphVars + ("SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "false"))
    g.allYearsWindow shouldBe None
    g.analysisWindows shouldBe g.windows
    g.windowsFor(None) shouldBe empty
    g.windowsFor(Some(2001)) shouldBe Vector(w(2000, 2004))
  }

  test("all-years window: the approved dev windows plus the all-years window load together") {
    val c = ConfigLoader
      .loadSection(
        GraphConfig,
        env(
          "dev",
          "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "p",
          "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "true",
          "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT" -> "5"
        )
      )
      .fold(e => fail(e.message), identity)
    c.allYearsWindow shouldBe Some(w(2000, 2025))
    c.analysisWindows.size shouldBe 6
  }

  test("all-years window: a fixed window equal to it is rejected") {
    invalid(
      GraphConfig,
      graphVars + ("SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2009")
    ) shouldBe Vector(
      InvalidSetting(
        "scholarchain.graph.windows",
        "window 2000-2009 equals the all-years window; remove it or set " +
          "includeAllYearsWindow = false"
      )
    )
    loaded(
      GraphConfig,
      graphVars ++ Map(
        "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2009",
        "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "false"
      )
    ).analysisWindows shouldBe Vector(w(2000, 2009))
  }

  test("includeAllYearsWindow must be exactly true or false") {
    Seq("yes", "TRUE", "1").foreach { v =>
      invalid(GraphConfig, graphVars + ("SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> v))
        .shouldBe(
          Vector(
            InvalidSetting(
              "scholarchain.graph.includeAllYearsWindow",
              s"'$v' is not a boolean (true or false)"
            )
          )
        )
    }
  }

  test("representativeWorkLimit (K) must be >= 1") {
    invalid(GraphConfig, graphVars + ("SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT" -> "0"))
      .map(_.path) shouldBe Vector("scholarchain.graph.representativeWorkLimit")
  }

  test("graph section: the new keys are required and listed with their variables") {
    section(
      GraphConfig,
      graphVars - "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -
        "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT"
    ) shouldBe Left(
      ConfigError.Invalid(
        Vector(
          MissingKey(
            "scholarchain.graph.includeAllYearsWindow",
            "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW"
          ),
          MissingKey(
            "scholarchain.graph.representativeWorkLimit",
            "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT"
          )
        ),
        Vector.empty
      )
    )
  }

  // --- spark ----------------------------------------------------------------------------------

  test("spark: every key is read from its variable; the Hadoop run ID is optional") {
    loaded(SparkJobConfig, sparkVars) shouldBe SparkJobConfig(
      hadoopInputRoot = "target/phase04/run-b4",
      hadoopRunId = None,
      matchDecisionsDir = "er/run",
      outputRoot = "local-run/",
      shufflePartitions = 8,
      checkpointDir = "local-run-checkpoints",
      checkpointInterval = 2
    )
    val run = "run:" + "a" * 64
    loaded(
      SparkJobConfig,
      sparkVars + ("SCHOLARCHAIN_SPARK_HADOOP_RUN_ID" -> run)
    ).hadoopRunId shouldBe Some(run)
    loaded(
      SparkJobConfig,
      sparkVars + ("SCHOLARCHAIN_SPARK_HADOOP_RUN_ID" -> "  ")
    ).hadoopRunId shouldBe None
  }

  test("spark: a malformed Hadoop run ID is rejected") {
    invalid(SparkJobConfig, sparkVars + ("SCHOLARCHAIN_SPARK_HADOOP_RUN_ID" -> "fa68b48b"))
      .shouldBe(
        Vector(
          InvalidSetting(
            "scholarchain.spark.hadoopRunId",
            "'fa68b48b' is not a run ID (run:<64 lower-case hex>)"
          )
        )
      )
  }

  test("spark: missing keys are listed with their variables") {
    section(SparkJobConfig, Map.empty) match
      case Left(ConfigError.Invalid(missing, Vector())) =>
        missing.map(_.environmentVariable) shouldBe Vector(
          "SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT",
          "SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR",
          "SCHOLARCHAIN_SPARK_OUTPUT_ROOT",
          "SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS",
          "SCHOLARCHAIN_SPARK_CHECKPOINT_DIR",
          "SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL"
        )
      case other => fail(s"unexpected $other")
  }

  test("spark: numeric lower bounds") {
    invalid(SparkJobConfig, sparkVars + ("SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS" -> "0"))
      .map(_.path) shouldBe Vector("scholarchain.spark.shufflePartitions")
    invalid(SparkJobConfig, sparkVars + ("SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL" -> "0"))
      .map(_.path) shouldBe Vector("scholarchain.spark.checkpointInterval")
  }

  test("spark: locations may not be http(s) URLs; object-store URIs are accepted") {
    invalid(
      SparkJobConfig,
      sparkVars + ("SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT" -> "https://example.invalid/in")
    ).map(_.path) shouldBe Vector("scholarchain.spark.hadoopInputRoot")
    loaded(
      SparkJobConfig,
      sparkVars + ("SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> "s3://bucket/graph/")
    ).outputRoot shouldBe "s3://bucket/graph/"
  }

  test("spark: the output root may not be an input, and checkpoints may not be the output") {
    invalid(
      SparkJobConfig,
      sparkVars + ("SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> "target/phase04/run-b4/")
    ) shouldBe Vector(
      InvalidSetting("scholarchain.spark.outputRoot", "must differ from spark.hadoopInputRoot")
    )
    invalid(SparkJobConfig, sparkVars + ("SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> "er/run"))
      .map(_.reason) shouldBe Vector("must differ from spark.matchDecisionsDir")
    invalid(SparkJobConfig, sparkVars + ("SCHOLARCHAIN_SPARK_CHECKPOINT_DIR" -> "local-run"))
      .shouldBe(
        Vector(
          InvalidSetting("scholarchain.spark.checkpointDir", "must differ from spark.outputRoot")
        )
      )
  }

  // --- analytics ------------------------------------------------------------------------------

  test("analytics: every parameter is read from its variable") {
    loaded(GraphAnalyticsConfig, analyticsVars) shouldBe GraphAnalyticsConfig(
      identity = IdentityConfig("v1", 10),
      components = ComponentsConfig(20),
      kcore = KCoreConfig(30),
      paths = PathConfig(6, Vector.empty, 4, 42L),
      diameter = DiameterConfig(3, -7L, 2),
      semanticFacts = SemanticFactsConfig(5, 10)
    )
  }

  test("analytics: lower bounds and formats are enforced") {
    val cases = Seq(
      "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION" -> "1" -> "scholarchain.graph.identity.version",
      "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION" -> "v0" -> "scholarchain.graph.identity.version",
      "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE" -> "0" ->
        "scholarchain.graph.identity.maxClusterSize",
      "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS" -> "0" ->
        "scholarchain.graph.components.maxIterations",
      "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS" -> "0" -> "scholarchain.graph.kcore.maxIterations",
      "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH" -> "0" -> "scholarchain.graph.paths.maxDepth",
      "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT" -> "0" ->
        "scholarchain.graph.paths.samplePairCount",
      "SCHOLARCHAIN_GRAPH_PATHS_SEED" -> "seed" -> "scholarchain.graph.paths.seed",
      "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES" -> "0" ->
        "scholarchain.graph.diameter.sampleSources",
      "SCHOLARCHAIN_GRAPH_DIAMETER_SEED" -> "1.5" -> "scholarchain.graph.diameter.seed",
      "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE" -> "1" ->
        "scholarchain.graph.diameter.minComponentSize",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT" -> "0" ->
        "scholarchain.graph.semanticFacts.topEntityLimit",
      "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT" -> "0" ->
        "scholarchain.graph.semanticFacts.memberLimit"
    )
    cases.foreach { case ((variable, value), path) =>
      withClue(s"$variable=$value: ")(
        invalid(GraphAnalyticsConfig, analyticsVars + (variable -> value))
          .map(_.path) shouldBe Vector(path)
      )
    }
  }

  test("analytics: missing keys are listed in reading order") {
    section(GraphAnalyticsConfig, Map.empty) match
      case Left(ConfigError.Invalid(missing, Vector())) =>
        missing.map(_.environmentVariable) shouldBe Vector(
          "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION",
          "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE",
          "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS",
          "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS",
          "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH",
          "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT",
          "SCHOLARCHAIN_GRAPH_PATHS_SEED",
          "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES",
          "SCHOLARCHAIN_GRAPH_DIAMETER_SEED",
          "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE",
          "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT",
          "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT"
        )
      case other => fail(s"unexpected $other")
  }

  // --- benchmark pairs ------------------------------------------------------------------------

  private def pathVars(pairs: String): Map[String, String] =
    analyticsVars + ("SCHOLARCHAIN_GRAPH_PATHS_BENCHMARK_PAIRS" -> pairs)

  test("benchmark pairs: parsed in configured order and direction; blank means none") {
    val (a, f, g) = (rid("A"), rid("F"), rid("G"))
    loaded(PathConfig, pathVars(s"$a|$f, $g | $a")).benchmarkPairs shouldBe Vector(
      BenchmarkPair(a, f),
      BenchmarkPair(g, a)
    )
    loaded(PathConfig, pathVars("   ")).benchmarkPairs shouldBe empty
    loaded(PathConfig, analyticsVars).benchmarkPairs shouldBe empty
  }

  test("benchmark pairs: self-pairs, non-researcher IDs and malformed entries are rejected") {
    val a = rid("A")
    val key = "scholarchain.graph.paths.benchmarkPairs"
    invalid(PathConfig, pathVars(s"$a|$a")) shouldBe Vector(
      InvalidSetting(key, s"'$a|$a': source and target are the same researcher")
    )
    invalid(PathConfig, pathVars(s"$a|work:dblp:${"0" * 64}")).map(_.path) shouldBe Vector(key)
    invalid(PathConfig, pathVars(a)) shouldBe Vector(
      InvalidSetting(key, s"'$a' is not source|target")
    )
    invalid(PathConfig, pathVars(s"$a|${rid("B")},")).map(_.reason) shouldBe Vector(
      "'' is not source|target"
    )
  }

  test("benchmark pairs: the same unordered pair twice is rejected") {
    val (a, b) = (rid("A"), rid("B"))
    invalid(PathConfig, pathVars(s"$a|$b,$b|$a")) shouldBe Vector(
      InvalidSetting(
        "scholarchain.graph.paths.benchmarkPairs",
        "contains the same unordered pair more than once"
      )
    )
  }

  // --- full configuration ---------------------------------------------------------------------

  test("the full configuration carries the spark and analytics sections") {
    val c = ConfigLoader.load(env("contract-test")).fold(e => fail(e.message), identity)
    c.graph.allYearsWindow shouldBe Some(w(2001, 2002))
    c.graph.representativeWorkLimit shouldBe 3
    c.spark.outputRoot shouldBe "file-fixtures/out"
    c.spark.shufflePartitions shouldBe 2
    c.analytics.identity shouldBe IdentityConfig("v1", 4)
    c.analytics.paths shouldBe PathConfig(3, Vector.empty, 2, 11L)
    c.analytics.diameter shouldBe DiameterConfig(2, 13L, 3)
    c.analytics.semanticFacts shouldBe SemanticFactsConfig(5, 7)
    // Reused sections: label propagation and approximate betweenness parameters.
    c.community.maxIterations shouldBe 3
    (c.bridge.sampleSize, c.bridge.randomSeed) shouldBe (10, 7L)
  }

  test("an environment variable overrides an analytics value from the environment file") {
    val c = ConfigLoader
      .load(env("contract-test", "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH" -> "9"))
      .fold(e => fail(e.message), identity)
    c.analytics.paths.maxDepth shouldBe 9
  }
