package scholarchain.spark.graph

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, Row}
import scholarchain.core.model.{
  IterationReport,
  Provenance,
  SizeCount,
  SourceType,
  WindowGraphSummary
}
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{Versions, Windows}

/** Per-window graph statistics (`WindowGraphSummary`). Each statistic is a distributed
  * aggregate grouped by window; only the per-window results are collected (bounded by the number
  * of configured windows).
  */
object WindowSummaries:
  val ComponentsAlgorithm = "connected-components-min-label-propagation"

  private type Key = (Int, Int)

  private def perWindow(df: DataFrame, aggs: org.apache.spark.sql.Column*): Map[Key, Row] =
    // Bounded collect: one row per configured window.
    df.groupBy(Windows.Start, Windows.End)
      .agg(aggs.head, aggs.tail*)
      .collect()
      .map(r => ((r.getInt(0), r.getInt(1)), r))
      .toMap

  /** PART B inputs of the summary (absent for a PART A-only computation). */
  final case class PartB(
      analytics: AnalyticsOutputs,
      communityMaxIterations: Int,
      kcoreMaxIterations: Int
  )

  def compute(
      out: GraphOutputs,
      p: BuildParams,
      maxIterations: Int,
      partB: Option[PartB] = None
  ): Vector[WindowGraphSummary] =
    val rp = perWindow(
      out.researcherPapers,
      countDistinct("researcherId"),
      countDistinct("workId"),
      countDistinct(when(col("workYear").isNull, col("workId"))),
      countDistinct(when(col("oversize"), col("workId")))
    )
    val edges = perWindow(out.edges, count(lit(1)))
    val selfPairs = perWindow(out.selfPairs, count(lit(1)))
    val comps = perWindow(
      out.components,
      countDistinct("componentId"),
      max("componentSize"),
      countDistinct(when(col("componentSize") === 1, col("componentId")))
    )
    val iterations = out.componentRun.groups.map { g =>
      ((g.group(0).asInstanceOf[Int], g.group(1).asInstanceOf[Int]), g)
    }.toMap
    // Bounded collect: windows x distinct community sizes (a histogram, not a node table).
    val hist: Map[Key, Vector[SizeCount]] = partB.fold(Map.empty[Key, Vector[SizeCount]]) { b =>
      b.analytics.communities
        .select(col(Windows.Start), col(Windows.End), col("communityId"), col("communitySize"))
        .distinct()
        .groupBy(col(Windows.Start), col(Windows.End), col("communitySize"))
        .agg(count(lit(1)).as("n"))
        .collect()
        .toVector
        .groupBy(r => (r.getInt(0), r.getInt(1)))
        .map((k, rs) => k -> rs.map(r => SizeCount(r.getLong(2), r.getLong(3))).sortBy(_.size))
    }
    val maxCore: Map[Key, Row] =
      partB.fold(Map.empty[Key, Row])(b => perWindow(b.analytics.coreness, max("coreness")))
    def reportsOf(gs: Vector[ConnectedComponents.GroupIterations]) =
      gs.map(g => ((g.group(0).asInstanceOf[Int], g.group(1).asInstanceOf[Int]), g)).toMap
    val lpa = partB.fold(Map.empty[Key, ConnectedComponents.GroupIterations])(b =>
      reportsOf(b.analytics.communityRun)
    )
    val kc = partB.fold(Map.empty[Key, ConnectedComponents.GroupIterations])(b =>
      reportsOf(b.analytics.kcore.groups)
    )
    def l(m: Map[Key, Row], k: Key, i: Int): Long =
      m.get(k)
        .map(r => if r.isNullAt(i) then 0L else r.getAs[java.lang.Number](i).longValue)
        .getOrElse(0L)
    p.windows.map { w =>
      val k = (w.startYear, w.endYear)
      val it = iterations(k)
      WindowGraphSummary(
        schemaVersion = SchemaVersions.WindowGraphSummary,
        windowStartYear = w.startYear,
        windowEndYear = w.endYear,
        allYearsWindow = w.allYears,
        researcherCount = l(rp, k, 2),
        workCount = l(rp, k, 3),
        yearlessWorkCount = l(rp, k, 4),
        coauthorEdgeCount = l(edges, k, 2),
        selfPairsDropped = l(selfPairs, k, 2),
        oversizeTeamWorkCount = l(rp, k, 5),
        componentCount = l(comps, k, 2),
        largestComponentSize = l(comps, k, 3),
        singletonComponentCount = l(comps, k, 4),
        communityCount = partB.map(_ => hist.getOrElse(k, Vector.empty).map(_.count).sum),
        communitySizeHistogram = hist.getOrElse(k, Vector.empty),
        maxCoreness =
          partB.map(_ => maxCore.get(k).filterNot(_.isNullAt(2)).fold(0)(r => r.getInt(2))),
        iterations = Vector(
          IterationReport(ComponentsAlgorithm, it.iterations, maxIterations, it.converged)
        ) ++
          partB.toVector.flatMap { b =>
            Vector(
              lpa
                .get(k)
                .map(g =>
                  IterationReport(
                    Communities.Algorithm,
                    g.iterations,
                    b.communityMaxIterations,
                    g.converged
                  )
                ),
              kc.get(k)
                .map(g =>
                  IterationReport(KCore.Algorithm, g.iterations, b.kcoreMaxIterations, g.converged)
                )
            ).flatten
          },
        metricVersion = Versions.WindowSummary,
        runId = p.runId,
        provenance = Provenance(
          schemaVersion = SchemaVersions.Provenance,
          sourceDataset = SourceType.SCHOLARCHAIN,
          sourceRecordId = p.runId,
          snapshot = p.snapshot,
          runId = p.runId,
          policyVersion = Some(Versions.WindowSummary),
          modelVersion = None,
          promptVersion = None,
          derivedFrom = Vector.empty
        )
      )
    }
