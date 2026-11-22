package scholarchain.spark.graph

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, SparkSession}
import scholarchain.core.config.BenchmarkPair
import scholarchain.spark.{Lineage, Windows}

/** PART B parameters (all from configuration; see SparkAnalyticsSettings). */
final case class AnalyticsParams(
    communityMaxIterations: Int,
    kcoreMaxIterations: Int,
    pathMaxDepth: Int,
    benchmarkPairs: Vector[BenchmarkPair],
    samplePairCount: Int,
    pathSeed: Long,
    diameterSampleSources: Int,
    diameterSeed: Long,
    diameterMinComponentSize: Int,
    distanceDepthBound: Int,
    topEntityLimit: Int,
    memberLimit: Int
)

/** All PART B outputs. */
final case class AnalyticsOutputs(
    metrics: DataFrame,
    communities: DataFrame,
    crossEdges: DataFrame,
    bridges: DataFrame,
    coreness: DataFrame,
    paths: DataFrame,
    semanticFacts: DataFrame,
    windowChanges: Option[DataFrame],
    communityRun: Vector[ConnectedComponents.GroupIterations],
    kcore: KCore.Result,
    queries: Vector[Paths.Query],
    pathRun: Paths.PathResult,
    distances: Vector[Paths.DistanceEstimate],
    lpaPlan: DataFrame
)

/** PART B jobs 5-10 over the PART A outputs: paths, communities, bridges, k-core, temporal
  * comparison and semantic facts. Nothing is collected except the bounded values documented at
  * each collect (window counts, path queries, sampled sources, distance histograms).
  */
object Analytics:
  def build(
      spark: SparkSession,
      out: GraphOutputs,
      p: BuildParams,
      a: AnalyticsParams
  ): AnalyticsOutputs =
    val nodes = out.components.select(col(Windows.Start), col(Windows.End), col("researcherId"))

    // 6-7. communities, cross-community edges, bridge measures
    val comm = Communities.run(spark, nodes, out.edges, p, a.communityMaxIterations)
    val communities = Lineage.cut(comm.membership, p.checkpoint)
    val bridges = Lineage.cut(comm.bridges, p.checkpoint)

    // 8. k-core
    val kc = KCore.run(spark, nodes, out.edges, p, a.kcoreMaxIterations)
    val coreness = Lineage.cut(kc.coreness, p.checkpoint)

    // ResearcherMetrics gains communityId and coreness (bridgeMetric/bridgeScore stay unset: the
    // bridge measures are separate records, never one opaque score).
    val wk = Windows.Keys :+ "researcherId"
    val metricCols = out.metrics.columns.toSeq
    val metrics = out.metrics
      .drop("communityId", "coreness")
      .join(communities.select((wk.map(col) :+ col("communityId"))*), wk, "left")
      .join(coreness.select((wk.map(col) :+ col("coreness"))*), wk, "left")
      .select(metricCols.map(col)*)

    // 5. paths and the distance estimate
    val qs = Paths.queries(out.components, p, a.benchmarkPairs, a.samplePairCount, a.pathSeed)
    val pathRun =
      Paths.shortestPaths(spark, qs, out.edges, out.edgeSupport, out.components, p, a.pathMaxDepth)
    val paths = Lineage.cut(pathRun.evidence, p.checkpoint)
    val distances = Paths.distanceEstimates(
      spark,
      out.edges,
      out.components,
      p,
      a.diameterSampleSources,
      a.diameterSeed,
      a.diameterMinComponentSize,
      a.distanceDepthBound
    )

    // 9. temporal
    val snaps = Temporal.snapshots(metrics, communities, bridges, coreness)
    val changes = Temporal.changes(snaps, p)

    // 10. semantic-input facts
    val facts = SemanticFacts.build(
      SemanticFacts.Inputs(
        researchers = out.researchers,
        works = out.works,
        venues = out.venues,
        topics = out.topics,
        institutions = out.institutions,
        hasTopic = out.hasTopic,
        researcherPapers = out.researcherPapers,
        researcherInstitutions = out.researcherInstitutions,
        metrics = metrics,
        communities = communities,
        bridges = bridges,
        coreness = coreness,
        edges = out.edges,
        paths = paths
      ),
      p,
      SemanticFacts.Limits(a.topEntityLimit, a.memberLimit, p.representativeWorkLimit)
    )

    AnalyticsOutputs(
      metrics = metrics,
      communities = communities,
      crossEdges = comm.crossEdges,
      bridges = bridges,
      coreness = coreness,
      paths = paths,
      semanticFacts = facts,
      windowChanges = changes,
      communityRun = comm.groups,
      kcore = kc,
      queries = qs,
      pathRun = pathRun,
      distances = distances,
      lpaPlan = comm.firstIterationPlan
    )
