package scholarchain.adversarial.services

import java.net.{ConnectException, URI}
import java.net.http.{HttpClient, HttpRequest as JReq, HttpResponse as JResp}
import java.time.Duration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.services.config.*
import scholarchain.services.entrypoint.*
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.query.InMemoryJobStore
import scholarchain.services.runtime.{
  QueryApiPorts,
  ResourceApiPorts,
  RuntimeEnvironment,
  ServiceRuntime
}
import scholarchain.services.security.BearerAuthenticator
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}

/** The deployable entrypoints over a real loopback socket (port 0, fakes behind the ports) and
  * the live `open` functions with hostile or missing environments. Nothing here reaches AWS.
  */
class EntrypointAdvSpec extends AnyFunSuite with Matchers:

  private val Marker = "ZZ-ENTRY-MARKER-1"
  private val loopback = BindSettings("127.0.0.1", 0)
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

  final private case class R(status: Int, body: String, headers: Map[String, String])

  private def send(
      port: Int,
      method: String,
      rawPath: String,
      token: Option[String] = Some(Kit.aliceToken),
      body: Option[Array[Byte]] = None,
      ct: Option[String] = Some("application/json")
  ): R =
    val b = JReq
      .newBuilder(URI.create(s"http://127.0.0.1:$port$rawPath"))
      .timeout(Duration.ofSeconds(10))
      .method(method, body.fold(JReq.BodyPublishers.noBody())(JReq.BodyPublishers.ofByteArray(_)))
    token.foreach(t => b.header("Authorization", s"Bearer $t"))
    if body.isDefined then ct.foreach(c => b.header("Content-Type", c))
    val r = http.send(b.build(), JResp.BodyHandlers.ofString())
    R(
      r.statusCode(),
      r.body(),
      scala.jdk.CollectionConverters
        .MapHasAsScala(r.headers().map())
        .asScala
        .map((k, v) => k.toLowerCase -> v.get(0))
        .toMap
    )

  private def withQueryApi[A](f: (RunningHttp, ScriptedEngine) => A): A =
    val dynamo = DynamoPorts(new InMemoryItemStore, Kit.tables, 86400L)
    val engine = new ScriptedEngine(dynamo)
    val running = QueryApiRoot.start(
      QueryApiInputs(
        Kit.settings(),
        QueryApiSettings(
          loopback,
          LlmSettings(2, 1, 2, 1000),
          WorkflowTuningSettings(
            "unused",
            "answer-synthesis-prompt-v3",
            "answer-synthesis-v2",
            1000,
            None,
            200,
            100,
            100
          )
        ),
        Kit.limits(),
        QueryApiPorts(
          engine,
          dynamo.querySessions,
          dynamo.evidence,
          dynamo.queryAudit,
          new InMemoryJobStore,
          Vector(ReadinessProbe("s", () => true))
        ),
        new BearerAuthenticator(Kit.keys, 256),
        RuntimeEnvironment()
      )
    )
    try f(running, engine)
    finally running.stop()

  test("over a real socket: auth is enforced, probes are public, correlation headers are present") {
    withQueryApi { (run, engine) =>
      send(run.port, "GET", "/healthz", None).status shouldBe 200
      send(run.port, "GET", "/readyz", None).status shouldBe 200
      val noAuth =
        send(run.port, "POST", "/v1/query", None, Some(Kit.coauthorsBody().getBytes("UTF-8")))
      noAuth.status shouldBe 401
      noAuth.headers should contain key "x-request-id"
      noAuth.headers.get("www-authenticate") shouldBe Some("Bearer")
      engine.calls.get shouldBe 0
      send(
        run.port,
        "POST",
        "/v1/query",
        body = Some(Kit.coauthorsBody().getBytes("UTF-8"))
      ).status shouldBe 200
    }
  }

  test("a body far beyond the limit is refused 400 without the server buffering it or hanging") {
    withQueryApi { (run, engine) =>
      val big = ("{\"question\":\"" + "A" * (4 * 1024 * 1024) + "\"}").getBytes("UTF-8")
      val t0 = System.nanoTime()
      val x = send(run.port, "POST", "/v1/query", body = Some(big))
      x.status shouldBe 400
      ujson.read(x.body)("error")("reason").str shouldBe "BODY_TOO_LARGE"
      ((System.nanoTime() - t0) / 1_000_000L) should be < 5000L
      engine.calls.get shouldBe 0
      // the service is still healthy afterwards
      send(run.port, "GET", "/healthz", None).status shouldBe 200
    }
  }

  test(
    "a malformed percent-encoded query string is a caller error: 400 (QueryString.parse contract)"
  ) {
    withQueryApi { (run, _) =>
      QueryString_parseContract()
      // java.net.URI refuses the escape, so the request is written to the socket by hand
      val s = new java.net.Socket("127.0.0.1", run.port)
      val status =
        try
          s.setSoTimeout(5000)
          val out = s.getOutputStream
          out.write(
            s"GET /v1/jobs/job-${"0" * 32}?a=%zz HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${Kit.aliceToken}\r\nConnection: close\r\n\r\n"
              .getBytes("US-ASCII")
          )
          out.flush()
          new String(s.getInputStream.readAllBytes(), "UTF-8")
        finally s.close()
      withClue(s"observed: ${status.take(200)}: ") {
        status should startWith("HTTP/1.1 400")
      }
    }
  }

  private def QueryString_parseContract(): Unit =
    scholarchain.services.http.QueryString.parse("a=%zz") shouldBe None
    scholarchain.services.http.QueryString.parse("a=%") shouldBe None

  test("hostile paths over the wire are 4xx or 404, never 5xx, and are not echoed") {
    withQueryApi { (run, _) =>
      Seq(
        "/v1/jobs/%2e%2e%2f%2e%2e%2fetc%2fpasswd",
        "/v1/jobs/%00",
        "/v1/jobs/" + Marker,
        "//v1//query",
        "/v1/jobs/job-" + "0" * 32 + "/../../metrics",
        "/%ff%fe",
        "/v1/explain/" + "%E2%80%AE" * 50
      ).foreach { p =>
        val x = send(run.port, "GET", p)
        withClue(p)(x.status should (be >= 400 and be < 500))
        x.body should not include Marker
      }
    }
  }

  test("unexpected methods over the wire (HEAD, OPTIONS, TRACE-like) are not 5xx") {
    withQueryApi { (run, _) =>
      Seq("HEAD", "OPTIONS", "PUT", "DELETE", "PATCH").foreach { m =>
        val x = send(run.port, m, "/v1/query")
        withClue(m)(x.status should be < 500)
      }
    }
  }

  test("stop() releases the port: later connections are refused") {
    val dynamo = DynamoPorts(new InMemoryItemStore, Kit.tables, 86400L)
    val running = QueryApiRoot.start(
      QueryApiInputs(
        Kit.settings(),
        QueryApiSettings(
          loopback,
          LlmSettings(2, 1, 2, 1000),
          WorkflowTuningSettings("u", "p", "s", 1000, None, 200, 100, 100)
        ),
        Kit.limits(),
        QueryApiPorts(
          new ScriptedEngine(dynamo),
          dynamo.querySessions,
          dynamo.evidence,
          dynamo.queryAudit,
          new InMemoryJobStore,
          Vector.empty
        ),
        new BearerAuthenticator(Kit.keys, 256)
      )
    )
    val port = running.port
    send(port, "GET", "/healthz", None).status shouldBe 200
    running.stop()
    an[java.io.IOException] should be thrownBy send(port, "GET", "/healthz", None)
  }

  test(
    "the graph/evidence root serves only its own routes: /v1/query is not mounted, and a probes-only runtime opens nothing"
  ) {
    val dynamo = DynamoPorts(new InMemoryItemStore, Kit.tables, 86400L)
    val g = GraphEvidenceApiRoot.start(
      GraphEvidenceApiInputs(
        Kit.settings(),
        GraphEvidenceApiSettings(loopback),
        Kit.limits(),
        ResourceApiPorts(dynamo.evidence, new ScriptedGraph, Vector.empty),
        new BearerAuthenticator(Kit.keys, 256)
      )
    )
    try
      send(
        g.port,
        "POST",
        "/v1/query",
        body = Some(Kit.coauthorsBody().getBytes("UTF-8"))
      ).status shouldBe 404
      send(g.port, "GET", s"/v1/graph/nodes/${Kit.ada}").status shouldBe 200
      send(g.port, "GET", "/v1/jobs/job-" + "0" * 32).status shouldBe 404
    finally g.stop()

    val rt = ServiceRuntime.probesOnly(Kit.settings(), Vector(ReadinessProbe("w", () => true)))
    val run = RunningHttp.start(rt, 8192, loopback)
    try
      send(run.port, "GET", "/healthz", None).status shouldBe 200
      // no key can open any route of a probes-only worker
      send(run.port, "GET", "/metrics", Some(Kit.aliceToken)).status shouldBe 401
      send(
        run.port,
        "POST",
        "/v1/query",
        Some(Kit.aliceToken),
        Some(Array.emptyByteArray)
      ).status shouldBe 401
    finally run.stop()
  }

  // ---- live `open` with missing or hostile configuration ----------------------------------------

  private def noLiveSecretsConfigured(): Boolean =
    !sys.env.keys.exists(k =>
      k.startsWith(
        "SCHOLARCHAIN_SVC_"
      ) || k == "SCHOLARCHAIN_ENV" || k == "RAW_BUCKET" || k == "NEPTUNE_ENDPOINT"
    )

  test(
    "QueryApiMain.open with an empty or hostile environment fails closed, never throws, never echoes values"
  ) {
    assume(noLiveSecretsConfigured(), "the process environment configures a live deployment")
    val hostile = Map(
      "SCHOLARCHAIN_ENV" -> "dev",
      "AWS_SECRET_ACCESS_KEY" -> Marker,
      "NEPTUNE_ENDPOINT" -> Marker,
      "RAW_BUCKET" -> Marker,
      "NEPTUNE_PORT" -> "not-a-port"
    )
    Seq(Map.empty[String, String], hostile).foreach { env =>
      val r = QueryApiMain.open(env)
      r.isLeft shouldBe true
      r.left.toOption.get.foreach(p => p should not include Marker)
      r.left.toOption.get should not be empty
    }
  }

  test(
    "GraphEvidenceApiMain.open and EmbeddingWorkerMain.open fail closed on missing configuration"
  ) {
    assume(noLiveSecretsConfigured(), "the process environment configures a live deployment")
    val env = Map("SCHOLARCHAIN_ENV" -> "dev", "AWS_SECRET_ACCESS_KEY" -> Marker)
    GraphEvidenceApiMain.open(env).left.toOption.get.foreach(p => p should not include Marker)
    EmbeddingWorkerMain.open(env).left.toOption.get.foreach(p => p should not include Marker)
    GraphEvidenceApiMain.open(Map.empty).isLeft shouldBe true
    EmbeddingWorkerMain.open(Map.empty).isLeft shouldBe true
  }

  test("CoreConfigs omissions are reported as typed config errors, one section at a time") {
    CoreConfigs.query(Map.empty).isLeft shouldBe true
    CoreConfigs.resource(Map.empty).isLeft shouldBe true
    CoreConfigs.query(Map("SCHOLARCHAIN_ENV" -> "../x")).isLeft shouldBe true
    CoreConfigs
      .query(Map("SCHOLARCHAIN_ENV" -> "dev", "AWS_SECRET_ACCESS_KEY" -> Marker))
      .left
      .toOption
      .get
      .message should not include Marker
  }

  test(
    "RefreshingApiKeys: start-up fails closed on no keys; a failing refresh keeps the keys in force"
  ) {
    import scholarchain.services.security.{ApiKeyRecord, ApiKeySource}
    val clock = new FakeClock
    val rec = Kit.keys.head
    var mode = "ok"
    val src = new ApiKeySource:
      def load(): Either[String, Vector[ApiKeyRecord]] = mode match
        case "ok" => Right(Vector(rec))
        case "empty" => Right(Vector.empty)
        case "fail" => Left(Marker)
        case _ => throw new IllegalStateException(Marker)
    val keys = RefreshingApiKeys.load(src, 10, clock).toOption.get
    mode = "fail"; clock.advance(11_000); keys.current shouldBe Vector(rec)
    mode = "empty"; clock.advance(11_000); keys.current shouldBe Vector(rec)
    mode = "throw"; clock.advance(11_000); keys.current shouldBe Vector(rec)
    keys.loaded shouldBe true
    mode = "empty"
    RefreshingApiKeys.load(src, 10, clock).isLeft shouldBe true
    mode = "fail"
    RefreshingApiKeys
      .load(src, 10, clock)
      .left
      .toOption
      .get shouldBe Marker // the source's own text; see report on echo risk
  }
