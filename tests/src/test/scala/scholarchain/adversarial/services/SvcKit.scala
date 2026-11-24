package scholarchain.adversarial.services

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scholarchain.core.config.DynamoTablesConfig
import scholarchain.core.ids.{ResearcherNamespace, StableIds}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.rag.agents.StageFailure
import scholarchain.rag.llm.LlmUsage
import scholarchain.rag.workflow.{AnswerRequest, StageTrace, WorkflowFailure, WorkflowResult}
import scholarchain.services.config.*
import scholarchain.services.http.*
import scholarchain.services.ops.{AccessLog, AccessRecord, ReadinessProbe}
import scholarchain.services.query.{InMemoryJobStore, JobStore, QueryEngine}
import scholarchain.services.resilience.Sleeper
import scholarchain.services.runtime.{RuntimePorts, ServiceRuntime}
import scholarchain.services.security.*
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}
import scholarchain.storage.graph.*
import upickle.default.write

/** Independent fixtures for the adversarial service tests (the module's own TestKit is not on
  * this project's classpath, and these tests must not depend on the owner's fixtures anyway).
  */
object Kit:
  def ok[A](e: Either[String, A]): A = e.fold(m => throw new IllegalStateException(m), identity)

  val snapshot = "2026-11-01"
  val manifest: RunManifest = RunManifest(
    SV.RunManifest,
    "adv-services",
    "adv-services-v1",
    Vector(RunInput(SourceType.DBLP, snapshot, "fixture/dblp.xml", "sha256:" + "b" * 64)),
    Map.empty,
    Map.empty
  )
  val runId: String = ok(StableIds.runId(manifest))
  private def researcher(orcid: String) =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, orcid))
  val ada: String = researcher("0000-0002-1825-0097")
  val bea: String = researcher("0000-0001-5109-3700")
  val cyd: String = researcher("0000-0002-1694-233X")
  val ghost: String = researcher("0000-0002-9079-593X")
  val ws = 2010
  val we = 2013

  def settings(
      maxBody: Int = 8192,
      rate: Int = 1000,
      authFailMax: Int = 3,
      neighborMaxDepth: Int = 1,
      breakerThreshold: Int = 3,
      metrics: Boolean = true
  ): ServiceSettings = ServiceSettings(
    HttpSettings(maxBody, 500, 5, 8, 64),
    RateLimitSettings(rate, 60),
    BreakerSettings(breakerThreshold, 30000),
    RetrySettings(2, 10, 1000),
    ServingSettings(runId, snapshot),
    ExecutionSettings(4, 8),
    EvidenceSettings(40),
    GraphApiSettings(neighborMaxDepth, Vector("COAUTHORED_WITH")),
    OpsSettings(metricsEnabled = metrics, readinessTimeoutMillis = 200),
    AuthSettings(None, AuthFailureSettings(authFailMax, 60, 4, 0), 300, 256)
  )

  def limits(deadlineMs: Long = 2000): RuntimeLimits = RuntimeLimits(
    synchronousDeadlineMs = deadlineMs,
    maxWorkflowRetries = 2,
    graphLimit = 5,
    vectorLimit = 10,
    maxPathDepth = 4,
    graphTimeoutMillis = 1000,
    pageSize = 2
  )

  val tables: DynamoTablesConfig =
    DynamoTablesConfig("t-run", "t-link", "t-query-session", "t-evidence", "t-artifacts", None)

  // placeholder tokens that exist only in test code
  val aliceToken = "alice-adv-token"
  val bobToken = "bob-adv-token"
  val readerToken = "reader-adv-token"
  val keys: Vector[ApiKeyRecord] = Vector(
    ApiKeyRecord("alice", Sha256Hex.of(aliceToken), Scopes.all),
    ApiKeyRecord("bob", Sha256Hex.of(bobToken), Scopes.all),
    ApiKeyRecord("reader", Sha256Hex.of(readerToken), Set(Scopes.GraphRead))
  )

  def prov(sourceRecord: String = runId): Provenance =
    Provenance(
      SV.Provenance,
      SourceType.SCHOLARCHAIN,
      sourceRecord,
      snapshot,
      runId,
      Some("adv-policy-v1"),
      None,
      None,
      Vector.empty
    )

  def coauthorsBody(extra: String = ""): String =
    s"""{"operations":["DIRECT_COAUTHORS"],"researcherIds":["$ada"],"windowStartYear":$ws,"windowEndYear":$we$extra}"""

final class FakeClock(start: Long = 1_700_000_000_000L) extends ServiceClock:
  private val m = new AtomicLong(start)
  def nowMillis: Long = m.get()
  def advance(ms: Long): Unit = m.addAndGet(ms)

final class CountingIds extends IdGenerator:
  private val n = new AtomicLong(0)
  def next(): String = f"${n.incrementAndGet()}%032x"

final class NoSleep extends Sleeper:
  def sleep(millis: Long): Unit = ()

final class CollectingLog extends AccessLog:
  val records: mutable.ArrayBuffer[AccessRecord] = mutable.ArrayBuffer.empty
  def record(r: AccessRecord): Unit = synchronized(records += r)
  def text: String = synchronized(records.mkString("\n"))

/** The Graph-RAG runtime, faked at its port. */
final class ScriptedEngine(dynamo: DynamoPorts) extends QueryEngine:
  type Behavior = AnswerRequest => Either[WorkflowFailure, WorkflowResult]
  val calls = new AtomicInteger(0)
  val seen: mutable.ArrayBuffer[AnswerRequest] = mutable.ArrayBuffer.empty
  @volatile var behavior: Behavior = success

  def answer(request: AnswerRequest): Either[WorkflowFailure, WorkflowResult] =
    calls.incrementAndGet()
    seen.synchronized(seen += request)
    behavior(request)

  def failure(stage: String, f: StageFailure): Behavior =
    r => Left(WorkflowFailure(r.traceId, stage, f, Vector.empty, LlmUsage.Zero))

  def success: Behavior = request =>
    val q = request.queryId
    val edge = Kit.ok(
      StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, Vector(Kit.ada, Kit.bea), Kit.runId)
    )
    val edgeItem = EvidenceItem(
      SV.EvidenceItem,
      edge,
      EvidenceType.COAUTHOR_EDGE,
      Vector(Kit.ada, Kit.bea),
      None,
      "s3://adv-graph/edges/ada-bea",
      None,
      None,
      None,
      None,
      None,
      Kit.prov()
    )
    val bundle = EvidenceBundle(
      SV.EvidenceBundle,
      Kit.ok(StableIds.bundleId(q, Vector(edge))),
      q,
      Vector(edgeItem),
      Vector.empty,
      Map(Kit.ada -> 1.0),
      Kit.prov()
    )
    val claim = AnswerClaim(
      SV.AnswerClaim,
      Kit.ok(StableIds.claimId(q, 0)),
      s"${Kit.ada} and ${Kit.bea} have 3 joint publication(s).",
      Vector(edge),
      VerificationStatus.VERIFIED,
      None,
      Kit.prov(q)
    )
    val response = AnswerResponse(
      SV.AnswerResponse,
      q,
      "Ada has coauthored with Bea.",
      Vector(claim),
      bundle.bundleId,
      abstained = false,
      None,
      Kit.prov(q)
    )
    val plan = QueryPlan(
      SV.QueryPlan,
      q,
      Vector(QueryOperation.DIRECT_COAUTHORS),
      Vector.empty,
      Vector(Kit.ada),
      Some(Kit.ws),
      Some(Kit.we),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      None,
      5,
      5,
      2,
      "adv-plan-v1",
      Kit.prov(q)
    )
    val audit = QueryAuditRecord(
      SV.QueryAuditRecord,
      q,
      Kit.runId,
      write(plan),
      Vector(Kit.ada),
      Vector(edge),
      Vector("metrics-v1"),
      Vector("answer-synthesis-prompt-v3"),
      Vector("test-model-v1"),
      120L,
      40L,
      12L,
      VerificationStatus.VERIFIED,
      Kit.ok(StableIds.textHash(response.summary)),
      Kit.prov(q)
    )
    val stored = for
      _ <- dynamo.evidence.putBundle(bundle).left.flatMap {
        case _: StorageError.AlreadyExists => Right(())
        case e => Left(e)
      }
      _ <- dynamo.queryAudit.append(audit).left.flatMap {
        case _: StorageError.AlreadyExists => Right(())
        case e => Left(e)
      }
    yield ()
    stored.left.foreach(e => throw new IllegalStateException(e.message))
    Right(
      WorkflowResult(
        request.traceId,
        response,
        bundle,
        Vector.empty,
        VerificationStatus.VERIFIED,
        None,
        audit,
        LlmUsage(1, 120L, 40L, Vector("test-model-v1")),
        Vector(StageTrace(request.traceId, "plan", 3L, "ok"))
      )
    )

/** Ada has two coauthors; failure and delay can be injected. */
final class ScriptedGraph extends GraphReader:
  val calls = new AtomicInteger(0)
  @volatile var failure: Option[StorageError] = None
  @volatile var delayMillis: Long = 0L

  private def edge(a: String, b: String, joint: Long) = CoauthorEdgeView(
    s"coauthor-edge:$a|$b",
    a,
    b,
    joint,
    2010,
    2013,
    Kit.ws,
    Kit.we,
    s"coauthor-edge-support-v1:$a|$b",
    Kit.runId,
    "coauthor-policy-v1"
  )
  private val rows = Vector(
    CoauthorView(Kit.bea, edge(Kit.ada, Kit.bea, 3)),
    CoauthorView(Kit.cyd, edge(Kit.ada, Kit.cyd, 2))
  )

  private def gate[A](body: => Result[A]): Result[A] =
    calls.incrementAndGet()
    if delayMillis > 0 then Thread.sleep(delayMillis)
    failure.toLeft(()).flatMap(_ => body)

  def getResearcher(id: String): Result[Option[ResearcherView]] = gate {
    Right(
      Option.when(id == Kit.ada)(
        ResearcherView(id, "Ada Example", None, Kit.runId, None)
      )
    )
  }

  def directCoauthorsPage(
      id: String,
      ws: Int,
      we: Int,
      page: PageRequest
  ): Result[Page[CoauthorView]] = gate {
    val scope = PageCursor.scope(id, ws, we)
    val after = page.cursor match
      case None => Right(None)
      case Some(t) => PageCursor.decode(scope, t).map(Some(_))
    after.map { a =>
      val sorted = (if id == Kit.ada then rows else Vector.empty)
        .sortBy(c => (-c.edge.jointPublicationCount, c.coauthorId))
      val rest = a.fold(sorted) { (n, cid) =>
        sorted.filter(c =>
          c.edge.jointPublicationCount < n || (c.edge.jointPublicationCount == n && c.coauthorId > cid)
        )
      }
      PageCursor.page(scope, page.limit, rest.take(page.limit + 1))
    }
  }

  def directCoauthors(r: String, ws: Int, we: Int, limit: Int): Result[Vector[CoauthorView]] =
    Left(StorageError.Unsupported("unused"))
  def coauthorEdge(a: String, b: String, ws: Int, we: Int): Result[Option[CoauthorEdgeView]] =
    Left(StorageError.Unsupported("unused"))
  def paths(s: String, t: String, ws: Int, we: Int, l: PathQueryLimits): Result[Vector[EdgePath]] =
    Left(StorageError.Unsupported("unused"))
  def shortestPath(s: String, t: String, ws: Int, we: Int, d: Int): Result[Option[PathView]] =
    Left(StorageError.Unsupported("unused"))

final case class Resp(status: Int, headers: Map[String, String], raw: String):
  lazy val json: ujson.Value = ujson.read(raw)
  def errCode: String = json("error")("code").str
  def reason: Option[String] = json("error").obj.get("reason").map(_.str)

/** The whole service over fakes. */
final class Rig(
    val settings: ServiceSettings = Kit.settings(),
    val limits: RuntimeLimits = Kit.limits(),
    jobStore: Option[JobStore] = None,
    val items: InMemoryItemStore = new InMemoryItemStore,
    extraProbes: Vector[ReadinessProbe] = Vector.empty
):
  val jobs: InMemoryJobStore = new InMemoryJobStore
  val dynamo: DynamoPorts = DynamoPorts(items, Kit.tables, 86400L)
  val engine = new ScriptedEngine(dynamo)
  val graph = new ScriptedGraph
  val clock = new FakeClock
  val log = new CollectingLog
  @volatile var ready = true
  val runtime: ServiceRuntime = ServiceRuntime.build(
    settings,
    limits,
    RuntimePorts(
      engine,
      dynamo.querySessions,
      dynamo.evidence,
      dynamo.queryAudit,
      graph,
      jobStore.getOrElse(jobs),
      Vector(ReadinessProbe("store", () => ready)) ++ extraProbes
    ),
    new BearerAuthenticator(Kit.keys, 256),
    clock,
    new CountingIds,
    new NoSleep,
    log
  )

  def call(
      method: String,
      path: String,
      token: Option[String] = Some(Kit.aliceToken),
      body: Option[String] = None,
      query: Map[String, Vector[String]] = Map.empty,
      headers: Map[String, String] = Map.empty,
      contentType: Option[String] = Some("application/json"),
      remote: Option[String] = None,
      rawBody: Option[Array[Byte]] = None,
      authHeader: Option[String] = None
  ): Resp =
    val auth = authHeader.orElse(token.map(t => s"Bearer $t")).map("authorization" -> _).toMap
    val ct =
      if (body.isDefined || rawBody.isDefined) && contentType.isDefined then
        Map("content-type" -> contentType.get)
      else Map.empty
    val bytes = rawBody.orElse(body.map(_.getBytes("UTF-8"))).getOrElse(Array.emptyByteArray)
    val r = runtime.api.handle(
      HttpRequest.of(method, path, query, auth ++ ct ++ headers, bytes, remote)
    )
    Resp(r.status, r.headers, r.body)

  def get(path: String, token: Option[String] = Some(Kit.aliceToken)): Resp =
    call("GET", path, token)

  def post(
      body: String,
      token: Option[String] = Some(Kit.aliceToken),
      key: Option[String] = None
  ): Resp =
    call("POST", "/v1/query", token, Some(body), headers = key.map("idempotency-key" -> _).toMap)

  def close(): Unit = runtime.close()
