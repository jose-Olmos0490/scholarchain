package scholarchain.rag.provider

import scholarchain.core.config.*
import scholarchain.rag.common.*
import scholarchain.rag.embed.*
import scholarchain.rag.llm.*

/** The two provider choices of one process. */
final case class ProviderSelection(llm: LlmProviderConfig, embedding: EmbeddingProviderConfig)

/** Whether vectors are stored (ingestion) or used to search (retrieval). */
enum EmbeddingPurpose:
  case Ingest, Retrieval

/** What the Bedrock LLM adapter needs; read only when Bedrock is selected. */
final case class BedrockLlmSettings(
    region: String,
    profile: Option[String],
    attemptTimeoutMillis: Long,
    retry: BedrockRetryPolicy
)

/** Builds the configured model adapters. Nothing is defaulted.
  *
  * API keys are passed as suppliers and are asked only by the adapter of an external provider:
  * with Bedrock selected the supplier is never called.
  */
object ModelProviders:
  /** Environment variables that hold the keys in human-run live tests and local runs. */
  val AnthropicKeyVariable = "ANTHROPIC_API_KEY"
  val VoyageKeyVariable = "VOYAGE_API_KEY"

  /** Reads both provider sections and reports every problem of both at once. */
  val section: ConfigSection[ProviderSelection] = new ConfigSection[ProviderSelection]:
    def read(c: ConfigCursor): ProviderSelection =
      ProviderSelection(LlmProviderConfig.read(c), EmbeddingProviderConfig.read(c))

  def load(env: Map[String, String]): Either[ConfigError, ProviderSelection] =
    ConfigLoader.loadSection(section, env)

  /** The LLM client of the selected provider. `delays` applies to Anthropic; Bedrock keeps its
    * own policy from `bedrock`.
    */
  def llmClient(
      config: LlmProviderConfig,
      delays: => RetryDelays,
      apiKey: () => String,
      bedrock: => BedrockLlmSettings
  ): LlmClient =
    config match
      case LlmProviderConfig.Anthropic(settings) =>
        new AnthropicLlmClient(settings, apiKey, delays)
      case LlmProviderConfig.Bedrock(synthesisModelId, _) =>
        val b = bedrock
        new BedrockLlmClient(
          BedrockSdkInvoker.live(b.region, b.profile, b.attemptTimeoutMillis),
          synthesisModelId,
          b.retry
        )

  /** The embedding model settings of the selected provider. The model ID and dimension come from
    * the provider configuration; `EMBEDDING_MODEL_VERSION` and
    * `SCHOLARCHAIN_EMBEDDING_NORMALIZE` come from the environment.
    */
  def embeddingSpec(
      config: EmbeddingProviderConfig,
      env: Map[String, String]
  ): RResult[EmbeddingModelSpec] =
    EmbeddingModelSpec.load(
      env.updated(EmbeddingModelSpec.ModelIdVar, config.activeModelId),
      config.activeOutputDimension
    )

  /** The embedder of the selected provider. `delays` and `apiKey` apply to Voyage; `region` and
    * `profile` to Bedrock, which embeds the same way for both purposes. A model spec that does
    * not match the configuration is refused.
    */
  def embedder(
      config: EmbeddingProviderConfig,
      spec: EmbeddingModelSpec,
      purpose: EmbeddingPurpose,
      delays: => RetryDelays,
      apiKey: () => String,
      region: => String,
      profile: Option[String]
  ): RResult[Embedder] =
    if spec.modelId != config.activeModelId || spec.dimension != config.activeOutputDimension then
      Left(
        RagProblem(
          "MISCONFIGURED",
          "the embedding model settings do not match the configured embedding provider"
        )
      )
    else
      config match
        case EmbeddingProviderConfig.Voyage(settings) =>
          val inputType = purpose match
            case EmbeddingPurpose.Ingest => VoyageInputType.Document
            case EmbeddingPurpose.Retrieval => VoyageInputType.Query
          Right(new VoyageEmbedder(settings, spec, inputType, apiKey, delays))
        case _: EmbeddingProviderConfig.Bedrock =>
          Right(BedrockEmbedder.live(region, profile, spec))
