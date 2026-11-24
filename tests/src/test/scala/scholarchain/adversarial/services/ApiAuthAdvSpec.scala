package scholarchain.adversarial.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.services.security.*

/** Authentication, authorization, rate limiting and the response envelope, attacked through the
  * transport-neutral API (no AWS, no sockets).
  */
class ApiAuthAdvSpec extends AnyFunSuite with Matchers:

  private def withRig[A](r: Rig)(f: Rig => A): A = try f(r)
  finally r.close()
  private def rig[A](f: Rig => A): A = withRig(new Rig)(f)

  private val Marker = "ZZ-SECRET-MARKER-9"

  test("no Authorization header: 401 with WWW-Authenticate and both correlation IDs") {
    rig { r =>
      val x = r.call("POST", "/v1/query", token = None, body = Some(Kit.coauthorsBody()))
      x.status shouldBe 401
      x.reason shouldBe Some("MISSING_TOKEN")
      x.headers("www-authenticate") shouldBe "Bearer"
      x.headers should contain key "x-request-id"
      x.headers should contain key "x-trace-id"
      x.json("requestId").str shouldBe x.headers("x-request-id")
      r.engine.calls.get shouldBe 0
    }
  }

  test("malformed Authorization headers are 401 and never reach a route") {
    val bad = Seq(
      "Basic YWxpY2U6cGFzcw==",
      "Bearer",
      "Bearer ",
      "Bearer    ",
      "Token alice-adv-token",
      "alice-adv-token",
      "Bearer\talice-adv-token",
      "Bearer alice-adv-token\nX-Injected: 1",
      "Bearer " + "a" * 257,
      "Bearer " + "a" * 100000,
      "Bearer \u0000",
      ""
    )
    rig { r =>
      bad.zipWithIndex.foreach { (h, i) =>
        val x = r.call(
          "POST",
          "/v1/query",
          None,
          Some(Kit.coauthorsBody()),
          authHeader = Some(h),
          remote = Some(s"192.0.2.$i")
        )
        withClue(s"header [${h.take(40).replace("\n", "\\n")}]: ") {
          x.status shouldBe 401
          x.raw should not include "alice-adv-token"
        }
      }
      r.engine.calls.get shouldBe 0
    }
  }

  test("an unknown token is 401 and the response never repeats it") {
    rig { r =>
      val x = r.call(
        "POST",
        "/v1/query",
        Some(Marker),
        Some(Kit.coauthorsBody()),
        remote = Some("192.0.2.200")
      )
      x.status shouldBe 401
      x.raw should not include Marker
      r.log.text should not include Marker
    }
  }

  test("a token that is a prefix or a case variant of a valid one does not authenticate") {
    rig { r =>
      Seq(
        Kit.aliceToken.dropRight(1),
        Kit.aliceToken.toUpperCase,
        Kit.aliceToken + " ",
        " " + Kit.aliceToken.drop(1)
      ).zipWithIndex.foreach { (t, i) =>
        // trailing space is trimmed by the parser; every other variant must fail
        val s =
          r.call("GET", "/v1/jobs/job-" + "0" * 32, Some(t), remote = Some(s"192.0.2.$i")).status
        if t == Kit.aliceToken + " " then s shouldBe 404 else s shouldBe 401
      }
    }
  }

  test("authorization: a graph-only key cannot submit queries, read jobs, explain or see metrics") {
    rig { r =>
      val rd = Some(Kit.readerToken)
      r.post(Kit.coauthorsBody(), rd).status shouldBe 403
      r.get("/v1/jobs/job-" + "0" * 32, rd).status shouldBe 403
      r.get("/v1/explain/query:" + "0" * 64, rd).status shouldBe 403
      r.get("/metrics", rd).status shouldBe 403
      r.get("/v1/evidence/evidence:" + "0" * 64, rd).status shouldBe 403
      r.engine.calls.get shouldBe 0
      val x = r.post(Kit.coauthorsBody(), rd)
      x.reason shouldBe Some("MISSING_SCOPE")
      x.raw should not include Kit.readerToken
    }
  }

  test("an unauthenticated probe of an unknown route is 401, so routes cannot be enumerated") {
    rig { r =>
      r.call("GET", "/v1/admin", token = None).status shouldBe 401
      r.call("GET", "/v1/admin").status shouldBe 404
      r.call("DELETE", "/v1/query", token = None).status shouldBe 401
    }
  }

  test("a known path with the wrong method never reaches a handler and is not a 5xx") {
    rig { r =>
      Seq("GET", "PUT", "DELETE", "PATCH").foreach { m =>
        val s = r.call(m, "/v1/query").status
        withClue(m)(Seq(404, 405) should contain(s))
      }
      r.engine.calls.get shouldBe 0
    }
  }

  test("probes are public, report no detail, and /readyz fails closed when a probe fails") {
    rig { r =>
      r.call("GET", "/healthz", token = None).status shouldBe 200
      r.call("GET", "/readyz", token = None).status shouldBe 200
      r.ready = false
      val x = r.call("GET", "/readyz", token = None)
      x.status shouldBe 503
      x.raw should not include "Exception"
    }
  }

  test("a readiness probe that throws or hangs makes /readyz 503 within its bound, never a 500") {
    import scholarchain.services.ops.ReadinessProbe
    val hang = ReadinessProbe("hang", () => { Thread.sleep(3000); true })
    val boom = ReadinessProbe("boom", () => throw new IllegalStateException(Marker))
    withRig(new Rig(extraProbes = Vector(hang, boom))) { r =>
      val t0 = System.nanoTime()
      val x = r.call("GET", "/readyz", token = None)
      val ms = (System.nanoTime() - t0) / 1_000_000
      x.status shouldBe 503
      x.raw should not include Marker
      ms should be < 2500L
    }
  }

  test("failed-auth limiter: a source is cut off after N failures, even with a valid key") {
    withRig(new Rig(Kit.settings(authFailMax = 3))) { r =>
      val src = Some("203.0.113.9")
      (1 to 3).foreach(_ =>
        r.call("GET", "/v1/jobs/x", Some("wrong"), remote = src).status shouldBe 401
      )
      val blocked = r.call("GET", "/v1/jobs/job-" + "0" * 32, remote = src)
      blocked.status shouldBe 429
      blocked.reason shouldBe Some("AUTH_FAILURE_LIMIT")
      blocked.headers should contain key "retry-after"
      // another source is unaffected
      r.call("GET", "/v1/jobs/job-" + "0" * 32, remote = Some("203.0.113.10")).status shouldBe 404
    }
  }

  test("a forged X-Forwarded-For cannot choose the failure bucket when no proxy is trusted") {
    withRig(new Rig(Kit.settings(authFailMax = 2))) { r =>
      val src = Some("198.51.100.1")
      (1 to 4).foreach { i =>
        r.call(
          "GET",
          "/v1/jobs/x",
          Some("wrong"),
          remote = src,
          headers = Map("x-forwarded-for" -> s"10.0.0.$i")
        )
      }
      val x = r.call(
        "GET",
        "/v1/jobs/job-" + "0" * 32,
        remote = src,
        headers = Map("x-forwarded-for" -> "10.9.9.9")
      )
      x.status shouldBe 429
    }
  }

  test("a success does not reset the failure count of a source") {
    withRig(new Rig(Kit.settings(authFailMax = 2))) { r =>
      val src = Some("198.51.100.77")
      r.call("GET", "/v1/jobs/x", Some("wrong"), remote = src)
      r.call("GET", "/v1/jobs/job-" + "0" * 32, remote = src).status shouldBe 404 // valid key
      r.call("GET", "/v1/jobs/x", Some("wrong"), remote = src)
      r.call("GET", "/v1/jobs/job-" + "0" * 32, remote = src).status shouldBe 429
    }
  }

  test("a source with no peer address and a bogus peer string share one bounded bucket") {
    SourceIdentity.of(
      scholarchain.services.http.HttpRequest.of("GET", "/", remoteAddress = None),
      0
    ) shouldBe SourceIdentity.Unknown
    SourceIdentity.of(
      scholarchain.services.http.HttpRequest
        .of("GET", "/", remoteAddress = Some("not an ip; DROP")),
      0
    ) shouldBe SourceIdentity.Unknown
  }

  test("the failure limiter's memory is bounded under a flood of distinct sources") {
    val clock = new FakeClock
    val l =
      new AuthFailureLimiter(scholarchain.services.config.AuthFailureSettings(3, 60, 50, 0), clock)
    (1 to 5000).foreach(i => l.recordFailure(s"10.0.${i / 250}.${i % 250}"))
    l.trackedSources should be <= 50
  }

  test(
    "the per-principal rate limit answers 429 with Retry-After and does not leak across principals"
  ) {
    withRig(new Rig(Kit.settings(rate = 2))) { r =>
      val p = "/v1/jobs/job-" + "0" * 32
      r.get(p).status shouldBe 404
      r.get(p).status shouldBe 404
      val x = r.get(p)
      x.status shouldBe 429
      x.headers should contain key "retry-after"
      r.get(p, Some(Kit.bobToken)).status shouldBe 404
    }
  }

  test("every response carries no-store and nosniff, errors included") {
    rig { r =>
      Seq(r.get("/nope", None), r.get("/nope"), r.post("{", None), r.post("{}")).foreach { x =>
        x.headers("cache-control") shouldBe "no-store"
        x.headers("x-content-type-options") shouldBe "nosniff"
        x.headers should contain key "x-request-id"
      }
    }
  }

  test("a caller-supplied trace ID is accepted only in a safe form") {
    rig { r =>
      val good = r.call("GET", "/healthz", None, headers = Map("x-trace-id" -> "abc-123"))
      good.headers("x-trace-id") shouldBe "abc-123"
      Seq("bad id with spaces", "a" * 129, "x\r\nSet-Cookie: s=1", "<script>", "").foreach { t =>
        val x = r.call("GET", "/healthz", None, headers = Map("x-trace-id" -> t))
        withClue(t.take(20)) {
          x.headers("x-trace-id") should startWith("trace-")
          x.headers("x-trace-id") should not include "\n"
        }
      }
    }
  }

  test("metrics are labeled by route template only: no job ID, token or query text appears") {
    rig { r =>
      val id = "job-" + "ab" * 16
      r.get(s"/v1/jobs/$id")
      r.get("/v1/jobs/" + Marker)
      r.post("{" + Marker)
      val m = r.get("/metrics")
      m.status shouldBe 200
      m.raw should not include id
      m.raw should not include Marker
      m.raw should not include Kit.aliceToken
    }
  }

  test(
    "metrics disabled in configuration: the route does not exist (404), even for a full-scope key"
  ) {
    withRig(new Rig(Kit.settings(metrics = false))) { r =>
      r.get("/metrics").status shouldBe 404
    }
  }

  test("key documents: a malformed or hostile secret is refused without echoing it") {
    val docs = Seq(
      Marker,
      s"""[{"id":"$Marker","sha256":"zz","scopes":["query:write"]}]""",
      s"""[{"id":"a:b","sha256":"${"0" * 64}","scopes":["query:write"]}]""",
      s"""[{"id":"ok","sha256":"${"0" * 64}","scopes":["query:write","$Marker"]}]""",
      "[null]",
      "{}",
      ""
    )
    docs.foreach { d =>
      val e = ApiKeyJson.parse(d)
      e.isLeft shouldBe true
      e.left.toOption.get should not include Marker
    }
  }

  test(
    "an empty key set authenticates nobody and an uppercase digest still matches (case-insensitive hex)"
  ) {
    new BearerAuthenticator(Vector.empty, 256).authenticate(Some("Bearer anything")) shouldBe Left(
      AuthFailure.Unknown
    )
    val up = Vector(ApiKeyRecord("u", Sha256Hex.of("tok").toUpperCase, Set(Scopes.QueryRead)))
    new BearerAuthenticator(up, 256).authenticate(Some("Bearer tok")).map(_.id) shouldBe Right("u")
  }
