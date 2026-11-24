package scholarchain.rag.llm

import scholarchain.core.config.ModelConfig

/** What one Bedrock call needs. `userText` is the untrusted JSON document and is always sent as
  * the user turn, never as part of `system`.
  */
final case class BedrockCall(modelId: String, system: String, userText: String, maxTokens: Int)

/** The answer of one Bedrock call: only the visible answer text. Reasoning content that a model
  * may return is dropped by the binding and never reaches this type.
  */
final case class BedrockReply(
    text: String,
    inputTokens: Long,
    outputTokens: Long,
    stopReason: String
)

/** Why one Bedrock call failed. */
enum BedrockError:
  /** Throttling (HTTP 429). Retried. */
  case Throttled(detail: String)

  /** A call or connection deadline. Retried. */
  case TimedOut(detail: String)

  /** An HTTP 5xx answer. Retried. */
  case ServerError(status: Int, detail: String)

  /** A connection-level failure without a deadline. Retried. */
  case Network(detail: String)

  /** Any other refusal (validation, access denied, unknown model, guardrail). Never retried. */
  case Refused(detail: String)

  def detail: String

  def retryable: Boolean = this match
    case Refused(_) => false
    case _ => true

/** The small port over the Bedrock runtime. The SDK binding is [[BedrockSdkInvoker]]; tests use a
  * fake. An implementation does not retry: [[BedrockLlmClient]] owns the retry bound.
  */
trait BedrockInvoker:
  def invoke(call: BedrockCall): Either[BedrockError, BedrockReply]

/** Bounded retry with exponential backoff: the delay before retry `n` (1-based) is
  * `min(maxDelayMs, baseDelayMs * 2^(n-1))`. No defaults: every value comes from configuration.
  */
final case class BedrockRetryPolicy(maxAttempts: Int, baseDelayMs: Long, maxDelayMs: Long):
  require(maxAttempts >= 1, "maxAttempts must be at least 1")
  require(baseDelayMs >= 0, "baseDelayMs must not be negative")
  require(maxDelayMs >= baseDelayMs, "maxDelayMs must be at least baseDelayMs")

  def delayBeforeRetry(retry: Int): Long =
    val shift = math.min(math.max(retry - 1, 0), 30)
    math.min(maxDelayMs, baseDelayMs * (1L << shift))

/** Where the adapter waits between attempts; injectable so tests never sleep. */
trait Backoff:
  def pause(millis: Long): Unit

object Backoff:
  object Real extends Backoff:
    def pause(millis: Long): Unit = if millis > 0 then Thread.sleep(millis)

/** The live [[LlmClient]]: one Bedrock model, bounded retries, token usage reported.
  *
  * The model ID is injected (from `models.synthesisModelId`), never defaulted. Only throttling,
  * deadlines, connection failures and HTTP 5xx are retried, at most `retry.maxAttempts` times in
  * all; a refusal stops at once. When attempts run out the failure is typed: throttling and 5xx
  * become [[LlmFailure.Overloaded]], deadlines [[LlmFailure.TimedOut]], other connection
  * failures [[LlmFailure.Transient]]. A response that was cut off (`max_tokens`) is returned as it
  * is, so the structured call rejects it as invalid; a guardrail or content filter stop is a
  * permanent refusal. Raw provider messages never reach the failure detail beyond a category.
  */
final class BedrockLlmClient(
    invoker: BedrockInvoker,
    modelId: String,
    retry: BedrockRetryPolicy,
    backoff: Backoff = Backoff.Real
) extends LlmClient:
  require(modelId != null && modelId.trim.nonEmpty, "the model ID must come from configuration")

  def complete(request: LlmRequest): Either[LlmFailure, LlmResponse] =
    val call = BedrockCall(modelId, request.system, request.untrustedInput, request.maxOutputTokens)
    var attempt = 0
    var outcome: Option[Either[LlmFailure, LlmResponse]] = None
    while outcome.isEmpty do
      attempt += 1
      val result =
        try invoker.invoke(call)
        catch case scala.util.control.NonFatal(_) => Left(BedrockError.Network("the invoker threw"))
      result match
        case Right(r) => outcome = Some(reply(r))
        case Left(e) if !e.retryable =>
          outcome = Some(Left(LlmFailure.Permanent(s"the model call was refused: ${e.detail}")))
        case Left(e) if attempt >= retry.maxAttempts =>
          outcome = Some(Left(exhausted(e, attempt)))
        case Left(_) => backoff.pause(retry.delayBeforeRetry(attempt))
    outcome.get

  private def reply(r: BedrockReply): Either[LlmFailure, LlmResponse] =
    r.stopReason match
      case "guardrail_intervened" | "content_filtered" =>
        Left(LlmFailure.Permanent(s"the model stopped: ${r.stopReason}"))
      case _ =>
        Right(
          LlmResponse(
            if r.text == null then "" else r.text,
            modelId,
            math.max(r.inputTokens, 0L),
            math.max(r.outputTokens, 0L)
          )
        )

  private def exhausted(e: BedrockError, attempts: Int): LlmFailure =
    e match
      case BedrockError.Throttled(d) =>
        LlmFailure.Overloaded(s"throttled on $attempts attempt(s): $d")
      case BedrockError.ServerError(s, d) =>
        LlmFailure.Overloaded(s"server error $s on $attempts attempt(s): $d")
      case BedrockError.TimedOut(d) =>
        LlmFailure.TimedOut(s"timed out on $attempts attempt(s): $d")
      case other =>
        LlmFailure.Transient(s"failed on $attempts attempt(s): ${other.detail}")

object BedrockLlmClient:
  /** Builds the client for the synthesis model from the shared model configuration. */
  def forSynthesis(
      invoker: BedrockInvoker,
      models: ModelConfig,
      retry: BedrockRetryPolicy
  ): BedrockLlmClient = new BedrockLlmClient(invoker, models.synthesisModelId, retry)
