package scholarchain.storage.graph

import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.common.*

/** Optional narrowing of a ranking. Only `topicIds` can be served today: it follows
  * `AUTHORED` then `HAS_TOPIC`, which are loaded. `venueIds` and `institutionIds` need
  * `PUBLISHED_IN` and `AFFILIATED_WITH`, which the graph load does not produce, so a non-empty
  * value is refused with [[StorageError.Unsupported]] instead of being ignored.
  */
final case class ResearcherFilter(
    topicIds: Vector[String] = Vector.empty,
    venueIds: Vector[String] = Vector.empty,
    institutionIds: Vector[String] = Vector.empty
)

object ResearcherFilter:
  val Unfiltered: ResearcherFilter = ResearcherFilter()

/** A researcher with the value the ranking used and the node metrics it came from. */
final case class RankedResearcher(
    researcherId: String,
    displayName: String,
    metricValue: Double,
    metrics: ResearcherNodeMetrics
)

/** Ranking of researchers by one stored metric.
  *
  * Order: exactly by `metricValue` descending, ties by `researcherId` ascending. Researchers with
  * no stored value for the metric are not ranked.
  *
  * Availability. Metrics live on Researcher nodes for one window only (the node window chosen at
  * export; the widest window by default), so a ranking exists only for the windows reported by
  * [[loadedWindows]]. A window that is not loaded is refused with [[StorageError.Unsupported]],
  * never answered with an empty list. Supported metrics ([[MetricRanking.supported]]):
  * PUBLICATION_COUNT, DISTINCT_COAUTHOR_COUNT, TOTAL_COAUTHOR_OCCURRENCES,
  * AVERAGE_COAUTHORS_PER_PAPER, MAXIMUM_COAUTHORS_ON_ONE_PAPER, WEIGHTED_DEGREE, CORENESS,
  * COMPONENT_SIZE and COMMUNITY_SIZE. Every other metric is refused with
  * [[StorageError.Unsupported]]: SHORTEST_PATH_LENGTH is pairwise, and APPROX_BETWEENNESS,
  * CROSS_COMMUNITY_DEGREE, PARTICIPATION_COEFFICIENT and COMMUNITIES_CONNECTED are bridge
  * measures that no store loads (the node's `bridgeScore` does not say which bridge metric it
  * holds, so it is not offered under a name).
  *
  * `limit` is bounded by the configured graph limit and refused above it.
  */
trait MetricReader:
  def loadedWindows: Result[Vector[(Int, Int)]]

  def topK(
      metric: CollaborationMetric,
      windowStartYear: Int,
      windowEndYear: Int,
      filter: ResearcherFilter,
      limit: Int
  ): Result[Vector[RankedResearcher]]

/** The table that says which node property ranks each metric. */
object MetricRanking:
  enum Ranking:
    /** Rank by the value of one node property. */
    case Property(name: String)

    /** Rank by the number of nodes of the window that share the value of one node property. */
    case GroupSize(property: String)

  val table: Map[CollaborationMetric, Ranking] =
    import CollaborationMetric.*
    Map(
      PUBLICATION_COUNT -> Ranking.Property("P_r"),
      DISTINCT_COAUTHOR_COUNT -> Ranking.Property("C_r"),
      TOTAL_COAUTHOR_OCCURRENCES -> Ranking.Property("T_r"),
      AVERAGE_COAUTHORS_PER_PAPER -> Ranking.Property("A_r"),
      MAXIMUM_COAUTHORS_ON_ONE_PAPER -> Ranking.Property("M_r"),
      WEIGHTED_DEGREE -> Ranking.Property("W_r"),
      CORENESS -> Ranking.Property("coreness"),
      COMPONENT_SIZE -> Ranking.GroupSize("componentId"),
      COMMUNITY_SIZE -> Ranking.GroupSize("communityId")
    )

  val supported: Set[CollaborationMetric] = table.keySet

  def check(metric: CollaborationMetric): Result[Unit] =
    if table.contains(metric) then Right(())
    else Left(StorageError.Unsupported(s"ranking by $metric is not stored on any node"))

  def windowLabel(windowStartYear: Int, windowEndYear: Int): String =
    s"$windowStartYear-$windowEndYear"

  private val WindowPattern = "(\\d{4})-(\\d{4})".r

  def parseWindow(label: String): Option[(Int, Int)] = label match
    case WindowPattern(a, b) => Some((a.toInt, b.toInt))
    case _ => None

  /** The refusal for a window that no node carries, naming the windows that are loaded. */
  def windowNotLoaded(start: Int, end: Int, loaded: Vector[(Int, Int)]): StorageError =
    StorageError.Unsupported(
      s"window ${windowLabel(start, end)} is not stored on researcher nodes; loaded: " +
        (if loaded.isEmpty then "none" else loaded.map((a, b) => windowLabel(a, b)).mkString(", "))
    )
