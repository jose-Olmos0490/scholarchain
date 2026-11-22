package scholarchain.spark.graph

import org.apache.spark.sql.functions.{coalesce, col, count, least, lit, min}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.storage.StorageLevel

/** Connected components by iterative min-label propagation, as DataFrame joins (no GraphX).
  *
  * Definition: every node starts with its own ID as label. In each iteration every node takes the
  * minimum of its label and its neighbours' labels. At a fixed point every node holds the smallest
  * node ID of its connected component, so `componentId` = the smallest member ID (ties cannot
  * occur: IDs are distinct strings compared ordinally).
  *
  * Several independent graphs are processed together, one per value of `groupCols` (the analysis
  * window): edges and labels are joined on `groupCols :+ node`, so windows never mix.
  *
  * Per iteration (shuffle boundaries): one join of the directed edge list with the labels on
  * (group, node) and one `groupBy(group, node).min` of the messages, then a join back to the
  * previous labels. The labels of each iteration are persisted and re-wrapped as a leaf relation
  * (so the logical plan does not grow); every `checkpointInterval` iterations they are reliably
  * checkpointed to the configured directory, which also truncates the RDD lineage. The per-group count of changed labels is the only value collected to the driver
  * (bounded by the number of groups, i.e. configured windows).
  *
  * Convergence: a group has converged at the first iteration in which none of its labels changed;
  * the loop stops when every group has converged or `maxIterations` is reached. A group that never
  * had an unchanged iteration is reported `converged = false` and its labels are the partial
  * result after `maxIterations` (a lower bound on merging, never a wrong merge).
  */
object ConnectedComponents:

  /** Iteration outcome of one group.
    *
    * @param group
    *   the group-column values, in `groupCols` order
    */
  final case class GroupIterations(
      group: Vector[Any],
      iterations: Int,
      converged: Boolean
  )

  final case class Result(
      labels: DataFrame,
      groups: Vector[GroupIterations],
      iterationsRun: Int,
      firstIterationPlan: DataFrame
  )

  /** Runs min-label propagation.
    *
    * @param nodes
    *   `groupCols :+ "node"` (distinct)
    * @param edges
    *   `groupCols :+ "src" :+ "dst"` (undirected; either orientation)
    * @param groups
    *   every group that must be reported (also groups with no node)
    * @param checkpoint
    *   whether a reliable checkpoint directory is configured on the SparkContext
    * @return
    *   labels with columns `groupCols :+ "node" :+ "label"`
    */
  def run(
      nodes: DataFrame,
      edges: DataFrame,
      groupCols: Seq[String],
      groups: Vector[Vector[Any]],
      maxIterations: Int,
      checkpointInterval: Int,
      checkpoint: Boolean
  ): Result =
    require(maxIterations >= 1, "maxIterations must be >= 1")
    require(checkpointInterval >= 1, "checkpointInterval must be >= 1")
    val g = groupCols.map(col)
    val spark = nodes.sparkSession
    val directed = edges
      .select((g :+ col("src").as("a") :+ col("dst").as("b"))*)
      .union(edges.select((g :+ col("dst").as("a") :+ col("src").as("b"))*))
      .where(col("a") =!= col("b"))
      .distinct()
      .persist(StorageLevel.MEMORY_AND_DISK)

    var labels: DataFrame = nodes
      .select((g :+ col("node") :+ col("node").as("label"))*)
      .persist(StorageLevel.MEMORY_AND_DISK)
    // The cached frame behind `labels`, released once the next iteration is materialized.
    var labelsCache: DataFrame = labels

    val convergedAt = scala.collection.mutable.Map.empty[Vector[Any], Int]
    var firstPlan: DataFrame = null
    var i = 0
    var done = false
    while !done && i < maxIterations do
      i += 1
      val msgs = directed
        .join(labels.withColumnRenamed("node", "a"), groupCols :+ "a")
        .groupBy((g :+ col("b").as("node"))*)
        .agg(min(col("label")).as("msg"))
      val next = labels
        .join(msgs, groupCols :+ "node", "left")
        .select(
          (g :+ col("node") :+
            least(col("label"), coalesce(col("msg"), col("label"))).as("label") :+
            (coalesce(col("msg"), col("label")) < col("label")).as("changed"))*
        )
      if firstPlan == null then firstPlan = next
      val persisted = next.persist(StorageLevel.MEMORY_AND_DISK)
      // Bounded collect: one row per group (configured windows) with a changed-label count.
      val changedByGroup: Map[Vector[Any], Long] = persisted
        .where(col("changed"))
        .groupBy(g*)
        .agg(count(lit(1)).as("n"))
        .collect()
        .map((r: Row) => (groupCols.indices.map(r.get).toVector, r.getLong(groupCols.size)))
        .toMap
      groups.foreach { k =>
        if !convergedAt.contains(k) && changedByGroup.getOrElse(k, 0L) == 0L then convergedAt(k) = i
      }
      val dropped = persisted.drop("changed")
      // Lineage control. Each iteration's plan refers to the previous labels twice, so without
      // truncation the logical plan doubles every iteration. Every `checkpointInterval`
      // iterations the labels are reliably checkpointed (truncating plan and RDD lineage);
      // otherwise the cached labels are re-wrapped as a leaf relation over their RDD, which
      // truncates the logical plan while the RDD lineage (a DAG over the cached blocks) remains.
      val nextLabels =
        if checkpoint && i % checkpointInterval == 0 then dropped.checkpoint(eager = true)
        else spark.createDataFrame(dropped.rdd, dropped.schema)
      labelsCache.unpersist(blocking = false)
      labelsCache = persisted
      labels = nextLabels
      done = changedByGroup.values.forall(_ == 0L)

    directed.unpersist(blocking = false)
    val reports = groups.map { k =>
      convergedAt.get(k) match
        case Some(at) => GroupIterations(k, at, converged = true)
        case None => GroupIterations(k, i, converged = false)
    }
    Result(labels, reports, i, firstPlan)
