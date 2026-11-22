package scholarchain.spark.graph

import org.apache.spark.sql.functions.{col, count, lit}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.storage.StorageLevel
import scholarchain.spark.Windows

/** Shared plumbing of the PART B iterative DataFrame algorithms (label propagation, k-core
  * peeling, BFS frontiers). Every algorithm processes all analysis windows together; rows are
  * keyed by `(windowStartYear, windowEndYear, ...)`, so windows never mix.
  */
object Iterative:
  type Key = (Int, Int)

  /** The configured windows as group keys (in configuration order). */
  def keys(groups: Vector[Vector[Any]]): Vector[Key] =
    groups.map(g => (g(0).asInstanceOf[Int], g(1).asInstanceOf[Int]))

  /** Lineage control for iteration `i`: every `interval` iterations a reliable checkpoint (truncates
    * plan and RDD lineage); otherwise the materialized frame is re-wrapped as a leaf relation over
    * its RDD, which keeps the logical plan from growing with the iteration count.
    */
  def wrap(df: DataFrame, i: Int, interval: Int, checkpoint: Boolean): DataFrame =
    if checkpoint && i % interval == 0 then df.checkpoint(eager = true)
    else df.sparkSession.createDataFrame(df.rdd, df.schema)

  /** Both orientations of every windowed coauthor edge: `(window, a, b)`, distinct, no self-loop.
    */
  def directed(edges: DataFrame): DataFrame =
    val k = Windows.keyCols
    edges
      .select((k :+ col("researcherA").as("a") :+ col("researcherB").as("b"))*)
      .union(edges.select((k :+ col("researcherB").as("a") :+ col("researcherA").as("b"))*))
      .where(col("a") =!= col("b"))
      .distinct()

  /** Outcome of a fixed-point loop. */
  final case class Outcome(
      state: DataFrame,
      groups: Vector[ConnectedComponents.GroupIterations],
      iterationsRun: Int,
      firstIterationPlan: DataFrame
  )

  /** Iterates `step` from `initial` until no window changes or `maxIterations` is reached.
    *
    * `step(state)` must return the next state's columns plus a boolean `changed` column. A window
    * has converged at the first iteration in which none of its rows changed (that count includes
    * the confirming round). The per-window changed count is the only collected value (bounded by
    * the number of configured windows).
    */
  def untilStable(
      initial: DataFrame,
      groups: Vector[Vector[Any]],
      maxIterations: Int,
      interval: Int,
      checkpoint: Boolean
  )(step: DataFrame => DataFrame): Outcome =
    require(maxIterations >= 1, "maxIterations must be >= 1")
    require(interval >= 1, "checkpointInterval must be >= 1")
    val k = Windows.keyCols
    var state = initial.persist(StorageLevel.MEMORY_AND_DISK)
    var cache: DataFrame = state
    val convergedAt = scala.collection.mutable.Map.empty[Vector[Any], Int]
    var firstPlan: DataFrame = null
    var i = 0
    var done = false
    while !done && i < maxIterations do
      i += 1
      val next = step(state)
      if firstPlan == null then firstPlan = next
      val persisted = next.persist(StorageLevel.MEMORY_AND_DISK)
      // Bounded collect: one row per configured window.
      val changed: Map[Vector[Any], Long] = persisted
        .where(col("changed"))
        .groupBy(k*)
        .agg(count(lit(1)).as("n"))
        .collect()
        .map((r: Row) => (Vector[Any](r.getInt(0), r.getInt(1)), r.getLong(2)))
        .toMap
      groups.foreach { g =>
        if !convergedAt.contains(g) && changed.getOrElse(g, 0L) == 0L then convergedAt(g) = i
      }
      val nextState = wrap(persisted.drop("changed"), i, interval, checkpoint)
      cache.unpersist(blocking = false)
      cache = persisted
      state = nextState
      done = changed.values.forall(_ == 0L)
    val reports = groups.map { g =>
      convergedAt.get(g) match
        case Some(at) => ConnectedComponents.GroupIterations(g, at, converged = true)
        case None => ConnectedComponents.GroupIterations(g, i, converged = false)
    }
    Outcome(state, reports, i, firstPlan)
