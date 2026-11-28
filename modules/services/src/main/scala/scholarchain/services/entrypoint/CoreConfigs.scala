package scholarchain.services.entrypoint

import scholarchain.core.config.*
import scholarchain.services.config.RuntimeLimits
import scholarchain.storage.StorageSettings

/** The parts of the shared core configuration each entrypoint reads. An entrypoint loads only
  * its own section, so the graph API never needs a model ID and the worker never needs a
  * retrieval bound. Every value comes from the environment bound by the core `env-overrides.conf`.
  */
final case class QueryCoreConfig(
    storage: StorageSettings,
    retrieval: RetrievalConfig,
    operations: OperationsConfig,
    rag: RagConfig,
    llm: LlmProviderConfig,
    embedding: EmbeddingProviderConfig
):
  /** The model IDs in effect for the selected providers. */
  def models: ModelConfig = ModelConfig.active(llm, embedding)
  def limits: RuntimeLimits = RuntimeLimits.from(operations, retrieval)

final case class ResourceCoreConfig(
    storage: StorageSettings,
    retrieval: RetrievalConfig,
    operations: OperationsConfig
):
  def limits: RuntimeLimits = RuntimeLimits.from(operations, retrieval)

object CoreConfigs:
  private def environment(env: Map[String, String]): String =
    env.get(ConfigLoader.EnvironmentVariable).map(_.trim).getOrElse("")

  def query(env: Map[String, String]): Either[ConfigError, QueryCoreConfig] =
    val name = environment(env)
    ConfigLoader.loadSection(
      new ConfigSection[QueryCoreConfig]:
        def read(c: ConfigCursor): QueryCoreConfig = QueryCoreConfig(
          StorageSettings.section(name).read(c),
          RetrievalConfig.read(c),
          OperationsConfig.read(c),
          RagConfig.read(c),
          LlmProviderConfig.read(c),
          EmbeddingProviderConfig.read(c)
        )
      ,
      env
    )

  /** The embedding worker's model section: it needs no LLM provider. */
  def embedding(env: Map[String, String]): Either[ConfigError, EmbeddingProviderConfig] =
    ConfigLoader.loadSection(EmbeddingProviderConfig, env)

  def resource(env: Map[String, String]): Either[ConfigError, ResourceCoreConfig] =
    val name = environment(env)
    ConfigLoader.loadSection(
      new ConfigSection[ResourceCoreConfig]:
        def read(c: ConfigCursor): ResourceCoreConfig = ResourceCoreConfig(
          StorageSettings.section(name).read(c),
          RetrievalConfig.read(c),
          OperationsConfig.read(c)
        )
      ,
      env
    )
