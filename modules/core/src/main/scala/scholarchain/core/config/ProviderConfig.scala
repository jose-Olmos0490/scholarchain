package scholarchain.core.config

import java.net.URI
import scala.util.Try

/** Model-provider selection (Phase 16b). `llm.provider` and `embedding.provider` are required and
  * have no default in code or in any shipped file. Only the keys of the selected provider are read
  * and validated; keys of the other provider are ignored, so both can stay configured.
  *
  * No API key is part of this contract. Keys reach the adapters through an injected supplier
  * (Secrets Manager, or an environment variable for human-run live tests); the core loader neither
  * binds nor retains such variables.
  */
enum LlmProvider(val wire: String):
  case Bedrock extends LlmProvider("bedrock")
  case Anthropic extends LlmProvider("anthropic")

object LlmProvider:
  def parse(s: String): Option[LlmProvider] = values.find(_.wire == s)

enum EmbeddingProvider(val wire: String):
  case Bedrock extends EmbeddingProvider("bedrock")
  case Voyage extends EmbeddingProvider("voyage")

object EmbeddingProvider:
  def parse(s: String): Option[EmbeddingProvider] = values.find(_.wire == s)

/** Bounds that provider configuration is validated against; not tunables. */
object ProviderLimits:
  /** Project cap on the inputs of one Voyage `POST /v1/embeddings` request. It is deliberately
    * conservative and is not the API maximum, which this project has not verified. Raising it
    * needs the provider documentation as evidence.
    */
  val VoyageMaxBatch = 128

/** Anthropic Messages API settings (`llm.anthropic.*`). No value is shipped.
  *
  * @param modelId
  *   the model for every LLM call
  * @param baseUrl
  *   API root without a trailing `/` and without `/v1`; the adapter appends `/v1/messages`
  * @param apiVersion
  *   value of the `anthropic-version` request header (`YYYY-MM-DD`)
  * @param connectTimeoutMs
  *   TCP/TLS connect timeout (>= 1)
  * @param requestTimeoutMs
  *   deadline of one request attempt (>= 1)
  * @param maxRetries
  *   transport retries after the first attempt (>= 0), so at most `maxRetries + 1` attempts
  */
final case class AnthropicConfig(
    modelId: String,
    baseUrl: String,
    apiVersion: String,
    connectTimeoutMs: Int,
    requestTimeoutMs: Int,
    maxRetries: Int
)

object AnthropicConfig extends ConfigSection[AnthropicConfig]:
  private val ApiDate = "[0-9]{4}-[0-9]{2}-[0-9]{2}".r

  def read(c: ConfigCursor): AnthropicConfig =
    val modelId = c.string("llm.anthropic.modelId", "SCHOLARCHAIN_ANTHROPIC_MODEL_ID")
    val baseUrl =
      ProviderUrl.read(c, "llm.anthropic.baseUrl", "SCHOLARCHAIN_ANTHROPIC_BASE_URL")
    val version = c.string("llm.anthropic.apiVersion", "SCHOLARCHAIN_ANTHROPIC_VERSION")
    if version.nonEmpty && !ApiDate.matches(version) then
      c.fail("llm.anthropic.apiVersion", "must have the form YYYY-MM-DD")
    AnthropicConfig(
      modelId = modelId,
      baseUrl = baseUrl,
      apiVersion = version,
      connectTimeoutMs = c.int(
        "llm.anthropic.connectTimeoutMs",
        "SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS",
        min = 1
      ),
      requestTimeoutMs = c.int(
        "llm.anthropic.requestTimeoutMs",
        "SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS",
        min = 1
      ),
      maxRetries = c.int("llm.anthropic.maxRetries", "SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES", min = 0)
    )

/** Voyage embeddings API settings (`embedding.voyage.*`). Only `batchSize` has a shipped value
  * (`reference.conf`).
  *
  * @param modelId
  *   the embedding model
  * @param baseUrl
  *   API root without a trailing `/` and without `/v1`; the adapter appends `/v1/embeddings`
  * @param outputDimension
  *   requested `output_dimension`; must equal every `vectorIndexSettings.*.dimension`
  * @param batchSize
  *   inputs per request, 1 to [[ProviderLimits.VoyageMaxBatch]]; the last request of a run may
  *   carry fewer
  * @param connectTimeoutMs
  *   TCP/TLS connect timeout (>= 1)
  * @param requestTimeoutMs
  *   deadline of one request attempt (>= 1)
  * @param maxRetries
  *   transport retries after the first attempt (>= 0)
  */
final case class VoyageConfig(
    modelId: String,
    baseUrl: String,
    outputDimension: Int,
    batchSize: Int,
    connectTimeoutMs: Int,
    requestTimeoutMs: Int,
    maxRetries: Int
)

/** Reads the Voyage keys only. The cross-checks against the vector indexes belong to
  * [[EmbeddingProviderConfig]], which is the section consumers load.
  */
object VoyageConfig extends ConfigSection[VoyageConfig]:
  def read(c: ConfigCursor): VoyageConfig = VoyageConfig(
    modelId = c.string("embedding.voyage.modelId", "SCHOLARCHAIN_VOYAGE_MODEL_ID"),
    baseUrl = ProviderUrl.read(c, "embedding.voyage.baseUrl", "SCHOLARCHAIN_VOYAGE_BASE_URL"),
    outputDimension = c.int(
      "embedding.voyage.outputDimension",
      "SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION",
      min = 1,
      max = ServiceLimits.VectorMaxDimension
    ),
    batchSize = c.int(
      "embedding.voyage.batchSize",
      "SCHOLARCHAIN_VOYAGE_BATCH_SIZE",
      min = 1,
      max = ProviderLimits.VoyageMaxBatch
    ),
    connectTimeoutMs = c.int(
      "embedding.voyage.connectTimeoutMs",
      "SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS",
      min = 1
    ),
    requestTimeoutMs = c.int(
      "embedding.voyage.requestTimeoutMs",
      "SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS",
      min = 1
    ),
    maxRetries = c.int("embedding.voyage.maxRetries", "SCHOLARCHAIN_VOYAGE_MAX_RETRIES", min = 0)
  )

/** Provider API root URLs. An API key travels to this URL, so it must be `https`; plain `http` is
  * accepted only for a loopback host (local stub servers). User info, query and fragment are
  * rejected. The rejected value is never echoed, in case it carries a credential.
  */
private object ProviderUrl:
  private val Loopback = Set("localhost", "127.0.0.1", "[::1]")

  def read(c: ConfigCursor, key: String, envVar: String): String =
    val v = c.string(key, envVar)
    if v.isEmpty then v
    else
      var url = v
      while url.endsWith("/") do url = url.dropRight(1)
      val ok = Try(new URI(url)).toOption.exists { u =>
        val host = Option(u.getHost)
        val secure = u.getScheme == "https" ||
          (u.getScheme == "http" && host.exists(Loopback.contains))
        secure && host.isDefined && u.getRawUserInfo == null && u.getRawQuery == null &&
        u.getRawFragment == null
      }
      if !ok then
        c.fail(
          key,
          "must be an https URL (http only for a loopback host) without user info, query " +
            "or fragment"
        )
      else if url.endsWith("/v1") then
        c.fail(key, "must not include the /v1 path; the adapter appends it")
      url

/** The selected LLM provider with the settings it needs (`llm.provider`,
  * `SCHOLARCHAIN_LLM_PROVIDER`).
  *
  *   - `bedrock`: `models.synthesisModelId` and `models.verificationModelId`
  *   - `anthropic`: `llm.anthropic.*`; the one Anthropic model serves synthesis and verification,
  *     and the Bedrock model IDs are not read
  */
enum LlmProviderConfig:
  case Bedrock(synthesisModelId: String, verificationModelId: String)
  case Anthropic(settings: AnthropicConfig)

  def provider: LlmProvider = this match
    case _: Bedrock => LlmProvider.Bedrock
    case _: Anthropic => LlmProvider.Anthropic

  /** The synthesis model of the selected provider. */
  def activeSynthesisModelId: String = this match
    case Bedrock(synthesis, _) => synthesis
    case Anthropic(settings) => settings.modelId

  /** The verification model of the selected provider. */
  def activeVerificationModelId: String = this match
    case Bedrock(_, verification) => verification
    case Anthropic(settings) => settings.modelId

object LlmProviderConfig extends ConfigSection[LlmProviderConfig]:
  def read(c: ConfigCursor): LlmProviderConfig =
    val name = c.string("llm.provider", "SCHOLARCHAIN_LLM_PROVIDER")
    LlmProvider.parse(name) match
      case Some(LlmProvider.Bedrock) =>
        Bedrock(
          c.string("models.synthesisModelId", "SYNTHESIS_MODEL_ID"),
          c.string("models.verificationModelId", "VERIFICATION_MODEL_ID")
        )
      case Some(LlmProvider.Anthropic) => Anthropic(AnthropicConfig.read(c))
      case None =>
        if name.nonEmpty then
          c.fail(
            "llm.provider",
            s"unknown provider; allowed: ${LlmProvider.values.map(_.wire).mkString(", ")}"
          )
        // Placeholder: the section result is discarded because a problem is recorded.
        Bedrock("", "")

/** The selected embedding provider with the settings it needs (`embedding.provider`,
  * `SCHOLARCHAIN_EMBEDDING_PROVIDER`).
  *
  * Dimension rule, for both providers: the embedding output dimension equals every
  * `vectorIndexSettings.<index>.dimension`, otherwise loading fails.
  *
  *   - `voyage`: the output dimension is `embedding.voyage.outputDimension`; each index that
  *     differs from it is reported.
  *   - `bedrock`: there is no separate output-dimension key (the request asks the model for the
  *     index dimension), so the output dimension is the one value all five indexes share; indexes
  *     that disagree are rejected.
  *
  * `voyage` also requires `models.embeddingModelId` (`EMBEDDING_MODEL_ID`), when set, to equal
  * `embedding.voyage.modelId`: the vector write path records that key as provenance.
  */
enum EmbeddingProviderConfig:
  case Bedrock(modelId: String, outputDimension: Int)
  case Voyage(settings: VoyageConfig)

  def provider: EmbeddingProvider = this match
    case _: Bedrock => EmbeddingProvider.Bedrock
    case _: Voyage => EmbeddingProvider.Voyage

  /** The embedding model of the selected provider. */
  def activeModelId: String = this match
    case Bedrock(modelId, _) => modelId
    case Voyage(settings) => settings.modelId

  /** The embedding output dimension, equal to every vector index dimension. */
  def activeOutputDimension: Int = this match
    case Bedrock(_, dimension) => dimension
    case Voyage(settings) => settings.outputDimension

object EmbeddingProviderConfig extends ConfigSection[EmbeddingProviderConfig]:
  private val Indexes = Vector(
    "researchers" -> "RESEARCHERS",
    "communities" -> "COMMUNITIES",
    "abstracts" -> "ABSTRACTS",
    "paths" -> "PATHS",
    "topicInstitutions" -> "TOPIC_INSTITUTIONS"
  )

  /** The valid index dimensions by index name; a missing or invalid one is recorded. */
  private def indexDimensions(c: ConfigCursor): Vector[(String, Int)] =
    Indexes.flatMap { (name, envName) =>
      c.intOption(
        s"vectorIndexSettings.$name.dimension",
        s"SCHOLARCHAIN_VECTOR_INDEX_${envName}_DIMENSION",
        min = 1,
        max = ServiceLimits.VectorMaxDimension
      ).map(name -> _)
    }

  private def bedrock(c: ConfigCursor): EmbeddingProviderConfig =
    val modelId = c.string("models.embeddingModelId", "EMBEDDING_MODEL_ID")
    val dimensions = indexDimensions(c)
    if dimensions.map(_._2).distinct.size > 1 then
      c.fail(
        "vectorIndexSettings",
        "the embedding output dimension must equal every index dimension, but they differ: " +
          dimensions.map((name, d) => s"$name=$d").mkString(", ")
      )
    Bedrock(modelId, dimensions.headOption.map(_._2).getOrElse(0))

  private def voyage(c: ConfigCursor): EmbeddingProviderConfig =
    val settings = VoyageConfig.read(c)
    // 0 means the output dimension is missing or invalid, which is already recorded.
    val out = settings.outputDimension
    val dimensions = indexDimensions(c)
    if out > 0 then
      dimensions.filter(_._2 != out).foreach { (name, d) =>
        c.fail(
          "embedding.voyage.outputDimension",
          s"embedding output dimension $out does not equal vectorIndexSettings.$name.dimension $d"
        )
      }
    c.optionalString("models.embeddingModelId").foreach { recorded =>
      if settings.modelId.nonEmpty && recorded != settings.modelId then
        c.fail(
          "models.embeddingModelId",
          "must equal embedding.voyage.modelId (or be unset) when embedding.provider is voyage"
        )
    }
    Voyage(settings)

  def read(c: ConfigCursor): EmbeddingProviderConfig =
    val name = c.string("embedding.provider", "SCHOLARCHAIN_EMBEDDING_PROVIDER")
    EmbeddingProvider.parse(name) match
      case Some(EmbeddingProvider.Bedrock) => bedrock(c)
      case Some(EmbeddingProvider.Voyage) => voyage(c)
      case None =>
        if name.nonEmpty then
          c.fail(
            "embedding.provider",
            s"unknown provider; allowed: ${EmbeddingProvider.values.map(_.wire).mkString(", ")}"
          )
        // Placeholder: the section result is discarded because a problem is recorded.
        Bedrock("", 0)
