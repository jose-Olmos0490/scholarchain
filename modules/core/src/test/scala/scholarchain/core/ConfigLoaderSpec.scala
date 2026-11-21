package scholarchain.core

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.*

/** Configuration precedence (env var > versioned env file, optional > reference.conf),
  * missing-key and invalid-value behaviour, environment filtering. The process environment is
  * never read: every test passes its own map.
  */
class ConfigLoaderSpec extends AnyFunSuite with Matchers:

  private def env(name: String, vars: (String, String)*): Map[String, String] =
    Map(ConfigLoader.EnvironmentVariable -> name) ++ vars

  /** Every required key with its environment variable, as frozen by this contract. */
  private val requiredKeys: Vector[(String, String)] = Vector(
    "application.awsRegion" -> "AWS_REGION",
    "application.awsAccountId" -> "SCHOLARCHAIN_AWS_ACCOUNT_ID",
    "storage.rawBucket" -> "RAW_BUCKET",
    "storage.curatedBucket" -> "CURATED_BUCKET",
    "storage.graphBucket" -> "GRAPH_BUCKET",
    "storage.ragBucket" -> "RAG_BUCKET",
    "storage.vectorBucket" -> "VECTOR_BUCKET",
    "neptune.endpoint" -> "NEPTUNE_ENDPOINT",
    "neptune.port" -> "NEPTUNE_PORT",
    "neptune.queryTimeoutMs" -> "NEPTUNE_QUERY_TIMEOUT_MS",
    "vectorIndexes.researchers" -> "VECTOR_INDEX_RESEARCHERS",
    "vectorIndexes.communities" -> "VECTOR_INDEX_COMMUNITIES",
    "vectorIndexes.abstracts" -> "VECTOR_INDEX_ABSTRACTS",
    "vectorIndexes.paths" -> "VECTOR_INDEX_PATHS",
    "vectorIndexes.topicInstitutions" -> "VECTOR_INDEX_TOPIC_INSTITUTIONS",
    "datasets.dblpSubset.recordTypes" -> "DBLP_SUBSET_RECORD_TYPES",
    "datasets.dblpSubset.startYear" -> "DBLP_SUBSET_START_YEAR",
    "datasets.dblpSubset.endYear" -> "DBLP_SUBSET_END_YEAR",
    "datasets.dblpSubset.maxWorks" -> "DBLP_SUBSET_MAX_WORKS",
    "hadoop.numReducers" -> "SCHOLARCHAIN_HADOOP_NUM_REDUCERS",
    "hadoop.dblpDtdPath" -> "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH",
    "graph.windows" -> "SCHOLARCHAIN_GRAPH_WINDOWS",
    "graph.coauthorPolicyVersion" -> "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION",
    "entityResolution.policyVersion" -> "SCHOLARCHAIN_ER_POLICY_VERSION",
    "entityResolution.acceptThreshold" -> "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD",
    "entityResolution.rejectThreshold" -> "SCHOLARCHAIN_ER_REJECT_THRESHOLD",
    "entityResolution.llmBandMin" -> "SCHOLARCHAIN_ER_LLM_BAND_MIN",
    "entityResolution.llmBandMax" -> "SCHOLARCHAIN_ER_LLM_BAND_MAX",
    "bridge.algorithm" -> "SCHOLARCHAIN_BRIDGE_ALGORITHM",
    "bridge.sampleSize" -> "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE",
    "bridge.randomSeed" -> "SCHOLARCHAIN_BRIDGE_RANDOM_SEED",
    "community.algorithm" -> "SCHOLARCHAIN_COMMUNITY_ALGORITHM",
    "community.maxIterations" -> "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS",
    "retrieval.graphLimit" -> "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT",
    "retrieval.vectorLimit" -> "SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT",
    "retrieval.maxPathDepth" -> "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH",
    "retrieval.contextTokenBudget" -> "SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET",
    "retrieval.fusionPolicyVersion" -> "SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION",
    "models.embeddingModelId" -> "EMBEDDING_MODEL_ID",
    "models.synthesisModelId" -> "SYNTHESIS_MODEL_ID",
    "models.verificationModelId" -> "VERIFICATION_MODEL_ID",
    "operations.synchronousDeadlineMs" -> "SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS",
    "operations.maxWorkflowRetries" -> "SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES",
    "operations.maxConcurrentLlmCalls" -> "SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS"
  )

  /** Valid fictitious values for every required environment variable. */
  private val allEnvVars: Map[String, String] = Map(
    "AWS_REGION" -> "eu-west-1",
    "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "111111111111",
    "RAW_BUCKET" -> "env-raw-bucket",
    "CURATED_BUCKET" -> "env-curated-bucket",
    "GRAPH_BUCKET" -> "env-graph-bucket",
    "RAG_BUCKET" -> "env-rag-bucket",
    "VECTOR_BUCKET" -> "env-vector-bucket",
    "NEPTUNE_ENDPOINT" -> "env-neptune.example.invalid",
    "NEPTUNE_PORT" -> "2222",
    "NEPTUNE_QUERY_TIMEOUT_MS" -> "2000",
    "VECTOR_INDEX_RESEARCHERS" -> "env-researchers",
    "VECTOR_INDEX_COMMUNITIES" -> "env-communities",
    "VECTOR_INDEX_ABSTRACTS" -> "env-abstracts",
    "VECTOR_INDEX_PATHS" -> "env-paths",
    "VECTOR_INDEX_TOPIC_INSTITUTIONS" -> "env-topic-institutions",
    "DBLP_SUBSET_RECORD_TYPES" -> "inproceedings",
    "DBLP_SUBSET_START_YEAR" -> "2010",
    "DBLP_SUBSET_END_YEAR" -> "2012",
    "DBLP_SUBSET_MAX_WORKS" -> "25",
    "SCHOLARCHAIN_HADOOP_NUM_REDUCERS" -> "3",
    "SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH" -> "env/dblp.dtd",
    "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2011,2012-2012",
    "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "env-graph-policy",
    "SCHOLARCHAIN_ER_POLICY_VERSION" -> "env-er-policy",
    "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "0.8",
    "SCHOLARCHAIN_ER_REJECT_THRESHOLD" -> "0.2",
    "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.3",
    "SCHOLARCHAIN_ER_LLM_BAND_MAX" -> "0.7",
    "SCHOLARCHAIN_BRIDGE_ALGORITHM" -> "env-bridge",
    "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE" -> "20",
    "SCHOLARCHAIN_BRIDGE_RANDOM_SEED" -> "99",
    "SCHOLARCHAIN_COMMUNITY_ALGORITHM" -> "env-community",
    "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS" -> "4",
    "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT" -> "7",
    "SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT" -> "8",
    "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH" -> "3",
    "SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET" -> "200",
    "SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION" -> "env-fusion",
    "EMBEDDING_MODEL_ID" -> "env-embedding-model",
    "SYNTHESIS_MODEL_ID" -> "env-synthesis-model",
    "VERIFICATION_MODEL_ID" -> "env-verification-model",
    "SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS" -> "3000",
    "SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES" -> "2",
    "SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS" -> "5"
  )

  private def loaded(e: Map[String, String]): ScholarChainConfig =
    ConfigLoader.load(e).fold(err => fail(err.message), identity)

  private def invalid(e: Map[String, String]): ConfigError.Invalid =
    ConfigLoader.load(e) match
      case Left(i: ConfigError.Invalid) => i
      case other => fail(s"expected ConfigError.Invalid, got $other")

  // --- precedence ---------------------------------------------------------------------------

  test("precedence: the environment file supplies values when no variable is set") {
    val c = loaded(env("contract-test"))
    c.environment shouldBe "contract-test"
    c.storage.rawBucket shouldBe "file-raw-bucket"
    c.datasets.dblpSubset shouldBe
      DblpSubsetConfig(Vector("article"), YearWindow.of(2001, 2002).toOption.get, 50)
    c.graph.windows shouldBe Vector(
      YearWindow.of(2001, 2001).toOption.get,
      YearWindow.of(2002, 2002).toOption.get
    )
    c.entityResolution.acceptThreshold shouldBe 0.9
  }

  test("precedence: an environment variable overrides the environment file") {
    val c = loaded(
      env("contract-test", "RAW_BUCKET" -> "env-raw-bucket", "DBLP_SUBSET_END_YEAR" -> "2005")
    )
    c.storage.rawBucket shouldBe "env-raw-bucket"
    c.storage.curatedBucket shouldBe "file-curated-bucket"
    c.datasets.dblpSubset.years shouldBe YearWindow.of(2001, 2005).toOption.get
  }

  test("precedence: reference.conf supplies a value the other layers leave unset") {
    loaded(env("contract-test")).application.name shouldBe "scholarchain"
  }

  test("precedence: the environment file overrides reference.conf") {
    loaded(env("precedence-test")).application.name shouldBe "file-application-name"
  }

  test("precedence: an environment variable overrides both files") {
    loaded(
      env("precedence-test", "SCHOLARCHAIN_APPLICATION_NAME" -> "env-application-name")
    ).application.name shouldBe
      "env-application-name"
  }

  test("every required key can be supplied by its environment variable alone") {
    val c = loaded(env("empty-test") ++ allEnvVars)
    c.storage shouldBe StorageConfig(
      "env-raw-bucket",
      "env-curated-bucket",
      "env-graph-bucket",
      "env-rag-bucket",
      "env-vector-bucket"
    )
    c.neptune shouldBe NeptuneConfig("env-neptune.example.invalid", 2222, 2000)
    c.application.awsAccountId shouldBe "111111111111"
    c.models.embeddingModelId shouldBe "env-embedding-model"
    c.bridge shouldBe BridgeConfig("env-bridge", 20, 99L)
    c.operations shouldBe OperationsConfig(3000, 2, 5)
  }

  // --- missing keys -------------------------------------------------------------------------

  test(
    "missing keys: with no values supplied, the error lists every required key and its variable"
  ) {
    val err = invalid(env("empty-test"))
    err.invalidSettings shouldBe empty
    err.missingKeys.map(k => (k.path, k.environmentVariable)) shouldBe
      requiredKeys.map((p, v) => (s"scholarchain.$p", v))
  }

  test("missing keys: one absent variable is reported alone, with its name") {
    val err = invalid(env("empty-test") ++ (allEnvVars - "GRAPH_BUCKET" - "DBLP_SUBSET_START_YEAR"))
    err.missingKeys shouldBe Vector(
      MissingKey("scholarchain.storage.graphBucket", "GRAPH_BUCKET"),
      MissingKey("scholarchain.datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR")
    )
  }

  test("missing keys: a blank environment variable counts as missing") {
    val err = invalid(env("empty-test") ++ allEnvVars + ("EMBEDDING_MODEL_ID" -> "  "))
    err.missingKeys shouldBe Vector(
      MissingKey("scholarchain.models.embeddingModelId", "EMBEDDING_MODEL_ID")
    )
  }

  test(
    "no hardcoded defaults: reference.conf sets only the schema version, app name and the " +
      "human-approved Hadoop defaults"
  ) {
    val base =
      ConfigFactory.parseResources(getClass.getClassLoader, ConfigLoader.BaseResource).resolve()
    base
      .entrySet()
      .asScala
      .map(e => e.getKey -> e.getValue.unwrapped().toString)
      .toMap shouldBe Map(
      "scholarchain.config.schemaVersion" -> "config-v1",
      "scholarchain.application.name" -> "scholarchain",
      // Phase 04 work order A (human-authored): the only defaulted parameters.
      "scholarchain.hadoop.maxBlockSize" -> "200",
      "scholarchain.hadoop.largeTeamThreshold" -> "50",
      "scholarchain.hadoop.maxPairExpansionTeamSize" -> "2000",
      "scholarchain.hadoop.intermediateCodec" -> "org.apache.hadoop.io.compress.DefaultCodec",
      "scholarchain.hadoop.outputCodec" -> "org.apache.hadoop.io.compress.GzipCodec"
    )
  }

  test("no hardcoded defaults: env-overrides.conf contains only optional ${?VAR} bindings") {
    val overrides =
      ConfigFactory.parseResources(getClass.getClassLoader, ConfigLoader.OverridesResource)
    overrides
      .resolveWith(ConfigFactory.empty(), com.typesafe.config.ConfigResolveOptions.noSystem())
      .entrySet() shouldBe empty
  }

  test("sections load independently: storage loads without model or threshold values") {
    val storageOnly = Map(
      "RAW_BUCKET" -> "r",
      "CURATED_BUCKET" -> "c",
      "GRAPH_BUCKET" -> "g",
      "RAG_BUCKET" -> "a",
      "VECTOR_BUCKET" -> "v"
    )
    ConfigLoader.loadSection(StorageConfig, env("empty-test") ++ storageOnly) shouldBe
      Right(StorageConfig("r", "c", "g", "a", "v"))
    ConfigLoader.loadSection(DatasetConfig, env("empty-test")) shouldBe Left(
      ConfigError.Invalid(
        Vector(
          MissingKey("scholarchain.datasets.dblpSubset.recordTypes", "DBLP_SUBSET_RECORD_TYPES"),
          MissingKey("scholarchain.datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR"),
          MissingKey("scholarchain.datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR"),
          MissingKey("scholarchain.datasets.dblpSubset.maxWorks", "DBLP_SUBSET_MAX_WORKS")
        ),
        Vector.empty
      )
    )
  }

  // --- invalid values -----------------------------------------------------------------------

  test("invalid values: the DBLP subset window rejects start year > end year") {
    val err = invalid(
      env("contract-test", "DBLP_SUBSET_START_YEAR" -> "2010", "DBLP_SUBSET_END_YEAR" -> "2005")
    )
    err.invalidSettings shouldBe Vector(
      InvalidSetting(
        "scholarchain.datasets.dblpSubset",
        "window start year 2010 exceeds end year 2005"
      )
    )
  }

  test("missing DBLP window bound: only the missing key is reported, no window error") {
    val noEnd = invalid(env("empty-test") ++ (allEnvVars - "DBLP_SUBSET_END_YEAR"))
    noEnd.missingKeys shouldBe Vector(
      MissingKey("scholarchain.datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR")
    )
    noEnd.invalidSettings shouldBe empty
    val noStart =
      invalid(
        env("empty-test") ++ (allEnvVars - "DBLP_SUBSET_START_YEAR") +
          ("DBLP_SUBSET_END_YEAR" -> "1990")
      )
    noStart.missingKeys shouldBe Vector(
      MissingKey("scholarchain.datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR")
    )
    noStart.invalidSettings shouldBe empty
  }

  test("invalid DBLP window bound: only the parse error is reported, no window error") {
    val err = invalid(
      env("empty-test") ++ allEnvVars + ("DBLP_SUBSET_START_YEAR" -> "2010") +
        ("DBLP_SUBSET_END_YEAR" -> "soon")
    )
    err.missingKeys shouldBe empty
    err.invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.datasets.dblpSubset.endYear", "'soon' is not an integer")
    )
  }

  test("invalid values: a graph window with start year > end year is rejected") {
    val err = invalid(env("contract-test", "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2005,2010-2006"))
    err.invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.graph.windows", "window start year 2010 exceeds end year 2006")
    )
  }

  test("invalid values: every problem is reported together") {
    val err = invalid(
      env(
        "contract-test",
        "NEPTUNE_PORT" -> "eighty",
        "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD" -> "1.5",
        "SCHOLARCHAIN_AWS_ACCOUNT_ID" -> "12345",
        "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000to2005",
        "AWS_REGION" -> "Mars"
      )
    )
    err.missingKeys shouldBe empty
    err.invalidSettings.map(_.path) shouldBe Vector(
      "scholarchain.application.awsRegion",
      "scholarchain.application.awsAccountId",
      "scholarchain.neptune.port",
      "scholarchain.graph.windows",
      "scholarchain.entityResolution.acceptThreshold"
    )
  }

  test("invalid values: entity-resolution thresholds must be ordered") {
    val err = invalid(env("contract-test", "SCHOLARCHAIN_ER_LLM_BAND_MIN" -> "0.95"))
    err.invalidSettings.map(_.path) shouldBe Vector("scholarchain.entityResolution")
  }

  test("invalid values: an unsupported configuration schema version is rejected") {
    val err = invalid(env("contract-test", "SCHOLARCHAIN_CONFIG_SCHEMA_VERSION" -> "config-v2"))
    err.invalidSettings.map(_.path) shouldBe Vector("scholarchain.config.schemaVersion")
  }

  // --- environment selection ----------------------------------------------------------------

  test("environment: SCHOLARCHAIN_ENV is required") {
    ConfigLoader.load(Map.empty) shouldBe Left(
      ConfigError.MissingEnvironmentName("SCHOLARCHAIN_ENV")
    )
    ConfigLoader.load(Map("SCHOLARCHAIN_ENV" -> " ")) shouldBe
      Left(ConfigError.MissingEnvironmentName("SCHOLARCHAIN_ENV"))
  }

  test("environment: a malformed name is rejected before any file is read") {
    ConfigLoader.load(env("../secrets")) shouldBe Left(
      ConfigError.InvalidEnvironmentName("../secrets")
    )
  }

  test("environment: a missing versioned file is an empty layer; env vars alone configure it") {
    getClass.getClassLoader.getResource(ConfigLoader.environmentResource("no-such-env")) shouldBe
      null
    val c = loaded(env("no-such-env") ++ allEnvVars)
    c.environment shouldBe "no-such-env"
    c.storage.rawBucket shouldBe "env-raw-bucket"
    c.datasets.dblpSubset.years shouldBe YearWindow.of(2010, 2012).toOption.get
  }

  test("environment: a missing versioned file with no variables lists every required key") {
    val err = invalid(env("no-such-env"))
    err.invalidSettings shouldBe empty
    err.missingKeys.map(_.environmentVariable) shouldBe requiredKeys.map(_._2)
  }

  // --- shipped dev environment file ---------------------------------------------------------

  /** The approved functional-run parameters (human decision 3c, 2026-11-20): the only entries
    * dev.conf may set.
    */
  private val devConfEntries: Map[String, String] = Map(
    "scholarchain.datasets.dblpSubset.recordTypes" -> "article,inproceedings",
    "scholarchain.datasets.dblpSubset.startYear" -> "2000",
    "scholarchain.datasets.dblpSubset.endYear" -> "2025",
    "scholarchain.datasets.dblpSubset.maxWorks" -> "100000",
    "scholarchain.graph.windows" -> "2000-2005,2006-2010,2011-2015,2016-2020,2021-2025"
  )

  private val devConfVariables: Set[String] = Set(
    "DBLP_SUBSET_RECORD_TYPES",
    "DBLP_SUBSET_START_YEAR",
    "DBLP_SUBSET_END_YEAR",
    "DBLP_SUBSET_MAX_WORKS",
    "SCHOLARCHAIN_GRAPH_WINDOWS"
  )

  private def devConf =
    ConfigFactory.parseResources(
      getClass.getClassLoader,
      ConfigLoader.environmentResource("dev"),
      ConfigParseOptions.defaults().setAllowMissing(false)
    )

  test("dev.conf sets exactly the approved functional-run parameters and nothing else") {
    getClass.getClassLoader.getResource(ConfigLoader.environmentResource("dev")) should not be null
    val entries = devConf
      .resolve()
      .entrySet()
      .asScala
      .map { e =>
        e.getKey -> e.getValue.unwrapped().toString
      }
      .toMap
    entries shouldBe devConfEntries
  }

  test("dev.conf sets no bucket, endpoint, account, Region, model ID, threshold or index") {
    val forbidden = Seq(
      "scholarchain.storage",
      "scholarchain.neptune",
      "scholarchain.vectorIndexes",
      "scholarchain.models",
      "scholarchain.application",
      "scholarchain.entityResolution",
      "scholarchain.bridge",
      "scholarchain.community",
      "scholarchain.retrieval",
      "scholarchain.operations",
      "scholarchain.config"
    )
    val keys = devConf.resolve().entrySet().asScala.map(_.getKey).toSet
    forbidden.foreach(prefix => withClue(prefix)(keys.filter(_.startsWith(prefix)) shouldBe empty))
    "[0-9]{12}".r.findFirstIn(devConf.resolve().root().render()) shouldBe None
  }

  test("dev.conf: with no variables, every key except the five it sets is missing") {
    val err = invalid(env("dev"))
    err.invalidSettings shouldBe empty
    err.missingKeys.map(k => (k.path, k.environmentVariable)) shouldBe
      requiredKeys
        .filterNot((_, v) => devConfVariables.contains(v))
        .map((p, v) => (s"scholarchain.$p", v))
  }

  test("dev.conf plus the remaining required variables loads with exactly the approved values") {
    val c = loaded(env("dev") ++ (allEnvVars -- devConfVariables))
    c.environment shouldBe "dev"
    c.datasets shouldBe DatasetConfig(
      DblpSubsetConfig(
        Vector("article", "inproceedings"),
        YearWindow.of(2000, 2025).toOption.get,
        100000
      )
    )
    c.graph.windows.map(w => (w.startYear, w.endYear)) shouldBe Vector(
      (2000, 2005),
      (2006, 2010),
      (2011, 2015),
      (2016, 2020),
      (2021, 2025)
    )
    c.graph.coauthorPolicyVersion shouldBe "env-graph-policy"
    // The Hadoop subset parameters come from dev.conf's DBLP subset (the manifest rule).
    c.hadoop.dblpRecordTypes shouldBe Vector("article", "inproceedings")
    (c.hadoop.yearFrom, c.hadoop.yearTo) shouldBe (2000, 2025)
    c.models.embeddingModelId shouldBe "env-embedding-model"
    c.neptune shouldBe NeptuneConfig("env-neptune.example.invalid", 2222, 2000)
  }

  test("dev.conf: an environment variable overrides the max-works cap") {
    val c =
      loaded(env("dev") ++ (allEnvVars -- devConfVariables) + ("DBLP_SUBSET_MAX_WORKS" -> "500"))
    c.datasets.dblpSubset.maxWorks shouldBe 500
  }

  // --- DBLP subset and analysis-window rules ------------------------------------------------

  private def invalidWith(vars: (String, String)*): ConfigError.Invalid =
    invalid(env("empty-test") ++ allEnvVars ++ vars)

  test("max works must be > 0") {
    invalidWith("DBLP_SUBSET_MAX_WORKS" -> "0").invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.datasets.dblpSubset.maxWorks", "0 is outside [1, 2147483647]")
    )
    invalidWith("DBLP_SUBSET_MAX_WORKS" -> "-5").invalidSettings.map(_.path) shouldBe Vector(
      "scholarchain.datasets.dblpSubset.maxWorks"
    )
    loaded(
      env("empty-test") ++ allEnvVars + ("DBLP_SUBSET_MAX_WORKS" -> "1")
    ).datasets.dblpSubset.maxWorks shouldBe 1
  }

  test("record types must be DBLP DTD record elements, without blanks or duplicates") {
    invalidWith("DBLP_SUBSET_RECORD_TYPES" -> "article,paper").invalidSettings.map(s =>
      (s.path, s.reason.takeWhile(_ != ';'))
    ) shouldBe Vector(
      ("scholarchain.datasets.dblpSubset.recordTypes", "not DBLP record elements: paper")
    )
    invalidWith("DBLP_SUBSET_RECORD_TYPES" -> "article,,inproceedings").invalidSettings
      .map(_.path) shouldBe Vector("scholarchain.datasets.dblpSubset.recordTypes")
    invalidWith("DBLP_SUBSET_RECORD_TYPES" -> "article,article").invalidSettings
      .map(_.reason) shouldBe Vector("'article,article' contains duplicates")
    loaded(
      env("empty-test") ++ allEnvVars + ("DBLP_SUBSET_RECORD_TYPES" -> " article , inproceedings ")
    ).datasets.dblpSubset.recordTypes shouldBe Vector("article", "inproceedings")
  }

  test("analysis windows must be sorted by start year") {
    invalidWith(
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2012-2012,2010-2011"
    ).invalidSettings shouldBe Vector(
      InvalidSetting(
        "scholarchain.graph.windows",
        "windows must be sorted by start year: 2012-2012 precedes 2010-2011"
      )
    )
  }

  test("analysis windows must not overlap") {
    invalidWith(
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2011,2011-2012"
    ).invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.graph.windows", "windows 2010-2011 and 2011-2012 overlap")
    )
    invalidWith(
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2011,2010-2011"
    ).invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.graph.windows", "windows 2010-2011 and 2010-2011 overlap")
    )
  }

  test("analysis windows must lie inside the DBLP subset years") {
    // allEnvVars sets the subset to 2010-2012.
    invalidWith(
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2009-2010,2011-2013"
    ).invalidSettings shouldBe Vector(
      InvalidSetting(
        "scholarchain.graph.windows",
        "window 2009-2010 lies outside the DBLP subset years 2010-2012"
      ),
      InvalidSetting(
        "scholarchain.graph.windows",
        "window 2011-2013 lies outside the DBLP subset years 2010-2012"
      )
    )
    // The approved windows end in 2025, so a subset ending in 2024 must reject them.
    val err =
      invalid(env("dev") ++ (allEnvVars -- devConfVariables) + ("DBLP_SUBSET_END_YEAR" -> "2024"))
    err.invalidSettings shouldBe Vector(
      InvalidSetting(
        "scholarchain.graph.windows",
        "window 2021-2025 lies outside the DBLP subset years 2000-2024"
      )
    )
  }

  test("analysis windows: gaps between windows are allowed") {
    loaded(
      env("empty-test") ++ allEnvVars + ("SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2010,2012-2012")
    ).graph.windows.size shouldBe 2
  }

  test("analysis windows: a trailing comma is a malformed window, not ignored") {
    invalidWith("SCHOLARCHAIN_GRAPH_WINDOWS" -> "2010-2011,").invalidSettings shouldBe Vector(
      InvalidSetting("scholarchain.graph.windows", "'' is not YYYY-YYYY")
    )
  }

  test("an invalid subset window is reported once, even though the graph section re-reads it") {
    val err = invalidWith("DBLP_SUBSET_START_YEAR" -> "2013")
    err.invalidSettings shouldBe Vector(
      InvalidSetting(
        "scholarchain.datasets.dblpSubset",
        "window start year 2013 exceeds end year 2012"
      )
    )
  }

  test("the graph section alone checks windows against the DBLP subset years") {
    val vars = Map(
      "DBLP_SUBSET_START_YEAR" -> "2000",
      "DBLP_SUBSET_END_YEAR" -> "2005",
      "SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2006",
      "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION" -> "p"
    )
    ConfigLoader.loadSection(GraphConfig, env("empty-test") ++ vars) shouldBe Left(
      ConfigError.Invalid(
        Vector.empty,
        Vector(
          InvalidSetting(
            "scholarchain.graph.windows",
            "window 2000-2006 lies outside the DBLP subset years 2000-2005"
          )
        )
      )
    )
    ConfigLoader
      .loadSection(
        GraphConfig,
        env("empty-test") ++ vars + ("SCHOLARCHAIN_GRAPH_WINDOWS" -> "2000-2005")
      )
      .map(_.windows.size) shouldBe Right(1)
  }

  test("YearWindow.seriesViolations reports every rule in order (pure function)") {
    def w(a: Int, b: Int) = YearWindow.of(a, b).toOption.get
    YearWindow.seriesViolations(Vector.empty, Some(w(2000, 2025))) shouldBe empty
    YearWindow.seriesViolations(
      Vector(w(2000, 2005), w(2006, 2010), w(2011, 2015), w(2016, 2020), w(2021, 2025)),
      Some(w(2000, 2025))
    ) shouldBe empty
    YearWindow.seriesViolations(Vector(w(2006, 2010), w(2000, 2005)), None) shouldBe Vector(
      "windows must be sorted by start year: 2006-2010 precedes 2000-2005"
    )
    YearWindow.seriesViolations(Vector(w(2000, 2026)), Some(w(2000, 2025))) shouldBe Vector(
      "window 2000-2026 lies outside the DBLP subset years 2000-2025"
    )
  }

  // --- environment filtering ----------------------------------------------------------------

  test("only variables bound in env-overrides.conf, plus SCHOLARCHAIN_ENV, are read") {
    ConfigLoader.boundVariables() shouldBe Right(
      requiredKeys.map(_._2).toSet ++ Set(
        "SCHOLARCHAIN_CONFIG_SCHEMA_VERSION",
        "SCHOLARCHAIN_APPLICATION_NAME",
        "SCHOLARCHAIN_ENV",
        "SCHOLARCHAIN_HADOOP_MAX_BLOCK_SIZE",
        "SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD",
        "SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE",
        "SCHOLARCHAIN_HADOOP_INTERMEDIATE_CODEC",
        "SCHOLARCHAIN_HADOOP_OUTPUT_CODEC",
        "SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES",
        "SCHOLARCHAIN_HADOOP_YEAR_FROM",
        "SCHOLARCHAIN_HADOOP_YEAR_TO"
      )
    )
  }

  private val fakeSecret = "FAKE-test-only-not-a-secret-7f3a"

  test("an unrelated variable such as AWS_SECRET_ACCESS_KEY never enters the raw Config") {
    val e = env("empty-test") ++ allEnvVars ++ Map(
      "AWS_SECRET_ACCESS_KEY" -> fakeSecret,
      "GITHUB_PERSONAL_ACCESS_TOKEN" -> fakeSecret
    )
    val (_, raw) = ConfigLoader.loadRaw(e).fold(err => fail(err.message), identity)
    raw.hasPath("AWS_SECRET_ACCESS_KEY") shouldBe false
    raw.hasPath("GITHUB_PERSONAL_ACCESS_TOKEN") shouldBe false
    raw.root().keySet().asScala.toSet shouldBe Set("scholarchain")
    raw.root().render() should not include fakeSecret
    raw.getString("scholarchain.storage.rawBucket") shouldBe "env-raw-bucket"
  }

  test("an environment file cannot pull in an unbound variable") {
    // leak-test.conf sets storage.rawBucket = ${?AWS_SECRET_ACCESS_KEY}.
    val e = env("leak-test") ++ (allEnvVars - "RAW_BUCKET") +
      ("AWS_SECRET_ACCESS_KEY" -> fakeSecret)
    val err = invalid(e)
    err.missingKeys shouldBe Vector(MissingKey("scholarchain.storage.rawBucket", "RAW_BUCKET"))
    err.message should not include fakeSecret
  }

  test("YearWindow rejects start year > end year and accepts start == end") {
    YearWindow.of(2006, 2005).isLeft shouldBe true
    YearWindow.of(2005, 2005).map(w => (w.startYear, w.endYear)) shouldBe Right((2005, 2005))
  }
