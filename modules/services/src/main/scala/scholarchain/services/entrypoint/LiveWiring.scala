package scholarchain.services.entrypoint

import scala.util.control.NonFatal
import scholarchain.services.config.ServiceSettings
import scholarchain.services.http.ServiceClock
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.security.{BearerAuthenticator, SecretsManagerApiKeySource}
import scholarchain.storage.StorageSettings
import scholarchain.storage.aws.*
import scholarchain.storage.common.Retrier
import scholarchain.storage.dynamo.{DynamoPorts, ItemStore}
import scholarchain.storage.objects.{ObjectArea, ObjectLayout, ObjectRef, ObjectStore}
import scholarchain.storage.vectors.{IndexDefinition, VectorIndex}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.neptunedata.model.GetEngineStatusRequest
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient

/** The real AWS adapters, built from the storage settings and nothing else. Access comes from the
  * SDK's default chain (the task role); no key, profile, endpoint, bucket, table or account ID is
  * written in this code. Building a client contacts nothing; the first call does.
  *
  * Only the live `main` methods call this. Tests build the roots from fakes instead.
  */
final class LiveStores private (
    val settings: StorageSettings,
    val clients: AwsClients,
    val retrier: Retrier,
    val items: ItemStore,
    val dynamo: DynamoPorts,
    val definitions: Vector[IndexDefinition],
    val ragLayout: ObjectLayout
):
  def objects: ObjectStore = new S3ObjectStore(clients.s3, ragLayout, retrier)

  /** The vector index. `singleAttempt` is for the ingestor, which owns the retries itself. */
  def vectorIndex(singleAttempt: Boolean = false): VectorIndex =
    val policy =
      if singleAttempt then settings.runtime.retry.copy(maxAttempts = 1)
      else settings.runtime.retry
    new S3VectorsIndex(
      clients.vectors,
      settings.buckets.vectorBucket,
      new Retrier(policy, AwsErrors.isTransient),
      definitions
    )

  def close(): Unit = clients.close()

  // ---- readiness probes: one cheap call each, answering ok or failing -------------------------

  /** DynamoDB answers a read of a key that does not exist. */
  def dynamoProbe: ReadinessProbe =
    Probes.of("dynamodb")(
      items.get(settings.runtime.dynamoTables.querySession, "probe#readiness", "PROBE").isRight
    )

  /** Neptune answers its engine status call. */
  def neptuneProbe: ReadinessProbe =
    Probes.of("neptune") {
      clients.neptune.getEngineStatus(GetEngineStatusRequest.builder().build())
      true
    }

  /** S3 Vectors describes the first configured index. */
  def vectorProbe: ReadinessProbe =
    Probes.of("s3Vectors")(vectorIndex().describe(definitions.head.name).isRight)

  /** S3 answers a head request for a key that does not exist. */
  def objectProbe: ReadinessProbe =
    Probes.of("s3")(objects.head(ObjectRef(ObjectArea.Rag, "probe/readiness")).isRight)

object LiveStores:
  def open(settings: StorageSettings): Either[String, LiveStores] =
    val defs = IndexDefinition.fromSettings(settings)
    ObjectLayout
      .fromConfig(settings.buckets, settings.runtime.s3Prefixes, ObjectArea.Rag)
      .left
      .map(_.message)
      .map { layout =>
        val clients = AwsClients.forRuntime(settings)
        val retrier = new Retrier(settings.runtime.retry, AwsErrors.isTransient)
        val items = new DynamoItemStore(clients.dynamo, retrier)
        val dynamo = DynamoPorts(
          items,
          settings.runtime.dynamoTables,
          settings.runtime.querySession.ttlSeconds
        )
        new LiveStores(settings, clients, retrier, items, dynamo, defs, layout)
      }

/** The API-key authenticator over Secrets Manager. A missing secret name or an unreadable secret
  * stops start-up: a service that cannot authenticate does not run.
  */
object LiveAuth:
  def open(
      settings: ServiceSettings,
      region: String,
      clock: ServiceClock
  ): Either[String, (BearerAuthenticator, ReadinessProbe)] =
    for
      name <- settings.auth.keySecretName.toRight(
        "scholarchain.services.auth.keySecretName (SCHOLARCHAIN_SVC_KEY_SECRET_NAME) is required"
      )
      client <-
        try Right(SecretsManagerClient.builder().region(Region.of(region)).build())
        catch case NonFatal(_) => Left("the Secrets Manager client could not be built")
      keys <- RefreshingApiKeys.load(
        new SecretsManagerApiKeySource(client, name),
        settings.auth.keyRefreshSeconds,
        clock
      )
    yield (
      new BearerAuthenticator(keys.current, settings.auth.maxTokenChars),
      Probes.of("apiKeys")(keys.loaded)
    )
