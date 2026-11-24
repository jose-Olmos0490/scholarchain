package scholarchain.storage.aws

import scala.jdk.CollectionConverters.*
import scholarchain.storage.StorageSettings
import scholarchain.storage.common.*
import scholarchain.storage.queue.*
import software.amazon.awssdk.auth.credentials.{
  DefaultCredentialsProvider,
  ProfileCredentialsProvider
}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*

/** AWS SDK v2 adapter of [[WorkQueue]] over one SQS queue.
  *
  * Limits (https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html
  * and https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html):
  * 1 to 10 messages per receive; message size at most 1,048,576 bytes; visibility timeout 0 s to
  * 12 h. The queue URL comes from configuration; the queue and its redrive policy are Terraform
  * resources owned by `infra-engineer`. `ApproximateReceiveCount` is requested on every receive.
  * The SDK's own retries are off; `retrier` is the only retry layer.
  */
final class SqsWorkQueue(client: SqsClient, queueUrl: String, retrier: Retrier) extends WorkQueue:
  require(queueUrl.trim.nonEmpty, "queueUrl must not be blank")

  val MaxBodyBytes = 1048576
  val MaxVisibilitySeconds = 43200

  def send(body: String): Result[String] =
    val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    if bytes > MaxBodyBytes then
      Left(StorageError.LimitExceeded("SQS message bytes", MaxBodyBytes, bytes))
    else
      retrier.run("SQS send") {
        client
          .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build())
          .messageId()
      }

  def receive(
      max: Int,
      visibilityTimeoutSeconds: Int,
      waitSeconds: Int
  ): Result[Vector[QueueMessage]] =
    if max < 1 || max > 10 then
      Left(StorageError.LimitExceeded("SQS receive batch", 10, max.toLong))
    else if visibilityTimeoutSeconds < 0 || visibilityTimeoutSeconds > MaxVisibilitySeconds then
      Left(
        StorageError.LimitExceeded(
          "SQS visibility timeout seconds",
          MaxVisibilitySeconds,
          visibilityTimeoutSeconds.toLong
        )
      )
    else
      retrier.run("SQS receive") {
        client
          .receiveMessage(
            ReceiveMessageRequest
              .builder()
              .queueUrl(queueUrl)
              .maxNumberOfMessages(max)
              .visibilityTimeout(visibilityTimeoutSeconds)
              .waitTimeSeconds(waitSeconds)
              .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
              .build()
          )
          .messages()
          .asScala
          .toVector
          .map { m =>
            val count =
              Option(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                .flatMap(_.toIntOption)
                .getOrElse(1)
            QueueMessage(m.messageId(), m.receiptHandle(), m.body(), count)
          }
      }

  def delete(receiptHandle: String): Result[Unit] =
    retrier.run("SQS delete") {
      client.deleteMessage(
        DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build()
      )
      ()
    }

  def changeVisibility(receiptHandle: String, seconds: Int): Result[Unit] =
    if seconds < 0 || seconds > MaxVisibilitySeconds then
      Left(
        StorageError
          .LimitExceeded("SQS visibility timeout seconds", MaxVisibilitySeconds, seconds.toLong)
      )
    else
      retrier.run("SQS change visibility") {
        client.changeMessageVisibility(
          ChangeMessageVisibilityRequest
            .builder()
            .queueUrl(queueUrl)
            .receiptHandle(receiptHandle)
            .visibilityTimeout(seconds)
            .build()
        )
        ()
      }

object SqsWorkQueue:
  /** Runtime client (default credential chain, the task role) or, with `profile`, an explicit named
    * profile (integration tests). SDK retries are off.
    */
  def client(settings: StorageSettings, profile: Option[String] = None): SqsClient =
    SqsClient
      .builder()
      .region(Region.of(settings.awsRegion))
      .credentialsProvider(
        profile.fold[software.amazon.awssdk.auth.credentials.AwsCredentialsProvider](
          DefaultCredentialsProvider.builder().build()
        )(ProfileCredentialsProvider.create)
      )
      .overrideConfiguration(
        ClientOverrideConfiguration.builder().retryStrategy(AwsRetryStrategy.doNotRetry()).build()
      )
      .build()
