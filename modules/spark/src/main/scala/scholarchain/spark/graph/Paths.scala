package scholarchain.spark.graph

import java.util.Arrays
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import scholarchain.core.config.BenchmarkPair
import scholarchain.core.ids.StableIds
import scholarchain.core.model.PathSelection
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{Schemas, Versions, Windows}

/** Shortest paths with hop evidence (`sc-shortest-path-bfs-lexmin-v1`) and the sampled distance
  * estimate (`sc-distance-estimate-sampled-bfs-v1`). Definitions and limitations:
  * modules/spark/JOBS.md.
  *
  * BFS is a loop of frontier joins over the directed edge list, all queries of all windows at
  * once, keyed by `(window, queryId)`. Per level (shuffle boundaries): join of the frontier with
  * the directed edges on `(window, node)`, `distinct`, and an anti-join with the visited set on
  * `(window, queryId, node)`. The visited set and the frontier are persisted and re-wrapped (or
  * checkpointed every `spark.checkpointInterval` levels). The only collected value per level is
  * the frontier size (one number).
  *
  * Path choice. BFS runs from the TARGET of each query, so every reached node knows its distance
  * to the target. The path is then rebuilt forward from the source: at each step the smallest
  * neighbour (ordinal researcher-ID order) whose distance to the target is one less. That yields
  * the lexicographically smallest node sequence among all shortest paths (each step takes the
  * smallest feasible next node, and every feasible prefix extends to a shortest path).
  */
object Paths:
  val Algorithm = "bfs-frontier"

  /** One path query (bounded: configured benchmark pairs, or samplePairCount per window). */
  final case class Query(
      windowStartYear: Int,
      windowEndYear: Int,
      pathId: String,
      source: String,
      target: String,
      selection: PathSelection
  )

  final case class Bfs(
      visited: DataFrame,
      levels: Int,
      exhausted: Boolean,
      firstStepPlan: DataFrame
  )

  /** The sampled distance estimate of one window's largest component. An ESTIMATE when
    * `sources.size < componentSize`.
    */
  final case class DistanceEstimate(
      windowStartYear: Int,
      windowEndYear: Int,
      componentId: String,
      componentSize: Long,
      sampleSources: Int,
      seed: Long,
      sources: Vector[String],
      reachedPairs: Long,
      meanDistance: Double,
      effectiveDiameterP90: Int,
      maxObservedDistance: Int,
      distanceHistogram: Vector[(Int, Long)],
      bfsLevels: Int,
      bfsExhausted: Boolean,
      exactAllSources: Boolean
  )

  private val QuerySchema = StructType(
    Seq(
      StructField(Windows.Start, IntegerType, nullable = false),
      StructField(Windows.End, IntegerType, nullable = false),
      StructField("qid", StringType, nullable = false),
      StructField("source", StringType, nullable = false),
      StructField("target", StringType, nullable = false),
      StructField("selection", StringType, nullable = false)
    )
  )

  def queryFrame(spark: SparkSession, qs: Vector[Query]): DataFrame =
    val rows = qs.map(q =>
      Row(q.windowStartYear, q.windowEndYear, q.pathId, q.source, q.target, q.selection.toString)
    )
    spark.createDataFrame(Arrays.asList(rows*), QuerySchema)

  /** The largest component of each window (ties: smallest componentId). Bounded collect: one row
    * per configured window.
    */
  def largestComponents(components: DataFrame): Map[Iterative.Key, (String, Long)] =
    val k = Windows.keyCols
    val sizes = components.select((k :+ col("componentId") :+ col("componentSize"))*).distinct()
    val maxSize = sizes.groupBy(k*).agg(max("componentSize").as("componentSize"))
    sizes
      .join(maxSize, Windows.Keys :+ "componentSize")
      .groupBy((k :+ col("componentSize"))*)
      .agg(min("componentId").as("componentId"))
      .collect()
      .map(r => (r.getInt(0), r.getInt(1)) -> (r.getString(3), r.getLong(2)))
      .toMap

  /** The `n` members of one component with the smallest `xxhash64(seed, researcherId)` (ties:
    * researcher ID). Bounded collect: at most `n` rows. Deterministic for a given seed.
    */
  def seededMembers(
      components: DataFrame,
      w: Iterative.Key,
      componentId: String,
      seed: Long,
      n: Int
  ): Vector[String] =
    components
      .where(
        col(Windows.Start) === w._1 && col(Windows.End) === w._2 &&
          col("componentId") === componentId
      )
      .select(col("researcherId"), xxhash64(lit(seed), col("researcherId")).as("h"))
      .orderBy(col("h"), col("researcherId"))
      .limit(n)
      .collect()
      .map(_.getString(0))
      .toVector

  /** `count` distinct unordered pairs from the seeded member list: disjoint pairs (m0,m1),
    * (m2,m3), ... first, then the remaining index pairs in lexicographic order.
    */
  def pairsFrom(members: Vector[String], count: Int): Vector[(String, String)] =
    val idx = members.indices
    val disjoint = idx.grouped(2).collect { case Seq(a, b) => (a, b) }.toVector
    val rest = for i <- idx; j <- (i + 1) until members.size if !disjoint.contains((i, j))
    yield (i, j)
    (disjoint ++ rest).take(count).map((i, j) => (members(i), members(j)))

  /** The path queries: every configured benchmark pair in every window, or, when none is
    * configured, `samplePairCount` seeded pairs from each window's largest component.
    */
  def queries(
      components: DataFrame,
      p: BuildParams,
      benchmark: Vector[BenchmarkPair],
      samplePairCount: Int,
      seed: Long
  ): Vector[Query] =
    def q(w: Iterative.Key, s: String, t: String, sel: PathSelection) =
      val id = StableIds
        .pathId(s, t, w._1, w._2, p.coauthorPolicyVersion, p.runId)
        .fold(e => sys.error(s"path ID: $e"), identity)
      Query(w._1, w._2, id, s, t, sel)
    val windows = p.windows.map(w => (w.startYear, w.endYear))
    if benchmark.nonEmpty then
      for w <- windows; b <- benchmark
      yield q(w, b.sourceResearcherId, b.targetResearcherId, PathSelection.BENCHMARK)
    else
      val largest = largestComponents(components)
      windows.flatMap { w =>
        largest.get(w).toVector.flatMap { (cid, size) =>
          if size < 2 then Vector.empty
          else
            val members = seededMembers(components, w, cid, seed, 2 * samplePairCount)
            pairsFrom(members, samplePairCount).map((s, t) => q(w, s, t, PathSelection.SAMPLED))
        }
      }

  /** Multi-root BFS. `roots` is `(window, qid, node)`; `stop`, when given, is
    * `(window, qid, stopNode)`: a query leaves the frontier once its stop node is reached.
    */
  def bfs(
      roots: DataFrame,
      directed: DataFrame,
      maxDepth: Int,
      stop: Option[DataFrame],
      interval: Int,
      checkpoint: Boolean
  ): Bfs =
    val k = Windows.keyCols
    val kq = Windows.Keys :+ "qid"
    var visited = roots
      .select((k :+ col("qid") :+ col("node") :+ lit(0).as("dist"))*)
      .persist(StorageLevel.MEMORY_AND_DISK)
    var frontier =
      roots.select((k :+ col("qid") :+ col("node"))*).persist(StorageLevel.MEMORY_AND_DISK)
    var size = frontier.count()
    var firstPlan: DataFrame = null
    var d = 0
    while size > 0 && d < maxDepth do
      d += 1
      val next = frontier
        .withColumnRenamed("node", "a")
        .join(directed, Windows.Keys :+ "a")
        .select((k :+ col("qid") :+ col("b").as("node"))*)
        .distinct()
        .join(visited.select((k :+ col("qid") :+ col("node"))*), kq :+ "node", "left_anti")
      if firstPlan == null then firstPlan = next
      val nextP = next.persist(StorageLevel.MEMORY_AND_DISK)
      val visitedNext = visited
        .union(nextP.withColumn("dist", lit(d)))
        .persist(StorageLevel.MEMORY_AND_DISK)
      visitedNext.count()
      val nextFrontier = stop match
        case None => nextP
        case Some(s) =>
          val reached = nextP
            .join(broadcast(s), kq)
            .where(col("node") === col("stopNode"))
            .select((k :+ col("qid"))*)
            .distinct()
          nextP.join(reached, kq, "left_anti")
      val frontierP = nextFrontier.persist(StorageLevel.MEMORY_AND_DISK)
      size = frontierP.count()
      val oldVisited = visited
      val oldFrontier = frontier
      visited = Iterative.wrap(visitedNext, d, interval, checkpoint)
      frontier = Iterative.wrap(frontierP, d, interval, checkpoint)
      oldVisited.unpersist(blocking = false)
      oldFrontier.unpersist(blocking = false)
    Bfs(visited, d, size == 0, firstPlan)

  final case class PathResult(evidence: DataFrame, bfs: Bfs, walkSteps: Int)

  /** Path evidence (core `PathEvidence` columns) for `qs`. */
  def shortestPaths(
      spark: SparkSession,
      qs: Vector[Query],
      edges: DataFrame,
      edgeSupport: DataFrame,
      components: DataFrame,
      p: BuildParams,
      maxDepth: Int
  ): PathResult =
    val k = Windows.keyCols
    val kq = Windows.Keys :+ "qid"
    val directed = Iterative.directed(edges).persist(StorageLevel.MEMORY_AND_DISK)
    val queries = queryFrame(spark, qs).cache()
    val roots = queries.select((k :+ col("qid") :+ col("target").as("node"))*)
    val stop = queries.select((k :+ col("qid") :+ col("source").as("stopNode"))*)
    val b = bfs(roots, directed, maxDepth, Some(stop), p.checkpointInterval, p.checkpoint)
    val dist = b.visited
    val found = dist
      .join(queries.select((k :+ col("qid") :+ col("source"))*), kq)
      .where(col("node") === col("source"))
      .select((k :+ col("qid") :+ col("dist").as("length"))*)

    // Forward walk: one row per unfinished query; each step picks the smallest neighbour that is
    // one step closer to the target. At most maxDepth steps; one count collected per step.
    var cur: DataFrame = found
      .join(queries.select((k :+ col("qid") :+ col("source"))*), kq)
      .select((k :+ col("qid") :+ col("source").as("node") :+ col("length").as("remaining"))*)
    val hopFrames = Vector.newBuilder[DataFrame]
    var stepNo = 0
    var open = cur.where(col("remaining") > 0).count()
    while open > 0 && stepNo < maxDepth do
      stepNo += 1
      val nxt = cur
        .where(col("remaining") > 0)
        .withColumnRenamed("node", "a")
        .join(directed, Windows.Keys :+ "a")
        .join(
          dist.withColumnRenamed("node", "b").withColumnRenamed("dist", "bDist"),
          kq :+ "b"
        )
        .where(col("bDist") === col("remaining") - 1)
        .groupBy((k :+ col("qid") :+ col("a") :+ col("remaining"))*)
        .agg(min("b").as("b"))
        .persist(StorageLevel.MEMORY_AND_DISK)
      val wrapped = Iterative.wrap(nxt, stepNo, p.checkpointInterval, p.checkpoint)
      hopFrames += wrapped.select(
        (k :+ col("qid") :+ lit(stepNo).as("step") :+ col("a").as("from") :+ col("b").as("to"))*
      )
      cur = wrapped.select(
        (k :+ col("qid") :+ col("b").as("node") :+ (col("remaining") - 1).as("remaining"))*
      )
      open = cur.where(col("remaining") > 0).count()

    val hopList = hopFrames.result()
    val hopSchema = StructType(
      Seq(
        StructField(Windows.Start, IntegerType, nullable = false),
        StructField(Windows.End, IntegerType, nullable = false),
        StructField("qid", StringType, nullable = false),
        StructField("step", IntegerType, nullable = false),
        StructField("from", StringType, nullable = false),
        StructField("to", StringType, nullable = false)
      )
    )
    val hops =
      if hopList.isEmpty then spark.createDataFrame(Arrays.asList[Row](), hopSchema)
      else hopList.reduce(_.union(_))
    val hopEdges = hops
      .withColumn("researcherA", least(col("from"), col("to")))
      .withColumn("researcherB", greatest(col("from"), col("to")))
      .join(
        edges.select(
          (k :+ col("researcherA") :+ col("researcherB") :+ col("edgeId") :+
            col("jointPublicationCount"))*
        ),
        Windows.Keys ++ Seq("researcherA", "researcherB")
      )
    val support = edgeSupport
      .join(
        hopEdges.select((k :+ col("edgeId"))*).distinct(),
        Windows.Keys :+ "edgeId",
        "left_semi"
      )
      .groupBy((k :+ col("edgeId"))*)
      .agg(array_sort(collect_list("workId")).as("supportingWorkIds"))
    val hopRows = hopEdges
      .join(support, Windows.Keys :+ "edgeId")
      .groupBy((k :+ col("qid"))*)
      .agg(
        array_sort(
          collect_list(
            struct(
              col("step"),
              col("from").as("fromResearcherId"),
              col("to").as("toResearcherId"),
              col("edgeId"),
              col("jointPublicationCount"),
              col("supportingWorkIds")
            )
          )
        ).as("hs")
      )
      .select(
        (k :+ col("qid") :+ transform(
          col("hs"),
          h =>
            struct(
              h.getField("fromResearcherId").as("fromResearcherId"),
              h.getField("toResearcherId").as("toResearcherId"),
              h.getField("edgeId").as("edgeId"),
              h.getField("jointPublicationCount").as("jointPublicationCount"),
              h.getField("supportingWorkIds").as("supportingWorkIds")
            )
        ).as("hops"))*
      )
    val hopsType = hopRows.schema("hops").dataType

    val comp = components.select((k :+ col("researcherId") :+ col("componentId"))*)
    val evidence = queries
      .join(found, kq, "left")
      .join(hopRows, kq, "left")
      .join(
        comp.withColumnRenamed("researcherId", "source").withColumnRenamed("componentId", "cs"),
        Windows.Keys :+ "source",
        "left"
      )
      .join(
        comp.withColumnRenamed("researcherId", "target").withColumnRenamed("componentId", "ct"),
        Windows.Keys :+ "target",
        "left"
      )
      .withColumn(
        "status",
        when(col("length").isNotNull, lit("FOUND"))
          .when(col("cs").isNotNull && col("cs") === col("ct"), lit("NOT_FOUND_WITHIN_DEPTH"))
          .otherwise(lit("NO_PATH"))
      )
      .select(
        lit(SchemaVersions.PathEvidence).as("schemaVersion"),
        col("qid").as("pathId"),
        col("source").as("sourceResearcherId"),
        col("target").as("targetResearcherId"),
        col(Windows.Start),
        col(Windows.End),
        col("selection"),
        col("status"),
        lit(maxDepth).as("maxDepth"),
        when(col("status") === "FOUND", col("length"))
          .otherwise(lit(null).cast(IntegerType))
          .as("length"),
        when(col("status") === "FOUND", col("hops")).otherwise(array().cast(hopsType)).as("hops"),
        lit(p.coauthorPolicyVersion).as("policyVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("qid"),
            p.snapshot,
            p.runId,
            Some(Versions.Paths),
            when(col("status") === "FOUND", transform(col("hops"), h => h.getField("edgeId")))
              .otherwise(Schemas.emptyStrings)
          )
          .as("provenance")
      )
    directed.unpersist(blocking = false)
    PathResult(evidence, b, stepNo)

  /** Sampled-source distance estimate for each window's largest component of at least
    * `minComponentSize` researchers. BFS depth bound: `depthBound` (graph.components.maxIterations;
    * a converged component loop proves the component diameter is below it).
    */
  def distanceEstimates(
      spark: SparkSession,
      edges: DataFrame,
      components: DataFrame,
      p: BuildParams,
      sampleSources: Int,
      seed: Long,
      minComponentSize: Int,
      depthBound: Int
  ): Vector[DistanceEstimate] =
    val largest = largestComponents(components)
    val picks = p.windows.flatMap { w =>
      val key = (w.startYear, w.endYear)
      largest.get(key).filter(_._2 >= minComponentSize).map { (cid, size) =>
        (key, cid, size, seededMembers(components, key, cid, seed, sampleSources))
      }
    }
    if picks.isEmpty then Vector.empty
    else
      val directed = Iterative.directed(edges).persist(StorageLevel.MEMORY_AND_DISK)
      val rootRows = for (w, _, _, srcs) <- picks; s <- srcs yield Row(w._1, w._2, s, s)
      val rootSchema = StructType(
        Seq(
          StructField(Windows.Start, IntegerType, nullable = false),
          StructField(Windows.End, IntegerType, nullable = false),
          StructField("qid", StringType, nullable = false),
          StructField("node", StringType, nullable = false)
        )
      )
      val roots = spark.createDataFrame(Arrays.asList(rootRows*), rootSchema)
      val b = bfs(roots, directed, depthBound, None, p.checkpointInterval, p.checkpoint)
      // Bounded collect: windows x distinct distances (<= depthBound).
      val hist = b.visited
        .where(col("dist") > 0)
        .groupBy(col(Windows.Start), col(Windows.End), col("dist"))
        .agg(count(lit(1)).as("n"))
        .collect()
        .map(r => ((r.getInt(0), r.getInt(1)), r.getInt(2), r.getLong(3)))
        .toVector
      directed.unpersist(blocking = false)
      picks.map { (w, cid, size, srcs) =>
        val h = hist.filter(_._1 == w).map(x => (x._2, x._3)).sortBy(_._1)
        val total = h.map(_._2).sum
        val mean = if total == 0 then 0.0 else h.map((d, n) => d.toLong * n).sum.toDouble / total
        val p90 =
          if total == 0 then 0
          else
            val cum = h.scanLeft(0L)(_ + _._2).tail
            h.zip(cum).find((_, c) => c * 10 >= total * 9).map(_._1._1).get
        DistanceEstimate(
          w._1,
          w._2,
          cid,
          size,
          sampleSources,
          seed,
          srcs,
          total,
          mean,
          p90,
          h.lastOption.fold(0)(_._1),
          h,
          b.levels,
          b.exhausted,
          srcs.size.toLong == size
        )
      }
