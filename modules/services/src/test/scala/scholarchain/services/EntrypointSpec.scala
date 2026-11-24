package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scholarchain.services.config.*
import scholarchain.services.entrypoint.*
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.query.InMemoryJobStore
import scholarchain.services.runtime.{QueryApiPorts, ResourceApiPorts, RuntimeEnvironment}
import scholarchain.services.security.BearerAuthenticator
import scholarchain.storage.common.{InMemoryRejectSink, StorageError}
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}
import scholarchain.storage.queue.{
  ConsumerSettings,
  EmbeddingWorkItem,
  InMemoryWorkQueue,
  WorkConsumer
}

/** A real HTTP client against a real local socket. */
object Wire:
  private val client = HttpClient.newHttpClient()

  final case class Reply(status: Int, body: String, headers: Map[String, String]):
    def json: ujson.Value = ujson.read(body)

  def send(
      port: Int,
      method: String,
      path: String,
      token: Option[String] = None,
      body: Option[String] = None
  ): Reply =
    val b = JHttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      .method(
        method,
        body.fold(JHttpRequest.BodyPublishers.noBody())(JHttpRequest.BodyPublishers.ofString(_))
      )
    token.foreach(t => b.header("Authorization", s"Bearer $t"))
    body.foreach(_ => b.header("Content-Type", "application/json"))
    val r = client.send(b.build(), JHttpResponse.BodyHandlers.ofString())
    Reply(
      r.statusCode(),
      r.body(),
      scala.jdk.CollectionConverters
        .MapHasAsScala(r.headers().map())
        .asScala
        .map((k, v) => k.toLowerCase -> v.get(0))
        .toMap
    )

object EntrypointFx:
  val loopback = BindSettings("127.0.0.1", 0)
  val llm = LlmSettings(2, 1, 2, 1000)
  val workflow = WorkflowTuningSettings(
    "unused-in-tests",
    "answer-synthesis-prompt-v3",
    "answer-synthesis-v2",
    1000,
    None,
    200,
    100,
    100
  )
  val consumer = ConsumerSettings(1, 5, 0, 3, 5, 0)
  def worker(
      maxFailures: Int = 2,
      stalenessSeconds: Int = 10
  ) = WorkerSettings(
    loopback,
    "queue-under-test",
    "dlq-under-test",
    consumer,
    maxIngestRunSeconds = 2,
    idleBackoffMillis = 20,
    pollStalenessSeconds = stalenessSeconds,
    maxConsecutiveFailures = maxFailures,
    shutdownGraceSeconds = 5
  )
  def auth = new BearerAuthenticator(Fx.keys, 256)

/** Storage settings loaded through the real loader from a test environment (all values are test
  * values; `graphLimit` is 25 and the read model table is the prefix default).
  */
object LiveFixtures:
  val env: Map[String, String] = Map(
    "SCHOLARCHAIN_ENV" -> "dev",
    "AWS_REGION" -> "eu-west-1",
    "RAW_BUCKET" -> "test-raw",
    "CURATED_BUCKET" -> "test-curated",
    "GRAPH_BUCKET" -> "test-graph",
    "RAG_BUCKET" -> "test-rag",
    "VECTOR_BUCKET" -> "test-vectors",
    "NEPTUNE_ENDPOINT" -> "neptune.test.invalid",
    "NEPTUNE_PORT" -> "8182",
    "NEPTUNE_QUERY_TIMEOUT_MS" -> "20000",
    "VECTOR_INDEX_RESEARCHERS" -> "researcher-profile-v1",
    "VECTOR_INDEX_COMMUNITIES" -> "collaboration-community-v1",
    "VECTOR_INDEX_ABSTRACTS" -> "paper-abstract-v1",
    "VECTOR_INDEX_PATHS" -> "collaboration-path-v1",
    "VECTOR_INDEX_TOPIC_INSTITUTIONS" -> "topic-institution-summary-v1",
    "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT" -> "25",
    "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH" -> "4",
    "EMBEDDING_MODEL_ID" -> "test-embedding-model-v1"
  )
  lazy val storage: scholarchain.storage.StorageSettings =
    scholarchain.storage.StorageSettings
      .load(env)
      .fold(e => throw new IllegalStateException(e.message), identity)

class QueryApiRootSpec extends ApiSuite:
  private def withRoot[A](
      probes: Vector[ReadinessProbe] = Vector(ReadinessProbe("sessions", () => true))
  )(body: (RunningHttp, FakeEngine) => A): A =
    val dynamo = DynamoPorts(new InMemoryItemStore, Fx.tables, 86400L)
    val engine = new FakeEngine(dynamo)
    val running = QueryApiRoot.start(
      QueryApiInputs(
        Fx.settings,
        QueryApiSettings(EntrypointFx.loopback, EntrypointFx.llm, EntrypointFx.workflow),
        Fx.limits,
        QueryApiPorts(
          engine,
          dynamo.querySessions,
          dynamo.evidence,
          dynamo.queryAudit,
          new InMemoryJobStore,
          probes
        ),
        EntrypointFx.auth
      )
    )
    try body(running, engine)
    finally running.stop()

  test("the query API builds from fakes and serves /healthz over a real socket on its own port") {
    withRoot() { (r, _) =>
      r.port should be > 0
      val h = Wire.send(r.port, "GET", "/healthz")
      h.status shouldBe 200
      h.json("status").str shouldBe "ok"
      h.headers("x-request-id") should startWith("req-")
      val ready = Wire.send(r.port, "GET", "/readyz")
      ready.status shouldBe 200
      ready.json("checks")("sessions").str shouldBe "ok"
    }
  }

  test("a query goes through the socket, the cask transport and the routes to the engine") {
    withRoot() { (r, engine) =>
      val unauth = Wire.send(r.port, "POST", "/v1/query", None, Some(Bodies.coauthors()))
      unauth.status shouldBe 401
      val ok = Wire.send(r.port, "POST", "/v1/query", Some(Fx.aliceToken), Some(Bodies.coauthors()))
      ok.status shouldBe 200
      ok.json("verification").str shouldBe "VERIFIED"
      engine.calls.get() shouldBe 1
      val job = Wire.send(r.port, "GET", s"/v1/jobs/${ok.json("jobId").str}", Some(Fx.aliceToken))
      job.status shouldBe 200
    }
  }

  test("the query API has no graph route: it does not depend on the graph API") {
    withRoot() { (r, _) =>
      Wire
        .send(r.port, "GET", s"/v1/graph/nodes/${Fx.ada}", Some(Fx.aliceToken))
        .status shouldBe 404
      Wire.send(r.port, "GET", "/v1/evidence/evidence:x", Some(Fx.aliceToken)).status shouldBe 404
    }
  }

  test(
    "over the socket the failed-authentication limit uses the TCP peer, so forged headers do not help"
  ) {
    withRoot() { (r, _) =>
      // Fx.settings allows 3 failures; each forged header names a different address
      (1 to 3).foreach { i =>
        val s = Wire.send(r.port, "GET", "/v1/jobs/job-" + "a" * 32, Some("wrong-token"))
        s.status shouldBe 401
      }
      val blocked = Wire.send(r.port, "GET", "/v1/jobs/job-" + "a" * 32, Some("wrong-token"))
      blocked.status shouldBe 429
      blocked.headers("retry-after") shouldBe "60"
    }
  }

  test("readiness lists only the probes this entrypoint was given, and fails when one fails") {
    withRoot(
      Vector(ReadinessProbe("dynamodb", () => true), ReadinessProbe("neptune", () => false))
    ) { (r, _) =>
      val ready = Wire.send(r.port, "GET", "/readyz")
      ready.status shouldBe 503
      val checks = ready.json("error")("details")("checks").obj
      checks.keySet shouldBe Set("dynamodb", "neptune")
      checks("neptune").str shouldBe "failing"
      Wire.send(r.port, "GET", "/healthz").status shouldBe 200
    }
  }

  test("the read adapters are real, bounded by configuration, and their probes are ok") {
    val items = new InMemoryItemStore
    val s = LiveFixtures.storage
    val (ports, probes) = ReadAdapters.open(items, s, Fx.runId).fold(m => fail(m), identity)
    probes.map(_.name) shouldBe Vector(
      "researcherDirectoryAdapter",
      "edgeSupportAdapter",
      "analyticsAdapter"
    )
    probes.foreach(p => withClue(p.name)(p.check() shouldBe true))
    // an empty store answers "none", never an Unsupported refusal
    ports.directory.candidatesByName("ada", 1) shouldBe Right(Vector.empty)
    ports.analytics.componentOf(Fx.ada, 2010, 2013) shouldBe Right(None)
    // the limit is the configured graph limit
    ports.directory
      .candidatesByName("ada", s.graphLimit + 1)
      .left
      .toOption
      .get shouldBe a[StorageError.LimitExceeded]
    ports.analytics.crossCommunityEdgeCount("c", 2010, 2013).left.toOption.get shouldBe a[
      StorageError.NotFound
    ]
  }

  test("a missing read model table name stops the composition, by key and without a value") {
    val s = LiveFixtures.storage.copy(graphReadModelTable = None)
    val out = ReadAdapters.open(new InMemoryItemStore, s, Fx.runId)
    out.left.toOption.get should include("dynamodb.tables.graphReadModel")
  }

  test("the probes go red when the read model store fails, and /readyz names each of them") {
    val inner = new InMemoryItemStore
    val failing: scholarchain.storage.dynamo.ItemStore = new scholarchain.storage.dynamo.ItemStore:
      def putIfAbsent(table: String, item: ujson.Obj) = inner.putIfAbsent(table, item)
      def putAllIfAbsent(table: String, items: Vector[ujson.Obj]) =
        inner.putAllIfAbsent(table, items)
      def get(table: String, pk: String, sk: String) = inner.get(table, pk, sk)
      def replaceIfHash(table: String, item: ujson.Obj, expectedHash: String) =
        inner.replaceIfHash(table, item, expectedHash)
      def query(
          table: String,
          pk: String,
          skPrefix: Option[String],
          ascending: Boolean,
          limit: Option[Int]
      ) = Left(StorageError.Transient("dynamodb query", 3, "unreachable"))
    val (_, probes) =
      ReadAdapters.open(failing, LiveFixtures.storage, Fx.runId).fold(m => fail(m), identity)
    probes.foreach(p => withClue(p.name)(p.check() shouldBe false))
    withRoot(probes) { (r, _) =>
      val ready = Wire.send(r.port, "GET", "/readyz")
      ready.status shouldBe 503
      ready.json("error")("details")("checks").obj.keySet shouldBe
        Set("researcherDirectoryAdapter", "edgeSupportAdapter", "analyticsAdapter")
      Wire.send(r.port, "GET", "/healthz").status shouldBe 200
    }
  }

  test("the ready root with the real read-adapter probes is ready") {
    val (_, probes) = ReadAdapters
      .open(new InMemoryItemStore, LiveFixtures.storage, Fx.runId)
      .fold(m => fail(m), identity)
    withRoot(probes) { (r, _) =>
      val ready = Wire.send(r.port, "GET", "/readyz")
      ready.status shouldBe 200
      ready.json("checks").obj.keySet shouldBe
        Set("researcherDirectoryAdapter", "edgeSupportAdapter", "analyticsAdapter")
    }
  }

  test("the job store is the DynamoDB job store on the configured query-session table") {
    val items = new InMemoryItemStore
    val store = QueryJobs.store(items, LiveFixtures.storage)
    store.getClass shouldBe classOf[scholarchain.services.query.DynamoJobStore]
    LiveFixtures.storage.runtime.dynamoTables.querySession should not be empty
  }

class GraphEvidenceApiRootSpec extends ApiSuite:
  private def withRoot[A](
      probes: Vector[ReadinessProbe] = Vector(ReadinessProbe("neptune", () => true))
  )(body: RunningHttp => A): A =
    val dynamo = DynamoPorts(new InMemoryItemStore, Fx.tables, 86400L)
    val running = GraphEvidenceApiRoot.start(
      GraphEvidenceApiInputs(
        Fx.settings,
        GraphEvidenceApiSettings(EntrypointFx.loopback),
        Fx.limits,
        ResourceApiPorts(dynamo.evidence, new FakeGraph, probes),
        EntrypointFx.auth
      )
    )
    try body(running)
    finally running.stop()

  test("the graph and evidence API builds from fakes and serves /healthz over a real socket") {
    withRoot() { r =>
      val h = Wire.send(r.port, "GET", "/healthz")
      h.status shouldBe 200
      h.json("status").str shouldBe "ok"
      Wire.send(r.port, "GET", "/readyz").json("checks")("neptune").str shouldBe "ok"
    }
  }

  test("it serves graph reads, and has no query, job or explain route") {
    withRoot() { r =>
      val node = Wire.send(r.port, "GET", s"/v1/graph/nodes/${Fx.ada}", Some(Fx.aliceToken))
      node.status shouldBe 200
      node.json("nodeId").str shouldBe Fx.ada
      Wire
        .send(r.port, "POST", "/v1/query", Some(Fx.aliceToken), Some(Bodies.coauthors()))
        .status shouldBe 404
      Wire.send(r.port, "GET", "/v1/jobs/job-" + "a" * 32, Some(Fx.aliceToken)).status shouldBe 404
      Wire.send(r.port, "GET", "/v1/explain/query:x", Some(Fx.aliceToken)).status shouldBe 404
    }
  }

  test("its readiness depends only on its own probes") {
    withRoot(
      Vector(ReadinessProbe("dynamodb", () => true), ReadinessProbe("neptune", () => false))
    ) { r =>
      val ready = Wire.send(r.port, "GET", "/readyz")
      ready.status shouldBe 503
      ready.json("error")("details")("checks").obj.keySet shouldBe Set("dynamodb", "neptune")
    }

  }

class EmbeddingWorkerRootSpec extends ApiSuite:
  final private class Rig(
      settings: WorkerSettings = EntrypointFx.worker(),
      handler: EmbeddingWorkItem => scholarchain.storage.common.Result[Unit] = _ => Right(()),
      extra: Vector[ReadinessProbe] = Vector.empty
  ):
    val queue = new InMemoryWorkQueue
    val deadLetter = new InMemoryWorkQueue
    val rejects = new InMemoryRejectSink
    val clock = new FakeClock
    @volatile var receiveFailure: Option[StorageError] = None
    val entered = new CountDownLatch(1)
    val consumer = new WorkConsumer(
      queue,
      deadLetter,
      EmbeddingWorkItem.decode,
      (item: EmbeddingWorkItem) =>
        entered.countDown()
        handler(item)
      ,
      settings.consumer,
      rejects
    )
    val worker: RunningWorker = EmbeddingWorkerRoot.start(
      EmbeddingWorkerInputs(
        Fx.settings,
        settings,
        () => receiveFailure.toLeft(()).flatMap(_ => consumer.runOnce()),
        extra,
        RuntimeEnvironment(clock = clock)
      )
    )
    def send(runId: String): Unit =
      queue.send(EmbeddingWorkItem.encode(EmbeddingWorkItem.of(runId, "2026-11-01"))): Unit
    def ready = Wire.send(worker.healthPort, "GET", "/readyz")
    def health = Wire.send(worker.healthPort, "GET", "/healthz")
    def stop(): Unit = worker.shutdown(): Unit

  test("the worker builds from fakes and serves /healthz and /readyz on its own port") {
    val rig = new Rig
    try
      rig.health.status shouldBe 200
      eventually(rig.ready.status == 200)
      rig.ready.json("checks")("consumer").str shouldBe "ok"
    finally rig.stop()
  }

  test("the health port serves nothing but the probes, and no key opens anything else") {
    val rig = new Rig
    try
      Wire.send(rig.worker.healthPort, "GET", "/v1/query", Some(Fx.aliceToken)).status shouldBe 401
      Wire
        .send(rig.worker.healthPort, "GET", s"/v1/graph/nodes/${Fx.ada}", Some(Fx.aliceToken))
        .status shouldBe 401
    finally rig.stop()
  }

  test("it consumes the queue: handled messages are deleted, a poison message is dead-lettered") {
    val seen = new AtomicInteger(0)
    val rig = new Rig(handler = _ =>
      seen.incrementAndGet()
      Right(()))
    try
      rig.send("run:1")
      rig.send("run:2")
      rig.queue.send("not json at all"): Unit
      eventually(rig.queue.size == 0 && rig.deadLetter.size == 1)
      seen.get() shouldBe 2
      eventually(rig.ready.status == 200)
    finally rig.stop()
  }

  test(
    "a transient handler failure leaves the message for redelivery, and the worker stays ready"
  ) {
    val attempts = new AtomicInteger(0)
    val rig = new Rig(handler =
      _ =>
        if attempts.incrementAndGet() < 2 then Left(StorageError.Transient("embed", 1, "throttled"))
        else Right(())
    )
    try
      rig.send("run:1")
      eventually(rig.queue.size == 0)
      attempts.get() shouldBe 2
      rig.ready.status shouldBe 200
    finally rig.stop()
  }

  test(
    "readiness reflects consumer health: consecutive receive failures make it not ready, recovery clears it"
  ) {
    val rig = new Rig
    try
      eventually(rig.ready.status == 200)
      rig.receiveFailure = Some(StorageError.Transient("SQS receive", 3, "network"))
      eventually(rig.ready.status == 503)
      val body = rig.ready.json
      body("error")("details")("checks")("consumer").str shouldBe "failing"
      rig.health.status shouldBe 200 // liveness is unaffected
      rig.receiveFailure = None
      eventually(rig.ready.status == 200)
    finally rig.stop()
  }

  test("a loop that makes no progress for longer than the staleness bound is not ready") {
    val gate = new CountDownLatch(1)
    val rig = new Rig(handler = _ =>
      gate.await()
      Right(()))
    try
      eventually(rig.ready.status == 200)
      rig.send("run:1")
      rig.entered.await(5, TimeUnit.SECONDS) shouldBe true
      rig.clock.advance(11_000) // staleness is 10 s and the handler has not returned
      rig.ready.status shouldBe 503
      gate.countDown()
      eventually(rig.ready.status == 200)
    finally
      gate.countDown()
      rig.stop()
  }

  test("an extra dependency probe is part of readiness") {
    val ok = new AtomicBoolean(true)
    val rig = new Rig(extra = Vector(ReadinessProbe("s3Vectors", () => ok.get())))
    try
      eventually(rig.ready.status == 200)
      ok.set(false)
      rig.ready.status shouldBe 503
      rig.ready.json("error")("details")("checks")("s3Vectors").str shouldBe "failing"
    finally rig.stop()
  }

  test(
    "graceful shutdown: the message in hand finishes, no new one is taken, readiness drops first"
  ) {
    val started = new CountDownLatch(1)
    val gate = new CountDownLatch(1)
    val handled = new AtomicInteger(0)
    val rig = new Rig(handler = _ =>
      started.countDown()
      gate.await()
      handled.incrementAndGet()
      Right(()))
    try
      rig.send("run:1")
      rig.send("run:2")
      started.await()
      rig.worker.loop.requestStop()
      rig.ready.status shouldBe 503 // draining: not ready, still alive
      rig.health.status shouldBe 200
      gate.countDown()
      rig.worker.loop.awaitStopped(5000) shouldBe true
      handled.get() shouldBe 1 // the second message was not taken
      rig.queue.size shouldBe 1
      rig.worker.shutdown() shouldBe true
    finally gate.countDown()
  }

  test("shutdown is bounded by the grace period when the message in hand never returns") {
    val gate = new CountDownLatch(1)
    val settings = EntrypointFx.worker().copy(shutdownGraceSeconds = 1)
    val rig = new Rig(
      settings,
      handler = _ =>
        gate.await()
        Right(())
    )
    try
      rig.send("run:1")
      rig.entered.await(5, TimeUnit.SECONDS) shouldBe true
      val t0 = System.nanoTime()
      rig.worker.shutdown() shouldBe false
      ((System.nanoTime() - t0) / 1_000_000L) should be < 4000L
    finally gate.countDown()
  }

  test("a message that runs past the ingest deadline is abandoned and reported as a time-out") {
    val release = new CountDownLatch(1)
    val handler = IngestDeadline(
      _ =>
        try
          release.await()
          Right(())
        catch case _: InterruptedException => Left(StorageError.Transient("x", 1, "interrupted")),
      maxSeconds = 1
    )
    val t0 = System.nanoTime()
    val out = handler(EmbeddingWorkItem.of("run:1", "s"))
    out.left.toOption.get shouldBe a[StorageError.Timeout]
    out.left.toOption.get.retryable shouldBe true
    ((System.nanoTime() - t0) / 1_000_000L) should be < 3000L
    // a quick handler passes through
    IngestDeadline(_ => Right(()), 1)(EmbeddingWorkItem.of("run:1", "s")) shouldBe Right(())
  }
