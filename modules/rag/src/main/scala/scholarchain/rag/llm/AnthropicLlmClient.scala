package scholarchain.rag.llm

import scala.util.control.NonFatal
import scholarchain.core.config.AnthropicConfig
import scholarchain.rag.common.BoundedJson
import scholarchain.rag.provider.{ProviderFailure, ProviderFailureKind, ProviderHttp, RetryDelays}

/** The [[LlmClient]] over the Anthropic Messages API (`POST <baseUrl>/v1/messages`).
  *
  * The request holds only `model`, `max_tokens`, `system`, one user message with the untrusted
  * document and, when the request has an output schema, `output_config.format`. No sampling,
  * thinking or prefill parameter is sent, because which of them a model accepts differs.
  *
  * Only `text` content blocks are returned; thinking blocks are dropped unread. The model that
  * answered and the token usage are reported. A refusal stop is permanent; a cut-off answer is
  * returned as it is, so the structured call rejects it.
  *
  * Failures: 429 and 529 are [[LlmFailure.Overloaded]]; a client-side deadline is
  * [[LlmFailure.TimedOut]]; other 5xx (504 included) and connection failures are
  * [[LlmFailure.Transient]]; every other status (408 included) is [[LlmFailure.Permanent]]. All
  * but permanent failures are retried, at most
  * `config.maxRetries` times. Failure details never hold provider text or the key.
  *
  * @param apiKey
  *   supplies the key at each call (Secrets Manager or the environment); blank means missing
  */
final class AnthropicLlmClient(
    config: AnthropicConfig,
    apiKey: () => String,
    delays: RetryDelays,
    backoff: Backoff = Backoff.Real
) extends LlmClient:
  private val http = new ProviderHttp(
    config.connectTimeoutMs,
    config.requestTimeoutMs,
    config.maxRetries,
    delays,
    backoff
  )
  private val url = s"${config.baseUrl}/v1/messages"

  def complete(request: LlmRequest): Either[LlmFailure, LlmResponse] =
    body(request).flatMap { json =>
      http
        .postJson(
          url,
          apiKey,
          key => Seq("x-api-key" -> key, "anthropic-version" -> config.apiVersion),
          json
        )
        .left
        .map(failure)
        .flatMap(reply)
    }

  private def body(request: LlmRequest): Either[LlmFailure, String] =
    try
      val out = ujson.Obj(
        "model" -> config.modelId,
        "max_tokens" -> request.maxOutputTokens,
        "system" -> request.system,
        "messages" -> ujson.Arr(
          ujson.Obj("role" -> "user", "content" -> request.untrustedInput)
        )
      )
      request.outputSchema.foreach { text =>
        val schema = ProviderSchema.of(ujson.Obj(ujson.read(text).obj))
        out("output_config") =
          ujson.Obj("format" -> ujson.Obj("type" -> "json_schema", "schema" -> schema))
      }
      Right(out.render())
    catch case NonFatal(_) => Left(LlmFailure.Permanent("the request could not be built"))

  private def failure(f: ProviderFailure): LlmFailure =
    val detail = s"Anthropic API: ${f.detail}"
    f.kind match
      case ProviderFailureKind.Overloaded => LlmFailure.Overloaded(detail)
      case ProviderFailureKind.TimedOut => LlmFailure.TimedOut(detail)
      case ProviderFailureKind.Transient => LlmFailure.Transient(detail)
      case ProviderFailureKind.Permanent => LlmFailure.Permanent(detail)

  private def reply(body: String): Either[LlmFailure, LlmResponse] =
    try
      val json = BoundedJson.read(body).obj
      val stop = json.get("stop_reason").flatMap(_.strOpt).getOrElse("")
      if stop == "refusal" then Left(LlmFailure.Permanent("the model stopped: refusal"))
      else
        val text = json("content").arr
          .filter(block => block("type").str == "text")
          .map(block => block("text").str)
          .mkString
        val usage = json("usage").obj
        def count(name: String): Long =
          usage.get(name).flatMap(_.numOpt).fold(0L)(n => math.max(n.toLong, 0L))
        val model = json
          .get("model")
          .flatMap(_.strOpt)
          .filter(isConfiguredModel)
          .getOrElse(config.modelId)
        Right(
          LlmResponse(
            text,
            model,
            count("input_tokens") + count("cache_creation_input_tokens") +
              count("cache_read_input_tokens"),
            math.max(usage("output_tokens").num.toLong, 0L)
          )
        )
    catch
      case NonFatal(_) =>
        Left(LlmFailure.Transient("Anthropic API: the response could not be read"))

  /** The `model` field is provider text that goes into the audit record, so it is recorded only
    * when it is the configured ID, alone or with a `-YYYYMMDD` snapshot suffix. Anything else
    * (an echoed key, for example) is replaced by the configured ID.
    */
  private def isConfiguredModel(model: String): Boolean =
    model == config.modelId ||
      (model.startsWith(config.modelId) &&
        AnthropicLlmClient.Snapshot.matches(model.substring(config.modelId.length)))

object AnthropicLlmClient:
  private val Snapshot = "-[0-9]{8}".r
