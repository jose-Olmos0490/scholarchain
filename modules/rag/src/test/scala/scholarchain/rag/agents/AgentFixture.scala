package scholarchain.rag.agents

import scala.collection.mutable
import scala.math.Ordering.Implicits.infixOrderingOps
import scholarchain.core.config.*
import scholarchain.core.ids.{CanonicalPair, StableIds}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.rag.RagFixtures.*
import scholarchain.rag.llm.*
import scholarchain.storage.common.*
import scholarchain.storage.directory.*
import scholarchain.storage.dynamo.ResearcherMetricCache
import scholarchain.storage.graph.*

/** A scripted model: each call returns the next scripted response. It records every request, so
  * tests can check what the model was shown. An exhausted script is a permanent failure.
  */
final class ScriptedLlm(script: Seq[Either[LlmFailure, LlmResponse]]) extends LlmClient:
  private val queue = mutable.Queue.from(script)
  val requests = mutable.ArrayBuffer.empty[LlmRequest]

  def complete(request: LlmRequest): Either[LlmFailure, LlmResponse] =
    requests += request
    if queue.isEmpty then Left(LlmFailure.Permanent("script exhausted")) else queue.dequeue()

object ScriptedLlm:
  def reply(text: String, in: Long = 100, out: Long = 20): Either[LlmFailure, LlmResponse] =
    Right(LlmResponse(text, "fake-model-v1", in, out))

  def planJson(
      operations: Seq[String] = Seq.empty,
      names: Seq[String] = Seq.empty,
      start: Option[Int] = None,
      end: Option[Int] = None,
      topics: Seq[String] = Seq.empty,
      metric: Option[String] = None
  ): String =
    ujson
      .Obj(
        "operations" -> ujson.Arr.from(operations.map(ujson.Str(_))),
        "researcherNames" -> ujson.Arr.from(names.map(ujson.Str(_))),
        "windowStartYear" -> start.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
        "windowEndYear" -> end.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
        "topics" -> ujson.Arr.from(topics.map(ujson.Str(_))),
        "venues" -> ujson.Arr(),
        "institutions" -> ujson.Arr(),
        "rankingMetric" -> metric.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      )
      .render()

/** The fixture world: 12 researchers in two communities joined by one bridge researcher, one name
  * that maps to two researchers, and one giant-team paper. All stores are in-memory fakes.
  *
  * Community A: researchers 1-5 and the bridge, 6. Community B: 7-12. Edges (window 2010-2013):
  * A is 1-2, 2-3, 1-3, 3-4, 4-5; the bridge links 5-6 and 6-7; B is 7-8, 8-9, 9-10, 10-11, 11-12.
  * The shortest chain from 1 to 7 is 1-3-4-5-6-7 (5 hops).
  */
object World:
  val WS = 2010
  val WE = 2013

  private def ok[A](e: Either[String, A]): A =
    e.fold(m => throw new IllegalStateException(m), identity)

  val ids: Vector[String] = (1 to 12).toVector.map(researcher)
  def r(n: Int): String = researcher(n)

  val communityA: String = r(1)
  val communityB: String = r(7)
  def communityOfNumber(n: Int): String = if n <= 6 then communityA else communityB

  val giantWork: String = work(900)

  // ---- configuration -------------------------------------------------------------------

  val retrieval: RetrievalConfig = RetrievalConfig(
    graphLimit = 25,
    vectorLimit = 5,
    maxPathDepth = 4,
    contextTokenBudget = 4000,
    fusionPolicyVersion = "fusion-policy-v1",
    pathResultLimit = 10,
    pathTimeoutMillis = 5000,
    evidenceMaxItems = 50,
    coauthorPageSize = 3
  )
  val resolverCfg: ResolverConfig = ResolverConfig(0.8, 0.1, 5)
  val llmCfg: RagLlmConfig = RagLlmConfig(maxRetries = 2, maxOutputTokens = 1024)
  val plannerCfg: PlannerConfig = PlannerConfig("query-planner-prompt-v1", "query-plan-v1")
  val limits: GraphReaderLimits = GraphReaderLimits(25, 4, 5000L)

  // ---- stored metrics ------------------------------------------------------------------

  /** (P_r, C_r, T_r, A_r, M_r, W_r) per researcher. Researcher 3 is in the giant team: the most
    * distinct coauthors (C_r) but a modest weighted degree (W_r). Researcher 5 has the highest W_r.
    */
  private val values: Map[Int, (Long, Long, Long, Double, Long, Double)] = Map(
    1 -> (10L, 4L, 12L, 2.0, 3L, 8.0),
    2 -> (8L, 3L, 9L, 2.0, 3L, 6.0),
    3 -> (15L, 45L, 60L, 4.0, 41L, 12.5),
    4 -> (9L, 5L, 11L, 2.0, 3L, 15.0),
    5 -> (12L, 6L, 18L, 2.5, 4L, 20.0),
    6 -> (6L, 4L, 8L, 2.0, 3L, 9.0),
    7 -> (7L, 4L, 9L, 2.0, 3L, 7.0),
    8 -> (5L, 3L, 6L, 2.0, 3L, 5.0),
    9 -> (5L, 3L, 6L, 2.0, 3L, 5.5),
    10 -> (4L, 3L, 5L, 2.0, 3L, 4.0),
    11 -> (4L, 3L, 5L, 2.0, 3L, 4.5),
    12 -> (3L, 1L, 3L, 2.0, 2L, 2.0)
  )

  def metricsRow(
      n: Int,
      ws: Int = WS,
      we: Int = WE,
      version: String = metricVersion
  ): ResearcherMetrics =
    val (p, c, t, a, m, w) = values(n)
    ResearcherMetrics(
      SV.ResearcherMetrics,
      ok(StableIds.metricsId(r(n), ws, we, version, runId)),
      r(n),
      ws,
      we,
      p,
      c,
      t,
      a,
      m,
      if n == 3 then Vector(giantWork) else Vector(work(n * 10)),
      w,
      r(1),
      12L,
      Some(communityOfNumber(n)),
      if n == 6 then Some(CollaborationMetric.CROSS_COMMUNITY_DEGREE) else None,
      if n == 6 then Some(1.0) else None,
      Some(2),
      version,
      prov(r(n))
    )

  val metricRows: Vector[ResearcherMetrics] = (1 to 12).toVector.map(n => metricsRow(n))

  final class FakeCache(rows: Vector[ResearcherMetrics]) extends ResearcherMetricCache:
    def put(m: ResearcherMetrics): Result[scholarchain.storage.dynamo.CreateOutcome] =
      Left(StorageError.Permanent("put", "read-only fixture"))
    def get(id: String, ws: Int, we: Int): Result[Option[ResearcherMetrics]] =
      Right(
        rows.find(m => m.researcherId == id && m.windowStartYear == ws && m.windowEndYear == we)
      )
    def listWindows(id: String): Result[Vector[ResearcherMetrics]] =
      Right(rows.filter(_.researcherId == id))

  /** Ranks the fixture rows the way the metric store does: the supported metrics only, by value
    * descending then researcher ID, refusing bridge metrics, venue and institution filters and
    * windows that are not loaded.
    */
  final class FakeMetrics(rows: Vector[ResearcherMetrics], val calls: mutable.ArrayBuffer[String])
      extends MetricReader:
    def loadedWindows: Result[Vector[(Int, Int)]] = Right(Vector((WS, WE)))

    private def valueOf(metric: CollaborationMetric, m: ResearcherMetrics): Double = metric match
      case CollaborationMetric.PUBLICATION_COUNT => m.publicationCount.toDouble
      case CollaborationMetric.DISTINCT_COAUTHOR_COUNT => m.distinctCoauthorCount.toDouble
      case CollaborationMetric.TOTAL_COAUTHOR_OCCURRENCES => m.totalCoauthorOccurrences.toDouble
      case CollaborationMetric.AVERAGE_COAUTHORS_PER_PAPER => m.averageCoauthorsPerPaper
      case CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER =>
        m.maximumCoauthorsOnOnePaper.toDouble
      case CollaborationMetric.WEIGHTED_DEGREE => m.weightedDegree
      case CollaborationMetric.CORENESS => m.coreness.getOrElse(0).toDouble
      case CollaborationMetric.COMPONENT_SIZE => m.componentSize.toDouble
      case CollaborationMetric.COMMUNITY_SIZE =>
        rows.count(_.communityId == m.communityId).toDouble
      case _ => 0.0

    def topK(
        metric: CollaborationMetric,
        ws: Int,
        we: Int,
        filter: ResearcherFilter,
        limit: Int
    ): Result[Vector[RankedResearcher]] =
      calls += s"topK/$metric/$ws-$we/limit=$limit/topics=${filter.topicIds.mkString(",")}"
      for
        _ <- MetricRanking.check(metric)
        _ <-
          if filter.venueIds.nonEmpty || filter.institutionIds.nonEmpty then
            Left(StorageError.Unsupported("venue and institution filters are not stored"))
          else Right(())
        _ <-
          if limit > limits.maxLimit then
            Left(StorageError.LimitExceeded("limit", limits.maxLimit, limit))
          else Right(())
        _ <-
          if (ws, we) == (WS, WE) then Right(())
          else Left(MetricRanking.windowNotLoaded(ws, we, Vector((WS, WE))))
      yield rows
        .filter(m => m.windowStartYear == ws && m.windowEndYear == we)
        .map(m => (m, valueOf(metric, m)))
        .sortBy((m, v) => (-v, m.researcherId))
        .take(limit)
        .map { (m, v) =>
          RankedResearcher(
            m.researcherId,
            s"Researcher ${m.researcherId.takeRight(4)}",
            v,
            ResearcherNodeMetrics(
              MetricRanking.windowLabel(ws, we),
              m.publicationCount,
              m.distinctCoauthorCount,
              m.totalCoauthorOccurrences,
              m.averageCoauthorsPerPaper,
              m.maximumCoauthorsOnOnePaper,
              m.weightedDegree,
              m.componentId,
              m.communityId,
              m.bridgeScore,
              m.coreness,
              m.metricVersion
            )
          )
        }

  // ---- stored edges --------------------------------------------------------------------

  final case class Edge(a: Int, b: Int, count: Int, first: Int, last: Int)

  val edges: Vector[Edge] = Vector(
    Edge(1, 2, 2, 2010, 2012),
    Edge(2, 3, 1, 2011, 2011),
    Edge(1, 3, 3, 2010, 2013),
    Edge(3, 4, 2, 2011, 2012),
    Edge(4, 5, 4, 2010, 2013),
    Edge(5, 6, 1, 2012, 2012),
    Edge(6, 7, 1, 2013, 2013),
    Edge(7, 8, 2, 2010, 2011),
    Edge(8, 9, 1, 2012, 2012),
    Edge(9, 10, 3, 2010, 2013),
    Edge(10, 11, 1, 2011, 2011),
    Edge(11, 12, 2, 2012, 2013)
  )

  def edgeId(x: Int, y: Int): String =
    ok(StableIds.coauthorEdgeId(r(x), r(y), WS, WE, policyVersion))

  def edgeView(e: Edge): CoauthorEdgeView =
    val p = ok(CanonicalPair.of(r(e.a), r(e.b)))
    val id = edgeId(e.a, e.b)
    CoauthorEdgeView(
      id,
      p.a,
      p.b,
      e.count.toLong,
      e.first,
      e.last,
      WS,
      WE,
      CoauthorEdgeSupport.supportRef(id),
      runId,
      policyVersion
    )

  def workIdsOf(e: Edge): Vector[String] =
    (0 until e.count).toVector.map(k => work(e.a * 100 + e.b * 10 + k)).distinct

  /** Graph reader over the fixture edges. `timeout` makes every path query run out of time. */
  final class FakeGraph(
      val pathLimits: mutable.ArrayBuffer[PathQueryLimits],
      var timeout: Boolean = false
  ) extends GraphReader:
    private val views = edges.map(e => e -> edgeView(e))

    private def neighbours(n: String): Vector[(String, CoauthorEdgeView)] =
      views.collect {
        case (_, v) if v.researcherA == n => (v.researcherB, v)
        case (_, v) if v.researcherB == n => (v.researcherA, v)
      }

    def getResearcher(researcherId: String): Result[Option[ResearcherView]] = Right(None)

    def directCoauthors(id: String, ws: Int, we: Int, limit: Int): Result[Vector[CoauthorView]] =
      Right(neighbours(id).map((c, v) => CoauthorView(c, v)).take(limit))

    def directCoauthorsPage(
        id: String,
        ws: Int,
        we: Int,
        page: PageRequest
    ): Result[Page[CoauthorView]] =
      val scope = PageCursor.scope(id, ws, we)
      val sorted = neighbours(id)
        .map((c, v) => CoauthorView(c, v))
        .sortBy(c => (-c.edge.jointPublicationCount, c.coauthorId))
      for
        after <- page.cursor.fold[Result[Option[(Long, String)]]](Right(None))(
          PageCursor.decode(scope, _).map(Some(_))
        )
        rest = after.fold(sorted)((n, cid) =>
          sorted.filter(c => (-c.edge.jointPublicationCount, c.coauthorId) > (-n, cid))
        )
      yield PageCursor.page(scope, page.limit, rest.take(page.limit + 1))

    def coauthorEdge(a: String, b: String, ws: Int, we: Int): Result[Option[CoauthorEdgeView]] =
      Right(views.map(_._2).find(v => Set(v.researcherA, v.researcherB) == Set(a, b)))

    def paths(
        source: String,
        target: String,
        ws: Int,
        we: Int,
        limits: PathQueryLimits
    ): Result[Vector[EdgePath]] =
      pathLimits += limits
      if timeout then Left(StorageError.Timeout("paths", limits.timeoutMillis))
      else
        def go(path: Vector[String], used: Vector[CoauthorEdgeView]): Vector[EdgePath] =
          val here = path.last
          if here == target then Vector(EdgePath(path, used))
          else if used.size >= limits.maxDepth then Vector.empty
          else
            neighbours(here)
              .filterNot((n, _) => path.contains(n))
              .flatMap((n, v) => go(path :+ n, used :+ v))
        val found = go(Vector(source), Vector.empty)
          .sortBy(p =>
            (p.length, p.researcherIds.mkString(","), p.edges.map(_.edgeId).mkString(","))
          )
          .take(limits.resultLimit)
        Right(found)

    def shortestPath(
        s: String,
        t: String,
        ws: Int,
        we: Int,
        maxDepth: Int
    ): Result[Option[PathView]] =
      Right(None)

  // ---- analytics, directory, support ---------------------------------------------------

  def analytics(): FakeAnalyticsReader =
    val a = new FakeAnalyticsReader(limits)
    (1 to 12).foreach { n =>
      val c = communityOfNumber(n)
      a.putCommunity(
        CommunityMembership(
          SV.CommunityMembership,
          r(n),
          c,
          6L,
          WS,
          WE,
          metricVersion,
          runId,
          prov(r(n))
        )
      )
      a.putComponent(
        ComponentMembership(
          SV.ComponentMembership,
          r(n),
          r(1),
          12L,
          WS,
          WE,
          metricVersion,
          runId,
          prov(r(n))
        )
      )
    }
    val p = ok(CanonicalPair.of(r(6), r(7)))
    val cross = CrossCommunityEdge(
      SV.CrossCommunityEdge,
      edgeId(6, 7),
      p.a,
      p.b,
      if p.a == r(6) then communityA else communityB,
      if p.b == r(6) then communityA else communityB,
      WS,
      WE,
      1L,
      policyVersion,
      metricVersion,
      runId,
      prov(r(6))
    )
    a.putCrossEdge(cross)
    Vector(communityA, communityB).foreach { c =>
      a.putConnectivity(CommunityConnectivity(c, WS, WE, 1L, 1L, metricVersion, runId))
    }
    a

  def support(): FakeEdgeSupportReader =
    val s = new FakeEdgeSupportReader
    edges.foreach(e =>
      s.put(CoauthorEdgeSupport.supportRef(edgeId(e.a, e.b)), runId, workIdsOf(e).toSet)
    )
    s

  def candidate(
      n: Int,
      name: String,
      confidence: Double,
      affiliation: String = "Example University"
  ): NameCandidate =
    NameCandidate(
      r(n),
      name,
      confidence,
      Vector(s"openalex:A${5000000000L + n}"),
      Vector(affiliation),
      Vector("Ada Example", "Bo Sample"),
      Vector(ok(StableIds.matchDecisionId(r(n), r(n + 20), policyVersion))),
      policyVersion,
      runId
    )

  /** Names: "wei zhang" is two researchers (2 and 8) with close confidence; "ada example" is
    * one; "lin chen" has a clear leader; "sam low" has one weak candidate.
    */
  def directory(): FakeResearcherDirectory =
    val d = new FakeResearcherDirectory(limits.maxLimit)
    def put(name: String, c: NameCandidate) =
      d.put(name, c).fold(e => throw new IllegalStateException(e.message), identity)
    put("wei zhang", candidate(2, "Wei Zhang", 0.90, "Alpha University"))
    put("wei zhang", candidate(8, "Wei Zhang", 0.88, "Beta Institute"))
    put("ada example", candidate(1, "Ada Example", 0.97))
    put("lin chen", candidate(4, "Lin Chen", 0.95))
    put("lin chen", candidate(10, "Lin Chen", 0.60))
    put("sam low", candidate(5, "Sam Low", 0.50))
    d
