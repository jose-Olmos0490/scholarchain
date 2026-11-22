package scholarchain.storage.aws

import java.net.URI
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  ProfileCredentialsProvider
}
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.{SdkClientException, SdkServiceException}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import scala.jdk.CollectionConverters.*
import scholarchain.storage.StorageSettings

/** Which AWS failures are worth retrying: throttling, timeouts and transient server errors. */
object AwsErrors:
  private val TransientStatus = Set(408, 429, 500, 502, 503, 504)
  private val TransientCodes = Set(
    "ThrottlingException",
    "Throttling",
    "ThrottledException",
    "RequestLimitExceeded",
    "ProvisionedThroughputExceededException",
    "TooManyRequestsException",
    "RequestThrottled",
    "SlowDown",
    "ServiceUnavailable",
    "ServiceUnavailableException",
    "InternalServerError",
    "InternalServerException",
    "InternalFailure",
    "RequestTimeout",
    "RequestTimeoutException",
    "TransactionInProgressException",
    "ConditionalRequestConflict"
  )

  def isTransient(t: Throwable): Boolean = t match
    case s: SdkServiceException =>
      val code = s match
        case a: software.amazon.awssdk.awscore.exception.AwsServiceException =>
          Option(a.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("")
        case _ => ""
      s.isThrottlingException || TransientStatus.contains(s.statusCode()) || TransientCodes
        .contains(code)
    case c: SdkClientException => causes(c).exists(_.isInstanceOf[java.io.IOException])
    case _ => false

  private def causes(t: Throwable): LazyList[Throwable] =
    LazyList.unfold(Option(t))(_.map(x => (x, Option(x.getCause))))

/** SDK clients built from the core configuration. The SDK's own retries are switched off: the
  * storage retry policy (bounded exponential backoff with jitter, counted) is the only retry layer.
  */
final class AwsClients private (
    val s3: S3Client,
    val dynamo: DynamoDbClient,
    val vectors: S3VectorsClient,
    val neptune: NeptunedataClient
) extends AutoCloseable:
  def close(): Unit =
    s3.close()
    dynamo.close()
    vectors.close()
    neptune.close()

object AwsClients:
  private def noSdkRetry: ClientOverrideConfiguration =
    ClientOverrideConfiguration.builder().retryStrategy(AwsRetryStrategy.doNotRetry()).build()

  /** Runtime clients: the default credential chain (task role). Credentials are read-only for
    * graph reads by policy (Terraform), not by code.
    */
  def forRuntime(settings: StorageSettings): AwsClients =
    build(settings, DefaultCredentialsProvider.builder().build())

  /** Integration-test clients: an explicit named profile, never the default chain. */
  def forProfile(settings: StorageSettings, profile: String): AwsClients =
    require(profile.trim.nonEmpty, "an explicit AWS profile name is required")
    build(settings, ProfileCredentialsProvider.create(profile))

  private def build(settings: StorageSettings, creds: AwsCredentialsProvider): AwsClients =
    val region = Region.of(settings.awsRegion)
    val ep = settings.runtime.endpoints
    val s3b =
      S3Client.builder().region(region).credentialsProvider(creds).overrideConfiguration(noSdkRetry)
    val ddb = DynamoDbClient
      .builder()
      .region(region)
      .credentialsProvider(creds)
      .overrideConfiguration(noSdkRetry)
    val vec = S3VectorsClient
      .builder()
      .region(region)
      .credentialsProvider(creds)
      .overrideConfiguration(noSdkRetry)
    ep.s3.foreach(e => s3b.endpointOverride(URI.create(e)).forcePathStyle(true))
    ep.dynamodb.foreach(e => ddb.endpointOverride(URI.create(e)))
    ep.s3Vectors.foreach(e => vec.endpointOverride(URI.create(e)))
    val neptune = NeptunedataClient
      .builder()
      .region(region)
      .credentialsProvider(creds)
      .overrideConfiguration(noSdkRetry)
      .endpointOverride(
        URI.create(s"https://${settings.neptune.endpoint}:${settings.neptune.port}")
      )
      .build()
    new AwsClients(s3b.build(), ddb.build(), vec.build(), neptune)

/** ujson <-> AWS SDK `Document` (S3 Vectors metadata and filters, Neptune results). */
object Docs:
  def toDocument(v: ujson.Value): Document = v match
    case ujson.Str(s) => Document.fromString(s)
    case ujson.Num(n) =>
      if n.isWhole && math.abs(n) < 1e15 then Document.fromNumber(n.toLong)
      else Document.fromNumber(n)
    case ujson.Bool(b) => Document.fromBoolean(b)
    case ujson.Null => Document.fromNull()
    case ujson.Arr(items) => Document.fromList(items.map(toDocument).asJava)
    case ujson.Obj(fields) => Document.fromMap(fields.map((k, x) => k -> toDocument(x)).asJava)

  def toJson(d: Document): ujson.Value =
    if d == null || d.isNull then ujson.Null
    else if d.isString then ujson.Str(d.asString)
    else if d.isNumber then ujson.Num(d.asNumber.doubleValue)
    else if d.isBoolean then ujson.Bool(d.asBoolean)
    else if d.isList then ujson.Arr.from(d.asList.asScala.map(toJson))
    else if d.isMap then ujson.Obj.from(d.asMap.asScala.map((k, x) => k -> toJson(x)))
    else ujson.Null
