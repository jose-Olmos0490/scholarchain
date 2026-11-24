package scholarchain.adversarial.services

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.agents.StageFailure
import scholarchain.services.query.JobState

/** Idempotency, replay, ownership, timeouts, saturation and failure containment of the query
  * API. The engine is scripted at its port; the clock and the sleeper are fake.
  */
class ApiJobsAdvSpec extends AnyFunSuite with Matchers:

  private val Marker = "ZZ-JOB-MARKER-3"

  private def rig[A](r: Rig = new Rig)(f: Rig => A): A = try f(r)
  finally r.close()
  private val Key = "idem-key-0001"

  test("same key and same body replays the stored job: one engine run, same job, flagged") {
    rig() { r =>
      val a = r.post(Kit.coauthorsBody(), key = Some(Key))
      val b = r.post(Kit.coauthorsBody(), key = Some(Key))
      a.status shouldBe 200
      b.status shouldBe 200
      b.headers.get("idempotent-replayed") shouldBe Some("true")
      b.json("jobId").str shouldBe a.json("jobId").str
      b.json("queryId").str shouldBe a.json("queryId").str
      r.engine.calls.get shouldBe 1
      r.jobs.all.size shouldBe 1
    }
  }

  test("same key with a different body is 409 IDEMPOTENCY_KEY_REUSED and runs nothing") {
    rig() { r =>
      r.post(Kit.coauthorsBody(), key = Some(Key)).status shouldBe 200
      val x = r.post(Kit.coauthorsBody(""","graphLimit":2"""), key = Some(Key))
      x.status shouldBe 409
      x.reason shouldBe Some("IDEMPOTENCY_KEY_REUSED")
      r.engine.calls.get shouldBe 1
    }
  }

  test("keys are scoped to the principal: another caller's key reuse is a new, separate job") {
    rig() { r =>
      val a = r.post(Kit.coauthorsBody(), key = Some(Key))
      val b = r.post(Kit.coauthorsBody(), Some(Kit.bobToken), key = Some(Key))
      a.status shouldBe 200
      b.status shouldBe 200
      b.headers.get("idempotent-replayed") shouldBe None
      b.json("jobId").str should not be a.json("jobId").str
      b.json("queryId").str should not be a.json("queryId").str
      r.engine.calls.get shouldBe 2
    }
  }

  test("malformed Idempotency-Keys are 400 and nothing runs") {
    rig() { r =>
      Seq(
        "abc",
        "a" * 65,
        "key with space",
        "key\tTab",
        "ключ-0001",
        "key;drop",
        "key/with/slash",
        "k\u0000k-0001",
        "🔑🔑🔑🔑🔑"
      ).foreach { k =>
        val x = r.post(Kit.coauthorsBody(), key = Some(k))
        withClue(k.take(20)) {
          x.status shouldBe 400
          x.reason shouldBe Some("INVALID_IDEMPOTENCY_KEY")
        }
      }
      r.engine.calls.get shouldBe 0
    }
  }

  test("without a key every submit is a fresh query") {
    rig() { r =>
      val a = r.post(Kit.coauthorsBody())
      val b = r.post(Kit.coauthorsBody())
      a.json("queryId").str should not be b.json("queryId").str
      r.engine.calls.get shouldBe 2
    }
  }

  test("eight concurrent submits with one key: no 5xx, one engine run, one job") {
    rig() { r =>
      val pool = Executors.newFixedThreadPool(8)
      val go = new CountDownLatch(1)
      try
        val fs = (1 to 8).map(_ =>
          pool.submit(() =>
            go.await()
            r.post(Kit.coauthorsBody(), key = Some(Key))
          )
        )
        go.countDown()
        val rs = fs.map(_.get(30, TimeUnit.SECONDS))
        rs.map(_.status).foreach(s => s should (be(200) or be(202) or be(409)))
        rs.filter(_.status == 409).foreach(_.reason shouldBe Some("IDEMPOTENCY_KEY_IN_USE"))
        rs.count(_.status == 200) should be >= 1
        r.engine.calls.get shouldBe 1
        r.jobs.all.size shouldBe 1
        rs.filter(_.status == 200).map(_.json("jobId").str).distinct.size shouldBe 1
      finally pool.shutdownNow()
    }
  }

  test("replay of a retryable failure runs the engine again and can then succeed") {
    rig() { r =>
      r.engine.behavior =
        r.engine.failure("evidence", StageFailure.Retryable("evidence", "store reset"))
      val a = r.post(Kit.coauthorsBody(), key = Some(Key))
      a.status shouldBe 502
      a.json("error")("retryable").bool shouldBe true
      val first = r.engine.calls.get
      first should be > 0
      r.engine.behavior = r.engine.success
      val b = r.post(Kit.coauthorsBody(), key = Some(Key))
      b.status shouldBe 200
      b.headers.get("idempotent-replayed") shouldBe Some("true")
      r.jobs.all.size shouldBe 1
      r.jobs.all.head.attempts shouldBe 2
      r.jobs.all.head.state shouldBe JobState.Succeeded
    }
  }

  test("replay of a non-retryable failure returns the stored error and never reruns") {
    rig() { r =>
      r.engine.behavior =
        r.engine.failure("plan", StageFailure.Rejected("OPERATION_NOT_ALLOWED", "x"))
      val a = r.post(Kit.coauthorsBody(), key = Some(Key))
      a.status shouldBe 422
      val calls = r.engine.calls.get
      r.engine.behavior = r.engine.success
      val b = r.post(Kit.coauthorsBody(), key = Some(Key))
      b.status shouldBe 422
      b.json("requestId").str should not be a.json("requestId").str
      r.engine.calls.get shouldBe calls
    }
  }

  test("a replay whose job record is gone is a typed 409, not a rerun and not a 500") {
    val r = new Rig
    rig(r) { r =>
      r.post(Kit.coauthorsBody(), key = Some(Key)).status shouldBe 200
    }
    // a second rig sharing the item store (sessions) but with an empty job store
    val shared = new Rig(items = r.items)
    rig(shared) { s =>
      val x = s.post(Kit.coauthorsBody(), key = Some(Key))
      x.status shouldBe 409
      x.reason shouldBe Some("IDEMPOTENCY_RECORD_UNAVAILABLE")
      s.engine.calls.get shouldBe 0
    }
  }

  test("a job is visible only to its owner; a stranger gets the same 404 as for a missing job") {
    rig() { r =>
      val id = r.post(Kit.coauthorsBody()).json("jobId").str
      val qid = r.jobs.all.head.queryId
      r.get(s"/v1/jobs/$id").status shouldBe 200
      val other = r.get(s"/v1/jobs/$id", Some(Kit.bobToken))
      val missing = r.get("/v1/jobs/job-" + "f" * 32, Some(Kit.bobToken))
      other.status shouldBe 404
      missing.status shouldBe 404
      other.json("error") shouldBe missing.json("error")
      r.get(s"/v1/jobs/$id/result", Some(Kit.bobToken)).status shouldBe 404
      r.get(s"/v1/explain/$qid", Some(Kit.bobToken)).status shouldBe 404
      r.get(s"/v1/explain/$qid").status shouldBe 200
    }
  }

  test("hostile job and query IDs are 400 or 404, never 500 and never echoed") {
    rig() { r =>
      Seq(
        "job-",
        "job-xyz",
        "JOB-" + "0" * 32,
        "job-" + "0" * 33,
        "..",
        "%2e%2e",
        s"job-$Marker",
        "job-" + "0" * 31 + "'",
        "<script>"
      ).foreach { id =>
        val x = r.get(s"/v1/jobs/$id")
        withClue(id)(Seq(400, 404) should contain(x.status))
        x.raw should not include Marker
      }
      Seq("query:short", s"query:$Marker", "job-" + "0" * 32, "researcher:" + "0" * 64).foreach {
        id =>
          val x = r.get(s"/v1/explain/$id")
          withClue(id)(x.status shouldBe 400)
          x.raw should not include Marker
      }
    }
  }

  test("async: 202 with Location; the result is 409 JOB_NOT_FINISHED until the engine returns") {
    val gate = new CountDownLatch(1)
    rig() { r =>
      val real = r.engine.success
      r.engine.behavior = req => { gate.await(10, TimeUnit.SECONDS); real(req) }
      val a = r.post(Kit.coauthorsBody(""","mode":"async""""))
      a.status shouldBe 202
      val loc = a.headers("location")
      loc should startWith("/v1/jobs/job-")
      val early = r.get(loc + "/result")
      early.status shouldBe 409
      early.reason shouldBe Some("JOB_NOT_FINISHED")
      early.headers should contain key "retry-after"
      gate.countDown()
      val deadline = System.nanoTime() + 10_000_000_000L
      var st = ""
      while st != "SUCCEEDED" && System.nanoTime() < deadline do
        st = r.get(loc).json("state").str
        if st != "SUCCEEDED" then Thread.sleep(20)
      st shouldBe "SUCCEEDED"
      r.get(loc + "/result").status shouldBe 200
    }
  }

  test("sync deadline: a slow engine is 504 and a late success cannot overwrite the failure") {
    val proceed = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    rig(new Rig(limits = Kit.limits(deadlineMs = 150))) { r =>
      val real = r.engine.success
      r.engine.behavior = req =>
        try
          proceed.await(10, TimeUnit.SECONDS)
          real(req)
        finally finished.countDown()
      val x = r.post(Kit.coauthorsBody())
      x.status shouldBe 504
      x.json("error")("retryable").bool shouldBe true
      val job = r.jobs.all.head
      job.state shouldBe JobState.Failed
      proceed.countDown()
      finished.await(5, TimeUnit.SECONDS)
      Thread.sleep(100)
      r.jobs.get(job.jobId).get.state shouldBe JobState.Failed
      r.get(s"/v1/jobs/${job.jobId}").json("state").str shouldBe "FAILED"
    }
  }

  test(
    "saturation: a flood of async submits yields 503 with Retry-After, never 5xx other than 503"
  ) {
    val gate = new CountDownLatch(1)
    rig() { r =>
      val real = r.engine.success
      r.engine.behavior = req => { gate.await(20, TimeUnit.SECONDS); real(req) }
      val rs =
        (1 to 40).map(i => r.post(Kit.coauthorsBody(s""","mode":"async","question":"q$i"""")))
      val statuses = rs.map(_.status).toSet
      statuses should contain(202)
      statuses should contain(503)
      statuses.filter(_ >= 500) shouldBe Set(503)
      rs.filter(_.status == 503).foreach { x =>
        x.headers should contain key "retry-after"
        x.json("error")("retryable").bool shouldBe true
      }
      // every refused submit left a FAILED job, not a stuck QUEUED one
      try
        r.jobs.all.count(_.state == JobState.Failed) shouldBe rs.count(_.status == 503)
        r.jobs.all.count(_.state == JobState.Queued) shouldBe rs.count(_.status == 202) - 4
      finally gate.countDown()
    }
  }

  test("an engine that throws: a fixed 5xx body, no exception text in the response, job or log") {
    rig() { r =>
      r.engine.behavior = _ => throw new IllegalStateException(s"AKIA$Marker password=hunter2")
      val x = r.post(Kit.coauthorsBody())
      x.status should (be >= 500 and be < 600)
      x.raw should not include Marker
      x.raw should not include "hunter2"
      r.log.text should not include "hunter2"
      r.jobs.all.head.state shouldBe JobState.Failed
      r.jobs.all.head.error.get.message should not include "hunter2"
      r.get(s"/v1/jobs/${r.jobs.all.head.jobId}").raw should not include "hunter2"
    }
  }

  test("an engine failure carrying secrets in its detail is mapped to a fixed message") {
    rig() { r =>
      Seq[StageFailure](
        StageFailure.Failed("llm", s"token=$Marker"),
        StageFailure.Retryable("llm", s"host=$Marker"),
        StageFailure.Rejected("BAD", s"<$Marker>"),
        StageFailure.Timeout("llm", Marker),
        StageFailure.Overloaded("llm", Marker),
        StageFailure.NotFound(Marker),
        StageFailure.Unsupported(Marker),
        StageFailure.IdempotencyConflict(Marker)
      ).zipWithIndex.foreach { (f, i) =>
        r.engine.behavior = r.engine.failure("stage", f)
        val x = r.post(Kit.coauthorsBody(s""","question":"q$i""""))
        withClue(f.toString)(x.raw should not include Marker)
      }
      r.log.text should not include Marker
    }
  }

  test(
    "the workflow breaker opens after repeated downstream failures and stops calling the engine"
  ) {
    rig() { r =>
      r.engine.behavior = r.engine.failure("evidence", StageFailure.Failed("evidence", "down"))
      (1 to 3).foreach(i => r.post(Kit.coauthorsBody(s""","question":"b$i"""")).status shouldBe 502)
      val calls = r.engine.calls.get
      val x = r.post(Kit.coauthorsBody(""","question":"after""""))
      x.status shouldBe 503
      x.reason shouldBe Some("CIRCUIT_OPEN")
      r.engine.calls.get shouldBe calls
    }
  }

  test("caller mistakes (4xx from the engine) never open the breaker") {
    rig() { r =>
      r.engine.behavior =
        r.engine.failure("plan", StageFailure.Rejected("OPERATION_NOT_ALLOWED", "x"))
      (1 to 6).foreach(i => r.post(Kit.coauthorsBody(s""","question":"c$i"""")).status shouldBe 422)
      r.engine.behavior = r.engine.success
      r.post(Kit.coauthorsBody(""","question":"fine"""")).status shouldBe 200
    }
  }

  test(
    "the engine receives the validated request, the principal-scoped key, and a trace of safe form"
  ) {
    rig() { r =>
      r.call(
        "POST",
        "/v1/query",
        body = Some(Kit.coauthorsBody()),
        headers = Map("idempotency-key" -> Key, "x-trace-id" -> "t-1")
      )
      val seen = r.engine.seen.head
      seen.idempotencyKey shouldBe Some(s"alice:$Key")
      seen.traceId shouldBe "t-1"
      seen.runId shouldBe Kit.runId
      seen.snapshot shouldBe Kit.snapshot
    }
  }
