package scholarchain.rag

import java.nio.file.Paths
import scholarchain.core.config.*
import scholarchain.rag.llm.*
import scholarchain.rag.provider.{ModelProviders, RetryDelays}
import scholarchain.tags.AwsIntegration

/** Live check: ONE structured call to the Anthropic API, with the synthesis prompt and schema (the
  * largest schema the runtime sends) over an empty evidence bundle. It costs money, so it runs only
  * behind the live profile: SCHOLARCHAIN_AWS_IT=true, SCHOLARCHAIN_LLM_PROVIDER=anthropic with the
  * `llm.anthropic.*` variables, and ANTHROPIC_API_KEY. Cancelled, never passed, when any condition
  * is missing. `sbt check` never runs it. The key is never printed.
  */
class AnthropicLiveIT extends RagSuite:
  private val PromptVersion = "answer-synthesis-prompt-v3"
  private val SchemaVersion = "answer-synthesis-v2"

  private def liveGate(): AnthropicConfig =
    if !sys.env.get("SCHOLARCHAIN_AWS_IT").contains("true") then
      cancel("SCHOLARCHAIN_AWS_IT is not 'true'")
    if sys.env.get(ModelProviders.AnthropicKeyVariable).forall(_.trim.isEmpty) then
      cancel(s"${ModelProviders.AnthropicKeyVariable} is not set")
    ConfigLoader.loadSection(LlmProviderConfig, sys.env) match
      case Right(LlmProviderConfig.Anthropic(settings)) => settings
      case Right(_) => cancel("SCHOLARCHAIN_LLM_PROVIDER is not 'anthropic'")
      case Left(e) => cancel(s"configuration: ${e.message}")

  test("one live Anthropic structured call returns a schema-valid object", AwsIntegration) {
    val settings = liveGate()
    val retry = ConfigLoader
      .loadSection(RetryPolicyConfig, sys.env)
      .fold(
        e => fail(e.message),
        identity
      )
    val limits = ConfigLoader
      .loadSection(RagLlmConfig, sys.env)
      .fold(
        e => fail(e.message),
        identity
      )
    val prompts = PromptRepository
      .load(PromptRepository.locate(Paths.get("").toAbsolutePath).get)
      .fold(f => fail(f.message), identity)
    val prompt = prompts.prompt(PromptVersion).fold(f => fail(f.message), identity)
    val schema = prompts.schema(SchemaVersion).fold(f => fail(f.message), identity)
    val client = new AnthropicLlmClient(
      settings,
      () => sys.env(ModelProviders.AnthropicKeyVariable),
      RetryDelays(retry.baseDelayMs.toLong, retry.maxDelayMs.toLong)
    )
    val input =
      """{"bundleId":"live-check","graphEvidence":[],"semanticEvidence":[],"identityConfidence":{}}"""
    // no structured-level retry: exactly one structured call
    val result = new StructuredLlmCall(client, limits.copy(maxRetries = 0))
      .run(prompt.version, prompt.text, input, schema)
    val u = result.usage
    println(
      s"anthropic live: calls=${u.calls} models=${u.modelVersions.mkString(",")} " +
        s"inputTokens=${u.inputTokens} outputTokens=${u.outputTokens}"
    )
    result.outcome match
      case Left(failure) => fail(failure.message)
      case Right(answer) =>
        schema.validate(answer) shouldBe empty
        u.modelVersions should not be empty
        u.inputTokens should be > 0L
        u.outputTokens should be > 0L
  }
