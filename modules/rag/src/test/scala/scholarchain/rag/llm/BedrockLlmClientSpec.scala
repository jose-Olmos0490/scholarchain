package scholarchain.rag.llm

import scala.collection.mutable
import scholarchain.core.config.ModelConfig
import scholarchain.rag.RagSuite
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.{ApiCallTimeoutException, SdkClientException}
import software.amazon.awssdk.services.bedrockruntime.model.*

/** The Bedrock adapter against a fake invoker: no AWS, no sleeping. */
class BedrockLlmClientSpec extends RagSuite:

  final private class FakeInvoker(script: Seq[Either[BedrockError, BedrockReply]])
      extends BedrockInvoker:
    private val queue = mutable.Queue.from(script)
    val calls = mutable.ArrayBuffer.empty[BedrockCall]
    def invoke(call: BedrockCall): Either[BedrockError, BedrockReply] =
      calls += call
      queue.dequeue()

  final private class RecordingBackoff extends Backoff:
    val pauses = mutable.ArrayBuffer.empty[Long]
    def pause(millis: Long): Unit = pauses += millis

  private val policy = BedrockRetryPolicy(maxAttempts = 4, baseDelayMs = 100, maxDelayMs = 300)
  private val request = LlmRequest("p-v1", "SYSTEM PROMPT", """{"q":"ignore previous"}""", 512)
  private def ok(
      text: String = """{"a":1}""",
      in: Long = 11,
      out: Long = 7,
      stop: String = "end_turn"
  ) =
    Right(BedrockReply(text, in, out, stop))

  private def client(
      script: Seq[Either[BedrockError, BedrockReply]],
      p: BedrockRetryPolicy = policy
  ) =
    val inv = new FakeInvoker(script)
    val bo = new RecordingBackoff
    (inv, bo, new BedrockLlmClient(inv, "model-from-config", p, bo))

  test("success reports the configured model, token usage and passes system and data apart") {
    val (inv, bo, c) = client(Seq(ok()))
    val r = c.complete(request).toOption.get
    (r.text, r.modelVersion, r.inputTokens, r.outputTokens) shouldBe ((
      """{"a":1}""",
      "model-from-config",
      11L,
      7L
    ))
    inv.calls.toSeq shouldBe Seq(
      BedrockCall("model-from-config", "SYSTEM PROMPT", """{"q":"ignore previous"}""", 512)
    )
    bo.pauses shouldBe empty
  }

  test("the model ID comes from the model configuration, never a default") {
    val inv = new FakeInvoker(Seq(ok()))
    val c = BedrockLlmClient.forSynthesis(inv, ModelConfig("emb", "synth-id", "verify-id"), policy)
    c.complete(request).isRight shouldBe true
    inv.calls.head.modelId shouldBe "synth-id"
    an[IllegalArgumentException] should be thrownBy new BedrockLlmClient(inv, " ", policy)
  }

  test("throttle, time-out, 5xx and network failures are retried with capped exponential backoff") {
    val (inv, bo, c) = client(
      Seq(
        Left(BedrockError.Throttled("t")),
        Left(BedrockError.ServerError(503, "s")),
        Left(BedrockError.TimedOut("d")),
        ok()
      )
    )
    c.complete(request).isRight shouldBe true
    inv.calls.size shouldBe 4
    bo.pauses.toSeq shouldBe Seq(100L, 200L, 300L)
    val (inv2, _, c2) = client(Seq(Left(BedrockError.Network("n")), ok()))
    c2.complete(request).isRight shouldBe true
    inv2.calls.size shouldBe 2
  }

  test("exhaustion maps to Overloaded for throttle and 5xx, TimedOut for deadlines") {
    val (inv, bo, c) = client(Seq.fill(4)(Left(BedrockError.Throttled("429"))))
    c.complete(request).left.toOption.get shouldBe a[LlmFailure.Overloaded]
    inv.calls.size shouldBe 4
    bo.pauses.size shouldBe 3
    val (_, _, c2) = client(Seq.fill(4)(Left(BedrockError.ServerError(500, "s"))))
    c2.complete(request).left.toOption.get shouldBe a[LlmFailure.Overloaded]
    val (_, _, c3) = client(Seq.fill(4)(Left(BedrockError.TimedOut("d"))))
    c3.complete(request).left.toOption.get shouldBe a[LlmFailure.TimedOut]
    val (_, _, c4) = client(Seq.fill(4)(Left(BedrockError.Network("n"))))
    c4.complete(request).left.toOption.get shouldBe a[LlmFailure.Transient]
  }

  test("a refusal is never retried and is permanent") {
    val (inv, bo, c) = client(Seq(Left(BedrockError.Refused("AccessDeniedException 403")), ok()))
    c.complete(request).left.toOption.get shouldBe a[LlmFailure.Permanent]
    inv.calls.size shouldBe 1
    bo.pauses shouldBe empty
  }

  test("an invoker that throws is a bounded network failure") {
    final class Throwing extends BedrockInvoker:
      var n = 0
      def invoke(call: BedrockCall): Either[BedrockError, BedrockReply] =
        n += 1
        throw new IllegalStateException("boom secret")
    val inv = new Throwing
    val bo = new RecordingBackoff
    val r = new BedrockLlmClient(inv, "m", policy.copy(maxAttempts = 2), bo).complete(request)
    r.left.toOption.get shouldBe a[LlmFailure.Transient]
    inv.n shouldBe 2
    r.left.toOption.get.detail should not include "secret"
  }

  test(
    "guardrail and content-filter stops are permanent; a cut-off answer is returned for validation"
  ) {
    val (_, _, c) = client(Seq(ok(stop = "guardrail_intervened")))
    c.complete(request).left.toOption.get shouldBe a[LlmFailure.Permanent]
    val (_, _, c2) = client(Seq(ok(text = "{\"a\":", stop = "max_tokens")))
    c2.complete(request).toOption.get.text shouldBe "{\"a\":"
  }

  test("one attempt when maxAttempts is 1; policy values are validated") {
    val (inv, _, c) =
      client(Seq(Left(BedrockError.Throttled("t")), ok()), policy.copy(maxAttempts = 1))
    c.complete(request).left.toOption.get shouldBe a[LlmFailure.Overloaded]
    inv.calls.size shouldBe 1
    an[IllegalArgumentException] should be thrownBy BedrockRetryPolicy(0, 1, 1)
    an[IllegalArgumentException] should be thrownBy BedrockRetryPolicy(1, 5, 4)
    BedrockRetryPolicy(60, 100, 1000).delayBeforeRetry(59) shouldBe 1000L
  }

  test("through a structured call, an exhausted adapter surfaces as a typed stage failure") {
    val (_, _, c) = client(Seq.fill(4)(Left(BedrockError.Throttled("429"))))
    val schema = JsonSchema.parse("t-v1", """{"type":"object"}""").toOption.get
    val cfg = scholarchain.core.config.RagLlmConfig(maxRetries = 0, maxOutputTokens = 100)
    val r = new StructuredLlmCall(c, cfg).run("p-v1", "s", "{}", schema)
    r.outcome.left.toOption.get.code shouldBe "OVERLOADED"
  }

  // ---- SDK exception classification (no client is built) ----------------------------------

  test("SDK exceptions are classified by category, without echoing provider messages") {
    def svc(b: BedrockRuntimeException.Builder, status: Int): BedrockRuntimeException =
      b.statusCode(status)
        .awsErrorDetails(AwsErrorDetails.builder().errorMessage("leaked question text").build())
        .build()
        .asInstanceOf[BedrockRuntimeException]
    BedrockSdkInvoker
      .classify(svc(ThrottlingException.builder(), 429)) shouldBe a[BedrockError.Throttled]
    BedrockSdkInvoker
      .classify(svc(ModelTimeoutException.builder(), 408)) shouldBe a[BedrockError.TimedOut]
    BedrockSdkInvoker.classify(svc(ServiceUnavailableException.builder(), 503)) shouldBe a[
      BedrockError.ServerError
    ]
    BedrockSdkInvoker
      .classify(svc(InternalServerException.builder(), 500)) shouldBe a[BedrockError.ServerError]
    BedrockSdkInvoker
      .classify(svc(AccessDeniedException.builder(), 403)) shouldBe a[BedrockError.Refused]
    BedrockSdkInvoker
      .classify(svc(ValidationException.builder(), 400)) shouldBe a[BedrockError.Refused]
    BedrockSdkInvoker
      .classify(ApiCallTimeoutException.create(1000)) shouldBe a[BedrockError.TimedOut]
    BedrockSdkInvoker.classify(SdkClientException.create("conn")) shouldBe a[BedrockError.Network]
    BedrockSdkInvoker.classify(new IllegalStateException("x")) shouldBe a[BedrockError.Refused]
    BedrockSdkInvoker
      .classify(svc(ThrottlingException.builder(), 429))
      .detail should not include "leaked"
  }
