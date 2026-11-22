package scholarchain.storage

import scholarchain.core.config.*

/** Everything the storage module reads from the core configuration, in one value. No bucket,
  * table, index, endpoint, Region or batch size is defined anywhere else in this module.
  */
final case class StorageSettings(
    environment: String,
    awsRegion: String,
    buckets: StorageConfig,
    neptune: NeptuneConfig,
    vectorIndexNames: VectorIndexConfig,
    runtime: StorageRuntimeConfig,
    graphLimit: Int,
    maxPathDepth: Int,
    embeddingModelId: String
)

object StorageSettings:
  def from(c: ScholarChainConfig): StorageSettings = StorageSettings(
    environment = c.environment,
    awsRegion = c.application.awsRegion,
    buckets = c.storage,
    neptune = c.neptune,
    vectorIndexNames = c.vectorIndexes,
    runtime = c.storageRuntime,
    graphLimit = c.retrieval.graphLimit,
    maxPathDepth = c.retrieval.maxPathDepth,
    embeddingModelId = c.models.embeddingModelId
  )

  /** Section that reads only the keys the storage module uses (a storage process must not need,
    * say, the Hadoop or entity-resolution settings). It reuses the core sections for buckets,
    * Neptune, index names and storage runtime; the Region, the two retrieval bounds and the
    * embedding model ID are read by the same keys and environment variables as the core config.
    */
  def section(environment: String): ConfigSection[StorageSettings] =
    new ConfigSection[StorageSettings]:
      private val Region = "[a-z]{2}(-[a-z]+)+-[0-9]".r
      def read(c: ConfigCursor): StorageSettings =
        val region = c.string("application.awsRegion", "AWS_REGION")
        if region.nonEmpty && !Region.matches(region) then
          c.fail("application.awsRegion", s"'$region' is not an AWS Region code")
        StorageSettings(
          environment = environment,
          awsRegion = region,
          buckets = StorageConfig.read(c),
          neptune = NeptuneConfig.read(c),
          vectorIndexNames = VectorIndexConfig.read(c),
          runtime = StorageRuntimeConfig.section(environment).read(c),
          graphLimit = c.int("retrieval.graphLimit", "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT", min = 1),
          maxPathDepth =
            c.int("retrieval.maxPathDepth", "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH", min = 1),
          embeddingModelId = c.string("models.embeddingModelId", "EMBEDDING_MODEL_ID")
        )

  /** Loads the storage settings from an explicit environment map (never the process
    * environment, unless the caller passes `sys.env`).
    */
  def load(env: Map[String, String]): Either[ConfigError, StorageSettings] =
    val name = env.get(ConfigLoader.EnvironmentVariable).map(_.trim).getOrElse("")
    ConfigLoader.loadSection(section(name), env)
