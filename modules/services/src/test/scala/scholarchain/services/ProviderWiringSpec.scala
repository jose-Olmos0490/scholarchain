package scholarchain.services

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scholarchain.rag.llm.{LlmFailure, LlmRequest}
import scholarchain.rag.provider.{EmbeddingPurpose, RetryDelays}
import scholarchain.services.config.*
import scholarchain.services.entrypoint.*
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.query.InMemoryJobStore
import scholarchain.services.runtime.{QueryApiPorts, RuntimeEnvironment}
import scholarchain.services.security.{ProviderKey, SecretStringSource, SecretsManagerSecretStrings}
import scholarchain.storage.common.InMemoryRejectSink
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}
import scholarchain.storage.queue.{EmbeddingWorkItem, InMemoryWorkQueue, WorkConsumer}
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{
  GetSecretValueRequest,
  GetSecretValueResponse,
  ResourceNotFoundException
}

/** Fakes for the provider wiring: a secret store, a loopback provider and an environment. Every
  * key is an obvious fake and nothing leaves the loopback interface.
  */
object ProviderFx:
  val AnthropicName = "test/anthropic-key-secret"
  val VoyageName = "test/voyage-key-secret"
  val AnthropicValue = "fake-anthropic-key-1"
  val VoyageValue = "fake-voyage-key-1"
  val Dimension = 4

  val settings = ProviderKeySettings(Some(AnthropicName), Some(VoyageName), 300, 10, 1000L)
  val llmSettings = LlmSettings(5, 0, 0, 1000)

  final class Secrets(initial: Map[String, String] = Map.empty) extends SecretStringSource:
    @volatile var values: Map[String, String] = initial
    private val log = new ConcurrentLinkedQueue[String]()
    def reads: Vector[String] = log.asScala.toVector
    def read(secretName: String): Either[String, String] =
      log.add(secretName)
      values.get(secretName).toRight("the secret or its value does not exist")

  def full: Secrets =
    new Secrets(Map(AnthropicName -> AnthropicValue, VoyageName -> VoyageValue))

  final case class Call(path: String, headers: Map[String, String], body: String)

  final class Provider extends AutoCloseable:
    private val seen = new ConcurrentLinkedQueue[Call]()
    @volatile var status = 200
    private val server =
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
        val headers = exchange.getRequestHeaders.asScala
          .map((k, v) => k.toLowerCase -> v.get(0))
          .toMap
        val path = exchange.getRequestURI.getPath
        seen.add(Call(path, headers, body))
        val answer =
          if path == "/v1/messages" then """{"model":"claude-test","stop_reason":"end_turn",
              |"content":[{"type":"text","text":"{}"}],
              |"usage":{"input_tokens":1,"output_tokens":1}}""".stripMargin
          else """{"data":[{"index":0,"embedding":[1,0,0,0]}],"usage":{"total_tokens":1}}"""
        val bytes = answer.getBytes(UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    def calls: Vector[Call] = seen.asScala.toVector
    def close(): Unit = server.stop(0)

  /** The environment of a deployment with both external providers; no Bedrock model ID. */
  def external(url: String): Map[String, String] =
    val indexes = Vector("RESEARCHERS", "COMMUNITIES", "ABSTRACTS", "PATHS", "TOPIC_INSTITUTIONS")
    LiveFixtures.env ++
      indexes.map(i => s"SCHOLARCHAIN_VECTOR_INDEX_${i}_DIMENSION" -> Dimension.toString) ++
      Map(
        "SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT" -> "5",
        "SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET" -> "1000",
        "SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION" -> "fusion-test",
        "SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS" -> "5000",
        "SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES" -> "0",
        "SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS" -> "1",
        "SCHOLARCHAIN_LLM_PROVIDER" -> "anthropic",
        "SCHOLARCHAIN_ANTHROPIC_MODEL_ID" -> "claude-test",
        "SCHOLARCHAIN_ANTHROPIC_BASE_URL" -> url,
        "SCHOLARCHAIN_ANTHROPIC_VERSION" -> "2023-06-01",
        "SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS" -> "2000",
        "SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS" -> "2000",
        "SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES" -> "1",
        "SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "voyage",
        "SCHOLARCHAIN_VOYAGE_MODEL_ID" -> "voyage-test",
        "SCHOLARCHAIN_VOYAGE_BASE_URL" -> url,
        "SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION" -> Dimension.toString,
        "SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS" -> "2000",
        "SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS" -> "2000",
        "SCHOLARCHAIN_VOYAGE_MAX_RETRIES" -> "0",
        "EMBEDDING_MODEL_ID" -> "voyage-test",
        "EMBEDDING_MODEL_VERSION" -> "1",
        "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "true"
      )

  def bedrock: Map[String, String] =
    external("http://127.0.0.1:1") ++ Map(
      "SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock",
      "SCHOLARCHAIN_EMBEDDING_PROVIDER" -> "bedrock",
      "SYNTHESIS_MODEL_ID" -> "test-synthesis",
      "VERIFICATION_MODEL_ID" -> "test-verification"
    )

  def core(env: Map[String, String]): QueryCoreConfig =
    CoreConfigs.query(env).fold(e => throw new IllegalStateException(e.message), identity)

  val request: LlmRequest = LlmRequest("test-prompt", "system", "{}", 16)

class ProviderConfigWiringSpec extends AnyFunSuite with Matchers:
  import ProviderFx.*

  test("the query API configuration needs no Bedrock model ID under the external providers") {
    val c = core(external("http://127.0.0.1:1"))
    c.models.synthesisModelId shouldBe "claude-test"
    c.models.verificationModelId shouldBe "claude-test"
    c.models.embeddingModelId shouldBe "voyage-test"
  }

  test("Bedrock stays configurable, and then needs its model IDs") {
    core(bedrock).models.synthesisModelId shouldBe "test-synthesis"
    val missing = CoreConfigs.query(bedrock - "SYNTHESIS_MODEL_ID")
    missing.left.toOption.get.message should include("SYNTHESIS_MODEL_ID")
  }

  test("a provider choice is required, and the Voyage model must match EMBEDDING_MODEL_ID") {
    val env = external("http://127.0.0.1:1")
    CoreConfigs.query(env - "SCHOLARCHAIN_LLM_PROVIDER").left.toOption.get.message should include(
      "SCHOLARCHAIN_LLM_PROVIDER"
    )
    CoreConfigs
      .query(env.updated("EMBEDDING_MODEL_ID", "another-model"))
      .left
      .toOption
      .get
      .message should include("embeddingModelId")
  }

  test("the worker's model configuration needs no LLM provider") {
    val env = external("http://127.0.0.1:1")
      .filterNot((k, _) => k.contains("ANTHROPIC") || k == "SCHOLARCHAIN_LLM_PROVIDER")
    CoreConfigs.embedding(env).map(_.activeModelId) shouldBe Right("voyage-test")
  }

  test("the secret names come from configuration and have no default") {
    val none = EntrypointSettings.providerKeys().toOption.get
    none.anthropicSecretName shouldBe None
    none.voyageSecretName shouldBe None
    val named = EntrypointSettings
      .providerKeys(
        ConfigFactory.parseString(
          """scholarchain.services.providerKeys { anthropicSecretName = "a/b",
            |voyageSecretName = "c/d" }""".stripMargin
        )
      )
      .toOption
      .get
    named.anthropicSecretName shouldBe Some("a/b")
    named.voyageSecretName shouldBe Some("c/d")
  }

class QueryModelsSpec extends ApiSuite:
  import ProviderFx.*

  private def open(
      env: Map[String, String],
      secrets: SecretStringSource,
      keys: ProviderKeySettings = settings,
      clock: FakeClock = new FakeClock
  ): Either[String, QueryModels] =
    QueryModels.open(env, core(env), llmSettings, keys, () => Right(secrets), clock)

  test("keys read by secret name reach each provider, and are cached between requests") {
    val provider = new Provider
    val secrets = full
    try
      val models = open(external(provider.url), secrets).fold(m => fail(m), identity)
      models.llm.adapter.complete(request).map(_.text) shouldBe Right("{}")
      models.llm.adapter.complete(request).isRight shouldBe true
      models.embedder.adapter.embed(Seq("a question")).size shouldBe 1
      val messages = provider.calls.filter(_.path == "/v1/messages")
      messages.map(_.headers("x-api-key")).distinct shouldBe Vector(AnthropicValue)
      val embeddings = provider.calls.filter(_.path == "/v1/embeddings")
      embeddings.map(_.headers("authorization")) shouldBe Vector(s"Bearer $VoyageValue")
      // the query API embeds queries, not documents
      ujson.read(embeddings.head.body)("input_type").str shouldBe "query"
      secrets.reads shouldBe Vector(AnthropicName, VoyageName)
      models.probes.map(p => p.name -> p.check()) shouldBe
        Vector("anthropicApiKey" -> true, "voyageApiKey" -> true)
    finally provider.close()
  }

  test("the worker's embedder embeds documents") {
    val provider = new Provider
    try
      val env = external(provider.url)
      val model = ProviderWiring
        .embedder(
          CoreConfigs.embedding(env).toOption.get,
          env,
          EmbeddingPurpose.Ingest,
          settings.copy(anthropicSecretName = None),
          () => Right(full),
          new FakeClock,
          RetryDelays(0, 0),
          "eu-west-1"
        )
        .fold(m => fail(m), identity)
      model.adapter.embed(Seq("a document"))
      ujson.read(provider.calls.head.body)("input_type").str shouldBe "document"
    finally provider.close()
  }

  test("Anthropic attempts come from its core configuration, not from the Bedrock retry settings") {
    val provider = new Provider
    try
      provider.status = 529
      val models = open(external(provider.url), full).fold(m => fail(m), identity)
      models.llm.adapter.complete(request).left.toOption.get shouldBe a[LlmFailure.Overloaded]
      // llm.anthropic.maxRetries = 1; the services' llm.maxAttempts = 5 does not apply
      provider.calls.size shouldBe 2
    finally provider.close()
  }

  test("Bedrock needs no key secret: Secrets Manager is never asked and no check is added") {
    val none = ProviderKeySettings(None, None, 300, 10, 1000L)
    val env = bedrock
    val models = QueryModels
      .open(
        env,
        core(env),
        llmSettings,
        none,
        () => fail("Secrets Manager must not be used"),
        new FakeClock
      )
      .fold(m => fail(m), identity)
    models.probes shouldBe empty
    models.llm.key shouldBe None
    models.embedder.key shouldBe None
  }

  test("only the selected provider's secret is required") {
    val env = external("http://127.0.0.1:1") ++ Map("SCHOLARCHAIN_LLM_PROVIDER" -> "bedrock") ++
      Map("SYNTHESIS_MODEL_ID" -> "s", "VERIFICATION_MODEL_ID" -> "v")
    val secrets = full
    val models = open(env, secrets, settings.copy(anthropicSecretName = None))
      .fold(m => fail(m), identity)
    models.probes.map(_.name) shouldBe Vector("voyageApiKey")
    secrets.reads shouldBe Vector(VoyageName)
  }

  test("a secret name that is not set stops start-up, and key variables do not stand in for it") {
    val env = external("http://127.0.0.1:1") ++
      Map("ANTHROPIC_API_KEY" -> "ignored-value", "VOYAGE_API_KEY" -> "ignored-value")
    val a = open(env, full, settings.copy(anthropicSecretName = None)).left.toOption.get
    a should include("SCHOLARCHAIN_SVC_ANTHROPIC_KEY_SECRET_NAME")
    a should not include "ignored-value"
    val v = open(env, full, settings.copy(voyageSecretName = None)).left.toOption.get
    v should include("SCHOLARCHAIN_SVC_VOYAGE_KEY_SECRET_NAME")
  }

  test("/readyz fails by check name while a key secret is missing, and recovers without restart") {
    val secrets = new Secrets()
    val clock = new FakeClock
    val models = open(external("http://127.0.0.1:1"), secrets, clock = clock)
      .fold(m => fail(m), identity)
    val dynamo = DynamoPorts(new InMemoryItemStore, Fx.tables, 86400L)
    val running = QueryApiRoot.start(
      QueryApiInputs(
        Fx.settings,
        QueryApiSettings(EntrypointFx.loopback, EntrypointFx.llm, EntrypointFx.workflow),
        Fx.limits,
        QueryApiPorts(
          new FakeEngine(dynamo),
          dynamo.querySessions,
          dynamo.evidence,
          dynamo.queryAudit,
          new InMemoryJobStore,
          models.probes
        ),
        EntrypointFx.auth
      )
    )
    try
      val down = Wire.send(running.port, "GET", "/readyz")
      down.status shouldBe 503
      val example = ujson.read(
        Source
          .fromResource("api-examples/error-503-provider-key.json")
          .mkString
      )
      val error = down.json("error")
      Seq("code", "status", "message", "retryable", "reason", "details").foreach { field =>
        withClue(field)(error(field) shouldBe example("error")(field))
      }
      down.body should not include AnthropicName
      Wire.send(running.port, "GET", "/healthz").status shouldBe 200

      secrets.values = full.values
      clock.advance(10_000)
      val up = Wire.send(running.port, "GET", "/readyz")
      up.status shouldBe 200
      up.body should not include AnthropicValue
      up.body should not include VoyageValue
    finally running.stop()
  }

class ProviderKeySpec extends AnyFunSuite with Matchers:
  final private class Rig(var next: Either[String, String]):
    val clock = new FakeClock
    var reads = 0
    val key = new ProviderKey(
      "voyageApiKey",
      "SOME_VARIABLE",
      () =>
        reads += 1
        next
      ,
      60_000,
      5_000,
      clock
    )

  test("a key is read once, reused, and re-read after the refresh interval (rotation)") {
    val rig = new Rig(Right("  first-key\n"))
    rig.key.apiKey() shouldBe "first-key"
    rig.next = Right("second-key")
    rig.clock.advance(59_999)
    rig.key.apiKey() shouldBe "first-key"
    rig.reads shouldBe 1
    rig.clock.advance(1)
    rig.key.apiKey() shouldBe "second-key"
    rig.reads shouldBe 2
  }

  test("a failed first read is retried after the retry interval, never cached for good") {
    val rig = new Rig(Left("down"))
    rig.key.apiKey() shouldBe ""
    rig.key.available shouldBe false
    rig.reads shouldBe 1
    rig.next = Right("the-key")
    rig.clock.advance(4_999)
    rig.key.available shouldBe false
    rig.clock.advance(1)
    rig.key.available shouldBe true
    rig.reads shouldBe 2
  }

  test("a failed refresh keeps the held key and tries again after the retry interval") {
    val rig = new Rig(Right("held-key"))
    rig.key.apiKey() shouldBe "held-key"
    rig.next = Left("throttled")
    rig.clock.advance(60_000)
    rig.key.apiKey() shouldBe "held-key"
    rig.reads shouldBe 2
    rig.next = Right("new-key")
    rig.clock.advance(5_000)
    rig.key.apiKey() shouldBe "new-key"
  }

  test("a read that throws is a failed read") {
    var broken = true
    val clock = new FakeClock
    val key = new ProviderKey(
      "voyageApiKey",
      "SOME_VARIABLE",
      () => if broken then throw new RuntimeException("boom") else Right("k"),
      60_000,
      5_000,
      clock
    )
    key.apiKey() shouldBe ""
    broken = false
    clock.advance(5_000)
    key.apiKey() shouldBe "k"
  }

  test("the secret value is the plain key: empty, JSON and spaced text are refused") {
    ProviderKey.parse(" plain-key_123 \n") shouldBe Right("plain-key_123")
    Seq("", "   ", """{"apiKey":"k"}""", """["k"]""", "\"k\"", "two words", "kéy").foreach { bad =>
      val out = ProviderKey.parse(bad)
      withClue(bad)(out.isLeft shouldBe true)
      if bad.trim.nonEmpty then out.left.toOption.get should not include bad
    }
  }

  test("a Secrets Manager failure is reported as fixed text, without the SDK message") {
    def source(answer: => GetSecretValueResponse) =
      new SecretsManagerSecretStrings(new SecretsManagerClient:
        def serviceName(): String = "secretsmanager"
        def close(): Unit = ()
        override def getSecretValue(r: GetSecretValueRequest): GetSecretValueResponse = answer)
    source(
      GetSecretValueResponse.builder().secretString("the-key").build()
    ).read("n") shouldBe Right("the-key")
    source(throw ResourceNotFoundException.builder().message("detail-x").build())
      .read("n") shouldBe Left("the secret or its value does not exist")
    source(throw new RuntimeException("detail-y")).read("n") shouldBe Left(
      "the secret could not be read"
    )
    source(GetSecretValueResponse.builder().build()).read("n") shouldBe Left(
      "the secret has no string value"
    )
  }

class EmbeddingWorkerKeySpec extends ApiSuite:
  import ProviderFx.*

  test(
    "with the key secret missing the worker is not ready and takes no message; then it recovers"
  ) {
    val secrets = new Secrets()
    val keyClock = new FakeClock
    val env = external("http://127.0.0.1:1")
    val model = ProviderWiring
      .embedder(
        CoreConfigs.embedding(env).toOption.get,
        env,
        EmbeddingPurpose.Ingest,
        settings,
        () => Right(secrets),
        keyClock,
        RetryDelays(0, 0),
        "eu-west-1"
      )
      .fold(m => fail(m), identity)
    val queue = new InMemoryWorkQueue
    val deadLetter = new InMemoryWorkQueue
    val worker = EntrypointFx.worker()
    val consumer = new WorkConsumer(
      queue,
      deadLetter,
      EmbeddingWorkItem.decode,
      (_: EmbeddingWorkItem) => Right(()),
      worker.consumer,
      new InMemoryRejectSink
    )
    queue.send(EmbeddingWorkItem.encode(EmbeddingWorkItem.of("run:1", "2026-11-01"))): Unit
    val extra: Vector[ReadinessProbe] = model.probes
    val running = EmbeddingWorkerRoot.start(
      EmbeddingWorkerInputs(
        Fx.settings,
        worker,
        ProviderWiring.gated(() => consumer.runOnce(), model.key),
        extra,
        RuntimeEnvironment(clock = new FakeClock)
      )
    )
    def ready = Wire.send(running.healthPort, "GET", "/readyz")
    try
      eventually(
        ready.status == 503 &&
          ready.json("error")("details")("checks")("consumer").str == "failing"
      )
      ready.json("error")("details")("checks")("voyageApiKey").str shouldBe "failing"
      Wire.send(running.healthPort, "GET", "/healthz").status shouldBe 200
      queue.size shouldBe 1
      deadLetter.size shouldBe 0

      secrets.values = full.values
      keyClock.advance(10_000)
      eventually(queue.size == 0)
      eventually(ready.status == 200)
      deadLetter.size shouldBe 0
    finally running.shutdown(): Unit
  }
