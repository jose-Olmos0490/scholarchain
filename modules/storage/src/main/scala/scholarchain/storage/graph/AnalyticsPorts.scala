package scholarchain.storage.graph

import scala.collection.mutable
import scholarchain.core.model.{CommunityMembership, ComponentMembership, CrossCommunityEdge}
import scholarchain.storage.common.*

/** Stored per-community connectivity of one window: the exact number of cross-community edges and
  * the number of distinct other communities they reach. These are produced by the analytics run;
  * a reader returns the stored numbers and never derives them from a (possibly truncated) edge
  * list.
  */
final case class CommunityConnectivity(
    communityId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    crossCommunityEdgeCount: Long,
    communitiesConnected: Long,
    metricVersion: String,
    runId: String
)

/** Read port over the stored component and community records of the analytics run.
  *
  * Every operation is keyed by researcher or community and one window. Component and community
  * IDs are window-local labels and must not be compared across windows. A missing record is
  * `None` for the membership lookups and [[StorageError.NotFound]] for the stored counts. Limits
  * are refused above the configured graph limit.
  */
trait AnalyticsReader:
  def componentOf(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[ComponentMembership]]

  def communityOf(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Option[CommunityMembership]]

  /** Edges that leave or enter `communityId`, by `jointPublicationCount DESC, edgeId ASC`, at most
    * `limit`. Use [[crossCommunityEdgeCount]] for the exact total.
    */
  def crossCommunityEdges(
      communityId: String,
      windowStartYear: Int,
      windowEndYear: Int,
      limit: Int
  ): Result[Vector[CrossCommunityEdge]]

  /** The stored exact number of cross-community edges of the community. */
  def crossCommunityEdgeCount(
      communityId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Long]

  /** The stored number of distinct other communities the community is connected to. */
  def communitiesConnected(
      communityId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): Result[Long]

/** In-memory [[AnalyticsReader]]. No real adapter exists yet because no store loads these
  * records: the Neptune export skips `components`, `community_membership` and
  * `cross_community_edge`, and the DynamoDB load only takes `researcher_collaboration`.
  */
final class FakeAnalyticsReader(limits: GraphReaderLimits) extends AnalyticsReader:
  private val templates = new CypherTemplates(limits)
  private val components = mutable.LinkedHashMap.empty[(String, Int, Int), ComponentMembership]
  private val communities = mutable.LinkedHashMap.empty[(String, Int, Int), CommunityMembership]
  private val crossEdges = mutable.ArrayBuffer.empty[CrossCommunityEdge]
  private val connectivity = mutable.LinkedHashMap.empty[(String, Int, Int), CommunityConnectivity]

  def putComponent(m: ComponentMembership): Unit = synchronized {
    components((m.researcherId, m.windowStartYear, m.windowEndYear)) = m
  }
  def putCommunity(m: CommunityMembership): Unit = synchronized {
    communities((m.researcherId, m.windowStartYear, m.windowEndYear)) = m
  }
  def putCrossEdge(e: CrossCommunityEdge): Unit = synchronized {
    crossEdges.filterInPlace(_.edgeId != e.edgeId)
    crossEdges += e
  }
  def putConnectivity(c: CommunityConnectivity): Unit = synchronized {
    connectivity((c.communityId, c.windowStartYear, c.windowEndYear)) = c
  }

  def componentOf(r: String, ws: Int, we: Int): Result[Option[ComponentMembership]] =
    templates.checkWindow(ws, we).map(_ => synchronized(components.get((r, ws, we))))

  def communityOf(r: String, ws: Int, we: Int): Result[Option[CommunityMembership]] =
    templates.checkWindow(ws, we).map(_ => synchronized(communities.get((r, ws, we))))

  def crossCommunityEdges(
      communityId: String,
      ws: Int,
      we: Int,
      limit: Int
  ): Result[Vector[CrossCommunityEdge]] =
    for
      l <- templates.boundLimit(limit)
      _ <- templates.checkWindow(ws, we)
    yield synchronized {
      crossEdges.toVector
        .filter(e =>
          e.windowStartYear == ws && e.windowEndYear == we &&
            (e.communityA == communityId || e.communityB == communityId)
        )
        .sortBy(e => (-e.jointPublicationCount, e.edgeId))
        .take(l)
    }

  private def stored(communityId: String, ws: Int, we: Int): Result[CommunityConnectivity] =
    templates.checkWindow(ws, we).flatMap { _ =>
      synchronized(connectivity.get((communityId, ws, we))).toRight(
        StorageError.NotFound(s"community connectivity $communityId $ws-$we")
      )
    }

  def crossCommunityEdgeCount(communityId: String, ws: Int, we: Int): Result[Long] =
    stored(communityId, ws, we).map(_.crossCommunityEdgeCount)

  def communitiesConnected(communityId: String, ws: Int, we: Int): Result[Long] =
    stored(communityId, ws, we).map(_.communitiesConnected)
