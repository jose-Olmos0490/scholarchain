package scholarchain.adversarial.provider

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.{EmbeddingProviderConfig, LlmProviderConfig, VoyageConfig}
import scholarchain.rag.embed.*
import scholarchain.rag.llm.{Prompt as _, *}
import scholarchain.rag.provider.{BedrockLlmSettings, EmbeddingPurpose, ProviderFailureKind}
import scholarchain.services.config.ProviderKeySettings
import scholarchain.services.entrypoint.ProviderWiring
import scholarchain.services.http.ServiceClock
import scholarchain.services.security.*
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.*
import ProviderAdvKit.*

/** Secret-backed provider keys: a stub Secrets Manager client (nothing reaches AWS), the logback
  * output, and the wiring into the real adapters over a loopback stub.
  */
class ProviderKeysAdvSpec extends AnyFunSuite with Matchers:

  final private class Clock extends ServiceClock:
    val at = new AtomicLong(1000L)
    def nowMillis: Long = at.get

  final private class Secrets(answer: AtomicReference[() => GetSecretValueResponse])
      extends SecretsManagerClient:
    val reads = new AtomicInteger
    def serviceName(): String = "secretsmanager"
    def close(): Unit = ()
    override def getSecretValue(r: GetSecretValueRequest): GetSecretValueResponse =
      reads.incrementAndGet()
      answer.get()()

  private def value(s: String): () => GetSecretValueResponse =
    () => GetSecretValueResponse.builder().secretString(s).build()

  private def throwing(e: => RuntimeException): () => GetSecretValueResponse = () => throw e

  private def secrets(first: () => GetSecretValueResponse) =
    val answer = new AtomicReference(first)
    (new Secrets(answer), answer)

  private def providerKey(client: Secrets, clock: Clock) =
    val source = new SecretsManagerSecretStrings(client)
    new ProviderKey(
      "advKey",
      "ADV_SECRET_NAME_VARIABLE",
      () => source.read("adv/secret-name"),
      60000L,
      1000L,
      clock
    )

  /** Everything logged on `scholarchain.services` while `body` runs, throwables included. */
  private def captured[A](body: => A): (A, Vector[String]) =
    val loggerCls =
      try Class.forName("ch.qos.logback.classic.Logger")
      catch case _: ClassNotFoundException => cancel("logback is not on the test classpath")
    val listCls = Class.forName("ch.qos.logback.core.read.ListAppender")
    val ctxCls = Class.forName("ch.qos.logback.core.Context")
    val appCls = Class.forName("ch.qos.logback.core.Appender")
    val eventCls = Class.forName("ch.qos.logback.classic.spi.ILoggingEvent")
    val proxyCls = Class.forName("ch.qos.logback.classic.spi.IThrowableProxy")
    val levelCls = Class.forName("ch.qos.logback.classic.Level")
    val app = listCls.getDeclaredConstructor().newInstance()
    listCls.getMethod("setContext", ctxCls).invoke(app, org.slf4j.LoggerFactory.getILoggerFactory)
    listCls.getMethod("start").invoke(app)
    val target = org.slf4j.LoggerFactory.getLogger("scholarchain.services")
    val oldLevel = loggerCls.getMethod("getLevel").invoke(target)
    loggerCls.getMethod("setLevel", levelCls).invoke(target, levelCls.getField("ALL").get(null))
    loggerCls.getMethod("addAppender", appCls).invoke(target, app)
    try
      val result = body
      val list = listCls.getField("list").get(app).asInstanceOf[java.util.List[AnyRef]]
      val snapshot = list.synchronized(new java.util.ArrayList[AnyRef](list)).asScala.toVector
      val lines = snapshot.map { event =>
        val message = eventCls.getMethod("getFormattedMessage").invoke(event)
        val proxy = Option(eventCls.getMethod("getThrowableProxy").invoke(event))
        val thrown = proxy.fold("")(p => String.valueOf(proxyCls.getMethod("getMessage").invoke(p)))
        s"$message $thrown"
      }
      (result, lines)
    finally
      loggerCls.getMethod("detachAppender", appCls).invoke(target, app)
      loggerCls.getMethod("setLevel", levelCls).invoke(target, oldLevel)

  test("a secret that is not a plain key is refused and its content is never logged") {
    val k = AnthropicKey
    val bad = Seq(
      s"""{"apiKey":"$k"}""",
      s"""["$k"]""",
      s""""$k"""",
      s"$k\nsecond-line",
      s"$k\r\nx-injected: 1",
      s"key $k",
      k + "\u00e9",
      k + "\u0000x",
      "",
      "  \n "
    )
    for secret <- bad do
      val (client, _) = secrets(value(secret))
      val (key, lines) = captured {
        val key = providerKey(client, new Clock)
        key.apiKey()
        key
      }
      withClue(s"secret='${secret.replace(Canary, "C").take(30)}': ") {
        key.available shouldBe false
        key.apiKey() shouldBe ""
        lines should not be empty
        lines.mkString should not include Canary
        lines.mkString should not include "adv/secret-name"
      }
  }

  test("an SDK failure that quotes the key or the secret name is reduced to fixed text") {
    val failures: Seq[() => GetSecretValueResponse] = Seq(
      throwing(new RuntimeException(s"denied for $AnthropicKey on adv/secret-name")),
      throwing(
        ResourceNotFoundException.builder().message(s"adv/secret-name $AnthropicKey").build()
      ),
      throwing(DecryptionFailureException.builder().message(AnthropicKey).build()),
      () =>
        GetSecretValueResponse
          .builder()
          .secretBinary(software.amazon.awssdk.core.SdkBytes.fromUtf8String(AnthropicKey))
          .build()
    )
    for failure <- failures do
      val (client, _) = secrets(failure)
      new SecretsManagerSecretStrings(client).read("adv/secret-name") match
        case Left(reason) =>
          reason should not include Canary
          reason should not include "adv/secret-name"
        case Right(_) => fail("a failed read returned a value")
      val (key, lines) = captured {
        val key = providerKey(client, new Clock)
        key.apiKey() shouldBe ""
        key
      }
      key.available shouldBe false
      lines should not be empty
      lines.mkString should not include Canary
      lines.mkString should not include "adv/secret-name"
  }

  test("a failed first read is retried after the retry delay, not before, and not for ever") {
    val clock = new Clock
    val (client, answer) = secrets(throwing(new RuntimeException("down")))
    val key = providerKey(client, clock)
    key.apiKey() shouldBe ""
    key.apiKey() shouldBe ""
    client.reads.get shouldBe 1
    answer.set(value(s" $AnthropicKey\n"))
    clock.at.addAndGet(999)
    key.apiKey() shouldBe ""
    clock.at.addAndGet(1)
    key.apiKey() shouldBe AnthropicKey
    client.reads.get shouldBe 2
  }

  test("a rotation is picked up; a broken rotation keeps the held key and logs no value") {
    val clock = new Clock
    val (client, answer) = secrets(value(AnthropicKey))
    val key = providerKey(client, clock)
    key.apiKey() shouldBe AnthropicKey
    for broken <- Seq(
        throwing(new RuntimeException(AnthropicKey)),
        value(s"""{"k":"$VoyageKey"}"""),
        value("")
      )
    do
      answer.set(broken)
      clock.at.addAndGet(60000)
      val (held, lines) = captured(key.apiKey())
      held shouldBe AnthropicKey
      key.available shouldBe true
      lines.mkString should not include Canary
    answer.set(value("rotated-key"))
    clock.at.addAndGet(60000)
    key.apiKey() shouldBe "rotated-key"
  }

  test("callers during the first read wait for it instead of failing beside it") {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val (client, _) = secrets { () =>
      started.countDown()
      release.await(10, TimeUnit.SECONDS)
      value(AnthropicKey)()
    }
    val key = providerKey(client, new Clock)
    val results = new java.util.concurrent.ConcurrentLinkedQueue[String]
    val threads = Vector.fill(8)(new Thread(() => { results.add(key.apiKey()); () }))
    threads.head.start()
    started.await(5, TimeUnit.SECONDS) shouldBe true
    threads.tail.foreach(_.start())
    Thread.sleep(100)
    release.countDown()
    threads.foreach(_.join(5000))
    results.asScala.toVector shouldBe Vector.fill(8)(AnthropicKey)
    client.reads.get shouldBe 1
  }

  private val settings = ProviderKeySettings(Some("adv/anthropic"), Some("adv/voyage"), 60, 1, 500)

  private def voyageConfig(url: String) =
    EmbeddingProviderConfig.Voyage(VoyageConfig("adv-embed", url, Dim, 2, 2000, 5000, 1))

  private val env = Map(
    "EMBEDDING_MODEL_VERSION" -> "1",
    "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "false"
  )

  private def wiredEmbedder(url: String, source: SecretStringSource, clock: Clock) =
    ProviderWiring.embedder(
      voyageConfig(url),
      env,
      EmbeddingPurpose.Retrieval,
      settings,
      () => Right(source),
      clock,
      Delays,
      fail("Bedrock region must not be read")
    )

  private def wiredLlm(url: String, source: SecretStringSource, clock: Clock) =
    ProviderWiring.llm(
      LlmProviderConfig.Anthropic(anthropicConfig(url, retries = 1)),
      settings,
      () => Right(source),
      clock,
      Delays,
      fail("Bedrock settings must not be read"): BedrockLlmSettings
    )

  test("wired adapters send nothing while the secret is missing, then recover without restart") {
    val clock = new Clock
    @volatile var stored: Either[String, String] = Left("the secret or its value does not exist")
    val source: SecretStringSource = _ => stored
    stub((_, h) => if h.path == "/v1/embeddings" then vectorsFor(h) else okMessage("{}")) { s =>
      val llm = wiredLlm(s.url, source, clock).toOption.get
      val embed = wiredEmbedder(s.url, source, clock).toOption.get
      llm.adapter.complete(llmRequest).left.toOption.get shouldBe a[LlmFailure.Permanent]
      val e = the[EmbeddingProviderException] thrownBy embed.adapter.embed(Seq("a"))
      e.kind shouldBe ProviderFailureKind.Permanent
      s.requests shouldBe empty
      (llm.key ++ embed.key).map(_.available) shouldBe Seq(false, false)

      stored = Right(AnthropicKey)
      clock.at.addAndGet(1000)
      llm.adapter.complete(llmRequest).isRight shouldBe true
      embed.adapter.embed(Seq("a")).size shouldBe 1
      (llm.key ++ embed.key).map(_.available) shouldBe Seq(true, true)
      s.requests.size shouldBe 2
    }
  }

  test("each wired adapter reads its own secret name and sends only its own key") {
    val source: SecretStringSource = {
      case "adv/anthropic" => Right(AnthropicKey)
      case "adv/voyage" => Right(VoyageKey)
      case _ => Left("unexpected name")
    }
    stub((_, h) => if h.path == "/v1/embeddings" then vectorsFor(h) else okMessage("{}")) { s =>
      wiredLlm(s.url, source, new Clock).toOption.get.adapter.complete(llmRequest)
      wiredEmbedder(s.url, source, new Clock).toOption.get.adapter.embed(Seq("a"))
      val Seq(llm, embedding) = s.requests: @unchecked
      llm.headers("x-api-key") shouldBe AnthropicKey
      llm.headers.get("authorization") shouldBe None
      llm.headers.values.mkString should not include VoyageKey
      embedding.headers("authorization") shouldBe s"Bearer $VoyageKey"
      embedding.headers.get("x-api-key") shouldBe None
      embedding.headers.values.mkString should not include AnthropicKey
    }
  }

  test("a selected provider without a secret name stops start-up and names the variable") {
    val none = settings.copy(anthropicSecretName = None, voyageSecretName = None)
    val llm = ProviderWiring.llm(
      LlmProviderConfig.Anthropic(anthropicConfig("http://127.0.0.1:1")),
      none,
      () => fail("secrets must not be opened"),
      new Clock,
      Delays,
      fail("unused"): BedrockLlmSettings
    )
    llm.left.toOption.get should include(ProviderWiring.AnthropicSecretNameVariable)
    val embed = ProviderWiring.embedder(
      voyageConfig("http://127.0.0.1:1"),
      env,
      EmbeddingPurpose.Ingest,
      none,
      () => fail("secrets must not be opened"),
      new Clock,
      Delays,
      "unused"
    )
    embed.left.toOption.get should include(ProviderWiring.VoyageSecretNameVariable)
  }
