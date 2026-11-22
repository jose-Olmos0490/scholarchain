package scholarchain.spark

import scholarchain.core.config.{
  CommunityConfig,
  ComponentsConfig,
  ConfigCursor,
  ConfigSection,
  DatasetConfig,
  DiameterConfig,
  GraphConfig,
  IdentityConfig,
  KCoreConfig,
  PathConfig,
  SemanticFactsConfig,
  SparkJobConfig,
  YearWindow
}

/** The configuration the graph job (PART A and PART B) reads, loaded through the core loader
  * (`ConfigLoader.loadSection`). Only these sections are read, so the job is not blocked by
  * unrelated keys (bucket names, model IDs).
  *
  * @param subsetYears
  *   `datasets.dblpSubset.startYear/endYear`; the scope window recorded on non-windowed node and
  *   edge tables (it is also the all-years window when that is enabled)
  */
final case class SparkAnalyticsSettings(
    subsetYears: YearWindow,
    graph: GraphConfig,
    spark: SparkJobConfig,
    identity: IdentityConfig,
    components: ComponentsConfig,
    community: CommunityConfig,
    kcore: KCoreConfig,
    paths: PathConfig,
    diameter: DiameterConfig,
    semanticFacts: SemanticFactsConfig
):
  /** The configured analysis windows, all-years window first. */
  def windows: Vector[AnalysisWindow] =
    graph.analysisWindows.map(w =>
      AnalysisWindow(w.startYear, w.endYear, graph.isAllYearsWindow(w))
    )

  /** Run parameters that define the logical output (part of the run ID). Locations and execution
    * settings that do not change the logical result are recorded in the run summary instead.
    */
  def logicalParameters: Map[String, String] = Map(
    "datasets.dblpSubset.startYear" -> subsetYears.startYear.toString,
    "datasets.dblpSubset.endYear" -> subsetYears.endYear.toString,
    "graph.windows" -> graph.windows.map(_.render).mkString(","),
    "graph.includeAllYearsWindow" -> graph.allYearsWindow.isDefined.toString,
    "graph.coauthorPolicyVersion" -> graph.coauthorPolicyVersion,
    "graph.representativeWorkLimit" -> graph.representativeWorkLimit.toString,
    "graph.identity.version" -> identity.version,
    "graph.identity.maxClusterSize" -> identity.maxClusterSize.toString,
    "graph.components.maxIterations" -> components.maxIterations.toString,
    "community.algorithm" -> community.algorithm,
    "community.maxIterations" -> community.maxIterations.toString,
    "graph.kcore.maxIterations" -> kcore.maxIterations.toString,
    "graph.paths.maxDepth" -> paths.maxDepth.toString,
    "graph.paths.benchmarkPairs" -> paths.benchmarkPairs
      .map(b => s"${b.sourceResearcherId}|${b.targetResearcherId}")
      .mkString(","),
    "graph.paths.samplePairCount" -> paths.samplePairCount.toString,
    "graph.paths.seed" -> paths.seed.toString,
    "graph.diameter.sampleSources" -> diameter.sampleSources.toString,
    "graph.diameter.seed" -> diameter.seed.toString,
    "graph.diameter.minComponentSize" -> diameter.minComponentSize.toString,
    "graph.semanticFacts.topEntityLimit" -> semanticFacts.topEntityLimit.toString,
    "graph.semanticFacts.memberLimit" -> semanticFacts.memberLimit.toString,
    "spark.hadoopRunId" -> spark.hadoopRunId.getOrElse("")
  )

  /** Execution settings (recorded in the run summary, not in the run ID). */
  def executionParameters: Map[String, String] = Map(
    "spark.hadoopInputRoot" -> spark.hadoopInputRoot,
    "spark.matchDecisionsDir" -> spark.matchDecisionsDir,
    "spark.outputRoot" -> spark.outputRoot,
    "spark.shufflePartitions" -> spark.shufflePartitions.toString,
    "spark.checkpointDir" -> spark.checkpointDir,
    "spark.checkpointInterval" -> spark.checkpointInterval.toString
  )

object SparkAnalyticsSettings extends ConfigSection[SparkAnalyticsSettings]:
  def read(c: ConfigCursor): SparkAnalyticsSettings =
    val years = DatasetConfig.readSubsetYears(c)
    val graph = GraphConfig.read(c)
    val spark = SparkJobConfig.read(c)
    val identity = IdentityConfig.read(c)
    val components = ComponentsConfig.read(c)
    val community = CommunityConfig.read(c)
    if community.algorithm != null && community.algorithm.nonEmpty &&
      community.algorithm != Versions.CommunityAlgorithmName
    then
      c.fail(
        "community.algorithm",
        s"'${community.algorithm}' is not supported; the only implemented algorithm is " +
          s"'${Versions.CommunityAlgorithmName}'"
      )
    val kcore = KCoreConfig.read(c)
    val paths = PathConfig.read(c)
    val diameter = DiameterConfig.read(c)
    val semanticFacts = SemanticFactsConfig.read(c)
    // A missing or invalid subset window is already recorded by readSubsetYears, so the section
    // fails; the placeholder below never reaches a caller.
    SparkAnalyticsSettings(
      years.getOrElse(YearWindow.of(0, 0).toOption.get),
      graph,
      spark,
      identity,
      components,
      community,
      kcore,
      paths,
      diameter,
      semanticFacts
    )

/** One analysis window. `allYears` marks the all-years window (the only one with year-less works).
  */
final case class AnalysisWindow(startYear: Int, endYear: Int, allYears: Boolean):
  def label: String = s"$startYear-$endYear"
