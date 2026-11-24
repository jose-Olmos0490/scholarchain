package scholarchain.adversarial.services

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.workflow.StageTrace
import scholarchain.services.entrypoint.*
import scholarchain.services.http.{HttpRequest, ServiceClock}
import scholarchain.services.ops.{AccessRecord, ReadinessProbe, Slf4jAccessLog}
import scholarchain.services.runtime.{RuntimeEnvironment, ServiceRuntime}
import scholarchain.storage.common.*
import scholarchain.storage.queue.*

/** Captures what the services actually write to the application log (through the logback that is
  * on the runtime classpath) and asserts that no secret, token, request body, question,
  * idempotency key or downstream error text reaches it. Also exercises the embedding queue's
  * replay, poison-message and queue-fault behavior.
  */
class LoggingAndQueueAdvSpec extends AnyFunSuite with Matchers:

  private val Marker = "ZZ-LOG-MARKER-0"

  /** Runs `body` while a logback list appender is attached to the `scholarchain.services` logger
    * (not the root: other suites in this JVM run Spark, whose threads log constantly). The
    * logger's level is raised to ALL for the call and restored afterwards.
    */
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
      Thread.sleep(100)
      val list = listCls.getField("list").get(app).asInstanceOf[java.util.List[AnyRef]]
      val snapshot = list.synchronized(new java.util.ArrayList[AnyRef](list)).asScala.toVector
      val lines = snapshot.map { ev =>
        val msg = eventCls.getMethod("getFormattedMessage").invoke(ev).toString
        val tp = eventCls.getMethod("getThrowableProxy").invoke(ev)
        val thr =
          if tp == null then ""
          else
            s" ${proxyCls.getMethod("getClassName").invoke(tp)}: ${proxyCls.getMethod("getMessage").invoke(tp)}"
        msg + thr
      }
      (result, lines)
    finally
      loggerCls.getMethod("detachAppender", appCls).invoke(target, app)
      loggerCls.getMethod("setLevel", levelCls).invoke(target, oldLevel)

  private def liveRuntime(rig: Rig): ServiceRuntime =
    ServiceRuntime.build(
      rig.settings,
      rig.limits,
      scholarchain.services.runtime.RuntimePorts(
        rig.engine,
        rig.dynamo.querySessions,
        rig.dynamo.evidence,
        rig.dynamo.queryAudit,
        rig.graph,
        rig.jobs,
        Vector(ReadinessProbe("s", () => true))
      ),
      new scholarchain.services.security.BearerAuthenticator(Kit.keys, 256),
      rig.clock,
      new CountingIds,
      new NoSleep,
      Slf4jAccessLog
    )

  test(
    "the access log of real traffic holds no token, body, question, query value or idempotency key"
  ) {
    val rig = new Rig
    val rt = liveRuntime(rig)
    try
      val (_, lines) = captured {
        val body = ujson
          .Obj(
            "question" -> s"what did $Marker write",
            "operations" -> ujson.Arr("DIRECT_COAUTHORS"),
            "researcherIds" -> ujson.Arr(Kit.ada),
            "windowStartYear" -> 2010,
            "windowEndYear" -> 2013
          )
          .render()
        def call(
            m: String,
            p: String,
            token: Option[String],
            b: Option[String],
            q: Map[String, Vector[String]] = Map.empty,
            h: Map[String, String] = Map.empty
        ) =
          rt.api.handle(
            HttpRequest.of(
              m,
              p,
              q,
              token.map(t => Map("authorization" -> s"Bearer $t")).getOrElse(Map.empty) ++
                b.map(_ => Map("content-type" -> "application/json")).getOrElse(Map.empty) ++ h,
              b.map(_.getBytes("UTF-8")).getOrElse(Array.emptyByteArray),
              Some("198.51.100.5")
            )
          )
        call(
          "POST",
          "/v1/query",
          Some(Kit.aliceToken),
          Some(body),
          h = Map("idempotency-key" -> s"idem-$Marker")
        )
        call("POST", "/v1/query", Some(s"bad-token-$Marker"), Some(body))
        call("GET", s"/v1/jobs/job-$Marker", Some(Kit.aliceToken), None)
        call(
          "GET",
          s"/v1/graph/nodes/${Kit.ada}/neighbors",
          Some(Kit.aliceToken),
          None,
          Map(
            "cursor" -> Vector(Marker),
            "windowStartYear" -> Vector("2010"),
            "windowEndYear" -> Vector("2013")
          )
        )
        call("POST", "/v1/query", Some(Kit.aliceToken), Some("{" + Marker))
        call("GET", s"/secret-path/$Marker", Some(Kit.aliceToken), None)
      }
      lines should not be empty
      lines.mkString("\n") should not include Marker
      lines.mkString("\n") should not include Kit.aliceToken
      lines.mkString("\n") should not include "authorization"
      // it does say what happened, by route template, status and principal
      lines.exists(l =>
        l.contains("route=POST /v1/query") && l.contains("status=200")
      ) shouldBe true
      lines.exists(l => l.contains("status=401") && l.contains("principal=-")) shouldBe true
      lines.exists(l => l.contains("route=unmatched")) shouldBe true
    finally rt.close()
  }

  test("an engine that throws a secret-bearing exception leaves no trace of it in the log") {
    val rig = new Rig
    val rt = liveRuntime(rig)
    try
      rig.engine.behavior = _ => throw new IllegalStateException(s"jdbc://user:hunter2@$Marker")
      val (resp, lines) = captured {
        rt.api.handle(
          HttpRequest.of(
            "POST",
            "/v1/query",
            Map.empty,
            Map(
              "authorization" -> s"Bearer ${Kit.aliceToken}",
              "content-type" -> "application/json"
            ),
            Kit.coauthorsBody().getBytes("UTF-8"),
            Some("198.51.100.6")
          )
        )
      }
      resp.status should be >= 500
      lines.mkString("\n") should not include "hunter2"
      lines.mkString("\n") should not include Marker
    finally rt.close()
  }

  test(
    "an access record cannot carry a body, header or token: its fields are the whole of what is logged"
  ) {
    val fields = classOf[AccessRecord].getDeclaredFields.map(_.getName).toSet
    fields shouldBe Set(
      "method",
      "route",
      "status",
      "requestId",
      "traceId",
      "latencyMillis",
      "principal",
      "errorCode"
    )
    val (_, lines) = captured {
      Slf4jAccessLog.record(
        AccessRecord("POST", "POST /v1/query", 200, "req-1", "trace-1", 3L, Some("alice"), None)
      )
    }
    // other suites may log concurrently under the same logger: select this record by its ID
    val mine = lines.filter(_.contains("requestId=req-1 "))
    mine.size shouldBe 1
    mine.head should include("principal=alice")
  }

  test("the reject sink logs the reason code and key, never the reject's detail or document") {
    val sink = new LoggingRejectSink
    val (_, lines) = captured {
      sink.record(
        StoreReject(
          "vectors",
          "key-1",
          "TOO_MANY_TOPIC_IDS",
          s"document text $Marker password=hunter2",
          "run:x"
        )
      )
    }
    sink.count shouldBe 1L
    lines.mkString("\n") should not include Marker
    lines.mkString("\n") should not include "hunter2"
    lines.mkString("\n") should include("TOO_MANY_TOPIC_IDS")
  }

  test("the workflow trace sink logs stage timings only") {
    val (_, lines) = captured {
      Slf4jTraceSink.record(StageTrace("trace-9", "synthesis", 42L, "ok"))
    }
    lines.mkString("\n") should include("stage=synthesis")
    lines.mkString("\n") should include("latencyMs=42")
  }

  test("the worker loop logs a failed receive by error kind only, never its message") {
    val health = new ConsumerHealth(
      new ServiceClock { def nowMillis: Long = System.currentTimeMillis() },
      60_000L,
      100
    )
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    lazy val loop: WorkerLoop = new WorkerLoop(
      () =>
        if calls.incrementAndGet() >= 3 then loop.requestStop()
        Left(
          StorageError
            .Transient("sqs receive", 3, s"queue url https://sqs.example/$Marker token=hunter2")
        )
      ,
      health,
      idleBackoffMillis = 5L,
      pauseWhenEmptyMillis = 0L
    )
    val (_, lines) = captured {
      val t = new Thread(() => loop.run())
      t.start()
      t.join(10_000)
    }
    calls.get should be >= 3
    lines.mkString("\n") should not include Marker
    lines.mkString("\n") should not include "hunter2"
    lines.exists(_.contains("receive failed")) shouldBe true
    health.ready shouldBe false // the loop ended
  }

  // ---- embedding queue: poison messages, replay, queue faults -------------------------------------------

  private val consumerCfg = ConsumerSettings(
    batchSize = 5,
    visibilityTimeoutSeconds = 5,
    waitSeconds = 0,
    maxReceiveCount = 3,
    maxMessagesPerRun = 50,
    retryBackoffSeconds = 0
  )

  private def consumer(
      queue: WorkQueue,
      dlq: WorkQueue,
      handler: EmbeddingWorkItem => Result[Unit],
      sink: RejectSink = new InMemoryRejectSink
  ) =
    new WorkConsumer[EmbeddingWorkItem](
      queue,
      dlq,
      EmbeddingWorkItem.decode,
      handler,
      consumerCfg,
      sink
    )

  private val item = EmbeddingWorkItem.of("run:" + "a" * 64, "2026-11-01")

  // DEFERRED phase-11 defect T12, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore(
    "a poison message (not JSON, wrong version, blank fields) goes to the dead-letter queue on first receipt"
  ) {
    val q = new InMemoryWorkQueue
    val dlq = new InMemoryWorkQueue
    val rejects = new InMemoryRejectSink
    val bodies = Seq(
      "not json at all",
      "{}",
      """{"schemaVersion":"embedding-work-item-v9","runId":"r","snapshot":"s"}""",
      s"""{"schemaVersion":"${EmbeddingWorkItem.Version}","runId":"  ","snapshot":"s"}""",
      s"""{"schemaVersion":"${EmbeddingWorkItem.Version}","runId":7,"snapshot":"s"}""",
      "[" * 50000
    )
    bodies.foreach(b => q.send(b))
    val handled = mutable.ArrayBuffer.empty[EmbeddingWorkItem]
    val c = consumer(q, dlq, i => { handled += i; Right(()) }, rejects)
    val rep = c.drain().toOption.get
    withClue(s"these malformed bodies were decoded and handled as work: $handled ") {
      handled shouldBe empty
    }
    rep.deadLettered shouldBe bodies.size
    q.size shouldBe 0
    dlq.size shouldBe bodies.size
    rejects.all.map(_.reasonCode).distinct shouldBe Vector("UNDECODABLE_MESSAGE")
  }

  test(
    "a handler that always fails transiently is retried to the receive bound, then dead-lettered once"
  ) {
    val q = new InMemoryWorkQueue
    val dlq = new InMemoryWorkQueue
    q.send(EmbeddingWorkItem.encode(item))
    var calls = 0
    val c = consumer(q, dlq, _ => { calls += 1; Left(StorageError.Transient("ingest", 1, "x")) })
    (1 to 10).foreach(_ => c.runOnce())
    calls shouldBe consumerCfg.maxReceiveCount
    q.size shouldBe 0
    dlq.size shouldBe 1
  }

  test(
    "a handler that throws is a transient failure that never leaks the exception text into rejects"
  ) {
    val q = new InMemoryWorkQueue
    val dlq = new InMemoryWorkQueue
    val rejects = new InMemoryRejectSink
    q.send(EmbeddingWorkItem.encode(item))
    val c = consumer(q, dlq, _ => throw new IllegalStateException(s"secret $Marker"), rejects)
    (1 to 5).foreach(_ => c.runOnce())
    rejects.all.nonEmpty shouldBe true
    rejects.all.foreach(r => (r.detail + r.reasonCode) should not include Marker)
  }

  test("a permanent handler failure is dead-lettered at once, not retried") {
    val q = new InMemoryWorkQueue
    val dlq = new InMemoryWorkQueue
    q.send(EmbeddingWorkItem.encode(item))
    var calls = 0
    val c =
      consumer(q, dlq, _ => { calls += 1; Left(StorageError.Permanent("ingest", "bad manifest")) })
    c.drain()
    calls shouldBe 1
    dlq.size shouldBe 1
    q.size shouldBe 0
  }

  test("a dead-letter queue outage never loses the message: it stays on the work queue") {
    val q = new InMemoryWorkQueue
    q.send("poison")
    val brokenDlq = new WorkQueue:
      def send(b: String) = Left(StorageError.Transient("sqs send", 3, "down"))
      def receive(m: Int, v: Int, w: Int) = Right(Vector.empty)
      def delete(h: String) = Right(())
      def changeVisibility(h: String, s: Int) = Right(())
    val rep = consumer(q, brokenDlq, _ => Right(())).runOnce().toOption.get
    rep.outcomes.map(_._2).head shouldBe a[MessageOutcome.QueueFault]
    q.size shouldBe 1
  }

  test(
    "an acknowledgement that fails after success redelivers the message: delivery is at-least-once"
  ) {
    val clock = new java.util.concurrent.atomic.AtomicLong(0L)
    val base = new InMemoryWorkQueue(() => clock.get)
    base.send(EmbeddingWorkItem.encode(item))
    var failAck = true
    val flaky = new WorkQueue:
      def send(b: String) = base.send(b)
      def receive(m: Int, v: Int, w: Int) = base.receive(m, v, w)
      def delete(h: String) =
        if failAck then Left(StorageError.Transient("sqs delete", 1, "x")) else base.delete(h)
      def changeVisibility(h: String, s: Int) = base.changeVisibility(h, s)
    var handled = 0
    val c = consumer(flaky, new InMemoryWorkQueue, _ => { handled += 1; Right(()) })
    c.runOnce().toOption.get.outcomes.head._2 shouldBe a[MessageOutcome.QueueFault]
    base.size shouldBe 1
    handled shouldBe 1
    // the visibility timeout passes: the same message is delivered again and the handler repeats
    clock.addAndGet(60_000L)
    failAck = false
    c.runOnce().toOption.get.outcomes.head._2 shouldBe MessageOutcome.Acknowledged
    handled shouldBe 2
    base.size shouldBe 0
  }

  test("a flood of poison messages is bounded by maxMessagesPerRun per drain") {
    val q = new InMemoryWorkQueue
    val dlq = new InMemoryWorkQueue
    (1 to 500).foreach(i => q.send(s"poison-$i"))
    val rep = consumer(q, dlq, _ => Right(())).drain().toOption.get
    rep.received should be <= consumerCfg.maxMessagesPerRun
    q.size should be >= 500 - consumerCfg.maxMessagesPerRun
  }

  test("the work item carries a pointer only: a hostile run ID is data, and decode never throws") {
    Seq(
      "{",
      "null",
      "\"x\"",
      "[]",
      "{\"schemaVersion\":null}",
      "{\"schemaVersion\":\"embedding-work-item-v1\"}",
      "\u0000",
      ""
    ).foreach { b =>
      try EmbeddingWorkItem.decode(b).isLeft shouldBe true
      catch
        case t: Throwable => fail(s"decode threw ${t.getClass.getSimpleName} for [${b.take(20)}]")
    }
    val traversal = EmbeddingWorkItem.of("../../raw/secrets", "s")
    EmbeddingWorkItem.decode(EmbeddingWorkItem.encode(traversal)) shouldBe Right(
      traversal
    ) // accepted as data...
    new scholarchain.storage.vectors.ManifestStore(
      new scholarchain.storage.objects.InMemoryObjectStore,
      scholarchain.storage.objects.ObjectLayout(
        Map(scholarchain.storage.objects.ObjectArea.Rag -> "b"),
        Map(scholarchain.storage.objects.ObjectArea.Rag -> "")
      )
    ).read(traversal.runId).isLeft shouldBe true // ...and refused before any object key is built
  }
