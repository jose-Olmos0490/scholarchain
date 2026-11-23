package scholarchain.services.security

import scala.util.control.NonFatal
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

/** Reads the API-key digests from AWS Secrets Manager by secret name (the name comes from
  * configuration). The secret holds token digests only; the issued tokens are held by callers.
  * No value read here is logged, and a failure reports no more than that the load failed.
  */
final class SecretsManagerApiKeySource(client: SecretsManagerClient, secretName: String)
    extends ApiKeySource:
  def load(): Either[String, Vector[ApiKeyRecord]] =
    try
      val value =
        client.getSecretValue(GetSecretValueRequest.builder().secretId(secretName).build())
      Option(value.secretString())
        .toRight("the API key secret has no string value")
        .flatMap(
          ApiKeyJson.parse
        )
    catch case NonFatal(_) => Left("the API key secret could not be read")
