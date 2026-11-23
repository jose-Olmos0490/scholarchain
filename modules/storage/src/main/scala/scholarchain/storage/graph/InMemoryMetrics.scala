package scholarchain.storage.graph

import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.common.*

/** Fake [[MetricReader]] over an [[InMemoryGraph]]: the same bounds, the same refusals and the
  * same ordering as the Cypher templates of [[CypherTemplates.topK]].
  */
final class FakeMetricReader(graph: InMemoryGraph, limits: GraphReaderLimits) extends MetricReader:
  private val templates = new CypherTemplates(limits)
  private val reader = new FakeGraphReader(graph, limits)

  private def researchers: Vector[graph.Node] =
    graph.allNodes.filter(_.labels.contains("Researcher"))

  def loadedWindows: Result[Vector[(Int, Int)]] =
    Right(
      researchers
        .flatMap(_.props.get("window").collect { case ujson.Str(w) => w })
        .distinct
        .sorted
        .flatMap(w => MetricRanking.parseWindow(w).toVector)
        .take(limits.maxLimit)
    )

  def topK(
      metric: CollaborationMetric,
      windowStartYear: Int,
      windowEndYear: Int,
      filter: ResearcherFilter,
      limit: Int
  ): Result[Vector[RankedResearcher]] =
    for
      _ <- templates.topK(metric, windowStartYear, windowEndYear, filter, limit)
      ranked <- Right(rank(metric, windowStartYear, windowEndYear, filter, limit))
      out <-
        if ranked.nonEmpty then Right(ranked)
        else
          loadedWindows.flatMap { loaded =>
            if loaded.contains((windowStartYear, windowEndYear)) then Right(ranked)
            else Left(MetricRanking.windowNotLoaded(windowStartYear, windowEndYear, loaded))
          }
    yield out

  private def rank(
      metric: CollaborationMetric,
      ws: Int,
      we: Int,
      filter: ResearcherFilter,
      limit: Int
  ): Vector[RankedResearcher] =
    val label = MetricRanking.windowLabel(ws, we)
    val inWindow = researchers.filter(_.props.get("window").contains(ujson.Str(label)))
    val values: Vector[(graph.Node, Double)] = MetricRanking.table(metric) match
      case MetricRanking.Ranking.Property(name) =>
        inWindow.flatMap(n => n.props.get(name).collect { case ujson.Num(v) => (n, v) })
      case MetricRanking.Ranking.GroupSize(name) =>
        val group = (n: graph.Node) => n.props.get(name).collect { case ujson.Str(v) => v }
        val sizes = inWindow.flatMap(group).groupBy(identity).view.mapValues(_.size).toMap
        inWindow.flatMap(n => group(n).map(g => (n, sizes(g).toDouble)))
    val allowed: Set[String] =
      if filter.topicIds.isEmpty then values.map(_._1.id).toSet
      else
        val topics = filter.topicIds.toSet
        val topicWorks =
          graph.allRels.filter(r => r.relType == "HAS_TOPIC" && topics(r.end)).map(_.start).toSet
        graph.allRels
          .filter(r => r.relType == "AUTHORED" && topicWorks(r.end))
          .map(_.start)
          .toSet
    values
      .filter((n, _) => allowed(n.id))
      .sortBy((n, v) => (-v, n.id))
      .take(limit)
      .flatMap { (n, v) =>
        reader.getResearcher(n.id).toOption.flatten.flatMap(_.metrics).map { m =>
          RankedResearcher(n.id, reader.displayNameOf(n.id), v, m)
        }
      }
