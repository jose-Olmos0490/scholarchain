package scholarchain.services

import scholarchain.core.model.EvidenceType
import scholarchain.rag.agents.StageFailure
import scholarchain.services.config.RateLimitSettings
import scholarchain.services.error.ErrorCode
import scholarchain.services.query.{InMemoryJobStore, JobRecord, JobStore}

/** A job store that fails the way a broken adapter would, with a message that must not leak. */
final class ExplodingJobStore extends JobStore:
  private def boom = throw new IllegalStateException("jdbc:secret-host password=hunter2")
  def create(job: JobRecord): Boolean = boom
  def get(jobId: String): Option[JobRecord] = boom
  def findByQueryId(queryId: String): Option[JobRecord] = boom
  def update(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] = boom
  def restart(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] = boom

class ErrorModelSpec extends ApiSuite:

  /** One trigger per status of the error model. Each returns the response to inspect. */
  private val triggers: Vector[(ErrorCode, () => Resp)] = Vector(
    ErrorCode.BadRequest -> (() => new Local().h.post("{broken")),
    ErrorCode.Unauthenticated -> (() => new Local().h.post(Bodies.coauthors(), token = None)),
    ErrorCode.Forbidden -> (() =>
      new Local().h.post(Bodies.coauthors(), token = Some(Fx.readerToken))
    ),
    ErrorCode.NotFound -> (() => new Local().h.get("/v1/no/such/route")),
    ErrorCode.Conflict -> (() =>
      val l = new Local()
      l.h.post(Bodies.coauthors(), key = Some("idem-key-1001"))
      l.h.post(Bodies.coauthors(""","vectorLimit":2"""), key = Some("idem-key-1001"))
    ),
    ErrorCode.Unprocessable -> (() =>
      new Local().h.post(Bodies.coauthors().replace("DIRECT_COAUTHORS", "NOPE"))
    ),
    ErrorCode.RateLimited -> (() =>
      val l = new Local(
        Fx.settings.copy(rateLimit = RateLimitSettings(2, 60))
      )
      l.h.get("/healthz")
      l.h.get(s"/v1/graph/nodes/${Fx.ada}")
      l.h.get(s"/v1/graph/nodes/${Fx.ada}")
      l.h.get(s"/v1/graph/nodes/${Fx.ada}")
    ),
    ErrorCode.Internal -> (() =>
      new Local(jobs = Some(new ExplodingJobStore)).h.get("/v1/jobs/job-" + "1" * 32)
    ),
    ErrorCode.BadGateway -> (() =>
      val l = new Local()
      l.h.engine.behavior =
        l.h.engine.failure("evidence", StageFailure.Retryable("evidence", "store down"))
      l.h.post(Bodies.coauthors())
    ),
    ErrorCode.Unavailable -> (() =>
      val l = new Local()
      l.h.storeReady = false
      l.h.get("/readyz", token = None)
    ),
    ErrorCode.GatewayTimeout -> (() =>
      val l = new Local()
      l.h.graph.delayMillis = 1000
      l.h.get(
        s"/v1/graph/nodes/${Fx.ada}/neighbors",
        query = Map("windowStartYear" -> "2010", "windowEndYear" -> "2013", "timeoutMs" -> "30")
      )
    )
  )

  /** Owns a harness and closes it when the trigger is done reading. */
  final private class Local(
      settings: scholarchain.services.config.ServiceSettings = Fx.settings,
      jobs: Option[JobStore] = None
  ):
    val h = new Harness(settings = settings, jobStoreOverride = jobs)

  test("the model covers exactly 400 401 403 404 409 422 429 500 502 503 504") {
    ErrorCode.values.map(_.status).toSet shouldBe Set(400, 401, 403, 404, 409, 422, 429, 500, 502,
      503, 504)
    ErrorCode.values.map(_.wire).toSet should have size 11
    triggers.map(_._1.status).toSet shouldBe ErrorCode.values.map(_.status).toSet
  }

  test("each status has a typed code and a retryable flag, and is exercised") {
    triggers.foreach { (code, run) =>
      val r = run()
      withClue(s"${code.status}: ${r.raw}") {
        r.status shouldBe code.status
        r.err("status").num.toInt shouldBe code.status
        r.err("code").str shouldBe code.wire
        r.err("message").str should not be empty
        r.err("retryable").bool shouldBe code.retryable
      }
    }
    // the flags themselves
    ErrorCode.values.filter(_.retryable).map(_.status).toSet shouldBe Set(429, 502, 503, 504)
  }

  test("EVERY error body, on every status, carries traceId and requestId matching the headers") {
    triggers.foreach { (code, run) =>
      val r = run()
      withClue(s"${code.status}: ${r.raw}") {
        r.json.obj.contains("traceId") shouldBe true
        r.traceId should not be empty
        r.traceId shouldBe r.headers("x-trace-id")
        r.json("requestId").str shouldBe r.headers("x-request-id")
        r.json("requestId").str should startWith("req-")
        r.headers("content-type") shouldBe "application/json"
      }
    }
  }

  test("a trace ID sent by the caller is echoed on errors too; an unsafe one is replaced") {
    withHarness { h =>
      val r = h.call("GET", "/v1/nope", headers = Map("x-trace-id" -> "client-trace-77"))
      r.status shouldBe 404
      r.traceId shouldBe "client-trace-77"
      val unsafe = h.call("GET", "/v1/nope", headers = Map("x-trace-id" -> "bad id\nwith <script>"))
      unsafe.traceId should startWith("trace-")
      val w3c = h.call(
        "GET",
        "/v1/nope",
        headers = Map("traceparent" -> s"00-${"ab" * 16}-${"cd" * 8}-01")
      )
      w3c.traceId shouldBe "ab" * 16
    }
  }

  test(
    "errors that other layers produce carry traceId as well: malformed JSON, auth, unknown routes, 500, open circuit"
  ) {
    withHarness { h =>
      val unauth = h.call("GET", "/v1/nope", token = None)
      unauth.status shouldBe 401
      unauth.traceId should not be empty
      unauth.headers("www-authenticate") shouldBe "Bearer"
      val wrongMethod = h.call("DELETE", "/v1/query")
      wrongMethod.status shouldBe 404
      wrongMethod.traceId should not be empty
      val badJson = h.post("{")
      badJson.status shouldBe 400
      badJson.traceId should not be empty
      h.engine.behavior = h.engine.failure("x", StageFailure.Failed("x", "y"))
      (1 to 3).foreach(_ => h.post(Bodies.coauthors()))
      val open = h.post(Bodies.coauthors())
      (open.status, open.err("reason").str) shouldBe (503, "CIRCUIT_OPEN")
      open.traceId should not be empty
    }
  }

  test("the 500 fallback hides the cause: no message, class name, host or secret in body or log") {
    val l = new Local(jobs = Some(new ExplodingJobStore))
    try
      val r = l.h.get("/v1/jobs/job-" + "2" * 32)
      r.status shouldBe 500
      r.err("code").str shouldBe "INTERNAL_ERROR"
      r.err("retryable").bool shouldBe false
      val seen = r.raw + l.h.log.text
      Seq("hunter2", "secret-host", "IllegalStateException", "jdbc", "at scholarchain").foreach(w =>
        seen should not include w
      )
      l.h.log.records.last.status shouldBe 500
    finally l.h.close()
  }

  test("429 carries Retry-After and the window reopens") {
    val l = new Local(Fx.settings.copy(rateLimit = RateLimitSettings(2, 60)))
    try
      l.h.get(s"/v1/graph/nodes/${Fx.ada}").status shouldBe 200
      l.h.get(s"/v1/graph/nodes/${Fx.ada}").status shouldBe 200
      val limited = l.h.get(s"/v1/graph/nodes/${Fx.ada}")
      limited.status shouldBe 429
      limited.err("retryable").bool shouldBe true
      limited.headers("retry-after").toLong should (be >= 1L and be <= 60L)
      // another caller has its own budget
      l.h.get(s"/v1/graph/nodes/${Fx.ada}", token = Some(Fx.bobToken)).status shouldBe 200
      // probes are not rate limited
      l.h.get("/healthz", token = None).status shouldBe 200
      l.h.clock.advance(61_000)
      l.h.get(s"/v1/graph/nodes/${Fx.ada}").status shouldBe 200
    finally l.h.close()
  }

  test("no error body or log line carries a token, a stack trace or an exception text") {
    withHarness { h =>
      val bad = "wrong-token-value-123"
      val responses = Vector(
        h.get("/v1/jobs/x", token = Some(bad)),
        h.call("GET", "/v1/x", headers = Map("authorization" -> s"Basic $bad")),
        h.post("{", token = Some(Fx.aliceToken)),
        h.get(
          s"/v1/evidence/${scholarchain.core.ids.StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, Vector(Fx.ada, Fx.cyd), Fx.runId).toOption.get}"
        )
      )
      val seen = responses.map(_.raw).mkString + h.log.text
      Seq(bad, Fx.aliceToken, Fx.bobToken, "Bearer ", "Exception", "\tat ", "stacktrace").foreach(
        w => seen should not include w
      )
    }
  }
