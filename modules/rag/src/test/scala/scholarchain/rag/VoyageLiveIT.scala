package scholarchain.rag

import scholarchain.core.config.*
import scholarchain.rag.embed.*
import scholarchain.rag.provider.{EmbeddingPurpose, ModelProviders, RetryDelays}
import scholarchain.tags.AwsIntegration

/** Live check: ONE Voyage batch of 8 texts at dimension 1024. It costs money, so it runs only
  * behind the live profile: SCHOLARCHAIN_AWS_IT=true, SCHOLARCHAIN_EMBEDDING_PROVIDER=voyage with
  * the `embedding.voyage.*` and embedding model variables, and VOYAGE_API_KEY. Cancelled, never
  * passed, when any condition is missing. `sbt check` never runs it. The key is never printed.
  */
class VoyageLiveIT extends RagSuite:
  private def liveGate(): EmbeddingProviderConfig.Voyage =
    if !sys.env.get("SCHOLARCHAIN_AWS_IT").contains("true") then
      cancel("SCHOLARCHAIN_AWS_IT is not 'true'")
    if sys.env.get(ModelProviders.VoyageKeyVariable).forall(_.trim.isEmpty) then
      cancel(s"${ModelProviders.VoyageKeyVariable} is not set")
    ConfigLoader.loadSection(EmbeddingProviderConfig, sys.env) match
      case Right(v: EmbeddingProviderConfig.Voyage) => v
      case Right(_) => cancel("SCHOLARCHAIN_EMBEDDING_PROVIDER is not 'voyage'")
      case Left(e) => cancel(s"configuration: ${e.message}")

  test("one live Voyage batch: 8 texts, 1024 values each", AwsIntegration) {
    val config = liveGate()
    config.activeOutputDimension shouldBe 1024
    config.settings.batchSize should be >= 8
    val retry = ConfigLoader
      .loadSection(RetryPolicyConfig, sys.env)
      .fold(
        e => fail(e.message),
        identity
      )
    val model = good(ModelProviders.embeddingSpec(config, sys.env))
    val embedder = good(
      ModelProviders.embedder(
        config,
        model,
        EmbeddingPurpose.Ingest,
        RetryDelays(retry.baseDelayMs.toLong, retry.maxDelayMs.toLong),
        () => sys.env(ModelProviders.VoyageKeyVariable),
        fail("Voyage needs no AWS Region"),
        None
      )
    )
    val texts = (1 to 8).map(i => s"Research topic summary number $i: graph analytics")
    val vectors = embedder.embed(texts)
    vectors.size shouldBe 8
    vectors.map(_.length).distinct shouldBe Seq(1024)
    if model.normalize then
      vectors.foreach(v => math.sqrt(v.map(x => x.toDouble * x).sum) shouldBe 1.0 +- 1e-4)
    embedder match
      case v: VoyageEmbedder =>
        println(s"voyage live: texts=8 dimension=1024 tokens=${v.tokensUsed}")
      case other => fail(s"not a Voyage embedder: $other")
  }
