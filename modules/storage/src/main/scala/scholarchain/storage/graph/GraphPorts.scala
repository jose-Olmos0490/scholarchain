package scholarchain.storage.graph

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

/** Hard bounds from configuration (`retrieval.graphLimit`, `retrieval.maxPathDepth`). */
final case class GraphReaderLimits(maxLimit: Int, maxDepth: Int)

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

  def coauthorEdge(
      researcherA: String,
      researcherB: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[CoauthorEdgeView]]

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
final case class GraphQuery(name: String, text: String, parameters: Map[String, ujson.Value])

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
  val GetResearcher: String =
    """MATCH (r:Researcher)
      |WHERE id(r) = $researcherId
      |RETURN id(r) AS researcherId, r.displayName AS displayName, r.orcid AS orcid,
      |       r.runId AS runId, r.window AS window, r.P_r AS P_r, r.C_r AS C_r, r.T_r AS T_r,
      |       r.A_r AS A_r, r.M_r AS M_r, r.W_r AS W_r, r.componentId AS componentId,
      |       r.communityId AS communityId, r.bridgeScore AS bridgeScore,
      |       r.coreness AS coreness, r.metricVersion AS metricVersion""".stripMargin

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
