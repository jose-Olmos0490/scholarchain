package scholarchain.core.config

/** A typed configuration section. Modules load only the sections they need, so a Hadoop job is
  * not blocked by a missing model ID.
  */
trait ConfigSection[A]:
  def read(c: ConfigCursor): A

object ConfigSection:
  /** Reads the section; any missing key or invalid value yields [[ConfigError.Invalid]] listing
    * all of them.
    */
  def from[A](section: ConfigSection[A], cursor: ConfigCursor): Either[ConfigError, A] =
    val value = section.read(cursor)
    val (missing, invalid) = cursor.problems
    if missing.isEmpty && invalid.isEmpty then Right(value)
    else Left(ConfigError.Invalid(missing, invalid))

/** Configuration file format version (`scholarchain.config.schemaVersion`). */
object ConfigSchema:
  val Current = "config-v1"
  val Supported: Set[String] = Set(Current)

final case class ApplicationConfig(
    name: String,
    configSchemaVersion: String,
    awsRegion: String,
    awsAccountId: String
)

object ApplicationConfig extends ConfigSection[ApplicationConfig]:
  private val Region = "[a-z]{2}(-[a-z]+)+-[0-9]".r
  private val Account = "[0-9]{12}".r

  def read(c: ConfigCursor): ApplicationConfig =
    val schema = c.string("config.schemaVersion", "SCHOLARCHAIN_CONFIG_SCHEMA_VERSION")
    if schema.nonEmpty && !ConfigSchema.Supported.contains(schema) then
      c.fail(
        "config.schemaVersion",
        s"unsupported '$schema'; supported: ${ConfigSchema.Supported.mkString(", ")}"
      )
    val region = c.string("application.awsRegion", "AWS_REGION")
    if region.nonEmpty && !Region.matches(region) then
      c.fail("application.awsRegion", s"'$region' is not an AWS Region code")
    val account = c.string("application.awsAccountId", "SCHOLARCHAIN_AWS_ACCOUNT_ID")
    if account.nonEmpty && !Account.matches(account) then
      c.fail("application.awsAccountId", "must be 12 digits")
    ApplicationConfig(
      name = c.string("application.name", "SCHOLARCHAIN_APPLICATION_NAME"),
      configSchemaVersion = schema,
      awsRegion = region,
      awsAccountId = account
    )

final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    graphBucket: String,
    ragBucket: String,
    vectorBucket: String
)

object StorageConfig extends ConfigSection[StorageConfig]:
  def read(c: ConfigCursor): StorageConfig = StorageConfig(
    rawBucket = c.string("storage.rawBucket", "RAW_BUCKET"),
    curatedBucket = c.string("storage.curatedBucket", "CURATED_BUCKET"),
    graphBucket = c.string("storage.graphBucket", "GRAPH_BUCKET"),
    ragBucket = c.string("storage.ragBucket", "RAG_BUCKET"),
    vectorBucket = c.string("storage.vectorBucket", "VECTOR_BUCKET")
  )

final case class NeptuneConfig(endpoint: String, port: Int, queryTimeoutMs: Int)

object NeptuneConfig extends ConfigSection[NeptuneConfig]:
  def read(c: ConfigCursor): NeptuneConfig = NeptuneConfig(
    endpoint = c.string("neptune.endpoint", "NEPTUNE_ENDPOINT"),
    port = c.int("neptune.port", "NEPTUNE_PORT", min = 1, max = 65535),
    queryTimeoutMs = c.int("neptune.queryTimeoutMs", "NEPTUNE_QUERY_TIMEOUT_MS", min = 1)
  )

final case class VectorIndexConfig(
    researchers: String,
    communities: String,
    abstracts: String,
    paths: String,
    topicInstitutions: String
)

object VectorIndexConfig extends ConfigSection[VectorIndexConfig]:
  def read(c: ConfigCursor): VectorIndexConfig = VectorIndexConfig(
    researchers = c.string("vectorIndexes.researchers", "VECTOR_INDEX_RESEARCHERS"),
    communities = c.string("vectorIndexes.communities", "VECTOR_INDEX_COMMUNITIES"),
    abstracts = c.string("vectorIndexes.abstracts", "VECTOR_INDEX_ABSTRACTS"),
    paths = c.string("vectorIndexes.paths", "VECTOR_INDEX_PATHS"),
    topicInstitutions =
      c.string("vectorIndexes.topicInstitutions", "VECTOR_INDEX_TOPIC_INSTITUTIONS")
  )

/** The approved DBLP functional-run subset (manifest `datasets.dblp.subset_rule`): keep a record
  * iff its element name is in `recordTypes` and its integer `<year>` lies in `years` (inclusive).
  * At most `maxWorks` works enter the run.
  *
  * @param recordTypes
  *   DBLP record element names, each one declared by the DBLP DTD
  * @param years
  *   inclusive year window (`DBLP_SUBSET_START_YEAR`, `DBLP_SUBSET_END_YEAR`)
  * @param maxWorks
  *   cap on the number of works in the run, > 0 (`DBLP_SUBSET_MAX_WORKS`)
  */
final case class DblpSubsetConfig(recordTypes: Vector[String], years: YearWindow, maxWorks: Int)

/** Dataset run parameters. */
final case class DatasetConfig(dblpSubset: DblpSubsetConfig)

object DatasetConfig extends ConfigSection[DatasetConfig]:
  /** Record element names declared by the root element of the DBLP DTD
    * (`datasets/samples/dblp/dblp-2023-06-28.dtd`: `<!ELEMENT dblp (article|inproceedings|...)>`).
    * A source-format fact, not a tunable parameter: the subset may only name these.
    */
  val DblpRecordElements: Set[String] = Set(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "www",
    "person",
    "data"
  )

  /** Reads the DBLP subset year window. `None` when a bound is missing or invalid, or when
    * start > end; every such problem is recorded. A missing or invalid bound is reported only as
    * itself, never also as a window error against a placeholder.
    */
  def readSubsetYears(c: ConfigCursor): Option[YearWindow] =
    val start = c.intOption("datasets.dblpSubset.startYear", "DBLP_SUBSET_START_YEAR")
    val end = c.intOption("datasets.dblpSubset.endYear", "DBLP_SUBSET_END_YEAR")
    (start, end) match
      case (Some(s), Some(e)) =>
        YearWindow.of(s, e) match
          case Right(w) => Some(w)
          case Left(reason) =>
            c.fail("datasets.dblpSubset", reason)
            None
      case _ => None

  def read(c: ConfigCursor): DatasetConfig =
    val recordTypes =
      c.stringList("datasets.dblpSubset.recordTypes", "DBLP_SUBSET_RECORD_TYPES")
        .map { types =>
          val unknown = types.filterNot(DblpRecordElements.contains)
          if unknown.nonEmpty then
            c.fail(
              "datasets.dblpSubset.recordTypes",
              s"not DBLP record elements: ${unknown.mkString(", ")}; allowed: " +
                DblpRecordElements.toVector.sorted.mkString(", ")
            )
          types
        }
        .getOrElse(Vector.empty)
    val years = readSubsetYears(c).getOrElse(YearWindow.Placeholder)
    val maxWorks = c.int("datasets.dblpSubset.maxWorks", "DBLP_SUBSET_MAX_WORKS", min = 1)
    DatasetConfig(DblpSubsetConfig(recordTypes, years, maxWorks))

/** Coauthorship-graph parameters.
  *
  * `windows` are the analysis windows: each with start <= end, sorted by start year,
  * non-overlapping, and each inside the DBLP subset years. Because of the last rule this section
  * also reads `datasets.dblpSubset.startYear/endYear`; the containment check runs only when both
  * the windows and the subset years are valid, so one bad value is reported once.
  */
final case class GraphConfig(windows: Vector[YearWindow], coauthorPolicyVersion: String)

object GraphConfig extends ConfigSection[GraphConfig]:
  def read(c: ConfigCursor): GraphConfig =
    val subset = DatasetConfig.readSubsetYears(c)
    val windows = c.yearWindowsOption("graph.windows", "SCHOLARCHAIN_GRAPH_WINDOWS")
    windows.foreach(ws =>
      YearWindow.seriesViolations(ws, subset).foreach(c.fail("graph.windows", _))
    )
    GraphConfig(
      windows = windows.getOrElse(Vector.empty),
      coauthorPolicyVersion =
        c.string("graph.coauthorPolicyVersion", "SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION")
    )

/** Entity-resolution thresholds. Required ordering:
  * `rejectThreshold <= llmBandMin <= llmBandMax <= acceptThreshold`.
  */
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandMin: Double,
    llmBandMax: Double
)

object EntityResolutionConfig extends ConfigSection[EntityResolutionConfig]:
  def read(c: ConfigCursor): EntityResolutionConfig =
    val cfg = EntityResolutionConfig(
      policyVersion = c.string("entityResolution.policyVersion", "SCHOLARCHAIN_ER_POLICY_VERSION"),
      acceptThreshold =
        c.unitInterval("entityResolution.acceptThreshold", "SCHOLARCHAIN_ER_ACCEPT_THRESHOLD"),
      rejectThreshold =
        c.unitInterval("entityResolution.rejectThreshold", "SCHOLARCHAIN_ER_REJECT_THRESHOLD"),
      llmBandMin = c.unitInterval("entityResolution.llmBandMin", "SCHOLARCHAIN_ER_LLM_BAND_MIN"),
      llmBandMax = c.unitInterval("entityResolution.llmBandMax", "SCHOLARCHAIN_ER_LLM_BAND_MAX")
    )
    val (missing, invalid) = c.problems
    val touched =
      (missing.map(_.path) ++ invalid.map(_.path)).exists(_.contains(".entityResolution."))
    if !touched && !(cfg.rejectThreshold <= cfg.llmBandMin && cfg.llmBandMin <= cfg.llmBandMax &&
        cfg.llmBandMax <= cfg.acceptThreshold)
    then
      c.fail(
        "entityResolution",
        "thresholds must satisfy rejectThreshold <= llmBandMin <= llmBandMax <= acceptThreshold"
      )
    cfg

/** Bridge-measure parameters (algorithm name, source-sample size, random seed). */
final case class BridgeConfig(algorithm: String, sampleSize: Int, randomSeed: Long)

object BridgeConfig extends ConfigSection[BridgeConfig]:
  def read(c: ConfigCursor): BridgeConfig = BridgeConfig(
    algorithm = c.string("bridge.algorithm", "SCHOLARCHAIN_BRIDGE_ALGORITHM"),
    sampleSize = c.int("bridge.sampleSize", "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE", min = 1),
    randomSeed = c.long("bridge.randomSeed", "SCHOLARCHAIN_BRIDGE_RANDOM_SEED")
  )

final case class CommunityConfig(algorithm: String, maxIterations: Int)

object CommunityConfig extends ConfigSection[CommunityConfig]:
  def read(c: ConfigCursor): CommunityConfig = CommunityConfig(
    algorithm = c.string("community.algorithm", "SCHOLARCHAIN_COMMUNITY_ALGORITHM"),
    maxIterations =
      c.int("community.maxIterations", "SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS", min = 1)
  )

final case class RetrievalConfig(
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    contextTokenBudget: Int,
    fusionPolicyVersion: String
)

object RetrievalConfig extends ConfigSection[RetrievalConfig]:
  def read(c: ConfigCursor): RetrievalConfig = RetrievalConfig(
    graphLimit = c.int("retrieval.graphLimit", "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT", min = 1),
    vectorLimit = c.int("retrieval.vectorLimit", "SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT", min = 0),
    maxPathDepth =
      c.int("retrieval.maxPathDepth", "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH", min = 1),
    contextTokenBudget = c.int(
      "retrieval.contextTokenBudget",
      "SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET",
      min = 1
    ),
    fusionPolicyVersion =
      c.string("retrieval.fusionPolicyVersion", "SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION")
  )

final case class ModelConfig(
    embeddingModelId: String,
    synthesisModelId: String,
    verificationModelId: String
)

object ModelConfig extends ConfigSection[ModelConfig]:
  def read(c: ConfigCursor): ModelConfig = ModelConfig(
    embeddingModelId = c.string("models.embeddingModelId", "EMBEDDING_MODEL_ID"),
    synthesisModelId = c.string("models.synthesisModelId", "SYNTHESIS_MODEL_ID"),
    verificationModelId = c.string("models.verificationModelId", "VERIFICATION_MODEL_ID")
  )

final case class OperationsConfig(
    synchronousDeadlineMs: Int,
    maxWorkflowRetries: Int,
    maxConcurrentLlmCalls: Int
)

object OperationsConfig extends ConfigSection[OperationsConfig]:
  def read(c: ConfigCursor): OperationsConfig = OperationsConfig(
    synchronousDeadlineMs = c.int(
      "operations.synchronousDeadlineMs",
      "SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS",
      min = 1
    ),
    maxWorkflowRetries =
      c.int("operations.maxWorkflowRetries", "SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES", min = 0),
    maxConcurrentLlmCalls = c.int(
      "operations.maxConcurrentLlmCalls",
      "SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS",
      min = 1
    )
  )

/** The complete ScholarChain configuration. `environment` is the selected environment name. */
final case class ScholarChainConfig(
    environment: String,
    application: ApplicationConfig,
    storage: StorageConfig,
    neptune: NeptuneConfig,
    vectorIndexes: VectorIndexConfig,
    datasets: DatasetConfig,
    graph: GraphConfig,
    entityResolution: EntityResolutionConfig,
    bridge: BridgeConfig,
    community: CommunityConfig,
    retrieval: RetrievalConfig,
    models: ModelConfig,
    operations: OperationsConfig
)

object ScholarChainConfig:
  def section(environment: String): ConfigSection[ScholarChainConfig] =
    new ConfigSection[ScholarChainConfig]:
      def read(c: ConfigCursor): ScholarChainConfig = ScholarChainConfig(
        environment = environment,
        application = ApplicationConfig.read(c),
        storage = StorageConfig.read(c),
        neptune = NeptuneConfig.read(c),
        vectorIndexes = VectorIndexConfig.read(c),
        datasets = DatasetConfig.read(c),
        graph = GraphConfig.read(c),
        entityResolution = EntityResolutionConfig.read(c),
        bridge = BridgeConfig.read(c),
        community = CommunityConfig.read(c),
        retrieval = RetrievalConfig.read(c),
        models = ModelConfig.read(c),
        operations = OperationsConfig.read(c)
      )
