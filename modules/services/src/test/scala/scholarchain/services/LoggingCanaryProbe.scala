package scholarchain.services

import com.sun.net.httpserver.HttpServer
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.core.config.{
  AnthropicConfig,
  EmbeddingProviderConfig,
  LlmProviderConfig,
  VoyageConfig
}
import scholarchain.rag.llm.LlmRequest
import scholarchain.rag.provider.{EmbeddingPurpose, RetryDelays}
import scholarchain.rag.workflow.StageTrace
import scholarchain.services.config.ProviderKeySettings
import scholarchain.services.entrypoint.{ProviderWiring, Slf4jTraceSink}
import scholarchain.services.http.SystemServiceClock
import scholarchain.services.ops.{AccessRecord, Slf4jAccessLog}
import scholarchain.services.security.{SecretStringSource, SecretsManagerSecretStrings}
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

  // fake model-provider keys in the shapes of the real ones (joined so no scanner reads a key)
  val AnthropicKey = "sk-ant-" + "api03-FAKE-ANTHROPIC-KEY-CANARY"
  val VoyageKey = "pa-" + "FAKE-VOYAGE-KEY-CANARY"

  /** Printed straight to stdout when every AWS call was signed and answered, and both providers
    * were called with their key.
    */
  val Success = "probe-ok signedCalls=5 providerCalls=2"

  def main(args: Array[String]): Unit =
    val signed = new ConcurrentLinkedQueue[Boolean]()
    val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val headers = exchange.getRequestHeaders
        val requested = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
        signed.add(
          Option(headers.getFirst("Authorization")).exists(_.startsWith("AWS4-HMAC-SHA256")) &&
            headers.getFirst("X-Amz-Security-Token") == SessionToken
        )
        val name = ujson.read(requested)("SecretId").str
        val value = name match
          case "fake/anthropic" => AnthropicKey
          case "fake/voyage" => VoyageKey
          case "fake/malformed" => ujson.Obj("apiKey" -> AnthropicKey).render()
          case _ => SecretValue
        val body = ujson.Obj("Name" -> name, "SecretString" -> value).render().getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/x-amz-json-1.1")
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    // the model providers refuse the call and echo the key back in the error body
    val providerCalls = new ConcurrentLinkedQueue[Boolean]()
    Vector(
      "/v1/messages" -> ("x-api-key", AnthropicKey, AnthropicKey),
      "/v1/embeddings" -> ("authorization", s"Bearer $VoyageKey", VoyageKey)
    ).foreach { case (path, (header, expected, key)) =>
      server.createContext(
        path,
        exchange =>
          exchange.getRequestBody.readAllBytes()
          providerCalls.add(exchange.getRequestHeaders.getFirst(header) == expected)
          val body = ujson
            .Obj(
              "error" -> ujson.Obj("type" -> "authentication_error", "message" -> s"bad key $key"),
              "detail" -> s"bad key $key"
            )
            .render()
            .getBytes(UTF_8)
          exchange.sendResponseHeaders(401, body.length.toLong)
          exchange.getResponseBody.write(body)
          exchange.close()
      )
    }
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
        val read = Vector(
          sync.getSecretValue(request).secretString(),
          async.getSecretValue(request).join().secretString()
        )
        callProviders(endpoint.toString, new SecretsManagerSecretStrings(sync))
        read
      finally
        sync.close()
        async.close()
        server.stop(0)

    // the application's own lines, as the services write them
    Slf4jAccessLog.record(
      AccessRecord("GET", "/v1/probe", 200, "request-canary-probe", TraceId, 1L, None, None)
    )
    Slf4jTraceSink.record(StageTrace(TraceId, "probe", 1L, "OK"))

    val ok = answers.forall(_ == SecretValue) &&
      signed.asScala.toVector == Vector.fill(5)(true) &&
      providerCalls.asScala.toVector == Vector(true, true)
    println(if ok then Success else "probe-failed")
    System.exit(if ok then 0 else 1)

  /** The production path of both provider keys: Secrets Manager, the key cache, the wiring and
    * the adapters, logging every failure they report. A key secret that holds JSON is read too.
    */
  private def callProviders(url: String, secrets: SecretStringSource): Unit =
    val log = LoggerFactory.getLogger("scholarchain.services.canary")
    val clock = SystemServiceClock
    def settings(anthropic: String) =
      ProviderKeySettings(Some(anthropic), Some("fake/voyage"), 300, 10, 5000L)
    def llm(anthropic: String) = ProviderWiring
      .llm(
        LlmProviderConfig.Anthropic(
          AnthropicConfig("claude-canary", url, "2023-06-01", 5000, 5000, 0)
        ),
        settings(anthropic),
        () => Right(secrets),
        clock,
        RetryDelays(0, 0),
        sys.error("Bedrock is not selected")
      )
      .fold(m => sys.error(m), identity)
    llm("fake/malformed"): Unit
    llm("fake/anthropic").adapter
      .complete(LlmRequest("canary", "system", "{}", 16))
      .left
      .foreach(f => log.warn("model call failed: {}", f))
    val embedder = ProviderWiring
      .embedder(
        EmbeddingProviderConfig.Voyage(
          VoyageConfig("voyage-canary", url, 4, 8, 5000, 5000, 0)
        ),
        Map("EMBEDDING_MODEL_VERSION" -> "1", "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "true"),
        EmbeddingPurpose.Retrieval,
        settings("fake/anthropic"),
        () => Right(secrets),
        clock,
        RetryDelays(0, 0),
        sys.error("Bedrock is not selected")
      )
      .fold(m => sys.error(m), identity)
    try embedder.adapter.embed(Seq("canary text")): Unit
    catch case NonFatal(e) => log.warn("embedding call failed", e)
