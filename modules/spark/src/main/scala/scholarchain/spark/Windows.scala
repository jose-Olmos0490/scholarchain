package scholarchain.spark

import java.util.Arrays
import org.apache.spark.sql.functions.{broadcast, col}
import org.apache.spark.sql.types.{BooleanType, IntegerType, StructField, StructType}
import org.apache.spark.sql.{Column, DataFrame, Row, SparkSession}

/** Window assignment (frozen rule, `GraphConfig.windowsFor`): a row with year `y` belongs to every
  * window whose `[start, end]` contains `y`; a row with no year belongs only to the all-years
  * window.
  *
  * Plan: the window list is a handful of configuration rows, so it is broadcast and joined with a
  * non-equi condition (BroadcastNestedLoopJoin, no shuffle of the large side).
  */
object Windows:
  val Start = "windowStartYear"
  val End = "windowEndYear"
  val AllYears = "allYearsWindow"
  val Keys: Seq[String] = Seq(Start, End)

  val Schema: StructType = StructType(
    Seq(
      StructField(Start, IntegerType, nullable = false),
      StructField(End, IntegerType, nullable = false),
      StructField(AllYears, BooleanType, nullable = false)
    )
  )

  /** The configured windows as a DataFrame (bounded by the configuration). */
  def frame(spark: SparkSession, windows: Vector[AnalysisWindow]): DataFrame =
    val rows = windows.map(w => Row(w.startYear, w.endYear, w.allYears))
    spark.createDataFrame(Arrays.asList(rows*), Schema)

  /** `df` joined with every window its `yearCol` falls into (adds the three window columns). */
  def assign(df: DataFrame, yearCol: String, windows: DataFrame): DataFrame =
    val y = df.col(yearCol)
    val w = broadcast(windows)
    val cond: Column =
      (y.isNotNull && y.between(w.col(Start), w.col(End))) || (y.isNull && w.col(AllYears))
    df.join(w, cond, "inner")

  /** `window=<start>-<end>` partition value. */
  def label(start: Column, end: Column): Column =
    org.apache.spark.sql.functions.concat_ws("-", start.cast("string"), end.cast("string"))

  def keyCols: Seq[Column] = Keys.map(col)
