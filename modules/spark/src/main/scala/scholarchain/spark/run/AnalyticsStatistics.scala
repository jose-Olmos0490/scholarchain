package scholarchain.spark.run

import org.apache.spark.sql.functions.*
import scholarchain.core.model.WindowGraphSummary
import scholarchain.spark.Windows
import scholarchain.spark.graph.{AnalyticsOutputs, BuildParams, Temporal}

/** PART B run-level statistics. Every collect is a bounded aggregate (bound noted at each one). */
final case class AnalyticsStatistics(json: ujson.Value, lines: Vector[String])

object AnalyticsStatistics:
  private def w(s: Int, e: Int) = s"$s-$e"

  def compute(
      a: AnalyticsOutputs,
      p: BuildParams,
      summaries: Vector[WindowGraphSummary],
      topN: Int
  ): AnalyticsStatistics =
    val lines = Vector.newBuilder[String]
    val k = Windows.keyCols

    // Bridge distribution per window. Bounded: one row per window.
    val bridgeDist = a.bridges
      .groupBy(k*)
      .agg(
        count(lit(1)).as("n"),
        sum(when(col("crossCommunityDegree") > 0, 1L).otherwise(0L)).as("bridging"),
        avg("participationCoefficient").as("meanPc"),
        max("participationCoefficient").as("maxPc"),
        max("crossCommunityDegree").as("maxCross"),
        max("communitiesConnected").as("maxConnected")
      )
      .orderBy(k*)
      .collect()
      .toVector
    // Bounded: windows x distinct communitiesConnected values (<= max degree + 1).
    val connectedHist = a.bridges
      .groupBy((k :+ col("communitiesConnected"))*)
      .agg(count(lit(1)).as("n"))
      .orderBy((k :+ col("communitiesConnected"))*)
      .collect()
      .toVector
    val bridgeJson = ujson.Arr.from(bridgeDist.map { r =>
      val key = (r.getInt(0), r.getInt(1))
      val line =
        f"BRIDGES ${w(key._1, key._2)} researchers=${r.getLong(2)} bridging(cross>0)=${r.getLong(3)} meanPC=${r.getDouble(4)}%.4f maxPC=${r.getDouble(5)}%.4f maxCross=${r.getLong(6)} maxCommunitiesConnected=${r.getLong(7)}"
      lines += line
      ujson.Obj(
        "window" -> w(key._1, key._2),
        "researchers" -> r.getLong(2).toDouble,
        "bridgingResearchers" -> r.getLong(3).toDouble,
        "meanParticipationCoefficient" -> r.getDouble(4),
        "maxParticipationCoefficient" -> r.getDouble(5),
        "maxCrossCommunityDegree" -> r.getLong(6).toDouble,
        "maxCommunitiesConnected" -> r.getLong(7).toDouble,
        "communitiesConnectedHistogram" -> ujson.Arr.from(
          connectedHist
            .filter(h => h.getInt(0) == key._1 && h.getInt(1) == key._2)
            .map(h => ujson.Obj("value" -> h.getLong(2).toDouble, "count" -> h.getLong(3).toDouble))
        )
      )
    })

    // Top bridges of the first configured window (all-years when enabled). Bounded: topN rows.
    val first = p.windows.head
    val top = a.bridges
      .where(col(Windows.Start) === first.startYear && col(Windows.End) === first.endYear)
      .orderBy(
        col("participationCoefficient").desc,
        col("crossCommunityDegree").desc,
        col("researcherId").asc
      )
      .limit(topN)
      .select(
        "researcherId",
        "communityId",
        "degree",
        "crossCommunityDegree",
        "communitiesConnected",
        "participationCoefficient"
      )
      .collect()
      .toVector
    lines += s"TOP $topN BRIDGES (${first.label}, by participationCoefficient desc, crossCommunityDegree desc, researcherId asc)"
    top.zipWithIndex.foreach((r, i) =>
      lines += f"  ${i + 1}%2d ${r.getString(0)} community=${r.getString(1)} degree=${r.getLong(2)} cross=${r.getLong(3)} connected=${r.getLong(4)} pc=${r.getDouble(5)}%.4f"
    )

    // Coreness histogram. Bounded: windows x distinct coreness values (<= max degree).
    val coreHist = a.coreness
      .groupBy((k :+ col("coreness"))*)
      .agg(count(lit(1)).as("n"))
      .orderBy((k :+ col("coreness"))*)
      .collect()
      .toVector
    p.windows.foreach { win =>
      val hs = coreHist.filter(r => r.getInt(0) == win.startYear && r.getInt(1) == win.endYear)
      lines += s"CORENESS ${win.label} " + hs
        .map(r => s"${r.getInt(2)}:${r.getLong(3)}")
        .mkString(" ")
    }
    p.windows.foreach { win =>
      val s = summaries
        .find(x => x.windowStartYear == win.startYear && x.windowEndYear == win.endYear)
        .get
      lines += s"COMMUNITIES ${win.label} count=${s.communityCount.getOrElse(0L)} sizes=" +
        s.communitySizeHistogram.map(h => s"${h.size}x${h.count}").mkString(",")
      s.iterations.foreach(it =>
        lines += s"ITERATIONS ${win.label} ${it.algorithm} iterations=${it.iterations}/${it.maxIterations} converged=${it.converged}"
      )
    }
    a.kcore.unpeeled.foreach((key, n) => lines += s"KCORE-UNPEELED ${w(key._1, key._2)} $n")

    // Path outcomes. Bounded: one row per configured/sampled query.
    val paths = a.paths
      .select(
        col("pathId"),
        col(Windows.Start),
        col(Windows.End),
        col("sourceResearcherId"),
        col("targetResearcherId"),
        col("selection"),
        col("status"),
        col("length"),
        transform(col("hops"), h => h.getField("toResearcherId")).as("nodes"),
        transform(col("hops"), h => h.getField("jointPublicationCount")).as("weights")
      )
      .orderBy(col(Windows.Start), col(Windows.End), col("pathId"))
      .collect()
      .toVector
    val pathJson = ujson.Arr.from(paths.map { r =>
      val nodes = r.getString(3) +: r.getSeq[String](8).toVector
      lines += s"PATH ${w(r.getInt(1), r.getInt(2))} ${r.getString(5)} ${r.getString(6)} length=${Option(r.get(7)).getOrElse("-")} ${r.getString(3)} -> ${r.getString(4)}" +
        (if r.getString(6) == "FOUND" then
           s" via ${nodes.mkString(" > ")} weights=${r.getSeq[Long](9).mkString(",")}"
         else "")
      ujson.Obj(
        "pathId" -> r.getString(0),
        "window" -> w(r.getInt(1), r.getInt(2)),
        "source" -> r.getString(3),
        "target" -> r.getString(4),
        "selection" -> r.getString(5),
        "status" -> r.getString(6),
        "length" -> Option(r.get(7)).fold[ujson.Value](ujson.Null)(v =>
          ujson.Num(v.toString.toDouble)
        ),
        "nodes" -> (if r.getString(6) == "FOUND" then ujson.Arr.from(nodes.map(ujson.Str(_)))
                    else ujson.Arr())
      )
    })

    val distJson = ujson.Arr.from(a.distances.map { d =>
      lines += f"DISTANCE-ESTIMATE ${w(d.windowStartYear, d.windowEndYear)} (ESTIMATE: sampled BFS sources) component=${d.componentId} size=${d.componentSize} k=${d.sources.size} (configured ${d.sampleSources}) seed=${d.seed} reachedPairs=${d.reachedPairs} meanDistance=${d.meanDistance}%.4f p90EffectiveDiameter=${d.effectiveDiameterP90} maxObserved=${d.maxObservedDistance} exactAllSources=${d.exactAllSources} bfsLevels=${d.bfsLevels} exhausted=${d.bfsExhausted}"
      ujson.Obj(
        "label" -> "ESTIMATE (sampled BFS sources; exact only when exactAllSources)",
        "window" -> w(d.windowStartYear, d.windowEndYear),
        "componentId" -> d.componentId,
        "componentSize" -> d.componentSize.toDouble,
        "configuredSampleSources" -> d.sampleSources,
        "sources" -> ujson.Arr.from(d.sources.map(ujson.Str(_))),
        "seed" -> d.seed.toString,
        "reachedPairs" -> d.reachedPairs.toDouble,
        "meanDistance" -> d.meanDistance,
        "effectiveDiameterP90" -> d.effectiveDiameterP90,
        "maxObservedDistance" -> d.maxObservedDistance,
        "distanceHistogram" -> ujson.Arr.from(
          d.distanceHistogram.map((dist, n) => ujson.Obj("distance" -> dist, "pairs" -> n.toDouble))
        ),
        "bfsLevels" -> d.bfsLevels,
        "bfsExhausted" -> d.bfsExhausted,
        "exactAllSources" -> d.exactAllSources
      )
    })

    // Temporal: consecutive fixed windows, from the per-window summaries and bridge rows.
    val bridging = bridgeDist.map(r => (r.getInt(0), r.getInt(1)) -> r).toMap
    val temporal = Temporal.pairs(p.windows).map { (from, to) =>
      def s(x: scholarchain.spark.AnalysisWindow) =
        summaries.find(y => y.windowStartYear == x.startYear && y.windowEndYear == x.endYear).get
      val (a1, b1) = (s(from), s(to))
      def br(x: scholarchain.spark.AnalysisWindow) =
        bridging.get((x.startYear, x.endYear)).fold(0L)(_.getLong(3))
      lines += s"TEMPORAL ${from.label} -> ${to.label} researchers ${a1.researcherCount}->${b1.researcherCount} edges ${a1.coauthorEdgeCount}->${b1.coauthorEdgeCount} components ${a1.componentCount}->${b1.componentCount} largestComponent ${a1.largestComponentSize}->${b1.largestComponentSize} communities ${a1.communityCount.getOrElse(0L)}->${b1.communityCount.getOrElse(0L)} bridging ${br(from)}->${br(to)} maxCoreness ${a1.maxCoreness.getOrElse(0)}->${b1.maxCoreness.getOrElse(0)}"
      ujson.Obj(
        "from" -> from.label,
        "to" -> to.label,
        "researcherCount" -> ujson.Arr(a1.researcherCount.toDouble, b1.researcherCount.toDouble),
        "coauthorEdgeCount" -> ujson
          .Arr(a1.coauthorEdgeCount.toDouble, b1.coauthorEdgeCount.toDouble),
        "componentCount" -> ujson.Arr(a1.componentCount.toDouble, b1.componentCount.toDouble),
        "largestComponentSize" -> ujson
          .Arr(a1.largestComponentSize.toDouble, b1.largestComponentSize.toDouble),
        "communityCount" -> ujson
          .Arr(a1.communityCount.getOrElse(0L).toDouble, b1.communityCount.getOrElse(0L).toDouble),
        "communitySizeHistogram" -> ujson.Arr(
          ujson.Arr
            .from(a1.communitySizeHistogram.map(h => ujson.Arr(h.size.toDouble, h.count.toDouble))),
          ujson.Arr
            .from(b1.communitySizeHistogram.map(h => ujson.Arr(h.size.toDouble, h.count.toDouble)))
        ),
        "bridgingResearchers" -> ujson.Arr(br(from).toDouble, br(to).toDouble),
        "maxCoreness" -> ujson
          .Arr(a1.maxCoreness.getOrElse(0).toDouble, b1.maxCoreness.getOrElse(0).toDouble)
      )
    }
    // Researcher-level change counts per pair. Bounded: one row per window pair.
    val changeCounts = a.windowChanges.toVector.flatMap(df =>
      df.groupBy(
        col("fromWindowStartYear"),
        col("fromWindowEndYear"),
        col("toWindowStartYear"),
        col("toWindowEndYear")
      ).agg(
        count(lit(1)).as("rows"),
        sum(when(col("from").isNotNull && col("to").isNotNull, 1L).otherwise(0L)).as("both"),
        sum(when(col("from").isNull, 1L).otherwise(0L)).as("entering"),
        sum(when(col("to").isNull, 1L).otherwise(0L)).as("leaving")
      ).orderBy(col("fromWindowStartYear"))
        .collect()
        .toVector
    )
    changeCounts.foreach(r =>
      lines += s"TEMPORAL-RESEARCHERS ${r.getInt(0)}-${r.getInt(1)} -> ${r.getInt(2)}-${r.getInt(3)} rows=${r.getLong(4)} inBoth=${r.getLong(5)} onlyTo=${r.getLong(6)} onlyFrom=${r.getLong(7)}"
    )
    lines += s"TEMPORAL-LIMITATION ${Temporal.Limitation}"

    AnalyticsStatistics(
      ujson.Obj(
        "bridgeDistribution" -> bridgeJson,
        "topBridges" -> ujson.Obj(
          "window" -> first.label,
          "order" -> "participationCoefficient desc, crossCommunityDegree desc, researcherId asc",
          "rows" -> ujson.Arr.from(
            top.map(r =>
              ujson.Obj(
                "researcherId" -> r.getString(0),
                "communityId" -> r.getString(1),
                "degree" -> r.getLong(2).toDouble,
                "crossCommunityDegree" -> r.getLong(3).toDouble,
                "communitiesConnected" -> r.getLong(4).toDouble,
                "participationCoefficient" -> r.getDouble(5)
              )
            )
          )
        ),
        "corenessHistogram" -> ujson.Arr.from(
          coreHist.map(r =>
            ujson.Obj(
              "window" -> w(r.getInt(0), r.getInt(1)),
              "coreness" -> r.getInt(2),
              "count" -> r.getLong(3).toDouble
            )
          )
        ),
        "kcoreUnpeeled" -> ujson.Obj.from(
          a.kcore.unpeeled.map((key, n) => w(key._1, key._2) -> ujson.Num(n.toDouble))
        ),
        "paths" -> pathJson,
        "pathQueries" -> ujson.Obj(
          "selection" -> (if a.queries.exists(
                              _.selection == scholarchain.core.model.PathSelection.BENCHMARK
                            )
                          then "BENCHMARK"
                          else "SAMPLED"),
          "count" -> a.queries.size,
          "bfsLevels" -> a.pathRun.bfs.levels,
          "walkSteps" -> a.pathRun.walkSteps
        ),
        "distanceEstimates" -> distJson,
        "temporal" -> ujson.Obj(
          "limitation" -> Temporal.Limitation,
          "pairs" -> ujson.Arr.from(temporal),
          "researcherChangeCounts" -> ujson.Arr.from(
            changeCounts.map(r =>
              ujson.Obj(
                "from" -> s"${r.getInt(0)}-${r.getInt(1)}",
                "to" -> s"${r.getInt(2)}-${r.getInt(3)}",
                "rows" -> r.getLong(4).toDouble,
                "inBoth" -> r.getLong(5).toDouble,
                "onlyTo" -> r.getLong(6).toDouble,
                "onlyFrom" -> r.getLong(7).toDouble
              )
            )
          )
        )
      ),
      lines.result()
    )
