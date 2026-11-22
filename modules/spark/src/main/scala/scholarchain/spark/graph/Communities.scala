package scholarchain.spark.graph

import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, SparkSession}
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{Schemas, Versions, Windows}

/** Communities (`sc-communities-lpa-sync-v1`), cross-community edges and bridge measures
  * (`sc-bridge-measures-v1`). Definitions, parameters and limitations: modules/spark/JOBS.md.
  *
  * Label propagation, as DataFrame joins (no GraphX):
  *   - every researcher of a window starts with its own researcher ID as label
  *   - synchronous: every node computes its next label from the previous iteration's labels only
  *   - unweighted vote: the node's own label (one vote) plus one vote per neighbour (each distinct
  *     coauthor once, whatever the jointPublicationCount)
  *   - the label with most votes wins; ties go to the smallest label (ordinal string order)
  *   - stops when no label of any window changed, or at `community.maxIterations`
  *
  * Per iteration (shuffle boundaries): join of the directed edges with the labels on
  * `(window, a)`; union with the self-votes; `groupBy(window, node, label).count`; a window
  * function partitioned by `(window, node)` ordered by (votes desc, label asc) picks the winner;
  * a join back to the previous labels flags changes.
  */
object Communities:
  val Algorithm = "label-propagation-synchronous"

  final case class Result(
      membership: DataFrame,
      crossEdges: DataFrame,
      bridges: DataFrame,
      groups: Vector[ConnectedComponents.GroupIterations],
      firstIterationPlan: DataFrame
  )

  /** One LPA step over `labels` (`window, node, label`). */
  def step(directed: DataFrame, labels: DataFrame): DataFrame =
    val k = Windows.keyCols
    val neighbourVotes = directed
      .join(labels.withColumnRenamed("node", "a"), Windows.Keys :+ "a")
      .select((k :+ col("b").as("node") :+ col("label"))*)
    val selfVotes = labels.select((k :+ col("node") :+ col("label"))*)
    val counted = neighbourVotes
      .union(selfVotes)
      .groupBy((k :+ col("node") :+ col("label"))*)
      .agg(count(lit(1)).as("votes"))
    val rank = row_number().over(
      Window.partitionBy((k :+ col("node"))*).orderBy(col("votes").desc, col("label").asc)
    )
    val best = counted
      .withColumn("rk", rank)
      .where(col("rk") === 1)
      .select((k :+ col("node") :+ col("label").as("newLabel"))*)
    labels
      .join(best, Windows.Keys :+ "node")
      .select(
        (k :+ col("node") :+ col("newLabel").as("label") :+
          (col("newLabel") =!= col("label")).as("changed"))*
      )

  /** Runs label propagation and derives membership, cross-community edges and bridge measures.
    *
    * @param nodes
    *   `(window, researcherId)` of every researcher with a paper in the window
    * @param edges
    *   the windowed coauthor edges (core `CoauthorEdge` columns)
    */
  def run(
      spark: SparkSession,
      nodes: DataFrame,
      edges: DataFrame,
      p: BuildParams,
      maxIterations: Int
  ): Result =
    val k = Windows.keyCols
    val directed = Iterative.directed(edges).cache()
    val initial =
      nodes.select((k :+ col("researcherId").as("node") :+ col("researcherId").as("label"))*)
    val outcome = Iterative.untilStable(
      initial,
      p.windows.map(w => Vector[Any](w.startYear, w.endYear)),
      maxIterations,
      p.checkpointInterval,
      p.checkpoint
    )(labels => step(directed, labels))
    val labels = outcome.state.select(
      (k :+ col("node").as("researcherId") :+ col("label").as("communityId"))*
    )
    val sizes = labels
      .groupBy((k :+ col("communityId"))*)
      .agg(count(lit(1)).as("communitySize"))
    val member = labels.join(sizes, Windows.Keys :+ "communityId")
    val membership = member.select(
      lit(SchemaVersions.CommunityMembership).as("schemaVersion"),
      col("researcherId"),
      col("communityId"),
      col("communitySize"),
      col(Windows.Start),
      col(Windows.End),
      lit(Versions.Communities).as("metricVersion"),
      lit(p.runId).as("runId"),
      Schemas
        .derivedProvenance(
          col("researcherId"),
          p.snapshot,
          p.runId,
          Some(Versions.Communities),
          array(col("communityId"))
        )
        .as("provenance")
    )

    // Cross-community edges: both endpoints' communities (two joins on (window, researcher)).
    val ca = labels
      .withColumnRenamed("researcherId", "researcherA")
      .withColumnRenamed("communityId", "communityA")
    val cb = labels
      .withColumnRenamed("researcherId", "researcherB")
      .withColumnRenamed("communityId", "communityB")
    val crossEdges = edges
      .join(ca, Windows.Keys :+ "researcherA")
      .join(cb, Windows.Keys :+ "researcherB")
      .where(col("communityA") =!= col("communityB"))
      .select(
        lit(SchemaVersions.CrossCommunityEdge).as("schemaVersion"),
        col("edgeId"),
        col("researcherA"),
        col("researcherB"),
        col("communityA"),
        col("communityB"),
        col(Windows.Start),
        col(Windows.End),
        col("jointPublicationCount"),
        col("policyVersion"),
        lit(Versions.Bridges).as("metricVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("edgeId"),
            p.snapshot,
            p.runId,
            Some(Versions.Bridges),
            array(col("communityA"), col("communityB"))
          )
          .as("provenance")
      )

    // Bridge measures. k_rc = number of r's neighbours in community c (unweighted).
    //   degree k_r = sum_c k_rc
    //   crossCommunityDegree = sum of k_rc over c != own community
    //   communitiesConnected = |{own community} union {c : k_rc > 0}|  (counts the own community)
    //   participationCoefficient = 1 - sum_c (k_rc / k_r)^2 = (k_r^2 - sum_c k_rc^2) / k_r^2,
    //     computed from exact integer sums (deterministic), 0 when k_r = 0
    val neighbourCommunity = directed
      .join(
        labels.withColumnRenamed("researcherId", "b").withColumnRenamed("communityId", "cb"),
        Windows.Keys :+ "b"
      )
      .groupBy((k :+ col("a") :+ col("cb"))*)
      .agg(count(lit(1)).as("krc"))
    val perResearcher = neighbourCommunity
      .join(
        labels.withColumnRenamed("researcherId", "a").withColumnRenamed("communityId", "own"),
        Windows.Keys :+ "a"
      )
      .groupBy((k :+ col("a").as("researcherId"))*)
      .agg(
        sum("krc").as("degree"),
        sum(when(col("cb") =!= col("own"), col("krc")).otherwise(lit(0L))).as("cross"),
        sum(when(col("cb") =!= col("own"), lit(1L)).otherwise(lit(0L))).as("otherCommunities"),
        sum(col("krc") * col("krc")).as("sumSquares")
      )
    val bridges = labels
      .join(perResearcher, Windows.Keys :+ "researcherId", "left")
      .select(
        lit(SchemaVersions.BridgeMeasures).as("schemaVersion"),
        col("researcherId"),
        col("communityId"),
        col(Windows.Start),
        col(Windows.End),
        coalesce(col("degree"), lit(0L)).as("degree"),
        coalesce(col("cross"), lit(0L)).as("crossCommunityDegree"),
        (coalesce(col("otherCommunities"), lit(0L)) + lit(1L)).as("communitiesConnected"),
        when(
          coalesce(col("degree"), lit(0L)) === 0L,
          lit(0.0)
        ).otherwise(
          (col("degree") * col("degree") - col("sumSquares")).cast("double") /
            (col("degree") * col("degree")).cast("double")
        ).as("participationCoefficient"),
        lit(null).cast("double").as("betweenness"),
        lit(null).cast("string").as("betweennessMethod"),
        lit(null).cast("int").as("betweennessSampleSources"),
        lit(null).cast("long").as("betweennessSeed"),
        lit(Versions.Bridges).as("metricVersion"),
        lit(p.runId).as("runId"),
        Schemas
          .derivedProvenance(
            col("researcherId"),
            p.snapshot,
            p.runId,
            Some(Versions.Bridges),
            array(col("communityId"))
          )
          .as("provenance")
      )
    directed.unpersist(blocking = false)
    Result(membership, crossEdges, bridges, outcome.groups, outcome.firstIterationPlan)
