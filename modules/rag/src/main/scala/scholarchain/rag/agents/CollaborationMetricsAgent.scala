package scholarchain.rag.agents

import scholarchain.core.config.RetrievalConfig
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.core.model.{CollaborationMetric, Provenance, ResearcherMetrics}
import scholarchain.rag.render.TextSanitizer
import scholarchain.storage.dynamo.ResearcherMetricCache
import scholarchain.storage.graph.{MetricReader, ResearcherFilter}

/** A stored bridge measure with its name and definition. Never a composite score. */
final case class BridgeValue(metric: CollaborationMetric, value: Double, definition: String)

object BridgeValue:
  def definition(m: CollaborationMetric): String = m match
    case CollaborationMetric.CROSS_COMMUNITY_DEGREE =>
      "number of direct coauthors outside the researcher's own community"
    case CollaborationMetric.PARTICIPATION_COEFFICIENT =>
      "1 - sum over communities c of (k_rc / k_r)^2, with k_r the number of direct coauthors and k_rc those in community c"
    case CollaborationMetric.APPROX_BETWEENNESS =>
      "approximate betweenness centrality estimated from sampled source researchers"
    case CollaborationMetric.COMMUNITIES_CONNECTED =>
      "number of distinct other communities the researcher's coauthors belong to"
    case other => s"stored value of $other"

/** One researcher's stored measures in one window: exactly what the metric store holds.
  *
  * P_r publicationCount, C_r distinctCoauthorCount, T_r totalCoauthorOccurrences, A_r
  * averageCoauthorsPerPaper, M_r maximumCoauthorsOnOnePaper (with the works that reach it), W_r
  * weightedDegree.
  */
final case class MetricsReport(
    metricsId: String,
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    bridge: Option[BridgeValue],
    coreness: Option[Int],
    metricVersion: String,
    runId: String,
    provenance: Provenance
)

object MetricsReport:
  def of(m: ResearcherMetrics): MetricsReport =
    MetricsReport(
      m.metricsId,
      m.researcherId,
      m.windowStartYear,
      m.windowEndYear,
      m.publicationCount,
      m.distinctCoauthorCount,
      m.totalCoauthorOccurrences,
      m.averageCoauthorsPerPaper,
      m.maximumCoauthorsOnOnePaper,
      m.maximumTeamWorkIds,
      m.weightedDegree,
      m.componentId,
      m.componentSize,
      m.communityId,
      for
        metric <- m.bridgeMetric
        value <- m.bridgeScore
      yield BridgeValue(metric, value, BridgeValue.definition(metric)),
      m.coreness,
      m.metricVersion,
      m.provenance.runId,
      m.provenance
    )

/** One place of a ranking. `record` is the full stored metrics row when the metric cache has it. */
final case class RankedEntry(
    rank: Int,
    researcherId: String,
    displayName: String,
    metricValue: Double,
    record: Option[MetricsReport]
)

/** A ranking by exactly one stored metric. The order is the store's: `metricValue DESC,
  * researcherId ASC`. There is no composite score and no re-ranking here.
  */
final case class RankingReport(
    metric: CollaborationMetric,
    windowStartYear: Int,
    windowEndYear: Int,
    ordering: String,
    entries: Vector[RankedEntry],
    topicIds: Vector[String]
)

/** Filters of a ranking as the store takes them: stable IDs only. */
final case class RankingFilter(
    topics: Vector[String] = Vector.empty,
    venues: Vector[String] = Vector.empty,
    institutions: Vector[String] = Vector.empty
)

/** Stored collaboration measures, read from the metric store and the metric cache.
  *
  * Counts, components, communities and bridge scores come only from those stores. A request the
  * store refuses (a bridge ranking, a venue or institution filter, a window that is not loaded)
  * is returned as [[StageFailure.Unsupported]]; nothing here substitutes another measure.
  */
final class CollaborationMetricsAgent(
    metrics: MetricReader,
    cache: Option[ResearcherMetricCache],
    retrieval: RetrievalConfig
):
  val Ordering = "metricValue DESC, researcherId ASC"

  private def filterOf(f: RankingFilter): SResult[ResearcherFilter] =
    def ids(kind: IdKind, what: String, values: Vector[String]): SResult[Vector[String]] =
      values
        .find(v => StableId.parseAs(v, Set(kind)).isLeft)
        .fold[SResult[Vector[String]]](Right(values))(_ =>
          Left(
            StageFailure.Unsupported(
              s"$what are given as names and cannot be resolved to stable IDs: there is no $what directory"
            )
          )
        )
    for
      t <- ids(IdKind.Topic, "topics", f.topics)
      v <- ids(IdKind.Venue, "venues", f.venues)
      i <- ids(IdKind.Institution, "institutions", f.institutions)
    yield ResearcherFilter(t, v, i)

  private def cacheOrUnsupported: SResult[ResearcherMetricCache] =
    cache.toRight(StageFailure.Unsupported("the researcher metric cache is not configured"))

  /** The stored measures of one researcher in one window. */
  def profile(
      researcherId: String,
      windowStartYear: Int,
      windowEndYear: Int
  ): SResult[MetricsReport] =
    for
      c <- cacheOrUnsupported
      found <- c
        .get(researcherId, windowStartYear, windowEndYear)
        .left
        .map(StageFailure.fromStorage("researcherMetricCache.get", _))
      m <- found.toRight(
        StageFailure.NotFound(
          s"no stored metrics for $researcherId in $windowStartYear-$windowEndYear"
        )
      )
    yield MetricsReport.of(m)

  /** The stored measures of one researcher in every stored window, oldest first. */
  def windows(researcherId: String): SResult[Vector[MetricsReport]] =
    for
      c <- cacheOrUnsupported
      all <- c
        .listWindows(researcherId)
        .left
        .map(StageFailure.fromStorage("researcherMetricCache.listWindows", _))
    yield all.sortBy(m => (m.windowStartYear, m.windowEndYear)).map(MetricsReport.of)

  /** Researchers ranked by exactly `metric` in one window. `limit` is capped by
    * `retrieval.graphLimit`.
    */
  def rank(
      metric: CollaborationMetric,
      windowStartYear: Int,
      windowEndYear: Int,
      filter: RankingFilter,
      limit: Int
  ): SResult[RankingReport] =
    for
      f <- filterOf(filter)
      capped <-
        if limit >= 1 then Right(math.min(limit, retrieval.graphLimit))
        else Left(StageFailure.Rejected("LIMIT_INVALID", "limit must be at least 1"))
      ranked <- metrics
        .topK(metric, windowStartYear, windowEndYear, f, capped)
        .left
        .map(StageFailure.fromStorage("metricReader.topK", _))
    yield RankingReport(
      metric,
      windowStartYear,
      windowEndYear,
      Ordering,
      ranked.zipWithIndex.map { (r, i) =>
        val record = cache.flatMap(
          _.get(r.researcherId, windowStartYear, windowEndYear).toOption.flatten
            .map(MetricsReport.of)
        )
        RankedEntry(
          i + 1,
          r.researcherId,
          TextSanitizer.clean(r.displayName, 200),
          r.metricValue,
          record
        )
      },
      f.topicIds
    )

  /** The largest teams: a ranking by M_r, with the works that reach the maximum in `record`. */
  def maximumTeams(
      windowStartYear: Int,
      windowEndYear: Int,
      filter: RankingFilter,
      limit: Int
  ): SResult[RankingReport] =
    rank(
      CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER,
      windowStartYear,
      windowEndYear,
      filter,
      limit
    )
