package scholarchain.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.services.config.AuthFailureSettings
import scholarchain.services.http.HttpRequest
import scholarchain.services.security.{AuthFailureLimiter, SourceIdentity}

class AuthFailureLimiterSpec extends AnyFunSuite with Matchers:
  private def limiter(max: Int = 3, window: Int = 60, tracked: Int = 3, clock: FakeClock) =
    new AuthFailureLimiter(AuthFailureSettings(max, window, tracked, 0), clock)

  test("a source is refused after maxFailures failures, with the seconds left in the window") {
    val clock = new FakeClock
    val l = limiter(clock = clock)
    (1 to 3).foreach { _ =>
      l.check("1.1.1.1") shouldBe Right(())
      l.recordFailure("1.1.1.1")
    }
    l.check("1.1.1.1") shouldBe Left(60L)
    clock.advance(25_000)
    l.check("1.1.1.1") shouldBe Left(35L)
    l.check("2.2.2.2") shouldBe Right(()) // another source is not affected
  }

  test("the window ends by the injected clock, not by wall time") {
    val clock = new FakeClock
    val l = limiter(clock = clock)
    (1 to 3).foreach(_ => l.recordFailure("1.1.1.1"))
    l.check("1.1.1.1").isLeft shouldBe true
    clock.advance(59_999)
    l.check("1.1.1.1").isLeft shouldBe true
    clock.advance(1)
    l.check("1.1.1.1") shouldBe Right(())
    // a new failure starts a fresh window with a count of one
    l.recordFailure("1.1.1.1")
    l.check("1.1.1.1") shouldBe Right(())
  }

  test("memory is bounded: more sources than the limit never grow the table") {
    val clock = new FakeClock
    val l = limiter(tracked = 3, clock = clock)
    (1 to 50).foreach { i =>
      l.recordFailure(s"10.0.0.$i")
      l.trackedSources should be <= 3
    }
    l.trackedSources shouldBe 3
  }

  test("when the table is full, ended windows are dropped before a live one is evicted") {
    val clock = new FakeClock
    val l = limiter(max = 1, tracked = 2, clock = clock)
    l.recordFailure("a")
    clock.advance(30_000)
    l.recordFailure("b")
    clock.advance(31_000) // a's window ended, b's has not
    l.recordFailure("c")
    l.trackedSources shouldBe 2
    l.check("b").isLeft shouldBe true // b was kept and is still blocked
    l.check("c").isLeft shouldBe true
    l.check("a") shouldBe Right(()) // a was the one dropped
  }

  test("the oldest live window is evicted when every tracked window is still running") {
    val clock = new FakeClock
    val l = limiter(max = 1, tracked = 2, clock = clock)
    l.recordFailure("a")
    clock.advance(1000)
    l.recordFailure("b")
    clock.advance(1000)
    l.recordFailure("c")
    l.check("a") shouldBe Right(())
    l.check("b").isLeft shouldBe true
    l.check("c").isLeft shouldBe true
  }

class SourceIdentitySpec extends AnyFunSuite with Matchers:
  private def req(peer: Option[String], xff: Option[String] = None) =
    HttpRequest.of(
      "GET",
      "/v1/x",
      headers = xff.map("x-forwarded-for" -> _).toMap,
      remoteAddress = peer
    )

  test("with no trusted proxy the TCP peer is the source and X-Forwarded-For is ignored") {
    SourceIdentity.of(req(Some("203.0.113.9"), Some("198.51.100.1")), 0) shouldBe "203.0.113.9"
  }

  test(
    "behind one proxy the entry that proxy appended is the source; a forged left entry is ignored"
  ) {
    // the client typed "6.6.6.6"; the ALB appended the address it saw
    val r = req(Some("10.0.0.5"), Some("6.6.6.6, 203.0.113.9"))
    SourceIdentity.of(r, 1) shouldBe "203.0.113.9"
    // a client cannot choose its bucket by rotating the forged part
    SourceIdentity.of(req(Some("10.0.0.5"), Some("7.7.7.7, 203.0.113.9")), 1) shouldBe "203.0.113.9"
  }

  test("two hops read the second entry from the right") {
    SourceIdentity.of(req(Some("10.0.0.5"), Some("1.2.3.4, 203.0.113.9, 10.1.1.1")), 2) shouldBe
      "203.0.113.9"
  }

  test("a header with too few entries, or one that is not an IP literal, falls back to the peer") {
    SourceIdentity.of(req(Some("203.0.113.9"), Some("198.51.100.1")), 2) shouldBe "203.0.113.9"
    SourceIdentity.of(req(Some("203.0.113.9"), Some("<script>")), 1) shouldBe "203.0.113.9"
    SourceIdentity.of(req(Some("203.0.113.9"), Some("a" * 500)), 1) shouldBe "203.0.113.9"
  }

  test("a request with no usable peer shares one bucket, and the key is always short") {
    SourceIdentity.of(req(None), 0) shouldBe SourceIdentity.Unknown
    SourceIdentity.of(req(Some("not an address; DROP TABLE")), 0) shouldBe SourceIdentity.Unknown
    SourceIdentity.of(req(Some("2001:DB8::1")), 0) shouldBe "2001:db8::1"
  }

class AuthFailureApiSpec extends ApiSuite:
  private val bad = Some("not-a-real-token")

  test("repeated bad tokens from one source end in 429 with Retry-After, even for a valid token") {
    withHarness { h =>
      val src = Some("203.0.113.9")
      (1 to 3).foreach { _ =>
        h.call("GET", s"/v1/graph/nodes/${Fx.ada}", token = bad, remote = src).status shouldBe 401
      }
      val limited = h.call("GET", s"/v1/graph/nodes/${Fx.ada}", token = bad, remote = src)
      limited.status shouldBe 429
      limited.err("code").str shouldBe "RATE_LIMITED"
      limited.err("reason").str shouldBe "AUTH_FAILURE_LIMIT"
      limited.err("retryable").bool shouldBe true
      limited.headers("retry-after") shouldBe "60"
      limited.headers("x-request-id") should startWith("req-")
      limited.traceId should not be empty
      // a correct key from the blocked source is refused too: the limit cannot be used as an oracle
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}", remote = src).status shouldBe 429
      // after the window the source may try again
      h.clock.advance(60_000)
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}", remote = src).status shouldBe 200
    }
  }

  test("one source's failures do not block another, and probes stay open") {
    withHarness { h =>
      (1 to 4).foreach(_ => h.call("GET", "/v1/jobs/x", token = bad, remote = Some("198.51.100.1")))
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}", remote = Some("198.51.100.2")).status shouldBe 200
      h.call("GET", "/healthz", token = None, remote = Some("198.51.100.1")).status shouldBe 200
      h.call("GET", "/readyz", token = None, remote = Some("198.51.100.1")).status shouldBe 200
    }
  }

  test("a forged X-Forwarded-For does not change the source when no proxy is trusted") {
    withHarness { h =>
      val peer = Some("203.0.113.9")
      (1 to 3).foreach { i =>
        h.call(
          "GET",
          "/v1/jobs/x",
          token = bad,
          remote = peer,
          headers = Map("x-forwarded-for" -> s"9.9.9.$i")
        ).status shouldBe 401
      }
      h.call(
        "GET",
        "/v1/jobs/x",
        token = bad,
        remote = peer,
        headers = Map("x-forwarded-for" -> "9.9.9.200")
      ).status shouldBe 429
    }
  }

  test("behind a trusted proxy the appended address is the source") {
    val settings = Fx.settings.copy(auth =
      Fx.settings.auth.copy(failures = Fx.settings.auth.failures.copy(trustedProxyHops = 1))
    )
    withHarness(new Harness(settings = settings)) { h =>
      def call(forged: String) = h.call(
        "GET",
        "/v1/jobs/x",
        token = bad,
        remote = Some("10.0.0.5"),
        headers = Map("x-forwarded-for" -> s"$forged, 203.0.113.9")
      )
      Seq("1.1.1.1", "2.2.2.2", "3.3.3.3").foreach(f => call(f).status shouldBe 401)
      call("4.4.4.4").status shouldBe 429
      h.call(
        "GET",
        "/v1/jobs/x",
        token = bad,
        remote = Some("10.0.0.5"),
        headers = Map("x-forwarded-for" -> "x, 203.0.113.77")
      ).status shouldBe 401
    }
  }

  test("neither the token nor the source address reaches the access log or the response") {
    withHarness { h =>
      val r = h.call(
        "GET",
        "/v1/jobs/x",
        token = Some("super-secret-attempt"),
        remote = Some("203.0.113.9")
      )
      (1 to 3).foreach(_ =>
        h.call(
          "GET",
          "/v1/jobs/x",
          token = Some("super-secret-attempt"),
          remote = Some("203.0.113.9")
        )
      )
      val all = h.log.text + r.raw + h.call("GET", "/v1/jobs/x", remote = Some("203.0.113.9")).raw
      all should not include "super-secret-attempt"
      all should not include "203.0.113.9"
    }
  }

  test("a successful request does not clear the failure count") {
    withHarness { h =>
      val src = Some("203.0.113.9")
      h.call("GET", "/v1/jobs/x", token = bad, remote = src)
      h.call("GET", "/v1/jobs/x", token = bad, remote = src)
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}", remote = src).status shouldBe 200
      h.call("GET", "/v1/jobs/x", token = bad, remote = src).status shouldBe 401
      h.call("GET", s"/v1/graph/nodes/${Fx.ada}", remote = src).status shouldBe 429
    }
  }
