package scholarchain.services

import scholarchain.services.config.OpsSettings

class AuthSpec extends ApiSuite:

  private val protectedCalls: Vector[(String, String)] = Vector(
    "POST" -> "/v1/query",
    "GET" -> s"/v1/jobs/job-${"0" * 32}",
    "GET" -> s"/v1/jobs/job-${"0" * 32}/result",
    "GET" -> "/v1/evidence/evidence:x",
    "GET" -> s"/v1/graph/nodes/${Fx.ada}",
    "GET" -> s"/v1/graph/nodes/${Fx.ada}/neighbors",
    "GET" -> "/v1/explain/query:x",
    "GET" -> "/metrics"
  )

  test(
    "every protected route refuses a request with no token, a wrong token and a malformed header"
  ) {
    withHarness { h =>
      protectedCalls.foreach { (m, p) =>
        withClue(s"$m $p") {
          val none = h.call(m, p, token = None, body = Option.when(m == "POST")("{}"))
          none.status shouldBe 401
          none.err("code").str shouldBe "UNAUTHENTICATED"
          none.err("reason").str shouldBe "MISSING_TOKEN"
          none.headers("www-authenticate") shouldBe "Bearer"
          h.call(m, p, token = Some("not-a-real-token")).err("reason").str shouldBe "INVALID_TOKEN"
          h.call(m, p, token = None, headers = Map("authorization" -> "Token abc"))
            .err("reason")
            .str shouldBe "MALFORMED_TOKEN"
        }
      }
      h.engine.calls.get() shouldBe 0
      h.graph.calls.get() shouldBe 0
    }
  }

  test("a valid key without the route's scope is 403, and does no work") {
    withHarness { h =>
      val r = h.post(Bodies.coauthors(), token = Some(Fx.readerToken))
      r.status shouldBe 403
      r.err("code").str shouldBe "FORBIDDEN"
      r.err("reason").str shouldBe "MISSING_SCOPE"
      h.engine.calls.get() shouldBe 0
      h.get(s"/v1/graph/nodes/${Fx.ada}", token = Some(Fx.readerToken)).status shouldBe 200
      h.get("/v1/explain/query:x", token = Some(Fx.readerToken)).status shouldBe 403
      h.get("/v1/evidence/evidence:x", token = Some(Fx.readerToken)).status shouldBe 403
    }
  }

  test("an unknown route needs a valid token too, so routes cannot be probed anonymously") {
    withHarness { h =>
      h.get("/v1/secrets", token = None).status shouldBe 401
      h.get("/v1/secrets").status shouldBe 404
    }
  }

  test("only the SHA-256 of a token is held: the key record never contains the token") {
    Fx.keys.foreach { k =>
      k.tokenSha256 should fullyMatch regex "[0-9a-f]{64}"
      Seq(Fx.aliceToken, Fx.bobToken, Fx.readerToken).foreach(t => k.toString should not include t)
    }
  }

class OpsApiSpec extends ApiSuite:

  test("health and readiness are distinct: liveness stays up while readiness fails") {
    withHarness { h =>
      h.call("GET", "/healthz", token = None).status shouldBe 200
      h.call("GET", "/readyz", token = None).status shouldBe 200
      h.storeReady = false
      val health = h.call("GET", "/healthz", token = None)
      health.status shouldBe 200
      health.json("status").str shouldBe "ok"
      val ready = h.call("GET", "/readyz", token = None)
      ready.status shouldBe 503
      ready.err("code").str shouldBe "UNAVAILABLE"
      ready.err("reason").str shouldBe "NOT_READY"
      ready.err("details")("checks")("evidenceStore").str shouldBe "failing"
      ready.traceId should not be empty
      h.storeReady = true
      h.call("GET", "/readyz", token = None).json("checks")("evidenceStore").str shouldBe "ok"
    }
  }

  test("a readiness probe that hangs is failing within the configured time limit") {
    val ops = OpsSettings(metricsEnabled = true, readinessTimeoutMillis = 80)
    val service = new scholarchain.services.ops.OpsService(
      Vector(scholarchain.services.ops.ReadinessProbe("slow", () => { Thread.sleep(1500); true })),
      new scholarchain.services.resilience.BoundedExecutor(
        scholarchain.services.config.ExecutionSettings(1, 1)
      ),
      ops,
      new scholarchain.services.ops.Metrics,
      Nil
    )
    val t0 = System.nanoTime()
    val ready = service.ready
    ready.isLeft shouldBe true
    ready.left.toOption.get.details("checks")("slow").str shouldBe "failing"
    ((System.nanoTime() - t0) / 1_000_000L) should be < 1000L
  }

  test(
    "health and readiness expose nothing sensitive: no version, host, endpoint, key or error text"
  ) {
    withHarness { h =>
      h.storeReady = false
      val bodies = Seq(
        h.call("GET", "/healthz", token = None).raw,
        h.call("GET", "/readyz", token = None).raw
      ).mkString
      Seq(
        "version",
        "host",
        "endpoint",
        "arn:",
        "http",
        "password",
        "token",
        "Exception",
        "s3://",
        "dynamodb"
      )
        .foreach(w => bodies.toLowerCase should not include w.toLowerCase)
      h.call("GET", "/healthz", token = None).json.obj.keySet shouldBe Set("status")
    }
  }

  test("the probes need no token and are not counted against a caller") {
    withHarness { h =>
      (1 to 5).foreach(_ => h.call("GET", "/healthz", token = None).status shouldBe 200)
      h.call("POST", "/healthz", token = None).status shouldBe 401 // only GET is public
    }
  }

  test(
    "metrics: off by default when configured off (404), on needs the ops scope, and labels hold no IDs"
  ) {
    val off =
      Fx.settings.copy(ops = OpsSettings(metricsEnabled = false, readinessTimeoutMillis = 200))
    withHarness(new Harness(settings = off)) { h =>
      h.get("/metrics").status shouldBe 404
    }
    withHarness { h =>
      h.post(Bodies.coauthors()).status shouldBe 200
      h.get(s"/v1/graph/nodes/${Fx.ada}")
      h.get("/metrics", token = Some(Fx.readerToken)).status shouldBe 403
      val m = h.get("/metrics")
      m.status shouldBe 200
      m.headers("content-type") should startWith("text/plain")
      m.raw should include(
        """scholarchain_http_requests_total{route="POST /v1/query",status="200"} 1"""
      )
      m.raw should include("""route="GET /v1/graph/nodes/{nodeId}"""")
      m.raw should include("""scholarchain_circuit_state{dependency="workflow",state="Closed"} 1""")
      m.raw should not include Fx.ada
      m.raw should not include "query:"
    }
  }
