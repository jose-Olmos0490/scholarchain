package scholarchain.adversarial.spark

import org.apache.spark.sql.Row
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.config.BenchmarkPair
import scholarchain.spark.AnalysisWindow
import scholarchain.spark.CoreRecords.Kind
import scholarchain.spark.graph.Paths

/** Case 10: a path with no route, a path exactly at maxPathDepth, and a path one hop beyond it,
  * in both directions and in windows that cut the chain. Expectations come from [[Oracle.path]]
  * (exhaustive enumeration of shortest paths, lexicographic minimum).
  */
class SparkPathsAdvSpec extends SparkSuite:

  // Chain N0 - N1 - ... - N7 (7 hops), with a parallel detour N1 - Mx - N3 (equal length), a
  // weight-2 hop N3 - N4, and a separate component Z1 - Z2.
  private val works = Vector(
    Work("PA-01", Some(2011), Vector("N0", "N1")),
    Work("PA-12", Some(2011), Vector("N1", "N2")),
    Work("PA-23", Some(2012), Vector("N2", "N3")),
    Work("PA-1M", Some(2012), Vector("N1", "Mx")),
    Work("PA-M3", Some(2012), Vector("Mx", "N3")),
    Work("PA-34a", Some(2013), Vector("N3", "N4")),
    Work("PA-34b", Some(2013), Vector("N4", "N3")),
    Work("PA-45", Some(2014), Vector("N4", "N5")),
    Work("PA-56", Some(2016), Vector("N5", "N6")),
    Work("PA-67", Some(2017), Vector("N6", "N7")),
    Work("PA-Z", Some(2012), Vector("Z1", "Z2"))
  )
  private val bench = Vector(
    BenchmarkPair(rid("N0"), rid("N7")),
    BenchmarkPair(rid("N7"), rid("N0")),
    BenchmarkPair(rid("N0"), rid("N6")),
    BenchmarkPair(rid("N0"), rid("Z1")),
    BenchmarkPair(rid("N0"), rid("Ghost Researcher"))
  )
  private val out = memo(build(tables(works)))

  /** Evidence rows and contract violations of one shortest-path run (evaluated once). */
  final private case class Result(rows: Vector[Row], violations: Vector[String])

  private def run(b: Vector[BenchmarkPair], maxDepth: Int): Result =
    val qs = Paths.queries(out().components, params(), b, 3, 23L)
    val ev = Paths
      .shortestPaths(
        spark,
        qs,
        out().edges,
        out().edgeSupport,
        out().components,
        params(),
        maxDepth
      )
      .evidence
    Result(ev.collect().toVector, violations(ev, Kind.Path))

  private val depth7 = memo(run(bench, 7))
  private val depth6 = memo(run(bench, 6))

  private def row(res: Result, w: AnalysisWindow, s: String, t: String): Row =
    res.rows
      .find(r =>
        long(r, "windowStartYear") == w.startYear && long(r, "windowEndYear") == w.endYear &&
          str(r, "sourceResearcherId") == rid(s) && str(r, "targetResearcherId") == t
      )
      .getOrElse(fail(s"no path row $s -> $t in $w"))

  private def check(maxDepth: Int, res: Result): Vector[String] =
    val errs = Vector.newBuilder[String]
    if res.rows.size != bench.size * ThreeWindows.size then
      errs += s"expected ${bench.size * ThreeWindows.size} path rows, got ${res.rows.size}"
    ThreeWindows.foreach { w =>
      val g = Oracle.graph(works, w)
      bench.foreach { b =>
        val r = res.rows
          .find(x =>
            long(x, "windowStartYear") == w.startYear && long(x, "windowEndYear") == w.endYear &&
              str(x, "sourceResearcherId") == b.sourceResearcherId &&
              str(x, "targetResearcherId") == b.targetResearcherId
          )
          .getOrElse(fail(s"no row for $b in $w"))
        val st = str(r, "status")
        val hops = r.getSeq[Row](r.fieldIndex("hops")).toVector
        if long(r, "maxDepth") != maxDepth then errs += s"$w $b maxDepth not recorded"
        Oracle.path(g, b.sourceResearcherId, b.targetResearcherId, maxDepth) match
          case Oracle.PathExpect.Found(nodes) =>
            val seq = hops.headOption.map(str(_, "fromResearcherId")).toVector ++
              hops.map(str(_, "toResearcherId"))
            if st != "FOUND" then errs += s"$w $b: expected FOUND, got $st"
            else if seq != nodes || long(r, "length") != nodes.size - 1 then
              errs += s"$w $b: expected nodes $nodes, got $seq"
          case Oracle.PathExpect.NotFoundWithinDepth =>
            if st != "NOT_FOUND_WITHIN_DEPTH" then errs += s"$w $b: expected NOT_FOUND, got $st"
          case Oracle.PathExpect.NoPath =>
            if st != "NO_PATH" then errs += s"$w $b: expected NO_PATH, got $st"
        if st != "FOUND" && (!r.isNullAt(r.fieldIndex("length")) || hops.nonEmpty) then
          errs += s"$w $b: a $st row must have no length and no hops"
      }
    }
    if res.violations.nonEmpty then
      errs += s"path rows violate the core contract: ${res.violations.take(3)}"
    errs.result()

  test("case 10: maxDepth = 7: the 7-hop chain is FOUND (length == maxDepth), both directions") {
    val errs = check(7, depth7())
    assert(errs.isEmpty, errs.mkString("\n"))
    val fwd = row(depth7(), AllYears, "N0", rid("N7"))
    val back = row(depth7(), AllYears, "N7", rid("N0"))
    assert(str(fwd, "status") == "FOUND" && long(fwd, "length") == 7L)
    assert(str(back, "status") == "FOUND" && long(back, "length") == 7L)
    // The weight-2 hop carries both supporting works.
    val h34 = fwd
      .getSeq[Row](fwd.fieldIndex("hops"))
      .find(h =>
        Set(str(h, "fromResearcherId"), str(h, "toResearcherId")) == Set(rid("N3"), rid("N4"))
      )
      .get
    assert(long(h34, "jointPublicationCount") == 2L)
    assert(strs(h34, "supportingWorkIds").size == 2)
  }

  test("case 10: maxDepth = 6: the 7-hop chain is NOT_FOUND_WITHIN_DEPTH; 6 hops still FOUND") {
    val errs = check(6, depth6())
    assert(errs.isEmpty, errs.mkString("\n"))
    assert(str(row(depth6(), AllYears, "N0", rid("N7")), "status") == "NOT_FOUND_WITHIN_DEPTH")
    assert(str(row(depth6(), AllYears, "N7", rid("N0")), "status") == "NOT_FOUND_WITHIN_DEPTH")
    val six = row(depth6(), AllYears, "N0", rid("N6"))
    assert(str(six, "status") == "FOUND" && long(six, "length") == 6L)
  }

  test("case 10: no route: other component, unknown researcher, endpoints cut by the window") {
    val r = depth7()
    assert(str(row(r, AllYears, "N0", rid("Z1")), "status") == "NO_PATH")
    assert(str(row(r, AllYears, "N0", rid("Ghost Researcher")), "status") == "NO_PATH")
    // 2010-2015 has N0..N5 only: N6 and N7 are absent.
    assert(str(row(r, W2010to2015, "N0", rid("N7")), "status") == "NO_PATH")
    assert(str(row(r, W2010to2015, "N0", rid("N6")), "status") == "NO_PATH")
    // 2016-2020 has N5..N7 only: N0 is absent.
    assert(str(row(r, W2016to2020, "N7", rid("N0")), "status") == "NO_PATH")
  }

  test("case 10: maxDepth = 1 with a 1-hop and a 2-hop query") {
    val r = run(Vector(BenchmarkPair(rid("N0"), rid("N1")), BenchmarkPair(rid("N0"), rid("N2"))), 1)
    assert(str(row(r, AllYears, "N0", rid("N1")), "status") == "FOUND")
    assert(str(row(r, AllYears, "N0", rid("N2")), "status") == "NOT_FOUND_WITHIN_DEPTH")
    assert(r.violations.isEmpty)
  }
