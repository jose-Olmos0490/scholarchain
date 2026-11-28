package scholarchain.er.adjudication

import scholarchain.core.config.LlmProviderConfig

/** The model adjudicator of the configured `llm.provider`. Nothing is defaulted. */
object ConfiguredAdjudicator:
  /** Environment variable that holds the Anthropic key in human-run live runs. */
  val AnthropicKeyVariable = "ANTHROPIC_API_KEY"

  /** Why no Bedrock adjudicator can be built from configuration. */
  val BedrockUnavailable =
    "the configuration contract has no key for the adjudication model ID or an enable flag " +
      "(owner: domain-engineer)"

  /** Anthropic: an [[AnthropicAdjudicator]] with the configured model. Bedrock: refused
    * ([[BedrockUnavailable]]); [[BedrockAdjudicator]] is still constructed by a caller that has a
    * model ID.
    */
  def apply(
      llm: LlmProviderConfig,
      apiKey: () => String,
      maxTokens: Int,
      baseDelayMs: Long,
      maxDelayMs: Long
  ): Either[String, Adjudicator] =
    llm match
      case LlmProviderConfig.Anthropic(settings) =>
        Right(new AnthropicAdjudicator(settings, apiKey, maxTokens, baseDelayMs, maxDelayMs))
      case _: LlmProviderConfig.Bedrock => Left(BedrockUnavailable)
