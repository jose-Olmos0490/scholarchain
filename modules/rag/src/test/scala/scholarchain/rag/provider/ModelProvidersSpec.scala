package scholarchain.rag.provider

import java.nio.file.Files
import scala.collection.mutable
import scholarchain.core.config.*
import scholarchain.rag.{RagSuite, SmokeEnv}
import scholarchain.rag.embed.*
import scholarchain.rag.ingest.{IngestMain, IngestionInputs}
import scholarchain.rag.llm.*

/** The provider factory and the start-up checks of the ingestion entrypoint. No AWS, no network:
  * every live-run case is refused before a client is built.
  */
class ModelProvidersSpec extends RagSuite:
  private val delays = RetryDelays(1, 2)

  private val anthropic = LlmProviderConfig.Anthropic(
    AnthropicConfig("a-model", "http://127.0.0.1:1", "2023-06-01", 100, 100, 0)
  )
  private val voyage = EmbeddingProviderConfig.Voyage(
    VoyageConfig("v-model", "http://127.0.0.1:1", 256, 8, 100, 100, 0)
  )
  private val bedrockEmbedding = EmbeddingProviderConfig.Bedrock("titan-model", 256)

  private def noKey(): String = fail("the key supplier must not be called")
  private def bedrockSettings =
    BedrockLlmSettings("eu-west-1", None, 1000, BedrockRetryPolicy(1, 0, 0))

  test("the LLM client follows the configured provider") {
    ModelProviders.llmClient(
      anthropic,
      delays,
      () => noKey(),
      fail("Bedrock settings must not be read")
    ) shouldBe an[AnthropicLlmClient]
    ModelProviders.llmClient(
      LlmProviderConfig.Bedrock("s-model", "v-model"),
      fail("provider delays must not be read"),
      () => noKey(),
      bedrockSettings
    ) shouldBe a[BedrockLlmClient]
  }

  test("model settings take the ID and dimension of the selected provider") {
    val env = Map(
      "EMBEDDING_MODEL_ID" -> "a-leftover-id",
      "EMBEDDING_MODEL_VERSION" -> "2026",
      "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "true"
    )
    good(ModelProviders.embeddingSpec(voyage, env)) shouldBe
      EmbeddingModelSpec("v-model", "2026", 256, normalize = true)
    good(ModelProviders.embeddingSpec(bedrockEmbedding, env)).modelId shouldBe "titan-model"
    bad(ModelProviders.embeddingSpec(voyage, Map.empty)).code shouldBe "MISCONFIGURED"
  }

  test("the embedder follows the provider and the purpose") {
    def build(
        config: EmbeddingProviderConfig,
        model: String,
        purpose: EmbeddingPurpose
    ) = ModelProviders.embedder(
      config,
      EmbeddingModelSpec(model, "2026", 256, normalize = true),
      purpose,
      delays,
      () => noKey(),
      "eu-west-1",
      None
    )
    def inputType(p: EmbeddingPurpose) =
      good(build(voyage, "v-model", p)) match
        case v: VoyageEmbedder => v.inputType
        case other => fail(s"not a Voyage embedder: $other")
    inputType(EmbeddingPurpose.Ingest) shouldBe VoyageInputType.Document
    inputType(EmbeddingPurpose.Retrieval) shouldBe VoyageInputType.Query
    good(build(bedrockEmbedding, "titan-model", EmbeddingPurpose.Ingest)) shouldBe
      a[BedrockEmbedder]
    bad(build(voyage, "titan-model", EmbeddingPurpose.Ingest)).code shouldBe "MISCONFIGURED"
  }

  // ---- ingestion entrypoint ------------------------------------------------------------------

  private val voyageEnv = SmokeEnv.vars ++ Map(
    "SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "voyage",
    "SCHOLARCHAIN_VOYAGE_MODEL_ID" -> "v-model",
    "SCHOLARCHAIN_VOYAGE_BASE_URL" -> "http://127.0.0.1:1",
    "SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "256",
    "SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS" -> "100",
    "SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS" -> "100",
    "SCHOLARCHAIN_VOYAGE_MAX_RETRIES" -> "0",
    "EMBEDDING_MODEL_ID" -> "v-model"
  )

  /** Runs IngestMain on one fact file; returns the exit code and the error text. */
  private def ingest(env: Map[String, String], live: Boolean): (Int, String) =
    val d = Files.createTempDirectory("rag-providers-run-")
    import upickle.default.*
    import scholarchain.rag.RagFixtures.*
    import scholarchain.core.model.SemanticSubjectType
    Files.writeString(
      d.resolve(IngestionInputs.FactsFile),
      write(fact(SemanticSubjectType.RESEARCHER, researcher(1), (2010, 2013))) + "\n"
    )
    val args =
      if live then Seq("--limit", "1", "--run-id", runId, "--snapshot", snapshot)
      else Seq("--dry-run")
    val err = mutable.ArrayBuffer.empty[String]
    val code =
      IngestMain.execute(Seq("--input-dir", d.toString) ++ args, env, _ => (), err += _)
    (code, err.mkString("\n"))

  test("the entrypoint rejects provider configuration at start-up, dry run included") {
    val cases = Seq(
      (SmokeEnv.vars - "SCHOLARCHAIN_LLM_PROVIDER") -> "SCHOLARCHAIN_LLM_PROVIDER",
      (SmokeEnv.vars - "SCHOLARCHAIN_EMBEDDING_PROVIDER") -> "SCHOLARCHAIN_EMBEDDING_PROVIDER",
      SmokeEnv.vars.updated("SCHOLARCHAIN_LLM_PROVIDER", "openai") -> "llm.provider",
      voyageEnv.updated("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION", "1024") -> "dimension",
      voyageEnv.updated("EMBEDDING_MODEL_ID", "titan") -> "embeddingModelId"
    )
    for (env, named) <- cases do
      val (code, err) = ingest(env, live = false)
      withClue(named) {
        code shouldBe 2
        err should (include("MISCONFIGURED") and include(named))
      }
    ingest(voyageEnv, live = false) shouldBe ((0, ""))
  }

  test("a live Voyage run without VOYAGE_API_KEY is refused before any client is built") {
    for env <- Seq(voyageEnv, voyageEnv.updated("VOYAGE_API_KEY", "  ")) do
      val (code, err) = ingest(env, live = true)
      code shouldBe 2
      err should include("VOYAGE_API_KEY is required")
  }
