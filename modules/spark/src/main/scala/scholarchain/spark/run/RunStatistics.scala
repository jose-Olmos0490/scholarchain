package scholarchain.spark.run

import org.apache.spark.sql.functions.*
import org.apache.spark.sql.{DataFrame, Row}
import scholarchain.spark.Windows
import scholarchain.spark.graph.{BuildParams, GraphBuilder, GraphOutputs}

/** Run-level counters and distributions. Every value is a distributed aggregate; only the
  * aggregated rows are collected, each bounded as noted (reason codes, ID sources, windows, or an
  * explicit limit).
  */
final case class RunStatistics(json: ujson.Value, lines: Vector[String])

object RunStatistics:
  private def counts(df: DataFrame, cols: String*): Vector[(Vector[String], Long)] =
    df.groupBy(cols.map(col)*)
      .agg(count(lit(1)).as("n"))
      .orderBy(cols.map(col)*)
      .collect()
      .toVector
      .map((r: Row) =>
        (cols.indices.map(i => String.valueOf(r.get(i))).toVector, r.getLong(cols.size))
      )

  private def countsJson(v: Vector[(Vector[String], Long)]): ujson.Value =
    ujson.Arr.from(
      v.map((k, n) =>
        ujson.Obj("key" -> ujson.Arr.from(k.map(ujson.Str(_))), "count" -> n.toDouble)
      )
    )

  def compute(out: GraphOutputs, p: BuildParams): RunStatistics =
    // Bounded: ID sources (3) x identity statuses (3).
    val byIdSource = counts(out.researchers, "idSource")
    val byStatus = counts(out.researchers, "idSource", "identityStatus")
    // Bounded: identity statuses of all identity groups (3).
    val groupsByStatus = counts(out.identity.groups, "status")
    // Bounded: conflict reason codes (6).
    val conflictReasons = counts(
      out.identity.conflicts.select(explode(col("details.reason")).as("reason")),
      "reason"
    )
    val conflictCount = out.identity.conflicts.count()
    // Bounded: integrity reject reason codes.
    val rejectReasons = counts(out.rejects, "reasonCode")
    val selfPairsTotal = out.selfPairs.select("candidateId").distinct().count()
    // Bounded: windows x invariant statuses (5).
    val wt = counts(
      out.wtInvariant.withColumn("w", concat_ws("-", col(Windows.Start), col(Windows.End))),
      "w",
      "status"
    )
    val wtFailures = out.wtInvariant.where(col("status") === GraphBuilder.WtFailure).count()
    val wtExceptions = out.wtInvariant
      .where(col("status") =!= GraphBuilder.WtFailure && col("status") =!= GraphBuilder.WtEqual)
      .count()
    val oversizeExceptionResearchers = out.wtInvariant
      .where(col("status").isin(GraphBuilder.WtExceptionOversize, GraphBuilder.WtExceptionBoth))
      .count()
    // Bounded: windows (one row each).
    val dist = out.metrics
      .groupBy(Windows.Start, Windows.End)
      .agg(
        count(lit(1)),
        min("publicationCount"),
        max("publicationCount"),
        avg("publicationCount"),
        min("distinctCoauthorCount"),
        max("distinctCoauthorCount"),
        avg("distinctCoauthorCount"),
        min("totalCoauthorOccurrences"),
        max("totalCoauthorOccurrences"),
        avg("totalCoauthorOccurrences"),
        min("averageCoauthorsPerPaper"),
        max("averageCoauthorsPerPaper"),
        avg("averageCoauthorsPerPaper"),
        min("maximumCoauthorsOnOnePaper"),
        max("maximumCoauthorsOnOnePaper"),
        avg("maximumCoauthorsOnOnePaper"),
        min("weightedDegree"),
        max("weightedDegree"),
        avg("weightedDegree")
      )
      .orderBy(Windows.Start, Windows.End)
      .collect()
      .toVector
    val names = Vector("P", "C", "T", "A", "M", "W")
    val distLines = dist.map { r =>
      val parts = names.indices.map { i =>
        val b = 3 + i * 3
        f"${names(i)}[min=${r.get(b)} max=${r.get(b + 1)} mean=${r.getDouble(b + 2)}%.3f]"
      }
      s"DISTRIBUTION ${r.getInt(0)}-${r.getInt(1)} rows=${r.getLong(2)} ${parts.mkString(" ")}"
    }
    // Bounded: 10 rows per window, the top researchers by C_r (ties: smallest researcher ID).
    val top = p.windows.headOption.toVector.flatMap { w =>
      out.metrics
        .where(col(Windows.Start) === w.startYear && col(Windows.End) === w.endYear)
        .join(out.researchers.select("researcherId", "displayName", "idSource"), "researcherId")
        .orderBy(col("distinctCoauthorCount").desc, col("researcherId").asc)
        .select(
          "researcherId",
          "displayName",
          "idSource",
          "publicationCount",
          "distinctCoauthorCount",
          "totalCoauthorOccurrences",
          "averageCoauthorsPerPaper",
          "maximumCoauthorsOnOnePaper",
          "weightedDegree",
          "componentSize"
        )
        .limit(10)
        .collect()
        .toVector
        .map(r => (w, r))
    }
    val topLines = top.zipWithIndex.map { case ((w, r), i) =>
      f"TOP_C ${w.label} #${i + 1} ${r.getString(0)} '${r.getString(1)}' source=${r.getString(2)} P=${r.getLong(3)} C=${r.getLong(4)} T=${r.getLong(5)} A=${r.getDouble(6)}%.3f M=${r.getLong(7)} W=${r.getDouble(8)}%.0f componentSize=${r.getLong(9)}"
    }
    def kv(v: Vector[(Vector[String], Long)]) =
      v.map((k, n) => s"${k.mkString("/")}=$n").mkString(" ")
    val lines = Vector(
      s"RESEARCHERS_BY_ID_SOURCE ${kv(byIdSource)}",
      s"RESEARCHERS_BY_ID_SOURCE_AND_STATUS ${kv(byStatus)}",
      s"IDENTITY_GROUPS_BY_STATUS ${kv(groupsByStatus)}",
      s"IDENTITY_CONFLICTS total=$conflictCount byReason: ${kv(conflictReasons)}",
      s"INTEGRITY_REJECTS total=${rejectReasons.map(_._2).sum} ${kv(rejectReasons)}",
      s"SELF_PAIRS_DROPPED distinctPairCandidates=$selfPairsTotal",
      s"WT_INVARIANT failures=$wtFailures exceptions=$wtExceptions oversizeTeamExceptions=$oversizeExceptionResearchers ${kv(wt)}"
    ) ++ distLines ++ topLines
    val json = ujson.Obj(
      "researchersByIdSource" -> countsJson(byIdSource),
      "researchersByIdSourceAndStatus" -> countsJson(byStatus),
      "identityGroupsByStatus" -> countsJson(groupsByStatus),
      "identityConflicts" -> conflictCount.toDouble,
      "identityConflictsByReason" -> countsJson(conflictReasons),
      "integrityRejectsByReason" -> countsJson(rejectReasons),
      "selfPairsDropped" -> selfPairsTotal.toDouble,
      "wtInvariant" -> ujson.Obj(
        "failures" -> wtFailures.toDouble,
        "exceptions" -> wtExceptions.toDouble,
        "oversizeTeamExceptions" -> oversizeExceptionResearchers.toDouble,
        "byWindowAndStatus" -> countsJson(wt)
      ),
      "metricDistributions" -> ujson.Arr.from(distLines.map(ujson.Str(_))),
      "topByDistinctCoauthors" -> ujson.Arr.from(topLines.map(ujson.Str(_)))
    )
    RunStatistics(json, lines)
