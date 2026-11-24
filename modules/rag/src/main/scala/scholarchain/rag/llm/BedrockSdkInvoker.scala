package scholarchain.rag.llm

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  InferenceConfiguration,
  Message,
  SystemContentBlock
}

/** The Bedrock Converse binding of [[BedrockInvoker]]. It does not retry (the client is built
  * with the SDK retry strategy off, so [[BedrockRetryPolicy]] is the only retry bound).
  *
  * Only `text` content blocks are returned; reasoning blocks are dropped. Never constructed by
  * `sbt check` against AWS: unit tests exercise [[BedrockSdkInvoker.classify]] and a stubbed
  * client.
  */
final class BedrockSdkInvoker(client: BedrockRuntimeClient) extends BedrockInvoker:
  def invoke(call: BedrockCall): Either[BedrockError, BedrockReply] =
    val req = ConverseRequest
      .builder()
      .modelId(call.modelId)
      .system(SystemContentBlock.builder().text(call.system).build())
      .messages(
        Message
          .builder()
          .role(ConversationRole.USER)
          .content(ContentBlock.fromText(call.userText))
          .build()
      )
      .inferenceConfig(InferenceConfiguration.builder().maxTokens(call.maxTokens).build())
      .build()
    try
      val r = client.converse(req)
      val text = r
        .output()
        .message()
        .content()
        .asScala
        .flatMap(b => Option(b.text()))
        .mkString
      val usage = r.usage()
      Right(
        BedrockReply(
          text,
          Option(usage).flatMap(u => Option(u.inputTokens())).map(_.toLong).getOrElse(0L),
          Option(usage).flatMap(u => Option(u.outputTokens())).map(_.toLong).getOrElse(0L),
          Option(r.stopReasonAsString()).getOrElse("")
        )
      )
    catch case scala.util.control.NonFatal(e) => Left(BedrockSdkInvoker.classify(e))

object BedrockSdkInvoker:
  /** Maps an SDK exception to a category. The detail is the exception class and status only;
    * provider messages can echo request content and are not repeated.
    */
  def classify(e: Throwable): BedrockError =
    val name = e.getClass.getSimpleName
    e match
      case _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException =>
        BedrockError.TimedOut(name)
      case a: AwsServiceException =>
        val status = a.statusCode()
        if name == "ModelTimeoutException" || status == 408 || status == 504 then
          BedrockError.TimedOut(s"$name $status")
        else if status == 429 || name == "ThrottlingException" then
          BedrockError.Throttled(s"$name $status")
        else if status >= 500 then BedrockError.ServerError(status, name)
        else BedrockError.Refused(s"$name $status")
      case _: SdkClientException => BedrockError.Network(name)
      case _ => BedrockError.Refused(name)

  /** A client for one Region, with an explicit profile when given (otherwise the default
    * credential chain). Region and timeout come from configuration; the SDK's own retries are
    * off.
    */
  def live(
      region: String,
      profile: Option[String],
      attemptTimeoutMillis: Long
  ): BedrockInvoker =
    require(attemptTimeoutMillis > 0, "attemptTimeoutMillis must be positive")
    val b = BedrockRuntimeClient
      .builder()
      .region(Region.of(region))
      .overrideConfiguration(
        ClientOverrideConfiguration
          .builder()
          .retryStrategy(AwsRetryStrategy.doNotRetry())
          .apiCallAttemptTimeout(java.time.Duration.ofMillis(attemptTimeoutMillis))
          .build()
      )
    profile.foreach(p =>
      b.credentialsProvider(
        software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider.create(p)
      )
    )
    new BedrockSdkInvoker(b.build())
