package scholarchain.core

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.*

/** Contract of the Phase 16b provider selection: `llm.provider`, `embedding.provider`, their
  * environment overrides and the embedding dimension rule. The process environment is never read.
  */
class ProviderConfigSpec extends AnyFunSuite with Matchers:
  import ProviderConfigSpec.*

  private def env(vars: Map[String, String]): Map[String, String] =
    Map(ConfigLoader.EnvironmentVariable -> "empty-test") ++ vars

  private def llm(vars: Map[String, String]): Either[ConfigError, LlmProviderConfig] =
    ConfigLoader.loadSection(LlmProviderConfig, env(vars))

  private def embedding(vars: Map[String, String]): Either[ConfigError, EmbeddingProviderConfig] =
    ConfigLoader.loadSection(EmbeddingProviderConfig, env(vars))

  private def invalid[A](r: Either[ConfigError, A]): ConfigError.Invalid = r match
    case Left(i: ConfigError.Invalid) => i
    case other => fail(s"expected ConfigError.Invalid, got $other")

  private def missingVariables[A](r: Either[ConfigError, A]): Vector[String] =
    invalid(r).missingKeys.map(_.environmentVariable)

  private def invalidPaths[A](r: Either[ConfigError, A]): Vector[String] =
    invalid(r).invalidSettings.map(_.path.stripPrefix("scholarchain."))

  private val indexNames =
    Vector("RESEARCHERS", "COMMUNITIES", "ABSTRACTS", "PATHS", "TOPIC_INSTITUTIONS")

  private def allDimensions(d: Int): Map[String, String] =
    indexNames.map(n => s"SCHOLARCHAIN_VECTOR_INDEX_${n}_DIMENSION" -> d.toString).toMap

  // --- provider keys ------------------------------------------------------------------------

  test("both provider keys are required: no default in any shipped layer") {
    invalid(llm(Map.empty)).missingKeys shouldBe Vector(
      MissingKey("scholarchain.llm.provider", "SCHOLARCHAIN_LLM_PROVIDER")
    )
    invalid(embedding(Map.empty)).missingKeys shouldBe Vector(
      MissingKey("scholarchain.embedding.provider", "SCHOLARCHAIN_EMBEDDING_PROVIDER")
    )
  }

  test("an unknown or differently-cased provider is rejected without echoing the value") {
    Seq("openai", "Bedrock", "voyage").foreach { name =>
      val err = invalid(llm(anthropic + ("SCHOLARCHAIN_LLM_PROVIDER" -> name)))
      err.missingKeys shouldBe empty
      err.invalidSettings shouldBe Vector(
        InvalidSetting(
          "scholarchain.llm.provider",
          "unknown provider; allowed: bedrock, anthropic"
        )
      )
    }
    Seq("openai", "Voyage", "anthropic").foreach { name =>
      invalid(
        embedding(voyage + ("SCHOLARCHAIN_EMBEDDING_PROVIDER" -> name))
      ).invalidSettings shouldBe Vector(
        InvalidSetting(
          "scholarchain.embedding.provider",
          "unknown provider; allowed: bedrock, voyage"
        )
      )
    }
  }

  // --- bedrock ------------------------------------------------------------------------------

  test("bedrock LLM: the existing model keys, and no Anthropic key, are required") {
    missingVariables(llm(Map("SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock"))) shouldBe
      Vector("SYNTHESIS_MODEL_ID", "VERIFICATION_MODEL_ID")
    val c = llm(bedrockLlm)
    c shouldBe Right(LlmProviderConfig.Bedrock("b-synthesis", "b-verification"))
    c.map(x => (x.provider, x.activeSynthesisModelId, x.activeVerificationModelId)) shouldBe
      Right((LlmProvider.Bedrock, "b-synthesis", "b-verification"))
  }

  test("bedrock embedding: the output dimension is the shared index dimension") {
    missingVariables(embedding(Map("SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "bedrock"))) shouldBe
      Vector("EMBEDDING_MODEL_ID")
    embedding(bedrockEmbedding) shouldBe
      Right(EmbeddingProviderConfig.Bedrock("b-embedding", 1024))
    embedding(bedrockEmbedding ++ allDimensions(512)).map(_.activeOutputDimension) shouldBe
      Right(512)
  }

  test("bedrock embedding: index dimensions that differ are rejected") {
    val err = invalid(
      embedding(bedrockEmbedding + ("SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "512"))
    )
    err.invalidSettings.map(_.path) shouldBe Vector("scholarchain.vectorIndexSettings")
    err.invalidSettings.head.reason should include("paths=512")
    err.invalidSettings.head.reason should include("researchers=1024")
  }

  test("bedrock: unused Anthropic and Voyage keys are ignored, even when invalid") {
    llm(bedrockLlm + ("SCHOLARCHAIN_ANTHROPIC_BASE_URL" -> "ftp://x")).isRight shouldBe true
    embedding(
      bedrockEmbedding + ("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "7")
    ).isRight shouldBe true
  }

  // --- anthropic ----------------------------------------------------------------------------

  test("anthropic: every setting comes from its environment variable") {
    val c = llm(anthropic)
    c shouldBe Right(
      LlmProviderConfig.Anthropic(
        AnthropicConfig(
          "test-anthropic-model",
          "https://anthropic.example.invalid",
          "2000-01-02",
          1500,
          25000,
          3
        )
      )
    )
    c.map(x => (x.provider, x.activeSynthesisModelId, x.activeVerificationModelId)) shouldBe
      Right((LlmProvider.Anthropic, "test-anthropic-model", "test-anthropic-model"))
  }

  test("anthropic: no value is shipped; every key is reported with its variable") {
    missingVariables(llm(Map("SCHOLARCHAIN_LLM_PROVIDER" -> "anthropic"))) shouldBe Vector(
      "SCHOLARCHAIN_ANTHROPIC_MODEL_ID",
      "SCHOLARCHAIN_ANTHROPIC_BASE_URL",
      "SCHOLARCHAIN_ANTHROPIC_VERSION",
      "SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS",
      "SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS",
      "SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES"
    )
  }

  test("anthropic: timeouts, retries and version are validated") {
    def rejected(variable: String, value: String, key: String) =
      withClue(s"$variable=$value: ")(
        invalidPaths(llm(anthropic + (variable -> value))) shouldBe Vector(s"llm.anthropic.$key")
      )
    rejected("SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS", "0", "connectTimeoutMs")
    rejected("SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS", "soon", "requestTimeoutMs")
    rejected("SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES", "-1", "maxRetries")
    rejected("SCHOLARCHAIN_ANTHROPIC_VERSION", "latest", "apiVersion")
    llm(anthropic + ("SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES" -> "0")).isRight shouldBe true
  }

  test("base URL: https, or http on loopback only; no credential, query, fragment or /v1") {
    def url(v: String) = llm(anthropic + ("SCHOLARCHAIN_ANTHROPIC_BASE_URL" -> v))
    def accepted(v: String): String = url(v) match
      case Right(LlmProviderConfig.Anthropic(a)) => a.baseUrl
      case other => fail(s"$v was not accepted: $other")
    accepted("https://api.example.invalid/") shouldBe "https://api.example.invalid"
    accepted("http://localhost:8089") shouldBe "http://localhost:8089"
    accepted("http://127.0.0.1:1") shouldBe "http://127.0.0.1:1"
    accepted("http://[::1]:1") shouldBe "http://[::1]:1"
    Seq(
      "http://api.example.invalid",
      "api.example.invalid",
      "ftp://api.example.invalid",
      "https://user:placeholder@api.example.invalid",
      "https://api.example.invalid?k=placeholder",
      "https://api.example.invalid#placeholder",
      "https://api.example.invalid/v1/",
      "https:// spaced"
    ).foreach { v =>
      withClue(s"$v: ") {
        val err = invalid(url(v))
        err.invalidSettings.map(_.path) shouldBe Vector("scholarchain.llm.anthropic.baseUrl")
        err.message should not include "placeholder"
      }
    }
    invalidPaths(
      embedding(voyage + ("SCHOLARCHAIN_VOYAGE_BASE_URL" -> "http://api.example.invalid"))
    ) shouldBe Vector("embedding.voyage.baseUrl")
  }

  // --- voyage and the dimension rule --------------------------------------------------------

  test("voyage: every setting comes from its environment variable") {
    val c = embedding(voyage)
    c shouldBe Right(
      EmbeddingProviderConfig.Voyage(
        VoyageConfig(
          "test-voyage-model",
          "https://voyage.example.invalid",
          1024,
          8,
          1500,
          20000,
          2
        )
      )
    )
    c.map(x => (x.provider, x.activeModelId, x.activeOutputDimension)) shouldBe
      Right((EmbeddingProvider.Voyage, "test-voyage-model", 1024))
  }

  test("voyage: every key without a default is reported with its variable") {
    missingVariables(embedding(Map("SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "voyage"))) shouldBe Vector(
      "SCHOLARCHAIN_VOYAGE_MODEL_ID",
      "SCHOLARCHAIN_VOYAGE_BASE_URL",
      "SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION",
      "SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS",
      "SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS",
      "SCHOLARCHAIN_VOYAGE_MAX_RETRIES"
    )
  }

  test("voyage: numeric bounds are enforced") {
    def rejected(variable: String, value: String, key: String) =
      withClue(s"$variable=$value: ")(
        invalidPaths(embedding(voyage + (variable -> value))) shouldBe
          Vector(s"embedding.voyage.$key")
      )
    rejected("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION", "0", "outputDimension")
    rejected("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION", "4097", "outputDimension")
    rejected("SCHOLARCHAIN_VOYAGE_BATCH_SIZE", "0", "batchSize")
    rejected("SCHOLARCHAIN_VOYAGE_BATCH_SIZE", "129", "batchSize")
    rejected("SCHOLARCHAIN_VOYAGE_BATCH_SIZE", "-1", "batchSize")
    rejected("SCHOLARCHAIN_VOYAGE_BATCH_SIZE", "12.5", "batchSize")
    rejected("SCHOLARCHAIN_VOYAGE_BATCH_SIZE", "many", "batchSize")
    rejected("SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS", "0", "connectTimeoutMs")
    rejected("SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS", "0", "requestTimeoutMs")
    rejected("SCHOLARCHAIN_VOYAGE_MAX_RETRIES", "-1", "maxRetries")
  }

  private def batchSize(vars: Map[String, String]): Int =
    ConfigLoader.loadSection(EmbeddingProviderConfig, vars) match
      case Right(EmbeddingProviderConfig.Voyage(v)) => v.batchSize
      case other => fail(s"expected a Voyage configuration, got $other")

  private val voyageWithoutBatchSize = voyage - "SCHOLARCHAIN_VOYAGE_BATCH_SIZE"

  test("voyage batch size: 128 from reference.conf when nothing overrides it") {
    batchSize(env(voyageWithoutBatchSize)) shouldBe 128
    ConfigFactory
      .parseResources(ConfigLoader.BaseResource)
      .getInt("scholarchain.embedding.voyage.batchSize") shouldBe 128
  }

  test("voyage batch size: an environment file overrides the default, a variable the file") {
    val file = Map(ConfigLoader.EnvironmentVariable -> "voyage-batch-test")
    batchSize(voyageWithoutBatchSize ++ file) shouldBe 32
    batchSize(voyage ++ file) shouldBe 8
    batchSize(env(voyage + ("SCHOLARCHAIN_VOYAGE_BATCH_SIZE" -> "1"))) shouldBe 1
    batchSize(env(voyage + ("SCHOLARCHAIN_VOYAGE_BATCH_SIZE" -> "128"))) shouldBe 128
  }

  test("dimension rule: any one index differing from the output dimension is rejected") {
    indexNames
      .zip(Vector("researchers", "communities", "abstracts", "paths", "topicInstitutions"))
      .foreach { (envName, name) =>
        val err = invalid(
          embedding(voyage + (s"SCHOLARCHAIN_VECTOR_INDEX_${envName}_DIMENSION" -> "512"))
        )
        err.invalidSettings shouldBe Vector(
          InvalidSetting(
            "scholarchain.embedding.voyage.outputDimension",
            s"embedding output dimension 1024 does not equal vectorIndexSettings.$name.dimension 512"
          )
        )
      }
  }

  test("dimension rule: an output dimension differing from all indexes reports each of them") {
    invalid(
      embedding(voyage + ("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "256"))
    ).invalidSettings.size shouldBe 5
    embedding(
      voyage ++ allDimensions(256) + ("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "256")
    ).map(_.activeOutputDimension) shouldBe Right(256)
  }

  test("dimension rule: an invalid index dimension is reported only as itself") {
    invalidPaths(
      embedding(voyage + ("SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "wide"))
    ) shouldBe Vector("vectorIndexSettings.paths.dimension")
  }

  test("voyage: a conflicting EMBEDDING_MODEL_ID is rejected; an equal one is accepted") {
    invalidPaths(embedding(voyage + ("EMBEDDING_MODEL_ID" -> "b-embedding"))) shouldBe
      Vector("models.embeddingModelId")
    embedding(voyage + ("EMBEDDING_MODEL_ID" -> "test-voyage-model")).isRight shouldBe true
  }

  // --- full configuration -------------------------------------------------------------------

  private def full(vars: Map[String, String]): Either[ConfigError, ScholarChainConfig] =
    ConfigLoader.load(Map(ConfigLoader.EnvironmentVariable -> "contract-test") ++ vars)

  test("full load: the file selects bedrock and keeps the existing model IDs") {
    val c = full(Map.empty).fold(e => fail(e.message), identity)
    c.llm.provider shouldBe LlmProvider.Bedrock
    c.embedding.provider shouldBe EmbeddingProvider.Bedrock
    c.models shouldBe ModelConfig(
      "file-embedding-model",
      "file-synthesis-model",
      "file-verification-model"
    )
  }

  test("full load: environment variables override the file's providers; models follow") {
    // contract-test.conf sets models.embeddingModelId, so EMBEDDING_MODEL_ID must agree.
    val c = full(anthropic ++ voyage + ("EMBEDDING_MODEL_ID" -> "test-voyage-model"))
      .fold(e => fail(e.message), identity)
    c.llm.provider shouldBe LlmProvider.Anthropic
    c.embedding.provider shouldBe EmbeddingProvider.Voyage
    c.models shouldBe ModelConfig(
      "test-voyage-model",
      "test-anthropic-model",
      "test-anthropic-model"
    )
  }

  test("full load: startup is rejected for a dimension mismatch") {
    invalidPaths(
      full(
        voyage + ("EMBEDDING_MODEL_ID" -> "test-voyage-model") +
          ("SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "512")
      )
    ).distinct shouldBe Vector("embedding.voyage.outputDimension")
  }

  // --- API keys are not configuration -------------------------------------------------------

  test("API key variables are neither bound nor retained by the loader") {
    val bound = ConfigLoader.boundVariables().fold(e => fail(e.message), identity)
    bound should not contain "ANTHROPIC_API_KEY"
    bound should not contain "VOYAGE_API_KEY"
    val canary = "FAKE-canary-not-a-key-91c2"
    val (_, raw) = ConfigLoader
      .loadRaw(
        env(anthropic ++ voyage ++ Map("ANTHROPIC_API_KEY" -> canary, "VOYAGE_API_KEY" -> canary))
      )
      .fold(e => fail(e.message), identity)
    raw.root().render() should not include canary
  }

object ProviderConfigSpec:
  val bedrockLlm: Map[String, String] = Map(
    "SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock",
    "SYNTHESIS_MODEL_ID" -> "b-synthesis",
    "VERIFICATION_MODEL_ID" -> "b-verification"
  )

  val bedrockEmbedding: Map[String, String] = Map(
    "SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "bedrock",
    "EMBEDDING_MODEL_ID" -> "b-embedding"
  )

  /** Fictitious values. */
  val anthropic: Map[String, String] = Map(
    "SCHOLARCHAIN_LLM_PROVIDER" -> "anthropic",
    "SCHOLARCHAIN_ANTHROPIC_MODEL_ID" -> "test-anthropic-model",
    "SCHOLARCHAIN_ANTHROPIC_BASE_URL" -> "https://anthropic.example.invalid",
    "SCHOLARCHAIN_ANTHROPIC_VERSION" -> "2000-01-02",
    "SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS" -> "1500",
    "SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS" -> "25000",
    "SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES" -> "3"
  )

  val voyage: Map[String, String] = Map(
    "SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "voyage",
    "SCHOLARCHAIN_VOYAGE_MODEL_ID" -> "test-voyage-model",
    "SCHOLARCHAIN_VOYAGE_BASE_URL" -> "https://voyage.example.invalid",
    "SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> "1024",
    "SCHOLARCHAIN_VOYAGE_BATCH_SIZE" -> "8",
    "SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS" -> "1500",
    "SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS" -> "20000",
    "SCHOLARCHAIN_VOYAGE_MAX_RETRIES" -> "2"
  )

  /** Every variable bound for provider selection and provider settings. */
  val providerVariables: Set[String] = anthropic.keySet ++ voyage.keySet
