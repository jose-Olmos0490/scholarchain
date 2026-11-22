package scholarchain.rag

import java.nio.charset.StandardCharsets
import scholarchain.core.config.*
import scholarchain.rag.embed.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.*
import RagFixtures.*

class EmbeddingSpec extends RagSuite:
  private val env = Map(
    "EMBEDDING_MODEL_ID" -> "test-embedding-model",
    "EMBEDDING_MODEL_VERSION" -> "2026-01",
    "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "true"
  )

  test("the model spec comes from configuration; a missing value is named") {
    good(EmbeddingModelSpec.load(env, 256)) shouldBe modelSpec
    bad(EmbeddingModelSpec.load(Map.empty, 256)).detail should (include(
      "EMBEDDING_MODEL_ID"
    ) and include("EMBEDDING_MODEL_VERSION") and include("SCHOLARCHAIN_EMBEDDING_NORMALIZE"))
    bad(
      EmbeddingModelSpec.load(env.updated("SCHOLARCHAIN_EMBEDDING_NORMALIZE", "yes"), 256)
    ).code shouldBe "MISCONFIGURED"
  }

  test("each index gets the configured dimension from the vector index settings") {
    def s(d: Int) = VectorIndexSettings(d, DistanceMetric.Cosine, VectorIndexType.Classic)
    val settings = VectorIndexSettingsConfig(s(256), s(256), s(512), s(256), s(128))
    val specs = good(EmbeddingModelSpec.perIndex(indexNames, settings, env))
    specs.keySet shouldBe Set(
      indexNames.researchers,
      indexNames.communities,
      indexNames.abstracts,
      indexNames.paths,
      indexNames.topicInstitutions
    )
    specs(indexNames.abstracts).dimension shouldBe 512
    specs(indexNames.topicInstitutions).dimension shouldBe 128
  }

  test(
    "an index is bound to one model; a different model means a new index version, never an overwrite"
  ) {
    val b = new InMemoryIndexBindings
    good(IndexBinder.ensure(b, "researcher-profile-v1", modelSpec))
    good(IndexBinder.ensure(b, "researcher-profile-v1", modelSpec)) // same model: fine
    val p = bad(IndexBinder.ensure(b, "researcher-profile-v1", modelSpec.copy(version = "2026-06")))
    p.code shouldBe "INDEX_MODEL_MISMATCH"
    p.detail should (include("researcher-profile-v2") and include(
      "never overwrite researcher-profile-v1"
    ))
    bad(
      IndexBinder.ensure(b, "researcher-profile-v1", modelSpec.copy(dimension = 512))
    ).code shouldBe "INDEX_MODEL_MISMATCH"
    bad(
      IndexBinder.ensure(b, "researcher-profile-v1", modelSpec.copy(normalize = false))
    ).code shouldBe "INDEX_MODEL_MISMATCH"
    b.bound("researcher-profile-v1") shouldBe Some(modelSpec) // untouched
    good(
      IndexBinder.ensure(b, "researcher-profile-v2", modelSpec.copy(version = "2026-06"))
    ) // new index, new model
    good(IndexBinder.nextIndexName("paper-abstract-v9")) shouldBe "paper-abstract-v10"
    bad(IndexBinder.nextIndexName("paper-abstract")).code shouldBe "UNVERSIONED_INDEX"
  }

  test(
    "the fake embedder is deterministic, has the configured dimension and is unit length when normalized"
  ) {
    val e = new FakeEmbedder(modelSpec)
    val v = e.embed(Seq("graph analytics", "graph analytics", "")).map(_.toVector)
    v(0) shouldBe v(1)
    v.foreach(_.size shouldBe 256)
    math.sqrt(v(0).map(x => x.toDouble * x).sum) shouldBe 1.0 +- 1e-5
    v(2).count(_ != 0f) shouldBe 1 // empty text: a fixed non-zero vector
    val raw = new FakeEmbedder(modelSpec.copy(normalize = false)).embed(Seq("graph analytics")).head
    math.sqrt(raw.map(x => x.toDouble * x).sum) should be > 1.0
  }

  test("texts that share tokens are closer than unrelated texts") {
    val e = new FakeEmbedder(modelSpec)
    def cos(a: String, b: String) =
      val Seq(x, y) = e.embed(Seq(a, b)); x.zip(y).map((p, q) => p.toDouble * q).sum
    cos("researcher ada lovelace graphs", "ada lovelace researcher") should be > cos(
      "researcher ada lovelace graphs",
      "quantum chemistry cooking"
    )
  }

  test("fail fast when the embedding dimension does not match the index dimension") {
    val e = new FakeEmbedder(modelSpec)
    val p = bad(Embedder.forIndex(e, "researcher-profile-v1", 512))
    p.code shouldBe "DIMENSION_MISMATCH"
    p.detail should (include("256") and include("512"))
    val fn = good(Embedder.forIndex(e, "researcher-profile-v1", 256))
    fn(Seq("a", "b")).map(_.length) shouldBe Seq(256, 256)
    // a model that returns a wrong-sized vector fails the whole batch
    val liar = new Embedder:
      val spec = modelSpec
      def embed(texts: Seq[String]) = texts.map(_ => Array(1f, 2f))
    an[EmbeddingDimensionException] should be thrownBy good(Embedder.forIndex(liar, "i-v1", 256))(
      Seq("x")
    )
  }

  private class StubBedrock(respond: InvokeModelRequest => String) extends BedrockRuntimeClient:
    var requests = Vector.empty[InvokeModelRequest]
    def serviceName(): String = "bedrock-runtime"
    def close(): Unit = ()
    override def invokeModel(r: InvokeModelRequest): InvokeModelResponse =
      requests :+= r
      InvokeModelResponse
        .builder()
        .body(SdkBytes.fromString(respond(r), StandardCharsets.UTF_8))
        .build()

  test(
    "the Bedrock embedder sends the configured model, dimension and normalize flag, and parses the vector"
  ) {
    val spec = modelSpec.copy(dimension = 4)
    val stub = new StubBedrock(_ => """{"embedding":[0.5,0.25,0.0,1.0],"inputTextTokenCount":3}""")
    val out = new BedrockEmbedder(stub, spec).embed(Seq("one", "two"))
    out.map(_.toVector) shouldBe Seq(Vector(0.5f, 0.25f, 0f, 1f), Vector(0.5f, 0.25f, 0f, 1f))
    stub.requests.map(_.modelId).distinct shouldBe Vector("test-embedding-model")
    val body = ujson.read(stub.requests.head.body.asString(StandardCharsets.UTF_8))
    (body("inputText").str, body("dimensions").num, body("normalize").bool) shouldBe (
      "one",
      4.0,
      true
    )
  }

  test("a Bedrock answer of the wrong size is an error, not a silent mismatch") {
    val stub = new StubBedrock(_ => """{"embedding":[1.0,2.0]}""")
    an[EmbeddingDimensionException] should be thrownBy new BedrockEmbedder(stub, modelSpec).embed(
      Seq("x")
    )
  }
