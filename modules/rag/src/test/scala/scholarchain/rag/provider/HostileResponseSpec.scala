package scholarchain.rag.provider

import scholarchain.core.config.{AnthropicConfig, RagLlmConfig, VoyageConfig}
import scholarchain.rag.RagSuite
import scholarchain.rag.common.BoundedJson
import scholarchain.rag.embed.*
import scholarchain.rag.llm.*

/** Hostile provider answers: deep nesting, echoed keys, sender-chosen names, odd indexes. */
class HostileResponseSpec extends RagSuite:
  private val Key = "sk-ant-api03-CANARY0000000000000000000000-test"
  private val delays = RetryDelays(0, 0)
  private val deep = "[" * 200000 + "]" * 200000
  private val request = LlmRequest("p-v1", "SYSTEM", "{}", 64)

  private def anthropic(stub: StubHttp) = new AnthropicLlmClient(
    AnthropicConfig("claude-test", stub.baseUrl, "2023-06-01", 2000, 5000, 0),
    () => Key,
    delays
  )

  private def voyage(stub: StubHttp) = new VoyageEmbedder(
    VoyageConfig("v", stub.baseUrl, 2, 4, 2000, 5000, 0),
    EmbeddingModelSpec("v", "1", 2, normalize = false),
    VoyageInputType.Document,
    () => Key,
    delays
  )

  private def answeredBy(model: String): Answer =
    Answer(
      200,
      ujson
        .Obj(
          "model" -> model,
          "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "{}")),
          "usage" -> ujson.Obj("input_tokens" -> 1, "output_tokens" -> 1)
        )
        .render()
    )

  test("the nesting bound counts structure, not brackets inside strings") {
    def nested(n: Int) = "[" * n + "]" * n
    noException should be thrownBy BoundedJson.read(nested(BoundedJson.MaxDepth))
    an[IllegalArgumentException] should be thrownBy BoundedJson.read(
      nested(BoundedJson.MaxDepth + 1)
    )
    val quoted = ujson.Obj("a" -> ("\\\"" + "[{" * 500)).render()
    noException should be thrownBy BoundedJson.read(quoted)
  }

  test("a deeply nested Anthropic answer or error body is a typed failure") {
    for body <- Seq(deep, s"""{"content":$deep,"usage":{"output_tokens":1}}""") do
      StubHttp.script(Answer(200, body)) { stub =>
        anthropic(stub).complete(request).left.toOption.get shouldBe a[LlmFailure.Transient]
      }
    StubHttp.script(Answer(400, deep)) { stub =>
      anthropic(stub).complete(request).left.toOption.get shouldBe a[LlmFailure.Permanent]
    }
  }

  test("a deeply nested Voyage answer is a typed failure") {
    for body <- Seq(deep, s"""{"data":$deep}""") do
      StubHttp.script(Answer(200, body)) { stub =>
        an[EmbeddingProviderException] should be thrownBy voyage(stub).embed(Seq("a"))
      }
  }

  test("only the configured model, or its dated snapshot, is recorded as the model version") {
    def recorded(model: String) =
      StubHttp.script(answeredBy(model)) { stub =>
        anthropic(stub).complete(request).toOption.get.modelVersion
      }
    recorded("claude-test") shouldBe "claude-test"
    recorded("claude-test-20261101") shouldBe "claude-test-20261101"
    for other <- Seq(
        Key,
        s"claude-test-$Key",
        "claude-test-2026",
        "claude-test-20261101x",
        "another-model"
      )
    do recorded(other) shouldBe "claude-test"
  }

  test("a property name chosen by the model is not repeated, also through a structured call") {
    val schema = JsonSchema
      .parse(
        "t-v1",
        """{"type":"object","additionalProperties":false,"properties":
          |{"a":{"type":"object","additionalProperties":false,"properties":{}}}}""".stripMargin
      )
      .toOption
      .get
    val answer = ujson.Obj(Key -> 1, "a" -> ujson.Obj(s"x-$Key" -> 2, s"y-$Key" -> 3))
    schema.validate(answer).sorted shouldBe
      Vector("$.a: a property is not allowed", "$: a property is not allowed")
    val client = new LlmClient:
      def complete(r: LlmRequest) = Right(LlmResponse(answer.render(), "m", 1, 1))
    val result = new StructuredLlmCall(client, RagLlmConfig(0, 64)).run("p", "s", "{}", schema)
    result.outcome.left.toOption.get.toString should not include "CANARY"
  }

  test("a deeply nested model output is an invalid output, not a stack overflow") {
    val schema = JsonSchema.parse("t-v1", """{"type":"object"}""").toOption.get
    val client = new LlmClient:
      def complete(r: LlmRequest) = Right(LlmResponse(s"""{"a":$deep}""", "m", 1, 1))
    val result = new StructuredLlmCall(client, RagLlmConfig(0, 64)).run("p", "s", "{}", schema)
    result.outcome.left.toOption.get.code shouldBe "MODEL_OUTPUT_INVALID"
  }

  test("a fractional or oversize Voyage index is refused, never truncated to a position") {
    for (first, second) <- Seq("0.7" -> "1.2", "-0.5" -> "1", "0" -> "1.999", "0" -> "4294967297")
    do
      val body = s"""{"data":[{"index":$first,"embedding":[1,0]},""" +
        s"""{"index":$second,"embedding":[0,1]}]}"""
      StubHttp.script(Answer(200, body)) { stub =>
        an[EmbeddingDimensionException] should be thrownBy voyage(stub).embed(Seq("a", "b"))
      }
    val whole = """{"data":[{"index":1.0,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}"""
    StubHttp.script(Answer(200, whole)) { stub =>
      voyage(stub).embed(Seq("a", "b")).map(_.toVector) shouldBe
        Seq(Vector(1f, 0f), Vector(0f, 1f))
    }
  }
