package scholarchain.rag.llm

import scholarchain.core.config.RagLlmConfig
import scholarchain.rag.agents.{SResult, StageFailure}
import scholarchain.rag.common.BoundedJson

/** The outcome of a structured call and what it cost, also when it failed. */
final case class StructuredResult(outcome: SResult[ujson.Obj], usage: LlmUsage)

/** A model call whose output must be one JSON object that validates against a versioned schema.
  *
  * Bounds: at most `1 + rag.llm.maxRetries` attempts; every response is capped by
  * `rag.llm.maxOutputTokens`. A transient failure, a response that is not exactly one JSON
  * object, a response over the token cap and a response that violates the schema are each one
  * failed attempt. A permanent failure stops at once. The text of a rejected response is never
  * repeated to the model, logged or returned: only the violated rule is reported. Once the thread
  * is interrupted (a stage deadline) no further attempt is made, the failure is a time-out and the
  * interrupt status is kept.
  *
  * The output is parsed strictly. A code fence or prose around the JSON is a violation, not
  * something to strip.
  */
final class StructuredLlmCall(client: LlmClient, cfg: RagLlmConfig):
  def maxOutputTokens: Int = cfg.maxOutputTokens

  def run(
      promptVersion: String,
      system: String,
      untrustedInput: String,
      schema: JsonSchema
  ): StructuredResult =
    val request =
      LlmRequest(promptVersion, system, untrustedInput, cfg.maxOutputTokens, Some(schema.text))
    val attempts = 1 + cfg.maxRetries
    var usage = LlmUsage.Zero
    var last = "no attempt was made"
    var n = 0
    // the failure the last attempt ended with, when it was a transport failure
    var lastTransport: Option[LlmFailure] = None
    var result: Option[SResult[ujson.Obj]] = None
    while result.isEmpty && n < attempts && !Thread.currentThread().isInterrupted do
      n += 1
      val call =
        try client.complete(request)
        catch
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            Left(LlmFailure.Transient("the call was interrupted"))
          case scala.util.control.NonFatal(_) => Left(LlmFailure.Transient("the client threw"))
      call match
        case Left(LlmFailure.Permanent(d)) =>
          result = Some(Left(StageFailure.Failed("llm", s"the model call was refused: $d")))
        case Left(
              f @ (LlmFailure.Transient(_) | LlmFailure.Overloaded(_) | LlmFailure.TimedOut(_))
            ) =>
          usage = usage + LlmUsage(1, 0L, 0L, Vector.empty)
          lastTransport = Some(f)
          last = s"transient model failure: ${f.detail}"
        case Right(r) =>
          lastTransport = None
          usage = usage + LlmUsage(1, r.inputTokens, r.outputTokens, Vector(r.modelVersion))
          if r.outputTokens > cfg.maxOutputTokens then
            last =
              s"the response used ${r.outputTokens} tokens, over the limit ${cfg.maxOutputTokens}"
          else
            parse(r.text) match
              case Left(why) => last = why
              case Right(obj) =>
                val violations = schema.validate(obj)
                if violations.isEmpty then result = Some(Right(obj))
                else last = s"schema ${schema.version}: ${violations.take(5).mkString("; ")}"
    val outcome = result.getOrElse {
      if Thread.currentThread().isInterrupted then
        Left(
          StageFailure.Timeout(
            "llm",
            s"the call was interrupted after $n attempt(s); no further request was made"
          )
        )
      else Left(exhausted(lastTransport, attempts, last))
    }
    StructuredResult(outcome, usage)

  /** No attempt produced a valid output. When the last attempt failed in transport the cause is
    * the model service (overloaded or too slow), not the output, and is typed as such.
    */
  private def exhausted(transport: Option[LlmFailure], attempts: Int, last: String): StageFailure =
    transport match
      case Some(LlmFailure.Overloaded(d)) =>
        StageFailure.Overloaded(
          "llm",
          s"the model service stayed overloaded for $attempts attempt(s): $d"
        )
      case Some(LlmFailure.TimedOut(d)) =>
        StageFailure.Timeout("llm", s"the model call timed out on $attempts attempt(s): $d")
      case _ =>
        StageFailure.Rejected(
          "MODEL_OUTPUT_INVALID",
          s"no valid output after $attempts attempt(s); last problem: $last"
        )

  private def parse(text: String): Either[String, ujson.Obj] =
    if text == null then Left("the response is empty")
    else
      try
        BoundedJson.read(text.trim) match
          case o: ujson.Obj => Right(o)
          case _ => Left("the response is not a JSON object")
      catch case scala.util.control.NonFatal(_) => Left("the response is not valid JSON")
