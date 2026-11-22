package scholarchain.spark

import java.nio.file.Files
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import scholarchain.core.config.BenchmarkPair
import scholarchain.spark.graph.{
  Analytics,
  AnalyticsOutputs,
  AnalyticsParams,
  BuildParams,
  GraphBuilder,
  GraphOutputs
}
import scholarchain.spark.run.GraphPipeline

/** One local SparkSession per forked test JVM (tests run serially), with the job's SQL settings,
  * 4 shuffle partitions and a temporary reliable checkpoint directory.
  */
object SparkTestSupport:
  lazy val spark: SparkSession =
    val s = GraphPipeline
      .configure(
        SparkSession
          .builder()
          .master("local[2]")
          .appName("scholarchain-spark-tests")
          .config("spark.ui.enabled", "false"),
        4
      )
      .getOrCreate()
    s.sparkContext.setLogLevel("ERROR")
    s.sparkContext.setCheckpointDir(Files.createTempDirectory("sc-spark-ckpt").toString)
    s

  val RunId: String = "run:" + "3" * 64

  def params(
      windows: Vector[AnalysisWindow] = Vector(Fixture.AllYears, Fixture.W2010to2015),
      maxClusterSize: Int = 50,
      maxIterations: Int = 30,
      k: Int = 2
  ): BuildParams =
    BuildParams(
      runId = RunId,
      snapshot = Fixture.Snapshot,
      windows = windows,
      scope = AnalysisWindow(2000, 2025, allYears = false),
      coauthorPolicyVersion = "coauthor-policy-v1",
      representativeWorkLimit = k,
      identityVersion = Fixture.IdentityVersion,
      maxClusterSize = maxClusterSize,
      componentsMaxIterations = maxIterations,
      checkpointInterval = 2,
      checkpoint = true
    )

  def build(t: Fixture.Tables, p: BuildParams = params()): GraphOutputs =
    GraphBuilder.build(spark, Fixture.inputs(spark, t), p)

  def analyticsParams(
      benchmark: Vector[BenchmarkPair] = Vector.empty,
      maxDepth: Int = 6,
      samplePairCount: Int = 3,
      diameterSources: Int = 6,
      communityMaxIterations: Int = 20,
      kcoreMaxIterations: Int = 30
  ): AnalyticsParams =
    AnalyticsParams(
      communityMaxIterations = communityMaxIterations,
      kcoreMaxIterations = kcoreMaxIterations,
      pathMaxDepth = maxDepth,
      benchmarkPairs = benchmark,
      samplePairCount = samplePairCount,
      pathSeed = 17L,
      diameterSampleSources = diameterSources,
      diameterSeed = 19L,
      diameterMinComponentSize = 2,
      distanceDepthBound = 30,
      topEntityLimit = 3,
      memberLimit = 4
    )

  def analytics(
      out: GraphOutputs,
      a: AnalyticsParams = analyticsParams(),
      p: BuildParams = params()
  ): AnalyticsOutputs = Analytics.build(spark, out, p, a)

  /** Rows of one window (bounded fixture data; tests only). */
  def inWindow(df: DataFrame, w: AnalysisWindow): Vector[Row] =
    df.where(col(Windows.Start) === w.startYear && col(Windows.End) === w.endYear)
      .collect()
      .toVector

  /** Number of rows violating their core contract. */
  def violations(df: DataFrame, kind: CoreRecords.Kind): Vector[String] =
    CoreRecords
      .withViolations(df, kind)
      .where(col("contractViolation").isNotNull)
      .select("contractViolation")
      .collect()
      .map(_.getString(0))
      .toVector
