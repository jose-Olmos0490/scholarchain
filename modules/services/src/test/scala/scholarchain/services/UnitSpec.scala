package scholarchain.services

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.agents.StageFailure
import scholarchain.rag.llm.LlmUsage
import scholarchain.rag.workflow.WorkflowFailure
import scholarchain.services.config.*
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.query.FailureMapper
import scholarchain.services.resilience.*
import scholarchain.services.security.*

class SettingsSpec extends AnyFunSuite with Matchers:

  private val deployment = ConfigFactory.parseString(
    s"""scholarchain.services.serving { runId = "${Fx.runId}", snapshot = "${Fx.snapshot}" }"""
  )

  test("the shipped configuration loads once the deployment names its run and snapshot") {
    val s = ServiceSettings.load(deployment).fold(p => fail(p.mkString("; ")), identity)
    s.serving shouldBe ServingSettings(Fx.runId, Fx.snapshot)
    s.graph.edgeTypes shouldBe Vector("COAUTHORED_WITH")
    s.ops.metricsEnabled shouldBe false
    s.auth.keySecretName shouldBe None
  }

  test("a missing run or snapshot is reported by key, never defaulted") {
    val problems = ServiceSettings.load().left.toOption.get
    problems.exists(_.contains("serving.runId")) shouldBe true
    problems.exists(_.contains("serving.snapshot")) shouldBe true
  }

  test("out-of-range values are reported") {
    val bad = ConfigFactory
      .parseString("scholarchain.services.rateLimit.requestsPerWindow = 0")
      .withFallback(deployment)
    ServiceSettings
      .load(bad)
      .left
      .toOption
      .get
      .exists(_.contains("rateLimit.requestsPerWindow")) shouldBe true
  }

  test("the shared limits come from the core configuration values") {
    val l = RuntimeLimits.from(
      scholarchain.core.config.OperationsConfig(1234, 2, 3),
      scholarchain.core.config.RetrievalConfig(7, 8, 3, 100, "fusion-v1", 10, 4321, 50, 25)
    )
    (l.synchronousDeadlineMs, l.maxWorkflowRetries, l.graphLimit, l.maxPathDepth) shouldBe (
      1234L,
      2,
      7,
      3
    )
    l.graphTimeoutMillis shouldBe 4321L
    l.pageSize shouldBe 7 // the page size never exceeds the graph limit
  }

class SecurityUnitSpec extends AnyFunSuite with Matchers:

  test("API key documents parse, and a bad one is refused without echoing it") {
    val good =
      s"""[{"id":"svc-a","sha256":"${Sha256Hex.of("t")}","scopes":["query:write","graph:read"]}]"""
    ApiKeyJson.parse(good).toOption.get.head.scopes shouldBe Set("query:write", "graph:read")
    Seq(
      "not json",
      """[{"id":"a#b","sha256":"00","scopes":["query:write"]}]""",
      s"""[{"id":"x","sha256":"${Sha256Hex.of("t")}","scopes":["root"]}]""",
      s"""[{"id":"x","sha256":"${Sha256Hex.of("t")}","scopes":[]}]"""
    ).foreach { doc =>
      val e = ApiKeyJson.parse(doc)
      e.isLeft shouldBe true
      e.left.toOption.get should not include "root"
    }
  }

  test(
    "the bearer authenticator matches by digest, ignores case of the scheme and caps token size"
  ) {
    val auth = new BearerAuthenticator(Fx.keys, 64)
    auth.authenticate(Some(s"bearer ${Fx.aliceToken}")).toOption.get.id shouldBe "alice"
    auth.authenticate(Some(s"Bearer ${Fx.aliceToken}x")) shouldBe Left(AuthFailure.Unknown)
    auth.authenticate(Some("Bearer " + "a" * 65)) shouldBe Left(AuthFailure.Malformed)
    auth.authenticate(None) shouldBe Left(AuthFailure.Missing)
  }

  test("the rate limiter counts per principal per window") {
    val clock = new FakeClock
    val l = new RateLimiter(RateLimitSettings(2, 10), clock)
    l.tryAcquire("a") shouldBe Right(())
    l.tryAcquire("a") shouldBe Right(())
    l.tryAcquire("a") shouldBe Left(10L)
    l.tryAcquire("b") shouldBe Right(())
    clock.advance(4000)
    l.tryAcquire("a") shouldBe Left(6L)
    clock.advance(6000)
    l.tryAcquire("a") shouldBe Right(())
  }

class ResilienceUnitSpec extends AnyFunSuite with Matchers:
  private val down = ApiError.of(ErrorCode.BadGateway, "x", "down")

  test("a breaker opens at the threshold, half-opens after the pause and closes on success") {
    val clock = new FakeClock
    val b = new CircuitBreaker("d", BreakerSettings(2, 1000), clock)
    b.guard[Int](Left(down))
    b.current shouldBe BreakerState.Closed
    b.guard[Int](Left(down))
    b.current shouldBe BreakerState.Open
    var called = false
    b.guard { called = true; Right(1) }.left.toOption.get.reason shouldBe Some("CIRCUIT_OPEN")
    called shouldBe false
    clock.advance(1000)
    b.current shouldBe BreakerState.HalfOpen
    b.guard[Int](Left(down)) // the trial fails: open again
    b.current shouldBe BreakerState.Open
    clock.advance(1000)
    b.guard(Right(1)) shouldBe Right(1)
    b.current shouldBe BreakerState.Closed
  }

  test("only one trial call passes while half-open") {
    val clock = new FakeClock
    val b = new CircuitBreaker("d", BreakerSettings(1, 1000), clock)
    b.guard[Int](Left(down))
    clock.advance(1000)
    var second: Either[ApiError, Int] = Right(0)
    b.guard { second = b.guard(Right(2)); Right(1) } shouldBe Right(1)
    second.left.toOption.get.reason shouldBe Some("CIRCUIT_OPEN")
  }

  test("the retrier retries only retryable errors, within the bound, and never a rate limit") {
    val sleeper = new RecordingSleeper
    val r = new Retrier(RetrySettings(2, 5, 100), sleeper)
    var n = 0
    r.reads { n += 1; Left(down) }.isLeft shouldBe true
    n shouldBe 3
    sleeper.sleeps.toVector shouldBe Vector(5L, 10L)
    n = 0
    r.reads { n += 1; Left(ApiError.notFound("x")) }
    n shouldBe 1
    n = 0
    r.reads { n += 1; Left(ApiError.of(ErrorCode.RateLimited, "RATE_LIMIT", "x")) }
    n shouldBe 1
    n = 0
    r.reads { n += 1; if n < 2 then Left(down) else Right("ok") } shouldBe Right("ok")
  }

  test("the bounded executor answers 504 on a deadline and 503 when full") {
    val ex = new BoundedExecutor(ExecutionSettings(1, 1))
    try
      ex.callWithin(50)({ Thread.sleep(1000); Right(1) })
        .left
        .toOption
        .get
        .code shouldBe ErrorCode.GatewayTimeout
      ex.callWithin(1000)(Right(7)) shouldBe Right(7)
      val gate = new java.util.concurrent.CountDownLatch(1)
      val started = new java.util.concurrent.CountDownLatch(1)
      ex.submit { started.countDown(); gate.await() } shouldBe true // occupies the thread
      started.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      ex.submit(gate.await()) shouldBe true // fills the queue
      ex.submit(()) shouldBe false
      ex.callWithin(100)(Right(1)).left.toOption.get.code shouldBe ErrorCode.Unavailable
      gate.countDown()
    finally ex.shutdown()
  }

class FailureMapperSpec extends AnyFunSuite with Matchers:
  private def map(f: StageFailure) =
    FailureMapper.toApi(WorkflowFailure("trace-1", "stage-x", f, Vector.empty, LlmUsage.Zero))

  test("every runtime failure maps to one typed status; server faults are 500") {
    val table = Seq[(StageFailure, ErrorCode)](
      StageFailure.Rejected("OPERATION_NOT_ALLOWED", "x") -> ErrorCode.Unprocessable,
      StageFailure.Rejected("INVALID_REQUEST", "x") -> ErrorCode.BadRequest,
      StageFailure.Rejected("IDEMPOTENCY_KEY_REUSED", "x") -> ErrorCode.Conflict,
      StageFailure.Rejected("TRACE_ID_INVALID", "x") -> ErrorCode.Internal,
      StageFailure.Rejected("ANSWER_INVALID", "x") -> ErrorCode.Internal,
      StageFailure.Unsupported("venue filter") -> ErrorCode.Unprocessable,
      StageFailure.IdempotencyConflict("x") -> ErrorCode.Conflict,
      StageFailure.NotFound("k") -> ErrorCode.Unprocessable,
      StageFailure
        .Retryable("s", "the stage did not finish within 5 ms") -> ErrorCode.GatewayTimeout,
      StageFailure
        .Retryable("s", "too many stages are running; try again") -> ErrorCode.Unavailable,
      StageFailure.Retryable("s", "dynamo reset") -> ErrorCode.BadGateway,
      StageFailure.Failed("s", "boom") -> ErrorCode.BadGateway
    )
    table.foreach((f, code) => map(f).code shouldBe code)
    map(StageFailure.Failed("s", "boom")).retryable shouldBe false
    map(StageFailure.Retryable("s", "x")).retryable shouldBe true
  }

  test("typed Timeout is 504 and typed Overloaded is 503, whatever the detail says") {
    val timeout = map(StageFailure.Timeout("model", "no marker words here"))
    timeout.code shouldBe ErrorCode.GatewayTimeout
    timeout.reason shouldBe Some("STAGE_TIMEOUT")
    timeout.retryable shouldBe true
    val busy = map(StageFailure.Overloaded("model", "no marker words here"))
    busy.code shouldBe ErrorCode.Unavailable
    busy.reason shouldBe Some("OVERLOADED")
    busy.retryable shouldBe true
    // the status comes from the type: marker text in a Failed does not change it
    map(StageFailure.Failed("s", "did not finish within 5 ms")).code shouldBe ErrorCode.BadGateway
    // the typed answer never repeats the detail
    map(StageFailure.Timeout("model", "secret-detail")).message should not include "secret-detail"
  }

  test("a detail or code from a stage is never echoed unless it is a plain reason token") {
    val e = map(StageFailure.Rejected("bad code with <html> and secrets", "password=hunter2"))
    e.reason shouldBe Some("REFUSED")
    e.message should not include "hunter2"
  }
