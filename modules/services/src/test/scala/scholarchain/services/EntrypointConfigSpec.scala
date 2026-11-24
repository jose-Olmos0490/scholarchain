package scholarchain.services

import com.typesafe.config.ConfigFactory
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.services.config.*
import scholarchain.services.entrypoint.*
import scholarchain.services.http.ServiceClock
import scholarchain.services.security.{
  ApiKeyRecord,
  ApiKeySource,
  Scopes,
  Sha256Hex,
  StaticApiKeySource
}

class EntrypointSettingsSpec extends AnyFunSuite with Matchers:
  private def conf(s: String) = ConfigFactory.parseString(s)

  private val workflowOk = conf(
    """scholarchain.services.workflow.promptsDir = "/opt/prompts""""
  )
  private val queuesOk = conf(
    """scholarchain.services.embeddingWorker { queueUrl = "q-1", deadLetterQueueUrl = "q-2" }"""
  )

  test("the query API's settings load once the deployment names the prompts directory") {
    val s = EntrypointSettings.queryApi(workflowOk).fold(p => fail(p.mkString("; ")), identity)
    s.bind shouldBe BindSettings("0.0.0.0", 8080)
    s.llm shouldBe LlmSettings(3, 200, 2000, 20000)
    s.workflow.promptsDir shouldBe "/opt/prompts"
    s.workflow.defaultWindow shouldBe None
    s.workflow.synthesisPromptVersion shouldBe "answer-synthesis-prompt-v3"
  }

  test("a missing prompts directory is reported by key, never defaulted") {
    EntrypointSettings
      .queryApi()
      .left
      .toOption
      .get
      .exists(_.contains("workflow.promptsDir")) shouldBe true
  }

  test("ports come from configuration, and a bad value is reported by key") {
    val ok = EntrypointSettings
      .queryApi(workflowOk.withFallback(conf("scholarchain.services.queryApi.bind.port = 9090")))
      .toOption
      .get
    ok.bind.port shouldBe 9090
    val bad = EntrypointSettings.queryApi(
      workflowOk.withFallback(conf("scholarchain.services.queryApi.bind.port = 70000"))
    )
    bad.left.toOption.get.exists(_.contains("queryApi.bind.port")) shouldBe true
    val llm = EntrypointSettings.queryApi(
      workflowOk.withFallback(
        conf("scholarchain.services.llm { baseDelayMs = 500, maxDelayMs = 100 }")
      )
    )
    llm.left.toOption.get.exists(_.contains("llm.maxDelayMs")) shouldBe true
  }

  test("a default window needs both years") {
    val half = EntrypointSettings.queryApi(
      workflowOk.withFallback(conf("scholarchain.services.workflow.defaultWindowStartYear = 2010"))
    )
    half.isLeft shouldBe true
    val both = EntrypointSettings.queryApi(
      workflowOk.withFallback(
        conf(
          "scholarchain.services.workflow { defaultWindowStartYear = 2010, defaultWindowEndYear = 2013 }"
        )
      )
    )
    both.toOption.get.workflow.defaultWindow shouldBe Some((2010, 2013))
  }

  test("the graph API needs neither a run ID nor a prompts directory nor a queue") {
    EntrypointSettings.graphEvidenceApi().toOption.get.bind shouldBe BindSettings("0.0.0.0", 8081)
    ServiceSettings.load(requireServing = false).isRight shouldBe true
    ServiceSettings.load().isLeft shouldBe true
  }

  test("the worker's settings need both queue URLs, which are never defaulted") {
    val missing = EntrypointSettings.embeddingWorker().left.toOption.get
    missing.exists(_.contains("embeddingWorker.queueUrl")) shouldBe true
    missing.exists(_.contains("embeddingWorker.deadLetterQueueUrl")) shouldBe true
    val s = EntrypointSettings.embeddingWorker(queuesOk).fold(p => fail(p.mkString("; ")), identity)
    s.health shouldBe BindSettings("0.0.0.0", 8082)
    s.consumer.batchSize shouldBe 1
    s.consumer.visibilityTimeoutSeconds should be > s.maxIngestRunSeconds
    s.pollStalenessSeconds should be > s.consumer.waitSeconds + s.maxIngestRunSeconds
  }

  test("a visibility timeout that does not exceed the longest ingest run is refused") {
    val bad = EntrypointSettings.embeddingWorker(
      queuesOk.withFallback(
        conf(
          "scholarchain.services.embeddingWorker { maxIngestRunSeconds = 900, consumer.visibilityTimeoutSeconds = 900 }"
        )
      )
    )
    bad.left.toOption.get.exists(_.contains("visibilityTimeoutSeconds must exceed")) shouldBe true
  }

  test(
    "a staleness bound shorter than one poll plus one run, or one queue used twice, is refused"
  ) {
    val stale = EntrypointSettings.embeddingWorker(
      queuesOk.withFallback(
        conf("scholarchain.services.embeddingWorker.pollStalenessSeconds = 100")
      )
    )
    stale.left.toOption.get.exists(_.contains("pollStalenessSeconds must exceed")) shouldBe true
    val same = EntrypointSettings.embeddingWorker(
      conf("""scholarchain.services.embeddingWorker { queueUrl = "q", deadLetterQueueUrl = "q" }""")
    )
    same.left.toOption.get.exists(_.contains("deadLetterQueueUrl must differ")) shouldBe true
  }

  test("a live entrypoint with no configuration stops with a list of keys, not a stack trace") {
    val q = QueryApiMain.open(Map.empty)
    q.isLeft shouldBe true
    q.left.toOption.get.foreach(_.length should be < 400)
    GraphEvidenceApiMain.open(Map.empty).isLeft shouldBe true
    EmbeddingWorkerMain.open(Map.empty).isLeft shouldBe true
  }

class RefreshingApiKeysSpec extends AnyFunSuite with Matchers:
  private def key(id: String, token: String) =
    ApiKeyRecord(id, Sha256Hex.of(token), Set(Scopes.GraphRead))

  final private class Source(var next: Either[String, Vector[ApiKeyRecord]]) extends ApiKeySource:
    val loads = new AtomicInteger(0)
    def load(): Either[String, Vector[ApiKeyRecord]] =
      loads.incrementAndGet()
      next

  test("start-up fails closed when the source holds no keys or cannot be read") {
    val clock = new FakeClock
    RefreshingApiKeys.load(new StaticApiKeySource(Vector.empty), 60, clock).isLeft shouldBe true
    RefreshingApiKeys.load(new Source(Left("unreadable")), 60, clock).isLeft shouldBe true
  }

  test("a rotation is picked up after the refresh interval, and not before") {
    val clock = new FakeClock
    val src = new Source(Right(Vector(key("old", "t-old"))))
    val keys = RefreshingApiKeys.load(src, 60, clock).toOption.get
    src.next = Right(Vector(key("new", "t-new")))
    keys.current.map(_.id) shouldBe Vector("old")
    clock.advance(59_000)
    keys.current.map(_.id) shouldBe Vector("old")
    src.loads.get() shouldBe 1
    clock.advance(1_000)
    keys.current.map(_.id) shouldBe Vector("new")
    src.loads.get() shouldBe 2
    keys.current
    src.loads.get() shouldBe 2 // one refresh per interval
  }

  test("a failed refresh keeps the keys in force, and an empty one is not accepted") {
    val clock = new FakeClock
    val src = new Source(Right(Vector(key("old", "t-old"))))
    val keys = RefreshingApiKeys.load(src, 60, clock).toOption.get
    src.next = Left("secrets manager down")
    clock.advance(61_000)
    keys.current.map(_.id) shouldBe Vector("old")
    src.next = Right(Vector.empty)
    clock.advance(61_000)
    keys.current.map(_.id) shouldBe Vector("old")
    keys.loaded shouldBe true
  }
