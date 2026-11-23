package scholarchain.rag.agents

import scholarchain.core.config.RetrievalConfig
import scholarchain.core.model.{CommunityMembership, ComponentMembership, CrossCommunityEdge}
import scholarchain.storage.common.StorageError
import scholarchain.storage.dynamo.ResearcherMetricCache
import scholarchain.storage.graph.AnalyticsReader

/** The community of one researcher in one window, from the stored analytics records.
  *
  * Direct degree and bridge importance are separate fields on purpose: `directDegree` is C_r, the
  * number of distinct direct coauthors; `bridge` is the stored bridge measure with its name and
  * definition. One does not stand for the other.
  *
  * @param crossCommunityEdgeCount
  *   the stored exact count; never the length of `crossEdgeSample`
  * @param communitiesConnected
  *   the stored number of distinct other communities the community reaches
  * @param crossEdgeSample
  *   at most `retrieval.graphLimit` of the cross-community edges, strongest first (a sample)
  * @param gaps
  *   stored values the stores did not hold, so a caller can state what is unknown
  */
final case class CommunityReport(
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    membership: CommunityMembership,
    communityId: String,
    communitySize: Long,
    crossCommunityEdgeCount: Option[Long],
    communitiesConnected: Option[Long],
    crossEdgeSample: Vector[CrossCommunityEdge],
    directDegree: Option[Long],
    bridge: Option[BridgeValue],
    gaps: Vector[String]
)

/** Several researchers' communities in one window. Community IDs are window-local labels; they
  * are compared only inside this one window.
  */
final case class CommunityComparison(
    windowStartYear: Int,
    windowEndYear: Int,
    reports: Vector[CommunityReport],
    sameCommunity: Boolean
)

/** Community and component lookups over the stored analytics records.
  *
  * Counts are the stored ones. A stored count that is absent is reported as a gap, never
  * computed from a sample.
  */
final class CommunityAnalystAgent(
    analytics: AnalyticsReader,
    cache: Option[ResearcherMetricCache],
    retrieval: RetrievalConfig
):
  def componentOf(researcherId: String, ws: Int, we: Int): SResult[ComponentMembership] =
    analytics
      .componentOf(researcherId, ws, we)
      .left
      .map(StageFailure.fromStorage("analyticsReader.componentOf", _))
      .flatMap(
        _.toRight(StageFailure.NotFound(s"no component record for $researcherId in $ws-$we"))
      )

  def analyse(researcherId: String, ws: Int, we: Int): SResult[CommunityReport] =
    for
      found <- analytics
        .communityOf(researcherId, ws, we)
        .left
        .map(StageFailure.fromStorage("analyticsReader.communityOf", _))
      m <- found.toRight(
        StageFailure.NotFound(s"no community record for $researcherId in $ws-$we")
      )
      edgeCount <- stored(
        "crossCommunityEdgeCount",
        analytics.crossCommunityEdgeCount(m.communityId, ws, we)
      )
      connected <- stored(
        "communitiesConnected",
        analytics.communitiesConnected(m.communityId, ws, we)
      )
      sample <- analytics
        .crossCommunityEdges(m.communityId, ws, we, retrieval.graphLimit)
        .left
        .map(StageFailure.fromStorage("analyticsReader.crossCommunityEdges", _))
      metrics <- cache match
        case None => Right(None)
        case Some(c) =>
          c.get(researcherId, ws, we)
            .left
            .map(StageFailure.fromStorage("researcherMetricCache.get", _))
      cacheGap =
        if cache.isEmpty then Vector("direct degree and bridge metric: no metric cache")
        else Vector.empty
      metricGap =
        if cache.isDefined && metrics.isEmpty then
          Vector("direct degree and bridge metric: no stored metrics row")
        else Vector.empty
    yield CommunityReport(
      researcherId,
      ws,
      we,
      m,
      m.communityId,
      m.communitySize,
      edgeCount,
      connected,
      sample,
      metrics.map(_.distinctCoauthorCount),
      metrics.flatMap(x => MetricsReport.of(x).bridge),
      cacheGap ++ metricGap ++ gapOf("crossCommunityEdgeCount", edgeCount) ++ gapOf(
        "communitiesConnected",
        connected
      )
    )

  /** Stored count: a value, a recorded gap (not stored), or a failure of the call. */
  private def stored(name: String, r: Either[StorageError, Long]): SResult[Option[Long]] =
    r match
      case Right(n) => Right(Some(n))
      case Left(StorageError.NotFound(_)) => Right(None)
      case Left(e) => Left(StageFailure.fromStorage(s"analyticsReader.$name", e))

  private def gapOf(name: String, v: Option[Long]): Vector[String] =
    if v.isEmpty then Vector(s"$name: not stored for this community and window") else Vector.empty

  /** Compares the communities of up to the requested researchers in one window. */
  def compare(researcherIds: Vector[String], ws: Int, we: Int): SResult[CommunityComparison] =
    researcherIds.distinct
      .foldLeft[SResult[Vector[CommunityReport]]](Right(Vector.empty)) { (acc, id) =>
        acc.flatMap(done => analyse(id, ws, we).map(done :+ _))
      }
      .map(rs => CommunityComparison(ws, we, rs, rs.map(_.communityId).distinct.size == 1))
