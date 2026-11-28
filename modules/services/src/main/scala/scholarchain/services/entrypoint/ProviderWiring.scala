package scholarchain.services.entrypoint

import java.time.Duration
import scala.util.control.NonFatal
import scholarchain.core.config.{EmbeddingProviderConfig, LlmProviderConfig}
import scholarchain.rag.embed.Embedder
import scholarchain.rag.llm.LlmClient
import scholarchain.rag.provider.{BedrockLlmSettings, EmbeddingPurpose, ModelProviders, RetryDelays}
import scholarchain.services.config.ProviderKeySettings
import scholarchain.services.http.ServiceClock
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.security.{ProviderKey, SecretStringSource, SecretsManagerSecretStrings}
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.queue.ConsumerReport
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient

/** A configured model adapter and, for an external provider, the key it uses. Bedrock uses the
  * task role and has no key.
  */
final case class Wired[A](adapter: A, key: Option[ProviderKey]):
  def probes: Vector[ReadinessProbe] = key.toVector.map(ProviderWiring.probe)

/** Wires the configured model providers for the entrypoints. Keys come from Secrets Manager only,
  * by the secret name the deployment puts in the environment. The services never read
  * `ANTHROPIC_API_KEY` or `VOYAGE_API_KEY`: those serve the human-run live tests and tools of
  * other modules.
  *
  * A secret name that is not configured stops start-up. A secret that cannot be read does not:
  * the process runs, `/readyz` fails by check name, and the read is tried again.
  */
object ProviderWiring:
  val AnthropicSecretNameVariable = "SCHOLARCHAIN_SVC_ANTHROPIC_KEY_SECRET_NAME"
  val VoyageSecretNameVariable = "SCHOLARCHAIN_SVC_VOYAGE_KEY_SECRET_NAME"
  val AnthropicKeyCheck = "anthropicApiKey"
  val VoyageKeyCheck = "voyageApiKey"

  /** Secrets Manager with a bounded call time. Building the client contacts nothing. */
  def secretsManager(
      region: String,
      settings: ProviderKeySettings
  ): Either[String, SecretStringSource] =
    try
      val client = SecretsManagerClient
        .builder()
        .region(Region.of(region))
        .overrideConfiguration(
          ClientOverrideConfiguration
            .builder()
            .apiCallTimeout(Duration.ofMillis(settings.readTimeoutMillis))
            .build()
        )
        .build()
      Right(new SecretsManagerSecretStrings(client))
    catch case NonFatal(_) => Left("the Secrets Manager client could not be built")

  private def key(
      check: String,
      variable: String,
      configPath: String,
      secretName: Option[String],
      settings: ProviderKeySettings,
      secrets: () => Either[String, SecretStringSource],
      clock: ServiceClock
  ): Either[String, ProviderKey] =
    for
      name <- secretName.toRight(
        s"scholarchain.services.providerKeys.$configPath ($variable) is required " +
          "for the selected provider"
      )
      source <- secrets()
    yield
      val built = new ProviderKey(
        check,
        variable,
        () => source.read(name),
        settings.refreshSeconds * 1000L,
        settings.retrySeconds * 1000L,
        clock
      )
      built.current: Unit // read at start-up so a missing secret is logged at once
      built

  /** The LLM client of the selected provider. `delays` applies to Anthropic; `bedrock` to
    * Bedrock. `secrets` is not called when Bedrock is selected.
    */
  def llm(
      config: LlmProviderConfig,
      settings: ProviderKeySettings,
      secrets: () => Either[String, SecretStringSource],
      clock: ServiceClock,
      delays: => RetryDelays,
      bedrock: => BedrockLlmSettings
  ): Either[String, Wired[LlmClient]] =
    val needed = config match
      case _: LlmProviderConfig.Anthropic =>
        key(
          AnthropicKeyCheck,
          AnthropicSecretNameVariable,
          "anthropicSecretName",
          settings.anthropicSecretName,
          settings,
          secrets,
          clock
        ).map(Some(_))
      case _: LlmProviderConfig.Bedrock => Right(None)
    needed.map { k =>
      val supplier: () => String = () => k.fold("")(_.apiKey())
      Wired(ModelProviders.llmClient(config, delays, supplier, bedrock), k)
    }

  /** The embedder of the selected provider, for ingestion or retrieval. `env` gives the model
    * version and normalization; model ID and dimension come from `config`.
    */
  def embedder(
      config: EmbeddingProviderConfig,
      env: Map[String, String],
      purpose: EmbeddingPurpose,
      settings: ProviderKeySettings,
      secrets: () => Either[String, SecretStringSource],
      clock: ServiceClock,
      delays: => RetryDelays,
      region: => String
  ): Either[String, Wired[Embedder]] =
    val needed = config match
      case _: EmbeddingProviderConfig.Voyage =>
        key(
          VoyageKeyCheck,
          VoyageSecretNameVariable,
          "voyageSecretName",
          settings.voyageSecretName,
          settings,
          secrets,
          clock
        ).map(Some(_))
      case _: EmbeddingProviderConfig.Bedrock => Right(None)
    for
      k <- needed
      spec <- ModelProviders.embeddingSpec(config, env).left.map(_.message)
      supplier = () => k.fold("")(_.apiKey())
      built <- ModelProviders
        .embedder(config, spec, purpose, delays, supplier, region, None)
        .left
        .map(_.message)
    yield Wired(built, k)

  /** Ready only while the key is held. The check reports `ok` or `failing`, nothing else. */
  def probe(key: ProviderKey): ReadinessProbe = Probes.of(key.checkName)(key.available)

  /** A consumer step that takes no message while the key is missing: a received message could
    * only fail and move towards the dead-letter queue. The failed step makes the worker not ready.
    */
  def gated(
      poll: () => Result[ConsumerReport],
      key: Option[ProviderKey]
  ): () => Result[ConsumerReport] =
    () =>
      if key.forall(_.available) then poll()
      else Left(StorageError.Misconfigured("the embedding provider API key is not available"))
