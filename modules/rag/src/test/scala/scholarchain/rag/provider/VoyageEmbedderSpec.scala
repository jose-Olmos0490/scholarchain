package scholarchain.rag.provider

import scholarchain.core.config.VoyageConfig
import scholarchain.rag.RagSuite
import scholarchain.rag.embed.*

/** The Voyage adapter against a loopback stub: no network, no sleeping. */
class VoyageEmbedderSpec extends RagSuite:
  private val Key = "pa-CANARY000000000000000000000000000000-test"
  private val delays = RetryDelays(100, 5000)

  private def config(baseUrl: String, batch: Int = 3, retries: Int = 2, timeoutMs: Int = 5000) =
    VoyageConfig("voyage-test-model", baseUrl, 4, batch, 2000, timeoutMs, retries)

  private def spec(normalize: Boolean = false) =
    EmbeddingModelSpec("voyage-test-model", "v-2026", 4, normalize)

  private def embedder(
      stub: StubHttp,
      purpose: VoyageInputType = VoyageInputType.Document,
      batch: Int = 3,
      retries: Int = 2,
      normalize: Boolean = false,
      key: () => String = () => Key,
      timeoutMs: Int = 5000,
      backoff: RecordingBackoff = new RecordingBackoff
  ) =
    new VoyageEmbedder(
      config(stub.baseUrl, batch, retries, timeoutMs),
      spec(normalize),
      purpose,
      key,
      delays,
      backoff
    )

  private def data(items: Seq[(Int, Seq[Double])], tokens: Int = 5): Answer =
    Answer(
      200,
      ujson
        .Obj(
          "object" -> "list",
          "data" -> ujson.Arr.from(
            items.map((i, v) =>
              ujson.Obj("index" -> i, "embedding" -> ujson.Arr.from(v.map(ujson.Num(_))))
            )
          ),
          "model" -> "voyage-test-model",
          "usage" -> ujson.Obj("total_tokens" -> tokens)
        )
        .render()
    )

  /** The vector of text "tN" starts with N; items come back in reverse order. */
  private def echo(seen: Seen): Answer =
    val inputs = seen.json("input").arr.map(_.str).toVector
    data(inputs.zipWithIndex.map((t, i) => i -> Seq(t.drop(1).toDouble, 0.0, 0.0, 1.0)).reverse)

  private val texts = (1 to 7).map(i => s"t$i")

  test("batches in order, places vectors by index and sends the configured request fields") {
    StubHttp.using((_, s) => echo(s)) { stub =>
      val e = embedder(stub)
      val out = e.embed(texts)
      out.map(_.head) shouldBe (1 to 7).map(_.toFloat)
      stub.requests.map(_.json("input").arr.size) shouldBe Vector(3, 3, 1)
      val r = stub.requests.head
      (r.method, r.path) shouldBe (("POST", "/v1/embeddings"))
      r.headers("authorization") shouldBe s"Bearer $Key"
      r.json.obj.keySet shouldBe
        Set("input", "model", "input_type", "output_dimension", "truncation")
      r.json("model").str shouldBe "voyage-test-model"
      r.json("input_type").str shouldBe "document"
      r.json("output_dimension").num shouldBe 4
      r.json("truncation").bool shouldBe false
      e.tokensUsed shouldBe 15L
    }
  }

  test("retrieval sends input_type=query") {
    StubHttp.using((_, s) => echo(s)) { stub =>
      embedder(stub, VoyageInputType.Query).embed(Seq("t1"))
      stub.requests.head.json("input_type").str shouldBe "query"
    }
  }

  test("an empty input makes no request") {
    StubHttp.using((_, s) => echo(s)) { stub =>
      embedder(stub).embed(Seq.empty) shouldBe empty
      stub.requests shouldBe empty
    }
  }

  test("vectors are L2-normalized exactly when the model settings say so") {
    StubHttp.script(data(Seq(0 -> Seq(3.0, 4.0, 0.0, 0.0)))) { stub =>
      embedder(stub, normalize = true).embed(Seq("a")).head.toVector shouldBe
        Vector(0.6f, 0.8f, 0f, 0f)
      embedder(stub, normalize = false).embed(Seq("a")).head.toVector shouldBe
        Vector(3f, 4f, 0f, 0f)
    }
  }

  test("a wrong dimension, count or index set fails the whole batch") {
    for answer <- Seq(
        data(Seq(0 -> Seq(1.0, 2.0))),
        data(Seq.empty),
        data(Seq(0 -> Seq(1.0, 2.0, 3.0, 4.0), 1 -> Seq(1.0, 2.0, 3.0, 4.0))),
        data(Seq(5 -> Seq(1.0, 2.0, 3.0, 4.0)))
      )
    do
      StubHttp.script(answer) { stub =>
        an[EmbeddingDimensionException] should be thrownBy embedder(stub).embed(Seq("a"))
      }
  }

  test("malformed and non-finite responses are refused without echoing the body") {
    for body <- Seq(
        s"not json $Key",
        "{}",
        """{"data":[{"index":0}]}""",
        """{"data":[{"index":0,"embedding":["x",1,2,3]}]}""",
        """{"data":[{"index":0,"embedding":[1e300,1,2,3]}]}"""
      )
    do
      StubHttp.script(Answer(200, body)) { stub =>
        val e = the[EmbeddingProviderException] thrownBy embedder(stub).embed(Seq("a"))
        e.getMessage should not include "CANARY"
        e.getCause shouldBe null
      }
  }

  test("429 then success is retried; a 529 that persists is Overloaded and bounded") {
    val backoff = new RecordingBackoff
    StubHttp.script(Answer(429, "{}"), data(Seq(0 -> Seq(1.0, 0.0, 0.0, 0.0)))) { stub =>
      embedder(stub, backoff = backoff).embed(Seq("a")).size shouldBe 1
      stub.requests.size shouldBe 2
      backoff.pauses.toVector shouldBe Vector(100L)
    }
    StubHttp.script(Answer(529, "{}")) { stub =>
      val e = the[EmbeddingProviderException] thrownBy embedder(stub).embed(Seq("a"))
      e.kind shouldBe ProviderFailureKind.Overloaded
      stub.requests.size shouldBe 3
    }
  }

  test("a deadline is TimedOut") {
    StubHttp.script(Answer(200, "{}", stallBeforeHeaders = true)) { stub =>
      val e = the[EmbeddingProviderException] thrownBy
        embedder(stub, retries = 0, timeoutMs = 300).embed(Seq("a"))
      e.kind shouldBe ProviderFailureKind.TimedOut
    }
  }

  test("an oversize batch is a clear permanent error: no retry, no body, no text, no key") {
    for status <- Seq(400, 413) do
      StubHttp.script(Answer(status, s"""{"detail":"too many tokens, key $Key"}""")) { stub =>
        val e = the[EmbeddingProviderException] thrownBy
          embedder(stub).embed(Seq("secret input text", "b"))
        e.kind shouldBe ProviderFailureKind.Permanent
        e.getMessage should (include(s"HTTP $status") and include("batch of 2 texts") and include(
          "SCHOLARCHAIN_VOYAGE_BATCH_SIZE"
        ))
        e.getMessage should not include "CANARY"
        e.getMessage should not include "secret input text"
        stub.requests.size shouldBe 1
      }
    StubHttp.script(Answer(401, "{}")) { stub =>
      val e = the[EmbeddingProviderException] thrownBy embedder(stub).embed(Seq("a"))
      e.getMessage should not include "BATCH_SIZE"
    }
  }

  test("HTTP 504 is transient and retried; HTTP 408 is permanent and not retried") {
    for (status, kind, requests) <- Seq(
        (504, ProviderFailureKind.Transient, 3),
        (408, ProviderFailureKind.Permanent, 1)
      )
    do
      StubHttp.script(Answer(status, "{}")) { stub =>
        val e = the[EmbeddingProviderException] thrownBy embedder(stub).embed(Seq("a"))
        e.kind shouldBe kind
        stub.requests.size shouldBe requests
      }
  }

  test("a failed later batch fails the whole call") {
    StubHttp.using((i, s) => if i == 0 then echo(s) else Answer(400, "{}")) { stub =>
      an[EmbeddingProviderException] should be thrownBy embedder(stub).embed(texts)
    }
  }

  test("a missing key is permanent and sends nothing") {
    StubHttp.using((_, s) => echo(s)) { stub =>
      val e = the[EmbeddingProviderException] thrownBy
        embedder(stub, key = () => "").embed(Seq("a"))
      e.kind shouldBe ProviderFailureKind.Permanent
      stub.requests shouldBe empty
    }
  }

  test("model settings that differ from the Voyage configuration are refused") {
    val cfg = config("http://127.0.0.1:1")
    for wrong <- Seq(spec().copy(dimension = 8), spec().copy(modelId = "another-model")) do
      an[IllegalArgumentException] should be thrownBy
        new VoyageEmbedder(cfg, wrong, VoyageInputType.Document, () => Key, delays)
  }

  test("through the index function, vectors have the index dimension") {
    StubHttp.using((_, s) => echo(s)) { stub =>
      val fn = good(Embedder.forIndex(embedder(stub), "researcher-profile-v1", 4))
      fn(texts).map(_.length).distinct shouldBe Seq(4)
    }
  }
