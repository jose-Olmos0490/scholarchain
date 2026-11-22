package scholarchain.spark.graph

import java.util.Arrays
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{Schemas, Versions, Windows}

/** k-core decomposition by iterative peeling (`sc-kcore-peeling-v1`), as DataFrame joins.
  *
  * coreness(r) = the largest k such that r belongs to the k-core (the maximal subgraph in which
  * every node has degree >= k). Unweighted, simple graph of the window.
  *
  * Each round, per window, with level `k_w` (starts at 0):
  *   1. degree of every alive node counted over edges whose both endpoints are alive
  *   2. `kEff = max(k_w, min alive degree)` (jumping over empty levels is exact: when no alive
  *      node has degree <= k_w, the alive set is already a (min degree)-core)
  *   3. every alive node with degree <= kEff is removed with coreness kEff; `k_w := kEff`
  *
  * Every round removes at least one node of each non-empty window. A window has converged when
  * no node is alive. At `graph.kcore.maxIterations` the still-alive nodes get NO coreness row
  * (counted as unpeeled; never a guessed value) and the window reports `converged = false`.
  *
  * Shuffles per round: two joins of the directed edges with the alive set, `groupBy(window, a)`
  * for degrees, `groupBy(window)` for the minimum degree, and a join back into the state.
  */
object KCore:
  val Algorithm = "k-core-peeling"

  final case class Result(
      coreness: DataFrame,
      groups: Vector[ConnectedComponents.GroupIterations],
      unpeeled: Map[Iterative.Key, Long],
      firstRoundPlan: DataFrame
  )

  private val LevelSchema = StructType(
    Seq(
      StructField(Windows.Start, IntegerType, nullable = false),
      StructField(Windows.End, IntegerType, nullable = false),
      StructField("level", IntegerType, nullable = false)
    )
  )

  /** One peeling round: `state` is `(window, node, degree, coreness)`, alive when coreness is null.
    * Returns the next state plus `kEff`, `removedNow` for the per-window bookkeeping.
    */
  def round(
      spark: SparkSession,
      directed: DataFrame,
      state: DataFrame,
      levels: Map[Iterative.Key, Int]
  ): DataFrame =
    val k = Windows.keyCols
    val alive = state.where(col("coreness").isNull).select((k :+ col("node"))*)
    val aliveDeg = directed
      .join(alive.withColumnRenamed("node", "a"), Windows.Keys :+ "a")
      .join(alive.withColumnRenamed("node", "b"), Windows.Keys :+ "b")
      .groupBy((k :+ col("a").as("node"))*)
      .agg(count(lit(1)).cast(IntegerType).as("aliveDegree"))
    val deg = alive
      .join(aliveDeg, Windows.Keys :+ "node", "left")
      .withColumn("aliveDegree", coalesce(col("aliveDegree"), lit(0)))
    val minDeg = deg.groupBy(k*).agg(min("aliveDegree").as("minDegree"))
    // Bounded: one row per configured window.
    val levelRows = levels.toVector.map((w, l) => Row(w._1, w._2, l))
    val levelFrame = broadcast(spark.createDataFrame(Arrays.asList(levelRows*), LevelSchema))
    val removed = deg
      .join(minDeg, Windows.Keys)
      .join(levelFrame, Windows.Keys)
      .withColumn("kEff", greatest(col("level"), col("minDegree")))
      .where(col("aliveDegree") <= col("kEff"))
      .select((k :+ col("node") :+ col("kEff"))*)
    state
      .join(removed, Windows.Keys :+ "node", "left")
      .select(
        (k :+ col("node") :+ col("degree") :+
          coalesce(col("coreness"), col("kEff")).as("coreness") :+
          (col("coreness").isNull && col("kEff").isNotNull).as("removedNow"))*
      )

  def run(
      spark: SparkSession,
      nodes: DataFrame,
      edges: DataFrame,
      p: BuildParams,
      maxIterations: Int
  ): Result =
    require(maxIterations >= 1, "graph.kcore.maxIterations must be >= 1")
    val k = Windows.keyCols
    val directed = Iterative.directed(edges).persist(StorageLevel.MEMORY_AND_DISK)
    val fullDegree =
      directed.groupBy((k :+ col("a").as("researcherId"))*).agg(count(lit(1)).as("d"))
    val windows = Iterative.keys(p.windows.map(w => Vector[Any](w.startYear, w.endYear)))
    var state: DataFrame = nodes
      .join(fullDegree, Windows.Keys :+ "researcherId", "left")
      .select(
        (k :+ col("researcherId").as("node") :+ coalesce(col("d"), lit(0L)).as("degree") :+
          lit(null).cast(IntegerType).as("coreness"))*
      )
      .persist(StorageLevel.MEMORY_AND_DISK)
    var cache = state
    var levels: Map[Iterative.Key, Int] = windows.map(_ -> 0).toMap
    val convergedAt = scala.collection.mutable.Map.empty[Iterative.Key, Int]
    var firstPlan: DataFrame = null
    var lastAlive: Map[Iterative.Key, Long] = Map.empty
    var i = 0
    var done = false
    while !done && i < maxIterations do
      i += 1
      val next = round(spark, directed, state, levels)
      if firstPlan == null then firstPlan = next
      val persisted = next.persist(StorageLevel.MEMORY_AND_DISK)
      // Bounded collect: one row per configured window (new level, alive count after the round).
      val stats = persisted
        .groupBy(k*)
        .agg(
          max(when(col("removedNow"), col("coreness"))).as("level"),
          sum(when(col("coreness").isNull, lit(1L)).otherwise(lit(0L))).as("alive")
        )
        .collect()
        .map(r =>
          (r.getInt(0), r.getInt(1)) -> (Option(r.get(2)).map(_.asInstanceOf[Int]), r.getLong(3))
        )
        .toMap
      levels = windows.map { w =>
        w -> stats.get(w).flatMap(_._1).fold(levels(w))(l => math.max(l, levels(w)))
      }.toMap
      lastAlive = windows.map(w => w -> stats.get(w).fold(0L)(_._2)).toMap
      windows.foreach(w =>
        if !convergedAt.contains(w) && lastAlive(w) == 0L then convergedAt(w) = i
      )
      val nextState =
        Iterative.wrap(persisted.drop("removedNow"), i, p.checkpointInterval, p.checkpoint)
      cache.unpersist(blocking = false)
      cache = persisted
      state = nextState
      done = lastAlive.values.forall(_ == 0L)
    directed.unpersist(blocking = false)
    val coreness = state
      .where(col("coreness").isNotNull)
      .select(
        lit(SchemaVersions.CorenessRecord).as("schemaVersion"),
        col("node").as("researcherId"),
        col(Windows.Start),
        col(Windows.End),
        col("coreness"),
        col("degree"),
        lit(Versions.KCore).as("metricVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("node"),
            p.snapshot,
            p.runId,
            Some(Versions.KCore),
            Schemas.emptyStrings
          )
          .as("provenance")
      )
    val reports = windows.map { w =>
      val g = Vector[Any](w._1, w._2)
      convergedAt.get(w) match
        case Some(at) => ConnectedComponents.GroupIterations(g, at, converged = true)
        case None => ConnectedComponents.GroupIterations(g, i, converged = false)
    }
    Result(coreness, reports, lastAlive.filter(_._2 > 0), firstPlan)
