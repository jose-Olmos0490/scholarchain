package scholarchain.rag.answer

import scholarchain.core.model.{EvidenceBundle, EvidenceItem}
import scholarchain.rag.agents.{SResult, StageFailure}
import scholarchain.rag.common.Hashing
import scholarchain.rag.llm.*

/** The selected synthesis prompt and schema versions. They are version strings only; the text
  * lives under `prompts/`. (The shared configuration has no keys for them yet: they are supplied
  * where the runtime is wired.)
  */
final case class SynthesisConfig(promptVersion: String, schemaVersion: String)

final case class SynthesisResult(
    outcome: SResult[SynthesizedAnswer],
    usage: LlmUsage
)

/** Renders an evidence bundle as the untrusted input of a model call.
  *
  * Every evidence text (it comes from stored records and may hold instructions) is wrapped in a
  * delimiter pair that carries a per-bundle nonce, and any `<<<` or `>>>` inside the text is
  * broken up, so the text cannot close its own delimiter. No evidence text is placed anywhere
  * else in the input, and none in the system prompt.
  */
object SynthesisInput:
  def nonce(bundleId: String): String = Hashing.sha256Hex(bundleId).take(16)

  def delimit(text: String, nonce: String): String =
    val safe = text.replace("<<<", "< < <").replace(">>>", "> > >")
    s"<<<DATA $nonce>>>\n$safe\n<<<END $nonce>>>"

  private def item(i: EvidenceItem, nonce: String, semantic: Boolean): ujson.Obj =
    val o = ujson.Obj(
      "evidenceId" -> i.evidenceId,
      "evidenceType" -> i.evidenceType.toString,
      "referencedIds" -> ujson.Arr.from(i.referencedIds.map(ujson.Str(_))),
      "data" -> delimit(i.text.getOrElse(""), nonce)
    )
    i.graphPath.foreach(p => o("graphPath") = ujson.Arr.from(p.map(ujson.Str(_))))
    if semantic then
      o("label") = "SEMANTIC_CONTEXT"
      o("authoritative") = false
    o

  def render(b: EvidenceBundle): String =
    val n = nonce(b.bundleId)
    ujson
      .Obj(
        "bundleId" -> b.bundleId,
        "graphEvidence" -> ujson.Arr.from(b.graphEvidence.map(item(_, n, semantic = false))),
        "semanticEvidence" -> ujson.Arr.from(b.semanticEvidence.map(item(_, n, semantic = true))),
        "identityConfidence" -> ujson.Obj.from(
          b.identityConfidence.toVector.sortBy(_._1).map((k, v) => k -> ujson.Num(v))
        )
      )
      .render()

/** Writes an answer from an evidence bundle, and nothing else: the bundle is the only input.
  *
  * The model output must be one JSON object that validates against the versioned synthesis
  * schema; a malformed output is retried within `rag.llm.maxRetries` and then fails with a typed
  * `MODEL_OUTPUT_INVALID`. The result is not trusted: [[ClaimVerificationAgent]] checks it.
  */
final class AnswerSynthesisAgent(
    cfg: SynthesisConfig,
    llm: StructuredLlmCall,
    prompts: PromptRepository
):
  def synthesize(bundle: EvidenceBundle): SynthesisResult =
    val loaded = for
      prompt <- prompts.prompt(cfg.promptVersion)
      schema <- prompts.schema(cfg.schemaVersion)
    yield (prompt, schema)
    loaded match
      case Left(f) => SynthesisResult(Left(f), LlmUsage.Zero)
      case Right((prompt, schema)) =>
        val called = llm.run(prompt.version, prompt.text, SynthesisInput.render(bundle), schema)
        val outcome = called.outcome.flatMap { json =>
          SynthesizedAnswer
            .fromJson(json)
            .toRight(
              StageFailure.Rejected("MODEL_OUTPUT_INVALID", "the answer holds an unknown kind")
            )
        }
        SynthesisResult(outcome, called.usage)
