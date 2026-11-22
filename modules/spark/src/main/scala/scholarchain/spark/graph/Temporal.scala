package scholarchain.spark.graph

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.spark.{AnalysisWindow, Schemas, Versions, Windows}

/** Consecutive-window researcher comparison (`sc-temporal-window-change-v1`, core
  * `ResearcherWindowChange`).
  *
  * The fixed windows (the all-years window excluded) are sorted by start year; each adjacent pair
  * (from, to) is compared. A researcher with a row in either window gets one change row; the side
  * without a row is null (no snapshot). Snapshots hold P, C, A, M, componentSize, communitySize,
  * crossCommunityDegree, communitiesConnected, participationCoefficient and coreness.
  *
  * LIMITATION: community and component IDs are window-local labels, so they are never compared
  * across windows; only sizes and measures are.
  *
  * Plan: one full outer join on researcherId per window pair (bounded by the number of windows).
  */
object Temporal:
  val Limitation: String =
    "Community and component IDs are window-local labels (a community ID is a label-propagation " +
      "label, a component ID the smallest member ID in that window). They are not comparable " +
      "across windows, so only sizes and measures are compared, never labels."

  /** The adjacent fixed-window pairs, in start-year order. */
  def pairs(windows: Vector[AnalysisWindow]): Vector[(AnalysisWindow, AnalysisWindow)] =
    val fixed = windows.filterNot(_.allYears).sortBy(_.startYear)
    fixed.zip(fixed.drop(1))

  /** `(window, researcherId, snapshot)`; the snapshot struct has the core field names. */
  def snapshots(
      metrics: DataFrame,
      communities: DataFrame,
      bridges: DataFrame,
      coreness: DataFrame
  ): DataFrame =
    val wk = Windows.Keys :+ "researcherId"
    metrics
      .drop("communityId", "coreness") // re-joined from the community and k-core tables below
      .join(communities.select((wk.map(col) :+ col("communitySize"))*), wk, "left")
      .join(
        bridges.select(
          (wk.map(col) :+ col("crossCommunityDegree") :+ col("communitiesConnected") :+
            col("participationCoefficient"))*
        ),
        wk,
        "left"
      )
      .join(coreness.select((wk.map(col) :+ col("coreness"))*), wk, "left")
      .select(
        col(Windows.Start),
        col(Windows.End),
        col("researcherId"),
        struct(
          col("publicationCount"),
          col("distinctCoauthorCount"),
          col("averageCoauthorsPerPaper"),
          col("maximumCoauthorsOnOnePaper"),
          col("componentSize"),
          col("communitySize"),
          col("crossCommunityDegree"),
          col("communitiesConnected"),
          col("participationCoefficient"),
          col("coreness")
        ).as("snapshot")
      )

  /** Change rows of every adjacent window pair (empty when fewer than two fixed windows). */
  def changes(snaps: DataFrame, p: BuildParams): Option[DataFrame] =
    val ps = pairs(p.windows)
    def side(w: AnalysisWindow, name: String): DataFrame =
      snaps
        .where(col(Windows.Start) === w.startYear && col(Windows.End) === w.endYear)
        .select(col("researcherId"), col("snapshot").as(name))
    val frames = ps.map { (from, to) =>
      side(from, "from")
        .join(side(to, "to"), Seq("researcherId"), "full_outer")
        .select(
          lit(SchemaVersions.ResearcherWindowChange).as("schemaVersion"),
          col("researcherId"),
          lit(from.startYear).as("fromWindowStartYear"),
          lit(from.endYear).as("fromWindowEndYear"),
          lit(to.startYear).as("toWindowStartYear"),
          lit(to.endYear).as("toWindowEndYear"),
          col("from"),
          col("to"),
          lit(Versions.Temporal).as("metricVersion"),
          lit(p.runId).as("runId"),
          Schemas
            .derivedProvenance(
              col("researcherId"),
              p.snapshot,
              p.runId,
              Some(Versions.Temporal),
              Schemas.emptyStrings
            )
            .as("provenance")
        )
    }
    frames.reduceOption(_.union(_))
