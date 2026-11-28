package scholarchain.rag.agents

import scholarchain.core.config.VoyageConfig
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.RagSuite
import scholarchain.rag.embed.*
import scholarchain.rag.provider.{Answer, ProviderFailureKind, RetryDelays, StubHttp}

/** A failed query embedding keeps its type, so the caller learns whether a retry can help. */
class SemanticEmbeddingFailureSpec extends RagSuite with SemanticWorld:
  import World.retrieval

  private val Secret = "PROVIDER-TEXT-sk-ant-CANARY"

  private def failureWith(e: Embedder): StageFailure =
    new SemanticContextAgent(rig.index, rig.objects, rig.layout, e, allIndexes, retrieval, 200, 300)
      .retrieve(query("graph mining"))
      .left
      .toOption
      .get

  private def throwing(error: RuntimeException): Embedder = new Embedder:
    val spec = modelSpec
    def embed(texts: Seq[String]) = throw error

  private val expected = Seq(
    ProviderFailureKind.Overloaded -> (("OVERLOADED", true)),
    ProviderFailureKind.TimedOut -> (("TIMEOUT", true)),
    ProviderFailureKind.Transient -> (("RETRYABLE", true)),
    ProviderFailureKind.Permanent -> (("FAILED", false))
  )

  test("each embedding failure kind maps to its stage failure, without provider text") {
    for (kind, (code, retryable)) <- expected do
      val f = failureWith(throwing(new EmbeddingProviderException(kind, Secret)))
      withClue(s"$kind: ") {
        (f.code, f.retryable) shouldBe ((code, retryable))
        f.toString should not include Secret
        // markers the service reads as a stage deadline or a full stage pool
        f.detail should (not include "did not finish within" and not include "too many stages")
      }
  }

  test("any other embedder exception stays a failure that is not retryable") {
    val f = failureWith(throwing(new IllegalStateException(Secret)))
    (f.code, f.retryable) shouldBe (("FAILED", false))
    f.toString should not include Secret
  }

  test("Voyage 429, 504, 401 and a deadline keep their meaning through the semantic stage") {
    val cases = Seq(
      Answer(429, Secret) -> "OVERLOADED",
      Answer(504, Secret) -> "RETRYABLE",
      Answer(401, Secret) -> "FAILED",
      Answer(200, "{}", stallBeforeHeaders = true) -> "TIMEOUT"
    )
    for (answer, code) <- cases do
      StubHttp.script(answer) { stub =>
        val voyage = new VoyageEmbedder(
          VoyageConfig(modelSpec.modelId, stub.baseUrl, modelSpec.dimension, 8, 2000, 300, 0),
          modelSpec,
          VoyageInputType.Query,
          () => "test-key",
          RetryDelays(0, 0)
        )
        val f = failureWith(voyage)
        withClue(s"HTTP ${answer.status}: ") {
          f.code shouldBe code
          f.toString should not include Secret
        }
      }
  }
