package scholarchain.spark

import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.*
import scholarchain.spark.graph.{Communities, KCore, WindowSummaries}

/** Label-propagation communities, cross-community edges, bridge measures and k-core on the work
  * order's hand-checkable fixture (W1 2010: A,B,C | W2 2012: A,B | W3 2015: C,D | W4 2016: D,E,F |
  * W5 2018: E,F | W6 2019: G,H | W7 2020: I).
  *
  * Label propagation breaks ties on the smallest label, so its result depends on how the
  * researcher IDs sort. Two fixtures are checked, each against a hand derivation:
  *   - ORDERED keys (IDs sort A < B < ... < I, the order the work-order oracle assumes): the
  *     work-order expectation {A,B,C}, {D,E,F}, {G,H} (+ {I}) and its bridge values.
  *   - DEFAULT keys "A".."I" (IDs sort C < E < G < H < I < F < D < A < B): see the derivation
  *     in the test; the result is {A,B,C,D}, {E,F}, {G,H}, {I}.
  */
class CommunitySpec extends AnyFunSuite:
  private val names = Vector("A", "B", "C", "D", "E", "F", "G", "H", "I")

  final private class Run(keys: String => String, works: Vector[DblpWork]):
    val nameOf: Map[String, String] = names.map(n => researcher(keys(n)) -> n).toMap
    lazy val out = build(tables(works))
    lazy val an = analytics(out)
    def byName(df: org.apache.spark.sql.DataFrame, w: AnalysisWindow): Map[String, Row] =
      inWindow(df, w).map(r => nameOf(r.getAs[String]("researcherId")) -> r).toMap
    def groups(w: AnalysisWindow): Set[Set[String]] =
      inWindow(an.communities, w)
        .groupBy(_.getAs[String]("communityId"))
        .values
        .map(_.map(r => nameOf(r.getAs[String]("researcherId"))).toSet)
        .toSet
    def iterations(w: AnalysisWindow) =
      an.communityRun.find(g => g.group == Vector[Any](w.startYear, w.endYear)).get

  private val ordered = Run(OrderedKeys, HandCheckableOrdered)
  private val default = Run(Predef.identity, HandCheckable)

  test("ordered fixture keys really sort A < B < ... < I; default keys sort C,E,G,H,I,F,D,A,B") {
    assert(
      names
        .map(n => researcher(OrderedKeys(n))) == names.map(n => researcher(OrderedKeys(n))).sorted
    )
    assert(names.sortBy(researcher) == Vector("C", "E", "G", "H", "I", "F", "D", "A", "B"))
  }

  test("ordered IDs: communities {A,B,C}, {D,E,F}, {G,H}, {I}; converged (3 iterations)") {
    assert(
      ordered
        .groups(AllYears) == Set(Set("A", "B", "C"), Set("D", "E", "F"), Set("G", "H"), Set("I"))
    )
    val it = ordered.iterations(AllYears)
    assert(it.converged && it.iterations == 3)
    val sizes = ordered
      .byName(ordered.an.communities, AllYears)
      .view
      .mapValues(_.getAs[Long]("communitySize"))
      .toMap
    assert(
      sizes == Map(
        "A" -> 3L,
        "B" -> 3L,
        "C" -> 3L,
        "D" -> 3L,
        "E" -> 3L,
        "F" -> 3L,
        "G" -> 2L,
        "H" -> 2L,
        "I" -> 1L
      )
    )
  }

  test(
    "ordered IDs: crossCommunityDegree C=1 D=1 others 0; communitiesConnected C=2 D=2 others 1"
  ) {
    val b = ordered.byName(ordered.an.bridges, AllYears)
    val cross = b.view.mapValues(_.getAs[Long]("crossCommunityDegree")).toMap
    assert(cross == names.map(n => n -> (if n == "C" || n == "D" then 1L else 0L)).toMap)
    // communitiesConnected counts the researcher's own community (I, with no neighbour, has 1).
    val connected = b.view.mapValues(_.getAs[Long]("communitiesConnected")).toMap
    assert(connected == names.map(n => n -> (if n == "C" || n == "D" then 2L else 1L)).toMap)
    val degree = b.view.mapValues(_.getAs[Long]("degree")).toMap
    assert(
      degree == Map(
        "A" -> 2L,
        "B" -> 2L,
        "C" -> 3L,
        "D" -> 3L,
        "E" -> 2L,
        "F" -> 2L,
        "G" -> 1L,
        "H" -> 1L,
        "I" -> 0L
      )
    )
  }

  test("ordered IDs: participationCoefficient C = D = 4/9, every other researcher 0") {
    val pc = ordered
      .byName(ordered.an.bridges, AllYears)
      .view
      .mapValues(_.getAs[Double]("participationCoefficient"))
      .toMap
    for n <- Vector("C", "D") do assert(math.abs(pc(n) - 4.0 / 9.0) < 1e-12, n)
    for n <- Vector("A", "B", "E", "F", "G", "H", "I") do assert(pc(n) == 0.0, n)
    // No betweenness was computed (human scope decision): the optional fields stay unset.
    assert(
      ordered.an.bridges
        .where("betweenness is not null or betweennessMethod is not null")
        .count() == 0
    )
  }

  test("ordered IDs: the only cross-community edge is C-D") {
    val e = inWindow(ordered.an.crossEdges, AllYears).map(r =>
      Vector(
        ordered.nameOf(r.getAs[String]("researcherA")),
        ordered.nameOf(r.getAs[String]("researcherB"))
      ).sorted.mkString
    )
    assert(e == Vector("CD"))
  }

  test("default IDs: hand-derived LPA result {A,B,C,D}, {E,F}, {G,H}, {I}, converged in 2") {
    // Iteration 1: every vote count is 1, so each node takes the smallest label of its closed
    // neighbourhood: A,B,C,D -> C; E,F -> E; G,H -> G; I -> I. Iteration 2: D sees c (own), c (C),
    // e (E), e (F): a 2-2 tie, broken to the smaller label c; nothing changes, so it converges.
    assert(
      default
        .groups(AllYears) == Set(Set("A", "B", "C", "D"), Set("E", "F"), Set("G", "H"), Set("I"))
    )
    val it = default.iterations(AllYears)
    assert(it.converged && it.iterations == 2)
    val b = default.byName(default.an.bridges, AllYears)
    def v(n: String) =
      (
        b(n).getAs[Long]("crossCommunityDegree"),
        b(n).getAs[Long]("communitiesConnected"),
        b(n).getAs[Double]("participationCoefficient")
      )
    assert(v("D")._1 == 2L && v("D")._2 == 2L && math.abs(v("D")._3 - 4.0 / 9.0) < 1e-12)
    for n <- Vector("E", "F") do assert(v(n) == (1L, 2L, 0.5), n)
    for n <- Vector("A", "B", "C", "G", "H", "I") do assert(v(n) == (0L, 1L, 0.0), n)
  }

  test("coreness A..F = 2, G,H = 1, I = 0 (both key orders); converged") {
    for run <- Vector(ordered, default) do
      val c = run.byName(run.an.coreness, AllYears).view.mapValues(_.getAs[Int]("coreness")).toMap
      assert(c == names.map(n => n -> (if n < "G" then 2 else if n == "I" then 0 else 1)).toMap)
      val g =
        run.an.kcore.groups.find(_.group == Vector[Any](AllYears.startYear, AllYears.endYear)).get
      assert(g.converged && run.an.kcore.unpeeled.isEmpty)
  }

  test("coreness in window 2010-2015 (A,B,C triangle plus pendant D): A,B,C = 2, D = 1") {
    val c = default
      .byName(default.an.coreness, W2010to2015)
      .view
      .mapValues(_.getAs[Int]("coreness"))
      .toMap
    assert(c == Map("A" -> 2, "B" -> 2, "C" -> 2, "D" -> 1))
  }

  test("k-core stopped by maxIterations: converged=false and unpeeled nodes get no coreness row") {
    val an = analytics(default.out, analyticsParams(kcoreMaxIterations = 1))
    val g = an.kcore.groups.find(_.group == Vector[Any](AllYears.startYear, AllYears.endYear)).get
    assert(!g.converged && g.iterations == 1)
    // Round 1 removes only I (min degree 0); the other 8 stay unpeeled.
    assert(
      inWindow(an.coreness, AllYears).map(r =>
        default.nameOf(r.getAs[String]("researcherId"))
      ) == Vector("I")
    )
    assert(an.kcore.unpeeled((AllYears.startYear, AllYears.endYear)) == 8L)
  }

  test("LPA stopped by maxIterations: converged=false is reported") {
    val an = analytics(ordered.out, analyticsParams(communityMaxIterations = 1))
    val g = an.communityRun.find(_.group == Vector[Any](AllYears.startYear, AllYears.endYear)).get
    assert(!g.converged && g.iterations == 1)
  }

  test("every PART B community/bridge/k-core row satisfies its core contract") {
    for run <- Vector(ordered, default) do
      assert(violations(run.an.communities, CoreRecords.Kind.Community).isEmpty)
      assert(violations(run.an.crossEdges, CoreRecords.Kind.CrossEdge).isEmpty)
      assert(violations(run.an.bridges, CoreRecords.Kind.Bridge).isEmpty)
      assert(violations(run.an.coreness, CoreRecords.Kind.Coreness).isEmpty)
      assert(violations(run.an.metrics, CoreRecords.Kind.Metrics).isEmpty)
  }

  test("researcher metrics carry communityId and coreness; no single bridge score is set") {
    val m = ordered.byName(ordered.an.metrics, AllYears)
    val comm = ordered.byName(ordered.an.communities, AllYears)
    for n <- names do
      assert(m(n).getAs[String]("communityId") == comm(n).getAs[String]("communityId"), n)
      assert(
        m(n).getAs[String]("bridgeMetric") == null && m(n).isNullAt(m(n).fieldIndex("bridgeScore"))
      )
    assert(m("C").getAs[Int]("coreness") == 2 && m("I").getAs[Int]("coreness") == 0)
  }

  test(
    "window summary: community count and size histogram, max coreness, three iteration reports"
  ) {
    val s = WindowSummaries.compute(
      ordered.out,
      params(),
      30,
      Some(WindowSummaries.PartB(ordered.an, 20, 30))
    )
    val all = s.find(_.allYearsWindow).get
    assert(all.communityCount == Some(4L))
    assert(
      all.communitySizeHistogram.map(h => (h.size, h.count)) == Vector((1L, 1L), (2L, 1L), (3L, 2L))
    )
    assert(all.maxCoreness == Some(2))
    assert(
      all.iterations.map(_.algorithm) == Vector(
        WindowSummaries.ComponentsAlgorithm,
        Communities.Algorithm,
        KCore.Algorithm
      )
    )
    s.foreach(x => assert(scholarchain.core.json.WireCodec.validate(x).isRight, x))
  }
