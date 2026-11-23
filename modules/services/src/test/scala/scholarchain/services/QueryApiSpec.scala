package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.agents.StageFailure

trait ApiSuite extends AnyFunSuite with Matchers:
  def withHarness[A](h: Harness)(body: Harness => A): A =
    try body(h)
    finally h.close()

  def withHarness[A](body: Harness => A): A = withHarness(new Harness)(body)

  /** Polls until `cond` holds (real time, bounded). */
  def eventually(cond: => Boolean, millis: Long = 3000): Unit =
    val end = System.currentTimeMillis() + millis
    while !cond && System.currentTimeMillis() < end do Thread.sleep(10)
    assert(cond, "condition did not hold in time")

class QueryApiSpec extends ApiSuite:

  test("a synchronous query returns the answer with evidence IDs, provenance and links") {
    withHarness { h =>
      val r = h.call(
        "POST",
        "/v1/query",
        body = Some(Bodies.coauthors()),
        headers = Map("x-trace-id" -> "trace-from-client-1")
      )
      r.status shouldBe 200
      r.headers("x-trace-id") shouldBe "trace-from-client-1"
      r.headers("x-request-id") should startWith("req-")
      r.json("state").str shouldBe "SUCCEEDED"
      r.json("traceId").str shouldBe "trace-from-client-1"
      val claims = r.json("answer")("claims").arr
      claims should have size 1
      claims.head("evidenceIds").arr should not be empty
      r.json("answer")("provenance")("runId").str shouldBe Fx.runId
      r.json("answer")("abstained").bool shouldBe false
      r.json("verification").str shouldBe "VERIFIED"
      val evidenceId = claims.head("evidenceIds")(0).str
      r.json("links")("evidence")(0).str shouldBe s"/v1/evidence/$evidenceId"
      r.json("links")("explain").str should startWith("/v1/explain/query:")
      h.engine.calls.get() shouldBe 1
    }
  }

  test("the workflow gets the configured run and snapshot and the caller's trace ID") {
    withHarness { h =>
      var seen: Option[scholarchain.rag.workflow.AnswerRequest] = None
      val inner = h.engine.success
      h.engine.behavior = r =>
        seen = Some(r)
        inner(r)
      h.post(Bodies.coauthors()).status shouldBe 200
      val request = seen.get
      request.runId shouldBe Fx.runId
      request.snapshot shouldBe Fx.snapshot
      request.fields.researcherIds shouldBe Vector(Fx.ada)
      request.fields.windowStartYear shouldBe Some(Fx.windowStart)
    }
  }

  test("an asynchronous query returns 202 with a job reference and finishes in the background") {
    withHarness { h =>
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val inner = h.engine.success
      h.engine.behavior = r =>
        started.countDown()
        release.await(5, TimeUnit.SECONDS)
        inner(r)
      val accepted = h.post(Bodies.asyncCoauthors)
      accepted.status shouldBe 202
      val jobId = accepted.json("jobId").str
      accepted.headers("location") shouldBe s"/v1/jobs/$jobId"
      started.await(5, TimeUnit.SECONDS) shouldBe true
      val running = h.get(s"/v1/jobs/$jobId")
      running.json("state").str shouldBe "RUNNING"
      running.json("stage").str shouldBe "workflow"
      h.get(s"/v1/jobs/$jobId/result").status shouldBe 409
      release.countDown()
      eventually(h.get(s"/v1/jobs/$jobId").json("state").str == "SUCCEEDED")
      val done = h.get(s"/v1/jobs/$jobId")
      done.json("links")("result").str shouldBe s"/v1/jobs/$jobId/result"
      done.json.obj.contains("finishedAt") shouldBe true
      val result = h.get(s"/v1/jobs/$jobId/result")
      result.status shouldBe 200
      result.json("answer")("claims").arr should have size 1
    }
  }

  test("invalid JSON, a non-object, an empty body and a wrong content type are 400") {
    withHarness { h =>
      val cases = Seq(
        h.post("{not json") -> "INVALID_JSON",
        h.post("[1,2]") -> "INVALID_BODY",
        h.post("") -> "EMPTY_BODY",
        h.post("""{"question": 5}""") -> "INVALID_FIELD",
        h.post("""{"question": "hi", "bogus": 1}""") -> "UNKNOWN_FIELD",
        h.post("""{"mode": "later", "question": "hi"}""") -> "INVALID_FIELD",
        h.post("{}") -> "MISSING_QUERY",
        h.post(
          """{"operations":["DIRECT_COAUTHORS"],"researcherIds":["not-an-id"]}"""
        ) -> "INVALID_FIELD",
        h.call("POST", "/v1/query", body = Some("{}"), json = false) -> "CONTENT_TYPE_NOT_JSON",
        h.post("[" * 3000) -> "INVALID_JSON"
      )
      cases.foreach { (r, reason) =>
        withClue(s"$reason: ${r.raw}") {
          r.status shouldBe 400
          r.err("code").str shouldBe "BAD_REQUEST"
          r.err("reason").str shouldBe reason
          r.err("retryable").bool shouldBe false
        }
      }
      h.engine.calls.get() shouldBe 0
    }
  }

  test("a body over the configured size is refused before it is parsed") {
    withHarness { h =>
      val big = """{"question":"""" + ("a" * (Fx.settings.http.maxBodyBytes + 10)) + """"}"""
      val r = h.post(big)
      r.status shouldBe 400
      r.err("reason").str shouldBe "BODY_TOO_LARGE"
    }
  }

  test("an unsupported query constraint is 422, not 400") {
    withHarness { h =>
      val cases = Seq(
        Bodies.coauthors().replace("DIRECT_COAUTHORS", "DROP_ALL_NODES") -> "UNSUPPORTED_OPERATION",
        Bodies.coauthors(""","rankingMetric":"VIBES"""") -> "UNSUPPORTED_METRIC",
        Bodies.coauthors(s""","graphLimit":${Fx.limits.graphLimit + 1}""") -> "LIMIT_OUT_OF_BOUNDS",
        Bodies.coauthors(""","graphLimit":0""") -> "LIMIT_OUT_OF_BOUNDS",
        Bodies.coauthors(
          s""","maxPathDepth":${Fx.limits.maxPathDepth + 1}"""
        ) -> "LIMIT_OUT_OF_BOUNDS",
        s"""{"operations":["DIRECT_COAUTHORS"],"windowStartYear":2012}""" -> "WINDOW_INCOMPLETE",
        s"""{"operations":["DIRECT_COAUTHORS"],"windowStartYear":2013,"windowEndYear":2010}""" -> "WINDOW_INVALID"
      )
      cases.foreach { (body, reason) =>
        val r = h.post(body)
        withClue(s"$reason: ${r.raw}") {
          r.status shouldBe 422
          r.err("code").str shouldBe "UNPROCESSABLE"
          r.err("reason").str shouldBe reason
        }
      }
      h.engine.calls.get() shouldBe 0
    }
  }

  test("a refusal by the runtime planner is 422 with the planner's reason code") {
    withHarness { h =>
      h.engine.behavior =
        h.engine.failure("plan", StageFailure.Rejected("OPERATION_NOT_ALLOWED", "x"))
      val r = h.post(Bodies.coauthors())
      r.status shouldBe 422
      r.err("reason").str shouldBe "OPERATION_NOT_ALLOWED"
      r.err("details")("stage").str shouldBe "plan"
    }
  }

  test("a repeated request with the same Idempotency-Key runs once and replays the answer") {
    withHarness { h =>
      val first = h.post(Bodies.coauthors(), key = Some("idem-key-0001"))
      val second = h.post(Bodies.coauthors(), key = Some("idem-key-0001"))
      first.status shouldBe 200
      second.status shouldBe 200
      second.headers("idempotent-replayed") shouldBe "true"
      first.headers.contains("idempotent-replayed") shouldBe false
      second.json("queryId").str shouldBe first.json("queryId").str
      second.json("jobId").str shouldBe first.json("jobId").str
      second.json("answer") shouldBe first.json("answer")
      h.engine.calls.get() shouldBe 1
    }
  }

  test("the same key with a different request is 409, and keys are private to the caller") {
    withHarness { h =>
      val first = h.post(Bodies.coauthors(), key = Some("idem-key-0002"))
      val other = h.post(Bodies.coauthors(""","vectorLimit":3"""), key = Some("idem-key-0002"))
      other.status shouldBe 409
      other.err("reason").str shouldBe "IDEMPOTENCY_KEY_REUSED"
      // another caller with the same key text gets its own query, not alice's
      val bob = h.post(Bodies.coauthors(), token = Some(Fx.bobToken), key = Some("idem-key-0002"))
      bob.status shouldBe 200
      bob.json("queryId").str should not be first.json("queryId").str
      h.engine.calls.get() shouldBe 2
    }
  }

  test("a malformed Idempotency-Key is 400") {
    withHarness { h =>
      Seq("short", "has space in it", "x" * 65, "bad#key-00001").foreach { k =>
        val r = h.post(Bodies.coauthors(), key = Some(k))
        r.status shouldBe 400
        r.err("reason").str shouldBe "INVALID_IDEMPOTENCY_KEY"
      }
    }
  }

  test("a request without a key is a new query each time") {
    withHarness { h =>
      val a = h.post(Bodies.coauthors())
      val b = h.post(Bodies.coauthors())
      a.json("queryId").str should not be b.json("queryId").str
      h.engine.calls.get() shouldBe 2
    }
  }

  test("replaying a retryable failure under the same key runs the query again") {
    withHarness { h =>
      h.engine.behavior =
        h.engine.failure("evidence", StageFailure.Retryable("store", "unavailable"))
      val failed = h.post(Bodies.coauthors(), key = Some("idem-key-0003"))
      failed.status shouldBe 502
      failed.err("retryable").bool shouldBe true
      h.engine.behavior = h.engine.success
      val again = h.post(Bodies.coauthors(), key = Some("idem-key-0003"))
      again.status shouldBe 200
      again.json("queryId").str should not be empty
    }
  }

  test("a non-retryable failure replays as the same stored error") {
    withHarness { h =>
      h.engine.behavior = h.engine.failure("synthesis", StageFailure.Failed("synthesis", "boom"))
      val first = h.post(Bodies.coauthors(), key = Some("idem-key-0004"))
      first.status shouldBe 502
      first.err("retryable").bool shouldBe false
      h.engine.behavior = h.engine.success
      val second = h.post(Bodies.coauthors(), key = Some("idem-key-0004"))
      second.status shouldBe 502
      h.engine.calls.get() shouldBe 1
    }
  }

  test("a synchronous query that outlasts its deadline is 504 and the job records the failure") {
    val limits = Fx.limits.copy(synchronousDeadlineMs = 150)
    withHarness(new Harness(limits = limits)) { h =>
      val inner = h.engine.success
      h.engine.behavior = r =>
        Thread.sleep(2000)
        inner(r)
      val r = h.post(Bodies.coauthors())
      r.status shouldBe 504
      r.err("code").str shouldBe "DOWNSTREAM_TIMEOUT"
      r.err("retryable").bool shouldBe true
      val job = h.jobs.all.head
      job.state.toString shouldBe "Failed"
      job.error.get.code.status shouldBe 504
      job.stage shouldBe "workflow"
      // the worker finishing late must not overwrite the recorded failure
      Thread.sleep(50)
      h.jobs.all.head.state.toString shouldBe "Failed"
    }
  }

  test("a workflow stage time-out is 504, a busy workflow 503, a store failure 502") {
    withHarness { h =>
      def run(f: StageFailure) =
        h.engine.behavior = h.engine.failure("evidence", f)
        h.post(Bodies.coauthors())
      val timeout =
        run(StageFailure.Retryable("evidence", "the stage did not finish within 100 ms"))
      timeout.status shouldBe 504
      timeout.err("reason").str shouldBe "STAGE_TIMEOUT"
      val busy = run(StageFailure.Retryable("evidence", "too many stages are running; try again"))
      busy.status shouldBe 503
      busy.err("reason").str shouldBe "OVERLOADED"
      val store = run(StageFailure.Retryable("evidence", "store down"))
      store.status shouldBe 502
      store.err("code").str shouldBe "DOWNSTREAM_FAILURE"
      store.err("retryable").bool shouldBe true
    }
  }

  test("a retryable failure is retried a bounded number of times, then succeeds") {
    withHarness { h =>
      val inner = h.engine.success
      val failing = h.engine.failure("evidence", StageFailure.Retryable("store", "down"))
      h.engine.behavior = r => if h.engine.calls.get() <= 2 then failing(r) else inner(r)
      val r = h.post(Bodies.coauthors())
      r.status shouldBe 200
      h.engine.calls.get() shouldBe 3 // 1 try + maxWorkflowRetries (2)
      h.sleeper.sleeps.toVector shouldBe Vector(10L, 20L) // backoff grows, and no real sleeping
    }
  }

  test("retries stop at the configured bound") {
    withHarness { h =>
      h.engine.behavior = h.engine.failure("evidence", StageFailure.Retryable("store", "down"))
      h.post(Bodies.coauthors()).status shouldBe 502
      h.engine.calls.get() shouldBe 3
    }
  }

  test("a non-retryable failure is not retried") {
    withHarness { h =>
      h.engine.behavior = h.engine.failure("synthesis", StageFailure.Failed("synthesis", "bad"))
      h.post(Bodies.coauthors()).status shouldBe 502
      h.engine.calls.get() shouldBe 1
    }
  }

  test("an engine that throws is a 502 whose body leaks nothing") {
    withHarness { h =>
      h.engine.behavior =
        _ => throw new IllegalStateException("password=hunter2 at com.example.Secret")
      val r = h.post(Bodies.coauthors())
      r.status shouldBe 502
      r.raw should not include "hunter2"
      r.raw should not include "IllegalStateException"
      h.log.text should not include "hunter2"
    }
  }

  test("the workflow breaker opens after repeated downstream failures and answers 503 at once") {
    withHarness { h =>
      h.engine.behavior = h.engine.failure("evidence", StageFailure.Failed("evidence", "down"))
      (1 to Fx.settings.breaker.failureThreshold).foreach(_ =>
        h.post(Bodies.coauthors()).status shouldBe 502
      )
      val callsBefore = h.engine.calls.get()
      val open = h.post(Bodies.coauthors())
      open.status shouldBe 503
      open.err("reason").str shouldBe "CIRCUIT_OPEN"
      open.err("retryable").bool shouldBe true
      open.headers("retry-after") should not be empty
      open.traceId should not be empty
      h.engine.calls.get() shouldBe callsBefore // the engine was not called
      // after the open period one trial call goes through and a success closes the breaker
      h.clock.advance(Fx.settings.breaker.openMillis + 1)
      h.engine.behavior = h.engine.success
      h.post(Bodies.coauthors()).status shouldBe 200
      h.post(Bodies.coauthors()).status shouldBe 200
    }
  }

  test("caller mistakes (422) never open the breaker") {
    withHarness { h =>
      h.engine.behavior =
        h.engine.failure("plan", StageFailure.Rejected("OPERATION_NOT_ALLOWED", "x"))
      (1 to 10).foreach(_ => h.post(Bodies.coauthors()).status shouldBe 422)
      h.engine.behavior = h.engine.success
      h.post(Bodies.coauthors()).status shouldBe 200
    }
  }

  test("an abstaining answer is a 200 that says it abstained") {
    withHarness { h =>
      h.engine.behavior = r =>
        h.engine.success(r).map { w =>
          w.copy(
            response = w.response.copy(
              claims = Vector.empty,
              abstained = true,
              uncertainty = Some("No answer was given (RESEARCHER_NOT_FOUND).")
            ),
            abstention = Some(
              scholarchain.rag.workflow
                .Abstention("RESEARCHER_NOT_FOUND", "no researcher", Vector.empty, Vector("gap"))
            )
          )
        }
      val r = h.post(Bodies.coauthors())
      r.status shouldBe 200
      r.json("answer")("abstained").bool shouldBe true
      r.json("abstention")("code").str shouldBe "RESEARCHER_NOT_FOUND"
      r.json("answer")("claims").arr shouldBe empty
    }
  }

  test("the executor refuses asynchronous work when it is full, with a retryable 503") {
    val settings =
      Fx.settings.copy(execution = scholarchain.services.config.ExecutionSettings(1, 1))
    withHarness(new Harness(settings = settings)) { h =>
      val release = new CountDownLatch(1)
      val inner = h.engine.success
      h.engine.behavior = r =>
        release.await(5, TimeUnit.SECONDS)
        inner(r)
      val statuses = (1 to 4).map(_ => h.post(Bodies.asyncCoauthors).status)
      statuses.count(_ == 202) shouldBe 2 // one running, one queued
      statuses.count(_ == 503) shouldBe 2
      release.countDown()
      eventually(h.jobs.all.count(_.state.terminal) == h.jobs.all.size)
    }
  }
