package scholarchain.services

import com.sun.net.httpserver.HttpServer
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scholarchain.rag.workflow.StageTrace
import scholarchain.services.entrypoint.Slf4jTraceSink
import scholarchain.services.ops.{AccessRecord, Slf4jAccessLog}
import software.amazon.awssdk.auth.credentials.{AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.{
  SecretsManagerAsyncClient,
  SecretsManagerClient
}
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

/** A program run by [[LoggingCanarySpec]] in its own JVM, so that the logging configuration under
  * test applies to the whole process and everything it writes can be captured.
  *
  * It makes signed Secrets Manager calls (synchronous and asynchronous transports) to a fake
  * endpoint on the loopback interface. Every credential and value is an obvious fake, and the
  * credentials are static: the default provider chain is never consulted.
  */
object LoggingCanaryProbe:
  val AccessKey = "FAKE-ACCESS-KEY-CANARY"
  val SecretKey = "FAKE-SECRET-KEY-CANARY"
  val SessionToken = "FAKE-SESSION-TOKEN-CANARY"
  val SecretValue = "FAKE-SECRET-VALUE-CANARY"
  val TraceId = "trace-canary-probe"

  /** Printed straight to stdout when both calls were signed and both answers arrived. */
  val Success = "probe-ok signedCalls=2"

  def main(args: Array[String]): Unit =
    val signed = new ConcurrentLinkedQueue[Boolean]()
    val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val headers = exchange.getRequestHeaders
        exchange.getRequestBody.readAllBytes()
        signed.add(
          Option(headers.getFirst("Authorization")).exists(_.startsWith("AWS4-HMAC-SHA256")) &&
            headers.getFirst("X-Amz-Security-Token") == SessionToken
        )
        val body =
          s"""{"Name":"fake/canary","SecretString":"$SecretValue"}""".getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/x-amz-json-1.1")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    server.start()

    val endpoint = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}")
    val credentials = StaticCredentialsProvider.create(
      AwsSessionCredentials.create(AccessKey, SecretKey, SessionToken)
    )
    val request = GetSecretValueRequest.builder().secretId("fake/canary").build()

    val sync = SecretsManagerClient
      .builder()
      .endpointOverride(endpoint)
      .region(Region.US_EAST_2)
      .credentialsProvider(credentials)
      .build()
    val async = SecretsManagerAsyncClient
      .builder()
      .endpointOverride(endpoint)
      .region(Region.US_EAST_2)
      .credentialsProvider(credentials)
      .build()
    val answers =
      try
        Vector(
          sync.getSecretValue(request).secretString(),
          async.getSecretValue(request).join().secretString()
        )
      finally
        sync.close()
        async.close()
        server.stop(0)

    // the application's own lines, as the services write them
    Slf4jAccessLog.record(
      AccessRecord("GET", "/v1/probe", 200, "request-canary-probe", TraceId, 1L, None, None)
    )
    Slf4jTraceSink.record(StageTrace(TraceId, "probe", 1L, "OK"))

    val ok = answers.forall(_ == SecretValue) && signed.asScala.toVector == Vector(true, true)
    println(if ok then Success else "probe-failed")
    System.exit(if ok then 0 else 1)
