package scholarchain.rag.llm

/** One model call. `system` is a versioned prompt from `prompts/`. `untrustedInput` is data that
  * the model must not obey (a user question, retrieved text); it is always passed as a JSON
  * document, never concatenated into `system`.
  *
  * @param outputSchema
  *   the JSON Schema text the answer must follow; an adapter whose API can constrain generation
  *   sends it. The caller validates the answer either way.
  */
final case class LlmRequest(
    promptVersion: String,
    system: String,
    untrustedInput: String,
    maxOutputTokens: Int,
    outputSchema: Option[String] = None
)

/** @param modelVersion
  *   the model ID and version that answered (recorded in provenance and the audit record)
  */
final case class LlmResponse(
    text: String,
    modelVersion: String,
    inputTokens: Long,
    outputTokens: Long
)

enum LlmFailure:
  /** Throttling, a time-out or another failure worth repeating. */
  case Transient(detail: String)

  /** The model service throttled or was saturated, also after the adapter's own retries. Retried
    * by [[StructuredLlmCall]]; when no attempt succeeds the stage fails as overloaded.
    */
  case Overloaded(detail: String)

  /** The model call hit its deadline, also after the adapter's own retries. Retried by
    * [[StructuredLlmCall]]; when no attempt succeeds the stage fails as a timeout.
    */
  case TimedOut(detail: String)

  /** A refusal or a configuration problem that repeating cannot fix. */
  case Permanent(detail: String)

  def detail: String

/** The only door to a model. The module has no cloud SDK type here: an adapter lives where the
  * runtime is wired. Hidden reasoning is never part of [[LlmResponse]].
  */
trait LlmClient:
  def complete(request: LlmRequest): Either[LlmFailure, LlmResponse]

/** Tokens and model versions consumed by the model calls of one stage (failed attempts count). */
final case class LlmUsage(
    calls: Int,
    inputTokens: Long,
    outputTokens: Long,
    modelVersions: Vector[String]
):
  def +(o: LlmUsage): LlmUsage =
    LlmUsage(
      calls + o.calls,
      inputTokens + o.inputTokens,
      outputTokens + o.outputTokens,
      (modelVersions ++ o.modelVersions).distinct
    )

object LlmUsage:
  val Zero: LlmUsage = LlmUsage(0, 0L, 0L, Vector.empty)
