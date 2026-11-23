package scholarchain.storage.graph

import java.nio.charset.StandardCharsets
import java.util.Base64
import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.common.*
import scholarchain.storage.objects.ObjectRef

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

/** What to load: the object prefix that holds the exported `csv/` directory, for one run. */
final case class GraphLoadRequest(runId: String, csvPrefix: ObjectRef)

final case class GraphLoadJob(jobId: String, runId: String)

enum GraphLoadState:
  case Queued, InProgress, Completed
  case Failed(reason: String)

/** Port that starts and polls a bulk load. Starting a live load is an adapter operation; the fake
  * applies the CSV to an in-memory graph.
  */
trait GraphLoader:
  def startLoad(request: GraphLoadRequest): Result[GraphLoadJob]
  def status(job: GraphLoadJob): Result[GraphLoadState]

// ---------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------

/** Metrics stored on a Researcher node for the node window (P_r ... coreness). */
final case class ResearcherNodeMetrics(
    window: String,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    weightedDegree: Double,
    componentId: String,
    communityId: Option[String],
    bridgeScore: Option[Double],
    coreness: Option[Int],
    metricVersion: String
)

final case class ResearcherView(
    researcherId: String,
    displayName: String,
    orcid: Option[String],
    runId: String,
    metrics: Option[ResearcherNodeMetrics]
)

/** A stored COAUTHORED_WITH relationship. `evidencePointer` resolves to the supporting work IDs
  * (`coauthor-edge-support-v1:<edgeId>` rows of the run `runId`).
  */
final case class CoauthorEdgeView(
    edgeId: String,
    researcherA: String,
    researcherB: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    windowStartYear: Int,
    windowEndYear: Int,
    evidencePointer: String,
    runId: String,
    policyVersion: String
)

final case class CoauthorView(coauthorId: String, edge: CoauthorEdgeView)

/** A path as researcher IDs in hop order and the edge ID of each hop (`edgeIds.size ==
  * researcherIds.size - 1`).
  */
final case class PathView(researcherIds: Vector[String], edgeIds: Vector[String]):
  def length: Int = edgeIds.size

/** Hard bounds from configuration: `retrieval.graphLimit`, `retrieval.maxPathDepth` and
  * `retrieval.pathTimeoutMillis` (the ceiling of a per-query time limit; a request above it is
  * refused).
  */
final case class GraphReaderLimits(maxLimit: Int, maxDepth: Int, maxTimeoutMillis: Long)

/** One page request. `cursor` is the opaque token of the previous page's `nextCursor`. */
final case class PageRequest(limit: Int, cursor: Option[String] = None)

/** One page. `nextCursor` is `None` on the last page. */
final case class Page[+A](items: Vector[A], nextCursor: Option[String])

/** Per-request bounds of [[GraphReader.paths]]. Each value is checked against the configured
  * ceilings of [[GraphReaderLimits]] and refused, never clamped.
  */
final case class PathQueryLimits(maxDepth: Int, resultLimit: Int, timeoutMillis: Long)

/** A path as researcher IDs in hop order and the stored edge of each hop (`edges.size ==
  * researcherIds.size - 1`). Orientation of a hop is given by `researcherIds`; the edge keeps its
  * canonical `researcherA < researcherB` direction.
  */
final case class EdgePath(researcherIds: Vector[String], edges: Vector[CoauthorEdgeView]):
  def length: Int = edges.size

/** Opaque cursor of [[GraphReader.directCoauthorsPage]]. The order of that query is
  * `jointPublicationCount DESC, coauthorId ASC`, so the cursor carries both values of the last
  * row. The token is URL-safe base64 of a small JSON document with a version and a tag of the
  * query scope (researcher and window): a token made for another scope, a tampered token and a
  * malformed token are all refused. Callers must treat the token as opaque.
  */
object PageCursor:
  private val Version = 1

  private def tag(scope: String): String =
    Sha256.of(scope).stripPrefix("sha256:").take(16)

  def scope(researcherId: String, windowStartYear: Int, windowEndYear: Int): String =
    s"$researcherId|$windowStartYear|$windowEndYear"

  def encode(scope: String, jointPublicationCount: Long, coauthorId: String): String =
    val doc = ujson.Obj(
      "v" -> ujson.Num(Version),
      "n" -> ujson.Num(jointPublicationCount.toDouble),
      "i" -> ujson.Str(coauthorId),
      "s" -> ujson.Str(tag(scope))
    )
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      ujson.write(doc).getBytes(StandardCharsets.UTF_8)
    )

  def decode(scope: String, token: String): Result[(Long, String)] =
    val invalid = StorageError.InvalidRecord("cursor is malformed or was issued for another query")
    try
      val doc = ujson.read(new String(Base64.getUrlDecoder.decode(token), StandardCharsets.UTF_8))
      val n = doc("n").num
      if doc("v").num.toInt != Version || doc("s").str != tag(scope) || n < 0 || n != n.floor then
        Left(invalid)
      else Right((n.toLong, doc("i").str))
    catch case scala.util.control.NonFatal(_) => Left(invalid)

  /** Builds a page from up to `limit + 1` fetched rows: the extra row only tells that more exist. */
  def page(scope: String, limit: Int, fetched: Vector[CoauthorView]): Page[CoauthorView] =
    if fetched.size > limit then
      val items = fetched.take(limit)
      val last = items.last
      Page(items, Some(encode(scope, last.edge.jointPublicationCount, last.coauthorId)))
    else Page(fetched, None)

/** Read port over the graph. Every method maps to one named, parameterized openCypher template.
  * COAUTHORED_WITH is stored once per canonical pair and window, in one direction; reads treat it
  * as undirected (`-[:COAUTHORED_WITH]-`). Requests above the configured limits are refused, not
  * clamped.
  */
trait GraphReader:
  def getResearcher(researcherId: String): Result[Option[ResearcherView]]

  def directCoauthors(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limit: Int
  ): Result[Vector[CoauthorView]]

  /** Direct coauthors ordered by `jointPublicationCount DESC, coauthorId ASC`, one page at a time.
    * `page.limit` is bounded like `directCoauthors`; an invalid cursor is refused.
    */
  def directCoauthorsPage(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      page: PageRequest
  ): Result[Page[CoauthorView]]

  def coauthorEdge(
      researcherA: String,
      researcherB: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[CoauthorEdgeView]]

  /** Simple paths (no repeated researcher) of at most `limits.maxDepth` hops inside one window,
    * ordered by length, then by the researcher IDs in hop order, then by the edge IDs; at most
    * `limits.resultLimit` of them. A query that runs past `limits.timeoutMillis` fails with
    * [[StorageError.Timeout]]. `source` must differ from `target`.
    */
  def paths(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limits: PathQueryLimits
  ): Result[Vector[EdgePath]]

  /** A shortest path of at most `maxDepth` hops inside one window, if any. */
  def shortestPath(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      maxDepth: Int
  ): Result[Option[PathView]]

// ---------------------------------------------------------------------------
// Query templates
// ---------------------------------------------------------------------------

/** A query text and its named parameters. The text is a constant of this module. */
final case class GraphQuery(
    name: String,
    text: String,
    parameters: Map[String, ujson.Value],
    timeoutMillis: Option[Long] = None
)

/** The only openCypher this module executes. Caller values travel in `parameters` and never enter
  * the text. The one variable part of a text, the variable-length bound of `shortestPath`, comes
  * from a table built once from configuration and is selected by lookup, never formatted from a
  * caller value.
  *
  * Neptune does not support `shortestPath()` (openCypher compliance page), so the path template
  * enumerates bounded variable-length paths inside one window and orders them by length.
  */
final class CypherTemplates(limits: GraphReaderLimits):
  import CypherTemplates.*

  private val pathTemplates: Map[Int, String] =
    (1 to limits.maxDepth).map(d => d -> pathText(d)).toMap

  def getResearcher(researcherId: String): GraphQuery =
    GraphQuery("getResearcher", GetResearcher, Map("researcherId" -> ujson.Str(researcherId)))

  def directCoauthors(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limit: Int
  ): Result[GraphQuery] =
    boundLimit(limit).map { l =>
      GraphQuery(
        "directCoauthors",
        DirectCoauthors,
        Map(
          "researcherId" -> ujson.Str(researcherId),
          "windowStartYear" -> ujson.Num(windowStartYear),
          "windowEndYear" -> ujson.Num(windowEndYear),
          "limit" -> ujson.Num(l)
        )
      )
    }

  def coauthorEdge(
      researcherA: String,
      researcherB: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): GraphQuery =
    GraphQuery(
      "coauthorEdge",
      CoauthorEdgeText,
      Map(
        "researcherA" -> ujson.Str(researcherA),
        "researcherB" -> ujson.Str(researcherB),
        "windowStartYear" -> ujson.Num(windowStartYear),
        "windowEndYear" -> ujson.Num(windowEndYear)
      )
    )

  def shortestPath(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      maxDepth: Int
  ): Result[GraphQuery] =
    boundDepth(maxDepth).flatMap { d =>
      pathTemplates
        .get(d)
        .toRight(StorageError.LimitExceeded("path depth", limits.maxDepth, d))
        .map { text =>
          GraphQuery(
            s"shortestPath/$d",
            text,
            Map(
              "source" -> ujson.Str(source),
              "target" -> ujson.Str(target),
              "windowStartYear" -> ujson.Num(windowStartYear),
              "windowEndYear" -> ujson.Num(windowEndYear),
              "candidateLimit" -> ujson.Num(limits.maxLimit)
            )
          )
        }
    }

  def directCoauthorsPage(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      page: PageRequest
  ): Result[GraphQuery] =
    for
      l <- boundLimit(page.limit)
      _ <- checkWindow(windowStartYear, windowEndYear)
      after <- page.cursor match
        case None => Right(None)
        case Some(token) =>
          PageCursor
            .decode(PageCursor.scope(researcherId, windowStartYear, windowEndYear), token)
            .map(Some(_))
    yield
      val base = Map(
        "researcherId" -> ujson.Str(researcherId),
        "windowStartYear" -> ujson.Num(windowStartYear),
        "windowEndYear" -> ujson.Num(windowEndYear),
        // one more row than the page holds: it only tells that a next page exists
        "fetchLimit" -> ujson.Num(l + 1)
      )
      after match
        case None => GraphQuery("directCoauthorsPage/first", DirectCoauthorsFirst, base)
        case Some((count, id)) =>
          GraphQuery(
            "directCoauthorsPage/after",
            DirectCoauthorsAfter,
            base ++ Map(
              "afterJointPublicationCount" -> ujson.Num(count.toDouble),
              "afterCoauthorId" -> ujson.Str(id)
            )
          )

  /** The bounded-path template of one depth. The only value that enters the text is the numeric
    * query time limit of the Neptune hint `USING QUERY:TIMEOUTMILLISECONDS <n>` (a hint takes a
    * literal, not a parameter): a `Long` that has been checked against the configured ceiling and
    * printed as digits.
    */
  def paths(
      source: String,
      target: String,
      windowStartYear: Int,
      windowEndYear: Int,
      q: PathQueryLimits
  ): Result[GraphQuery] =
    for
      d <- boundDepth(q.maxDepth)
      l <- boundLimit(q.resultLimit)
      t <- boundTimeout(q.timeoutMillis)
      _ <- checkWindow(windowStartYear, windowEndYear)
      _ <-
        if source == target then Left(StorageError.InvalidRecord("source and target must differ"))
        else Right(())
      body <- allPathTemplates
        .get(d)
        .toRight(StorageError.LimitExceeded("path depth", limits.maxDepth, d))
    yield GraphQuery(
      s"paths/$d",
      s"USING QUERY:TIMEOUTMILLISECONDS $t\n$body",
      Map(
        "source" -> ujson.Str(source),
        "target" -> ujson.Str(target),
        "windowStartYear" -> ujson.Num(windowStartYear),
        "windowEndYear" -> ujson.Num(windowEndYear),
        "resultLimit" -> ujson.Num(l)
      ),
      Some(t)
    )

  /** Ranking template. One text per supported metric (and per topic filter), chosen from a table
    * built from constants; the ranked property name never comes from a caller value.
    */
  def topK(
      metric: CollaborationMetric,
      windowStartYear: Int,
      windowEndYear: Int,
      filter: ResearcherFilter,
      limit: Int
  ): Result[GraphQuery] =
    for
      l <- boundLimit(limit)
      _ <- checkWindow(windowStartYear, windowEndYear)
      _ <- MetricRanking.check(metric)
      _ <-
        if filter.venueIds.nonEmpty || filter.institutionIds.nonEmpty then
          Left(
            StorageError.Unsupported(
              "venue and institution filters: PUBLISHED_IN and AFFILIATED_WITH are not loaded"
            )
          )
        else Right(())
      _ <-
        if filter.topicIds.size > limits.maxLimit then
          Left(
            StorageError.LimitExceeded("topic filter ids", limits.maxLimit, filter.topicIds.size)
          )
        else Right(())
      withTopics = filter.topicIds.nonEmpty
      text <- topKTexts
        .get((metric, withTopics))
        .toRight(StorageError.Unsupported(s"ranking by $metric"))
    yield
      val base = Map(
        "window" -> ujson.Str(MetricRanking.windowLabel(windowStartYear, windowEndYear)),
        "limit" -> ujson.Num(l)
      )
      GraphQuery(
        s"topK/$metric${if withTopics then "/topics" else ""}",
        text,
        if withTopics then base + ("topicIds" -> ujson.Arr.from(filter.topicIds.map(ujson.Str(_))))
        else base
      )

  /** The windows whose metrics are stored on Researcher nodes. */
  def loadedWindows(): GraphQuery =
    GraphQuery("loadedWindows", LoadedWindows, Map("limit" -> ujson.Num(limits.maxLimit)))

  private val allPathTemplates: Map[Int, String] =
    (1 to limits.maxDepth).map(d => d -> pathsText(d)).toMap

  private val topKTexts: Map[(CollaborationMetric, Boolean), String] =
    (for
      (metric, ranking) <- MetricRanking.table.toVector
      withTopics <- Vector(false, true)
    yield (metric, withTopics) -> topKText(ranking, withTopics)).toMap

  def checkWindow(windowStartYear: Int, windowEndYear: Int): Result[Unit] =
    if windowStartYear > windowEndYear then
      Left(
        StorageError.InvalidRecord(
          s"window start $windowStartYear is after window end $windowEndYear"
        )
      )
    else Right(())

  def boundTimeout(timeoutMillis: Long): Result[Long] =
    if timeoutMillis < 1 then
      Left(StorageError.InvalidRecord(s"timeout must be >= 1 ms, got $timeoutMillis"))
    else if timeoutMillis > limits.maxTimeoutMillis then
      Left(
        StorageError.LimitExceeded("path timeout millis", limits.maxTimeoutMillis, timeoutMillis)
      )
    else Right(timeoutMillis)

  def boundLimit(limit: Int): Result[Int] =
    if limit < 1 then Left(StorageError.InvalidRecord(s"limit must be >= 1, got $limit"))
    else if limit > limits.maxLimit then
      Left(StorageError.LimitExceeded("result limit", limits.maxLimit, limit))
    else Right(limit)

  def boundDepth(depth: Int): Result[Int] =
    if depth < 1 then Left(StorageError.InvalidRecord(s"depth must be >= 1, got $depth"))
    else if depth > limits.maxDepth then
      Left(StorageError.LimitExceeded("path depth", limits.maxDepth, depth))
    else Right(depth)

object CypherTemplates:
  /** The columns of a Researcher node with the metrics of the node window. */
  private[storage] val ResearcherReturn: String =
    """id(r) AS researcherId, r.displayName AS displayName, r.orcid AS orcid,
      |       r.runId AS runId, r.window AS window, r.P_r AS P_r, r.C_r AS C_r, r.T_r AS T_r,
      |       r.A_r AS A_r, r.M_r AS M_r, r.W_r AS W_r, r.componentId AS componentId,
      |       r.communityId AS communityId, r.bridgeScore AS bridgeScore,
      |       r.coreness AS coreness, r.metricVersion AS metricVersion""".stripMargin

  val GetResearcher: String =
    s"""MATCH (r:Researcher)
       |WHERE id(r) = $$researcherId
       |RETURN $ResearcherReturn""".stripMargin

  private val EdgeReturn: String =
    """id(e) AS edgeId, id(startNode(e)) AS researcherA, id(endNode(e)) AS researcherB,
      |       e.jointPublicationCount AS jointPublicationCount,
      |       e.firstCollaborationYear AS firstCollaborationYear,
      |       e.lastCollaborationYear AS lastCollaborationYear,
      |       e.windowStartYear AS windowStartYear, e.windowEndYear AS windowEndYear,
      |       e.evidencePointer AS evidencePointer, e.runId AS runId,
      |       e.policyVersion AS policyVersion""".stripMargin

  val DirectCoauthors: String =
    s"""MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher)
       |WHERE id(r) = $$researcherId
       |  AND e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear
       |RETURN id(c) AS coauthorId, $EdgeReturn
       |ORDER BY e.jointPublicationCount DESC, id(c) ASC
       |LIMIT $$limit""".stripMargin

  val CoauthorEdgeText: String =
    s"""MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher)
       |WHERE id(a) = $$researcherA AND id(b) = $$researcherB
       |  AND e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear
       |RETURN $EdgeReturn
       |ORDER BY e.policyVersion ASC, id(e) ASC
       |LIMIT 2""".stripMargin

  private[storage] def pathText(depth: Int): String =
    s"""MATCH p = (a:Researcher)-[:COAUTHORED_WITH*1..$depth]-(b:Researcher)
       |WHERE id(a) = $$source AND id(b) = $$target
       |  AND ALL(e IN relationships(p)
       |          WHERE e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear)
       |RETURN [n IN nodes(p) | id(n)] AS researcherIds, [e IN relationships(p) | id(e)] AS edgeIds,
       |       length(p) AS length
       |ORDER BY length(p) ASC
       |LIMIT $$candidateLimit""".stripMargin

  val DirectCoauthorsFirst: String =
    s"""MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher)
       |WHERE id(r) = $$researcherId
       |  AND e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear
       |RETURN id(c) AS coauthorId, $EdgeReturn
       |ORDER BY e.jointPublicationCount DESC, id(c) ASC
       |LIMIT $$fetchLimit""".stripMargin

  val DirectCoauthorsAfter: String =
    s"""MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher)
       |WHERE id(r) = $$researcherId
       |  AND e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear
       |  AND (e.jointPublicationCount < $$afterJointPublicationCount
       |       OR (e.jointPublicationCount = $$afterJointPublicationCount
       |           AND id(c) > $$afterCoauthorId))
       |RETURN id(c) AS coauthorId, $EdgeReturn
       |ORDER BY e.jointPublicationCount DESC, id(c) ASC
       |LIMIT $$fetchLimit""".stripMargin

  /** Every hop edge is returned as a map so that one query yields the path and its evidence
    * pointers. Paths are simple (no repeated researcher), and ordered by length, then by the IDs
    * in hop order, then by the edge IDs, so that `LIMIT` cuts deterministically. The text starts
    * after the timeout hint line, which [[CypherTemplates.paths]] prepends.
    */
  private[storage] def pathsText(depth: Int): String =
    s"""MATCH p = (a:Researcher)-[:COAUTHORED_WITH*1..$depth]-(b:Researcher)
       |WHERE id(a) = $$source AND id(b) = $$target
       |  AND ALL(e IN relationships(p)
       |          WHERE e.windowStartYear = $$windowStartYear AND e.windowEndYear = $$windowEndYear)
       |  AND ALL(i IN range(0, size(nodes(p)) - 1)
       |          WHERE ALL(j IN range(i + 1, size(nodes(p)) - 1)
       |                    WHERE nodes(p)[i] <> nodes(p)[j]))
       |RETURN [n IN nodes(p) | id(n)] AS researcherIds,
       |       [e IN relationships(p) | {edgeId: id(e), researcherA: id(startNode(e)),
       |         researcherB: id(endNode(e)),
       |         jointPublicationCount: e.jointPublicationCount,
       |         firstCollaborationYear: e.firstCollaborationYear,
       |         lastCollaborationYear: e.lastCollaborationYear,
       |         windowStartYear: e.windowStartYear, windowEndYear: e.windowEndYear,
       |         evidencePointer: e.evidencePointer, runId: e.runId,
       |         policyVersion: e.policyVersion}] AS hops,
       |       length(p) AS length,
       |       join([n IN nodes(p) | id(n)], '|') AS pathKey,
       |       join([e IN relationships(p) | id(e)], '|') AS edgeKey
       |ORDER BY length ASC, pathKey ASC, edgeKey ASC
       |LIMIT $$resultLimit""".stripMargin

  val LoadedWindows: String =
    """MATCH (r:Researcher)
      |WHERE r.window IS NOT NULL
      |RETURN DISTINCT r.window AS window
      |ORDER BY window ASC
      |LIMIT $limit""".stripMargin

  private[storage] def topKText(ranking: MetricRanking.Ranking, withTopics: Boolean): String =
    ranking match
      case MetricRanking.Ranking.Property(prop) =>
        val head =
          if withTopics then s"""MATCH (r:Researcher)-[:AUTHORED]->(:Work)-[:HAS_TOPIC]->(t:Topic)
               |WHERE r.window = $$window AND r.$prop IS NOT NULL AND id(t) IN $$topicIds
               |WITH DISTINCT r""".stripMargin
          else s"""MATCH (r:Researcher)
                  |WHERE r.window = $$window AND r.$prop IS NOT NULL""".stripMargin
        s"""$head
           |RETURN $ResearcherReturn, r.$prop AS metricValue
           |ORDER BY r.$prop DESC, id(r) ASC
           |LIMIT $$limit""".stripMargin
      case MetricRanking.Ranking.GroupSize(prop) =>
        val tail =
          if withTopics then s"""MATCH (r:Researcher)-[:AUTHORED]->(:Work)-[:HAS_TOPIC]->(t:Topic)
               |WHERE r.window = $$window AND r.$prop = groupId AND id(t) IN $$topicIds
               |WITH DISTINCT r, groupSize""".stripMargin
          else s"""MATCH (r:Researcher)
                  |WHERE r.window = $$window AND r.$prop = groupId""".stripMargin
        s"""MATCH (c:Researcher)
           |WHERE c.window = $$window AND c.$prop IS NOT NULL
           |WITH c.$prop AS groupId, count(c) AS groupSize
           |$tail
           |RETURN $ResearcherReturn, groupSize AS metricValue
           |ORDER BY groupSize DESC, id(r) ASC
           |LIMIT $$limit""".stripMargin
