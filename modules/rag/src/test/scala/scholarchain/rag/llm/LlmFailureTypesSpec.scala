package scholarchain.rag.llm

import scholarchain.rag.RagSuite
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.agents.*
import scholarchain.rag.answer.WorkflowWorld
import scholarchain.rag.workflow.*
import ScriptedLlm.*

/** Typed Timeout and Overloaded failures: their shape, how a structured call produces them, and
  * that the workflow surfaces them instead of absorbing them as a gap.
  */
class LlmFailureTypesSpec extends RagSuite with WorkflowWorld:
  import World.*

  private val schema = JsonSchema
    .parse("t-v1", """{"type":"object","required":["a"],"properties":{"a":{"type":"string"}}}""")
    .fold(f => fail(f.message), identity)
  private val good = reply("""{"a":"x"}""")

  private def run(
      script: Seq[Either[LlmFailure, LlmResponse]],
      cfg: scholarchain.core.config.RagLlmConfig = llmCfg
  ) =
    val llm = new ScriptedLlm(script)
    (llm, new StructuredLlmCall(llm, cfg).run("p-v1", "system", "{}", schema))

  test("Timeout and Overloaded are typed, retryable and carry their own code") {
    val t = StageFailure.Timeout("llm", "slow")
    val o = StageFailure.Overloaded("llm", "busy")
    (t.code, t.retryable, t.message) shouldBe (("TIMEOUT", true, "TIMEOUT: slow"))
    (o.code, o.retryable, o.message) shouldBe (("OVERLOADED", true, "OVERLOADED: busy"))
    t should not be a[StageFailure.Retryable]
    o should not be a[StageFailure.Retryable]
  }

  test(
    "exhausted overload is Overloaded, exhausted time-out is Timeout, and usage counts attempts"
  ) {
    val (l1, r1) = run(Seq.fill(3)(Left(LlmFailure.Overloaded("429"))))
    r1.outcome.left.toOption.get shouldBe a[StageFailure.Overloaded]
    l1.requests.size shouldBe 3
    r1.usage.calls shouldBe 3
    val (_, r2) = run(Seq.fill(3)(Left(LlmFailure.TimedOut("deadline"))))
    r2.outcome.left.toOption.get shouldBe a[StageFailure.Timeout]
  }

  test("an overload that clears is retried to success") {
    val (l, r) = run(Seq(Left(LlmFailure.Overloaded("429")), Left(LlmFailure.TimedOut("t")), good))
    r.outcome.isRight shouldBe true
    l.requests.size shouldBe 3
  }

  test(
    "a generic transient failure keeps its old mapping; invalid output after overload is Rejected"
  ) {
    val (_, r1) = run(Seq.fill(3)(Left(LlmFailure.Transient("x"))))
    r1.outcome.left.toOption.get.code shouldBe "MODEL_OUTPUT_INVALID"
    val (_, r2) = run(Seq(Left(LlmFailure.Overloaded("429")), reply("not json"), reply("nope")))
    r2.outcome.left.toOption.get.code shouldBe "MODEL_OUTPUT_INVALID"
    val (_, r3) = run(Seq(reply("not json"), reply("nope"), Left(LlmFailure.TimedOut("t"))))
    r3.outcome.left.toOption.get shouldBe a[StageFailure.Timeout]
  }

  test("the retry bound still holds: maxRetries = 0 makes one attempt") {
    val (l, r) = run(Seq(Left(LlmFailure.Overloaded("429")), good), llmCfg.copy(maxRetries = 0))
    r.outcome.left.toOption.get shouldBe a[StageFailure.Overloaded]
    l.requests.size shouldBe 1
  }

  test("the workflow fails (retryable, not a gap) when synthesis is overloaded or times out") {
    val rr = request(adaFields, name = "overloaded")
    val f1 = new Rig(new ScriptedLlm(Seq.fill(3)(Left(LlmFailure.Overloaded("429"))))).workflow
      .answer(rr)
      .left
      .getOrElse(fail("expected a failure"))
    f1.failure shouldBe a[StageFailure.Overloaded]
    f1.retryable shouldBe true
    val f2 = new Rig(new ScriptedLlm(Seq.fill(3)(Left(LlmFailure.TimedOut("t"))))).workflow
      .answer(request(adaFields, name = "timedout"))
      .left
      .getOrElse(fail("expected a failure"))
    f2.failure shouldBe a[StageFailure.Timeout]
    f2.retryable shouldBe true
  }

  test("the composition entry wires every stage and answers with the model it was given") {
    val (c1, c2) = adaClaims(bundleOf(request(adaFields)))
    val llm = new ScriptedLlm(Seq(synthReply(Seq(c1, c2))))
    val wired = new Rig(llm)
    wired.agents.synthesis should not be null
    val res = wired.workflow.answer(request(adaFields)).fold(f => fail(f.message), identity)
    res.response.abstained shouldBe false
    llm.requests.size shouldBe 1
  }
