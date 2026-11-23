package scholarchain.services

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable
import scholarchain.core.ids.{ResearcherNamespace, StableIds}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.rag.agents.StageFailure
import scholarchain.rag.llm.LlmUsage
import scholarchain.rag.workflow.{AnswerRequest, StageTrace, WorkflowFailure, WorkflowResult}
import scholarchain.core.config.DynamoTablesConfig
import scholarchain.services.config.*
import scholarchain.services.http.*
import scholarchain.services.ops.{AccessLog, AccessRecord, ReadinessProbe}
import scholarchain.services.query.{InMemoryJobStore, JobStore, QueryEngine}
import scholarchain.services.resilience.Sleeper
import scholarchain.services.runtime.{RuntimePorts, ServiceRuntime}
import scholarchain.services.security.*
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.dynamo.{DynamoPorts, EvidenceStore, InMemoryItemStore}
import scholarchain.storage.graph.*
import upickle.default.write

/** Stable test data: every ID comes from the core formulas. */
object Fx:
  def ok[A](e: Either[String, A]): A = e.fold(m => throw new IllegalStateException(m), identity)

  val snapshot = "2026-11-01"
  val manifest: RunManifest = RunManifest(
    SV.RunManifest,
    "services-test",
    "services-test-v1",
    Vector(RunInput(SourceType.DBLP, snapshot, "fixture/dblp.xml", "sha256:" + "a" * 64)),
    Map.empty,
    Map.empty
  )
  val runId: String = ok(StableIds.runId(manifest))

  private def researcher(orcid: String) =
    ok(StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, orcid))
  val ada: String = researcher("0000-0002-1825-0097")
  val bea: String = researcher("0000-0001-5109-3700")
  val cyd: String = researcher("0000-0002-1694-233X")
  val dee: String = researcher("0000-0003-1415-9269")
  val unknownResearcher: String = researcher("0000-0002-9079-593X")

  val windowStart = 2010
  val windowEnd = 2013

  val settings: ServiceSettings = ServiceSettings(
    HttpSettings(8192, 500, 5, 8, 64),
    RateLimitSettings(1000, 60),
    BreakerSettings(3, 30000),
    RetrySettings(2, 10, 1000),
    ServingSettings(runId, snapshot),
    ExecutionSettings(4, 8),
    EvidenceSettings(40),
    GraphApiSettings(1, Vector("COAUTHORED_WITH")),
    OpsSettings(metricsEnabled = true, readinessTimeoutMillis = 200),
    AuthSettings(None)
  )

  val limits: RuntimeLimits = RuntimeLimits(
    synchronousDeadlineMs = 2000,
    maxWorkflowRetries = 2,
    graphLimit = 5,
    vectorLimit = 10,
    maxPathDepth = 4,
    graphTimeoutMillis = 1000,
    pageSize = 2
  )

  val tables: DynamoTablesConfig =
    DynamoTablesConfig(
      "t-run-ledger",
      "t-link-cache",
      "t-query-session",
      "t-evidence",
      "t-artifacts",
      None
    )

  // Placeholder tokens: they exist only in this test code and open nothing.
  val aliceToken = "alice-test-token"
  val bobToken = "bob-test-token"
  val readerToken = "reader-test-token"
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
      Some("services-test-policy-v1"),
      None,
      None,
      Vector.empty
    )

final class FakeClock(start: Long = 1_700_000_000_000L) extends ServiceClock:
  private val clockMillis = new AtomicLong(start)
  def nowMillis: Long = clockMillis.get()
  def advance(ms: Long): Unit = clockMillis.addAndGet(ms)

/** Deterministic IDs: a counter as 32 hex digits. */
final class CountingIds extends IdGenerator:
  private val n = new AtomicLong(0)
  def next(): String = f"${n.incrementAndGet()}%032x"

final class RecordingSleeper extends Sleeper:
  val sleeps: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty
  def sleep(millis: Long): Unit = synchronized(sleeps += millis)

final class CollectingLog extends AccessLog:
  val records: mutable.ArrayBuffer[AccessRecord] = mutable.ArrayBuffer.empty
  def record(r: AccessRecord): Unit = synchronized(records += r)
  def text: String = synchronized(records.mkString("\n"))

/** The Graph-RAG runtime, faked at its port. The success behavior writes the evidence bundle and
  * the audit record through the real (in-memory) storage ports, as the production workflow does.
  */
final class FakeEngine(dynamo: DynamoPorts) extends QueryEngine:
  type Behavior = AnswerRequest => Either[WorkflowFailure, WorkflowResult]

  val calls = new AtomicInteger(0)
  @volatile var behavior: Behavior = success

  def answer(request: AnswerRequest): Either[WorkflowFailure, WorkflowResult] =
    calls.incrementAndGet()
    behavior(request)

  def failure(stage: String, f: StageFailure): Behavior =
    r => Left(WorkflowFailure(r.traceId, stage, f, Vector.empty, LlmUsage.Zero))

  def success: Behavior = request =>
    val q = request.queryId
    val edge = Fx.ok(
      StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, Vector(Fx.ada, Fx.bea), Fx.runId)
    )
    val edgeItem = EvidenceItem(
      SV.EvidenceItem,
      edge,
      EvidenceType.COAUTHOR_EDGE,
      Vector(Fx.ada, Fx.bea),
      None,
      "s3://test-graph/edges/ada-bea",
      None,
      None,
      None,
      None,
      None,
      Fx.prov()
    )
    val text = "Ada and Bea share three joint publications. " + "x" * 200
    val hash = Fx.ok(StableIds.textHash(text))
    val chunk = Fx.ok(StableIds.chunkId(Fx.ada, "v1", "abstract", 0, hash))
    val chunkEvidence =
      Fx.ok(StableIds.evidenceId(EvidenceType.TEXT_CHUNK, Vector(chunk), Fx.runId))
    val textItem = EvidenceItem(
      SV.EvidenceItem,
      chunkEvidence,
      EvidenceType.TEXT_CHUNK,
      Vector(chunk),
      None,
      "s3://test-rag/chunks/1",
      Some(chunk),
      None,
      Some(0.5),
      Some(hash),
      Some(text),
      Fx.prov()
    )
    val bundle = EvidenceBundle(
      SV.EvidenceBundle,
      Fx.ok(StableIds.bundleId(q, Vector(edge, chunkEvidence))),
      q,
      Vector(edgeItem),
      Vector(textItem),
      Map(Fx.ada -> 1.0),
      Fx.prov()
    )
    val claim = AnswerClaim(
      SV.AnswerClaim,
      Fx.ok(StableIds.claimId(q, 0)),
      s"${Fx.ada} and ${Fx.bea} have 3 joint publication(s) in ${Fx.windowStart}-${Fx.windowEnd}.",
      Vector(edge),
      VerificationStatus.VERIFIED,
      None,
      Fx.prov(q)
    )
    val response = AnswerResponse(
      SV.AnswerResponse,
      q,
      "Ada has coauthored with Bea.",
      Vector(claim),
      bundle.bundleId,
      abstained = false,
      None,
      Fx.prov(q)
    )
    val plan = QueryPlan(
      SV.QueryPlan,
      q,
      Vector(QueryOperation.DIRECT_COAUTHORS),
      Vector.empty,
      Vector(Fx.ada),
      Some(Fx.windowStart),
      Some(Fx.windowEnd),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      None,
      5,
      5,
      2,
      "services-test-plan-v1",
      Fx.prov(q)
    )
    val audit = QueryAuditRecord(
      SV.QueryAuditRecord,
      q,
      Fx.runId,
      write(plan),
      Vector(Fx.ada),
      Vector(edge),
      Vector("metrics-v1"),
      Vector("answer-synthesis-prompt-v3"),
      Vector("test-model-v1"),
      120L,
      40L,
      12L,
      VerificationStatus.VERIFIED,
      Fx.ok(StableIds.textHash(response.summary)),
      Fx.prov(q)
    )
    val stored = for
      _ <- ignoreExisting(dynamo.evidence.putBundle(bundle))
      _ <- ignoreExisting(dynamo.queryAudit.append(audit))
    yield ()
    stored match
      case Left(e) => throw new IllegalStateException(e.message)
      case Right(_) =>
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
            Vector(
              StageTrace(request.traceId, "plan", 3L, "ok"),
              StageTrace(request.traceId, "evidence", 5L, "ok"),
              StageTrace(request.traceId, "audit", 2L, "ok")
            )
          )
        )

  private def ignoreExisting(r: Result[Unit]): Result[Unit] = r match
    case Left(_: StorageError.AlreadyExists) => Right(())
    case other => other

/** A graph with Ada connected to Bea and Cyd (3 joint papers each) and Dee (1). */
final class FakeGraph extends GraphReader:
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
    Fx.windowStart,
    Fx.windowEnd,
    s"coauthor-edge-support-v1:$a|$b",
    Fx.runId,
    "coauthor-policy-v1"
  )
  private val adaRows = Vector(
    CoauthorView(Fx.bea, edge(Fx.ada, Fx.bea, 3)),
    CoauthorView(Fx.cyd, edge(Fx.ada, Fx.cyd, 3)),
    CoauthorView(Fx.dee, edge(Fx.ada, Fx.dee, 1))
  )
  private val people = Map(
    Fx.ada -> "Ada Example",
    Fx.bea -> "Bea Example",
    Fx.cyd -> "Cyd Example",
    Fx.dee -> "Dee Example"
  )

  private def gate[A](body: => Result[A]): Result[A] =
    calls.incrementAndGet()
    if delayMillis > 0 then Thread.sleep(delayMillis)
    failure.toLeft(()).flatMap(_ => body)

  def getResearcher(researcherId: String): Result[Option[ResearcherView]] = gate {
    Right(
      people
        .get(researcherId)
        .map(n =>
          ResearcherView(
            researcherId,
            n,
            None,
            Fx.runId,
            Some(
              ResearcherNodeMetrics(
                "2010-2013",
                7,
                3,
                9,
                2.5,
                4,
                7.0,
                "component-1",
                Some("community-1"),
                None,
                Some(2),
                "metrics-v1"
              )
            )
          )
        )
    )
  }

  def directCoauthorsPage(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      page: PageRequest
  ): Result[Page[CoauthorView]] = gate {
    val scope = PageCursor.scope(researcherId, windowStartYear, windowEndYear)
    val sorted =
      (if researcherId == Fx.ada then adaRows else Vector.empty)
        .sortBy(c => (-c.edge.jointPublicationCount, c.coauthorId))
    val after = page.cursor match
      case None => Right(None)
      case Some(t) => PageCursor.decode(scope, t).map(Some(_))
    after.map { a =>
      val rest = a.fold(sorted) { (n, id) =>
        sorted.filter(c =>
          c.edge.jointPublicationCount < n || (c.edge.jointPublicationCount == n && c.coauthorId > id)
        )
      }
      PageCursor.page(scope, page.limit, rest.take(page.limit + 1))
    }
  }

  def directCoauthors(r: String, ws: Int, we: Int, limit: Int): Result[Vector[CoauthorView]] =
    Left(StorageError.Unsupported("not used by the services"))
  def coauthorEdge(a: String, b: String, ws: Int, we: Int): Result[Option[CoauthorEdgeView]] =
    Left(StorageError.Unsupported("not used by the services"))
  def paths(s: String, t: String, ws: Int, we: Int, l: PathQueryLimits): Result[Vector[EdgePath]] =
    Left(StorageError.Unsupported("not used by the services"))
  def shortestPath(s: String, t: String, ws: Int, we: Int, d: Int): Result[Option[PathView]] =
    Left(StorageError.Unsupported("not used by the services"))

/** The evidence store with an injectable failure and a call counter. */
final class FlakyEvidenceStore(inner: EvidenceStore, h: => Harness) extends EvidenceStore:
  def putBundle(bundle: EvidenceBundle): Result[Unit] = inner.putBundle(bundle)
  def getItem(evidenceId: String): Result[Option[EvidenceItem]] =
    h.evidenceCalls.incrementAndGet()
    h.evidenceFailure.toLeft(()).flatMap(_ => inner.getItem(evidenceId))
  def listForQuery(queryId: String): Result[Vector[EvidenceItem]] =
    inner.listForQuery(queryId)

/** A response as a test reads it. */
final case class Resp(status: Int, headers: Map[String, String], raw: String):
  lazy val json: ujson.Value = ujson.read(raw)
  def err: ujson.Value = json("error")
  def traceId: String = json("traceId").str

/** The whole service over fakes. */
final class Harness(
    val settings: ServiceSettings = Fx.settings,
    val limits: RuntimeLimits = Fx.limits,
    val jobs: InMemoryJobStore = new InMemoryJobStore,
    jobStoreOverride: Option[JobStore] = None,
    val graph: FakeGraph = new FakeGraph
):
  val items = new InMemoryItemStore
  val dynamo: DynamoPorts = DynamoPorts(items, Fx.tables, 86400L)
  val engine = new FakeEngine(dynamo)
  val clock = new FakeClock
  val sleeper = new RecordingSleeper
  val log = new CollectingLog
  @volatile var storeReady = true
  @volatile var evidenceFailure: Option[StorageError] = None
  val evidenceCalls = new AtomicInteger(0)
  private val probes = Vector(ReadinessProbe("evidenceStore", () => storeReady))
  val runtime: ServiceRuntime = ServiceRuntime.build(
    settings,
    limits,
    RuntimePorts(
      engine,
      dynamo.querySessions,
      new FlakyEvidenceStore(dynamo.evidence, this),
      dynamo.queryAudit,
      graph,
      jobStoreOverride.getOrElse(jobs),
      probes
    ),
    new BearerAuthenticator(Fx.keys, 256),
    clock,
    new CountingIds,
    sleeper,
    log
  )

  def call(
      method: String,
      path: String,
      token: Option[String] = Some(Fx.aliceToken),
      body: Option[String] = None,
      query: Map[String, String] = Map.empty,
      headers: Map[String, String] = Map.empty,
      json: Boolean = true
  ): Resp =
    val h = token.map(t => Map("authorization" -> s"Bearer $t")).getOrElse(Map.empty) ++
      (if body.isDefined && json then Map("content-type" -> "application/json") else Map.empty) ++
      headers
    val r = runtime.api.handle(
      HttpRequest.of(
        method,
        path,
        query.map((k, v) => k -> Vector(v)),
        h,
        body.map(_.getBytes("UTF-8")).getOrElse(Array.emptyByteArray)
      )
    )
    Resp(r.status, r.headers, r.body)

  def get(
      path: String,
      token: Option[String] = Some(Fx.aliceToken),
      query: Map[String, String] = Map.empty
  ) =
    call("GET", path, token, None, query)

  def post(
      body: String,
      token: Option[String] = Some(Fx.aliceToken),
      key: Option[String] = None,
      headers: Map[String, String] = Map.empty
  ): Resp =
    call(
      "POST",
      "/v1/query",
      token,
      Some(body),
      headers = headers ++ key.map("idempotency-key" -> _)
    )

  def close(): Unit = runtime.close()

object Bodies:
  def coauthors(extra: String = ""): String =
    s"""{"operations":["DIRECT_COAUTHORS"],"researcherIds":["${Fx.ada}"],"windowStartYear":${Fx.windowStart},"windowEndYear":${Fx.windowEnd}$extra}"""
  def asyncCoauthors: String = coauthors(""","mode":"async"""")
