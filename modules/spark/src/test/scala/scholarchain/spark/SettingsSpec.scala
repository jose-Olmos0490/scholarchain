package scholarchain.spark

import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.{ConfigError, ConfigLoader}

/** The job's configuration comes only from the core loader; nothing is hardcoded. */
class SettingsSpec extends AnyFunSuite:
  val Env: Map[String, String] = Map(
    "SCHOLARCHAIN_ENV" -> "dev",
    "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "coauthor-policy-v1",
    "SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW" -> "true",
    "SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT" -> "5",
    "SCHOLARCHAIN_GRAPH_IDENTITY_VERSION" -> "v1",
    "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE" -> "40",
    "SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS" -> "25",
    "SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT" -> "in",
    "SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR" -> "er",
    "SCHOLARCHAIN_SPARK_OUTPUT_ROOT" -> "out",
    "SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS" -> "8",
    "SCHOLARCHAIN_SPARK_CHECKPOINT_DIR" -> "ckpt",
    "SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL" -> "3",
    "SCHOLARCHAIN_COMMUNITY_ALGORITHM" -> "label-propagation",
    "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS" -> "20",
    "SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS" -> "30",
    "SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH" -> "6",
    "SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT" -> "3",
    "SCHOLARCHAIN_GRAPH_PATHS_SEED" -> "11",
    "SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES" -> "4",
    "SCHOLARCHAIN_GRAPH_DIAMETER_SEED" -> "13",
    "SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE" -> "2",
    "SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT" -> "3",
    "SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT" -> "5"
  )

  test("loads the PART A section; the all-years window comes first, then the dev windows") {
    val s =
      ConfigLoader.loadSection(SparkAnalyticsSettings, Env).fold(e => fail(e.toString), x => x)
    assert(
      s.windows.map(w => (w.startYear, w.endYear, w.allYears)) == Vector(
        (2000, 2025, true),
        (2000, 2005, false),
        (2006, 2010, false),
        (2011, 2015, false),
        (2016, 2020, false),
        (2021, 2025, false)
      )
    )
    assert(s.graph.representativeWorkLimit == 5 && s.identity.maxClusterSize == 40)
    assert(s.components.maxIterations == 25 && s.spark.shufflePartitions == 8)
    assert(s.spark.checkpointInterval == 3 && s.spark.hadoopRunId.isEmpty)
    assert(s.logicalParameters("graph.identity.version") == "v1")
    assert(!s.logicalParameters.contains("spark.outputRoot")) // locations do not change the run ID
  }

  test("loads the PART B parameters; they are logical parameters (part of the run ID)") {
    val s =
      ConfigLoader.loadSection(SparkAnalyticsSettings, Env).fold(e => fail(e.toString), x => x)
    assert(s.community.maxIterations == 20 && s.kcore.maxIterations == 30)
    assert(s.paths.maxDepth == 6 && s.paths.samplePairCount == 3 && s.paths.seed == 11L)
    assert(s.paths.benchmarkPairs.isEmpty)
    assert(s.diameter == scholarchain.core.config.DiameterConfig(4, 13L, 2))
    assert(s.semanticFacts.topEntityLimit == 3 && s.semanticFacts.memberLimit == 5)
    assert(s.logicalParameters("graph.paths.seed") == "11")
    assert(s.logicalParameters("community.maxIterations") == "20")
  }

  test("an unsupported community algorithm is rejected") {
    val r = ConfigLoader.loadSection(
      SparkAnalyticsSettings,
      Env + ("SCHOLARCHAIN_COMMUNITY_ALGORITHM" -> "louvain")
    )
    assert(r.left.exists(_.message.contains("community.algorithm")))
  }

  test("a missing key is reported with its environment variable") {
    val r = ConfigLoader.loadSection(
      SparkAnalyticsSettings,
      Env - "SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE"
    )
    r match
      case Left(ConfigError.Invalid(missing, _)) =>
        assert(
          missing
            .map(_.environmentVariable)
            .contains("SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE")
        )
      case other => fail(s"expected a missing-key error, got $other")
  }
