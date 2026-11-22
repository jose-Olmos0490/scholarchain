package scholarchain.adversarial.spark

import java.util.Arrays
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.spark.Windows
import scholarchain.spark.graph.Communities

/** Case 11: label-propagation tie-breaking. The rule under test (modules/spark/JOBS.md, job 6):
  * synchronous, unweighted, one vote for the node's own label plus one per distinct neighbour,
  * most votes wins, ties go to the smallest label (ordinal string order). The rule is tested on
  * hand-made label states through `Communities.step`, and end to end against [[Oracle.lpa]]. No
  * specific fixture outcome that depends on hashed researcher-ID order is encoded.
  */
class SparkLpaTieSpec extends SparkSuite:

  private val labelSchema = StructType(
    Seq(
      StructField(Windows.Start, IntegerType, nullable = false),
      StructField(Windows.End, IntegerType, nullable = false),
      StructField("node", StringType, nullable = false),
      StructField("label", StringType, nullable = false)
    )
  )
  private val edgeSchema = StructType(
    Seq(
      StructField(Windows.Start, IntegerType, nullable = false),
      StructField(Windows.End, IntegerType, nullable = false),
      StructField("a", StringType, nullable = false),
      StructField("b", StringType, nullable = false)
    )
  )

  /** labels: node -> label in window (2010, 2015) unless a window is given. */
  private def labels(ls: (String, String)*): DataFrame = labelsIn((2010, 2015))(ls*)

  private def labelsIn(w: (Int, Int))(ls: (String, String)*): DataFrame =
    spark.createDataFrame(
      Arrays.asList(ls.map((n, l) => Row(w._1, w._2, n, l))*),
      labelSchema
    )

  /** Undirected edges as the directed list `Iterative.directed` produces (both orientations). */
  private def edges(es: (String, String)*): DataFrame = edgesIn((2010, 2015))(es*)

  private def edgesIn(w: (Int, Int))(es: (String, String)*): DataFrame =
    spark.createDataFrame(
      Arrays.asList(es.flatMap((a, b) => Seq(Row(w._1, w._2, a, b), Row(w._1, w._2, b, a)))*),
      edgeSchema
    )

  private def next(directed: DataFrame, ls: DataFrame): Map[String, (String, Boolean)] =
    Communities
      .step(directed, ls)
      .collect()
      .map(r => r.getAs[String]("node") -> (r.getAs[String]("label"), r.getAs[Boolean]("changed")))
      .toMap

  test("case 11: all votes tied at 1: the smallest label wins (own label included)") {
    val n = next(
      edges("v" -> "x", "v" -> "y"),
      labels("v" -> "z", "x" -> "m", "y" -> "b")
    )
    assert(n("v") == ("b", true))
    // x sees {own m, v's z}: tie -> m (unchanged); y sees {own b, z} -> b.
    assert(n("x") == ("m", false))
    assert(n("y") == ("b", false))
  }

  test("case 11: more votes beat a smaller label") {
    val n = next(
      edges("v" -> "p", "v" -> "q", "v" -> "r"),
      labels("v" -> "w", "p" -> "x", "q" -> "x", "r" -> "a")
    )
    assert(n("v")._1 == "x")
  }

  test("case 11: a tie between two labels with 2 votes each goes to the smaller one") {
    val n = next(
      edges("v" -> "p", "v" -> "q", "v" -> "r", "v" -> "s"),
      labels("v" -> "z", "p" -> "k", "q" -> "k", "r" -> "c", "s" -> "c")
    )
    assert(n("v")._1 == "c")
  }

  test("case 11: the node's own label counts as one vote") {
    // own "c" + neighbour "c" = 2 votes beat the smaller "a" and "b" with 1 vote each.
    val n = next(
      edges("v" -> "p", "v" -> "q", "v" -> "r"),
      labels("v" -> "c", "p" -> "c", "q" -> "a", "r" -> "b")
    )
    assert(n("v") == ("c", false))
  }

  test("case 11: ties use ordinal string order, not numeric or case-insensitive order") {
    val n = next(
      edges("v" -> "p", "u" -> "q"),
      labels("v" -> "researcher:9", "p" -> "researcher:10", "u" -> "a", "q" -> "B")
    )
    assert(n("v")._1 == "researcher:10")
    assert(n("u")._1 == "B")
  }

  test("case 11: updates are synchronous (every node reads the previous labels only)") {
    // Path x - y - z with labels 5, 4, 3. Synchronous: x -> 4, y -> 3, z -> 3. An in-place
    // (asynchronous) update in the order x, y, z would give y = 4 (own 4 + x's new 4).
    val n = next(edges("x" -> "y", "y" -> "z"), labels("x" -> "5", "y" -> "4", "z" -> "3"))
    assert(n("x")._1 == "4" && n("y")._1 == "3" && n("z")._1 == "3")
  }

  test("case 11: windows never exchange votes") {
    val ls = labelsIn((2010, 2015))("v" -> "z", "p" -> "q")
      .union(labelsIn((2016, 2020))("v" -> "z", "p" -> "a"))
    val es = edgesIn((2010, 2015))("v" -> "p")
    val r = Communities
      .step(es, ls)
      .collect()
      .map(r => (r.getInt(0), r.getAs[String]("node")) -> r.getAs[String]("label"))
      .toMap
    assert(r((2010, "v")) == "q")
    // In 2016-2020 v has no edge: it keeps its own label, never the other window's "a".
    assert(r((2016, "v")) == "z")
  }

  test("case 11: end to end, LPA equals the reference rule on tie-heavy shapes; weights ignored") {
    // A 6-cycle, a 4-clique sharing one node with a star, and a pair written 5 times.
    val works = Vector(
      Work("C-1", Some(2012), Vector("Cy1", "Cy2")),
      Work("C-2", Some(2012), Vector("Cy2", "Cy3")),
      Work("C-3", Some(2012), Vector("Cy3", "Cy4")),
      Work("C-4", Some(2012), Vector("Cy4", "Cy5")),
      Work("C-5", Some(2012), Vector("Cy5", "Cy6")),
      Work("C-6", Some(2012), Vector("Cy6", "Cy1")),
      Work("Q-1", Some(2013), Vector("Qa", "Qb", "Qc", "Qd")),
      Work("S-1", Some(2014), Vector("Qd", "St1")),
      Work("S-2", Some(2014), Vector("Qd", "St2")),
      Work("S-3", Some(2014), Vector("Qd", "St3")),
      Work("S-4", Some(2014), Vector("St3", "St4"))
    ) ++ (1 to 5).map(i => Work(s"H-$i", Some(2011), Vector("Heavy1", "Heavy2"))) ++
      Vector(Work("H-L", Some(2011), Vector("Heavy2", "Light")))
    val out = build(tables(works))
    val an = analytics(out)
    val errs = Oracle.compare(works, ThreeWindows, out, Some(an), 40)
    assert(errs.isEmpty, errs.mkString("\n"))
    ThreeWindows.foreach { w =>
      val (_, conv) = Oracle.lpa(Oracle.graph(works, w), 40)
      val rep = an.communityRun.find(g => g.group == Vector[Any](w.startYear, w.endYear)).get
      assert(rep.converged == conv, s"$w converged flag")
    }
  }
