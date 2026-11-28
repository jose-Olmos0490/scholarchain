package scholarchain.services.security

import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.services.http.ServiceClock
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{
  GetSecretValueRequest,
  ResourceNotFoundException
}

/** Reads the string value of one secret by name. A failure is fixed text: never the value, and
  * never the SDK's message.
  */
trait SecretStringSource:
  def read(secretName: String): Either[String, String]

final class SecretsManagerSecretStrings(client: SecretsManagerClient) extends SecretStringSource:
  def read(secretName: String): Either[String, String] =
    try
      val value =
        client.getSecretValue(GetSecretValueRequest.builder().secretId(secretName).build())
      Option(value.secretString()).toRight("the secret has no string value")
    catch
      case _: ResourceNotFoundException => Left("the secret or its value does not exist")
      case NonFatal(_) => Left("the secret could not be read")

/** One model-provider API key, held in Secrets Manager and cached in memory.
  *
  * The secret string is the key itself (plain text, not JSON); surrounding white space is trimmed.
  *
  *   - A key that was read is reused for `refreshMillis`, so a rotation takes effect without a
  *     restart. When a later refresh fails, the key already held stays in force and the read is
  *     tried again after `retryMillis`.
  *   - A failed first read is never cached for good: it is tried again after `retryMillis`.
  *
  * The key is never logged; a failure is logged once per change, by check name and fixed reason.
  *
  * @param checkName
  *   the `/readyz` check name
  * @param secretNameVariable
  *   the environment variable that names the secret (logged instead of the name)
  */
final class ProviderKey(
    val checkName: String,
    secretNameVariable: String,
    load: () => Either[String, String],
    refreshMillis: Long,
    retryMillis: Long,
    clock: ServiceClock
):
  private val log = LoggerFactory.getLogger("scholarchain.services.provider-keys")
  @volatile private var held: Option[String] = None
  private var nextReadAt = Long.MinValue
  private var reading = false
  private var lastProblem: Option[String] = None

  /** The key, or none while it has never been read successfully. */
  def current: Option[String] =
    // with no key yet, callers wait for the read in progress instead of failing beside it
    if held.isEmpty then synchronized(if due then store(readOnce()))
    else
      val mine = synchronized {
        val go = !reading && due
        if go then reading = true
        go
      }
      // other callers keep using the held key while this one refreshes
      if mine then
        var outcome: Either[String, String] = Left("the secret could not be read")
        try outcome = readOnce()
        finally
          synchronized {
            reading = false
            store(outcome)
          }
    held

  /** What the provider adapters ask per request or batch; blank means no key. */
  def apiKey(): String = current.getOrElse("")

  def available: Boolean = current.isDefined

  private def due: Boolean = clock.nowMillis >= nextReadAt

  private def readOnce(): Either[String, String] =
    try load().flatMap(ProviderKey.parse)
    catch case NonFatal(_) => Left("the secret could not be read")

  private def store(outcome: Either[String, String]): Unit =
    outcome match
      case Right(key) =>
        held = Some(key)
        nextReadAt = clock.nowMillis + refreshMillis
        lastProblem = None
      case Left(reason) =>
        nextReadAt = clock.nowMillis + retryMillis
        if !lastProblem.contains(reason) then
          lastProblem = Some(reason)
          log.error(
            "provider key not loaded check={} secretNameVariable={} reason={} keptPreviousKey={}",
            checkName,
            secretNameVariable,
            reason,
            Boolean.box(held.isDefined)
          )

object ProviderKey:
  /** A plain key: visible ASCII with no white space inside, and not a JSON document. */
  def parse(secretString: String): Either[String, String] =
    val key = secretString.trim
    if key.isEmpty then Left("the secret value is empty")
    else if "{[\"".contains(key.charAt(0)) || !key.forall(c => c > ' ' && c < 127) then
      Left("the secret value must be the plain API key, not JSON or other text")
    else Right(key)
