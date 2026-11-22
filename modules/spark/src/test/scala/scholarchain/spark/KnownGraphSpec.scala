package scholarchain.spark

import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.*
import scholarchain.spark.graph.{GraphBuilder, WindowSummaries}

/** The work order's hand-checkable fixture (PART A expectations).
  *
  * Works (year: authors): W1 2010: A,B,C | W2 2012: A,B | W3 2015: C,D | W4 2016: D,E,F |
  * W5 2018: E,F | W6 2019: G,H | W7 2020: I.
  */
class KnownGraphSpec extends AnyFunSuite:
  private val names = Vector("A", "B", "C", "D", "E", "F", "G", "H", "I")
  private val nameOf: Map[String, String] = names.map(n => researcher(n) -> n).toMap
  private val work: Map[String, String] = HandCheckable.map(w => w.workId -> w.name).toMap
  private lazy val out = build(tables(HandCheckable))

  private def edgesOf(w: AnalysisWindow): Map[String, (Long, Int, Int)] =
    inWindow(out.edges, w).map { r =>
      val pair = Vector(
        nameOf(r.getAs[String]("researcherA")),
        nameOf(r.getAs[String]("researcherB"))
      ).sorted.mkString
      pair -> (
        r.getAs[Long]("jointPublicationCount"),
        r.getAs[Int]("firstCollaborationYear"),
        r.getAs[Int]("lastCollaborationYear")
      )
    }.toMap

  private def metricsOf(w: AnalysisWindow): Map[String, Row] =
    inWindow(out.metrics, w).map(r => nameOf(r.getAs[String]("researcherId")) -> r).toMap

  private def pTACMW(r: Row): (Long, Long, Long, Double, Long, Vector[String], Double) =
    (
      r.getAs[Long]("publicationCount"),
      r.getAs[Long]("distinctCoauthorCount"),
      r.getAs[Long]("totalCoauthorOccurrences"),
      r.getAs[Double]("averageCoauthorsPerPaper"),
      r.getAs[Long]("maximumCoauthorsOnOnePaper"),
      r.getSeq[String](r.fieldIndex("maximumTeamWorkIds")).toVector.map(work),
      r.getAs[Double]("weightedDegree")
    )

  test("all-years window: exactly the 8 expected edges with weights and first/last years") {
    assert(
      edgesOf(AllYears) == Map(
        "AB" -> (2L, 2010, 2012),
        "AC" -> (1L, 2010, 2010),
        "BC" -> (1L, 2010, 2010),
        "CD" -> (1L, 2015, 2015),
        "DE" -> (1L, 2016, 2016),
        "DF" -> (1L, 2016, 2016),
        "EF" -> (2L, 2016, 2018),
        "GH" -> (1L, 2019, 2019)
      )
    )
  }

  test("all-years window: P, C, T, A, M, maximumTeamWorkIds and W per researcher") {
    val m = metricsOf(AllYears)
    assert(m.keySet == names.toSet)
    for n <- Vector("A", "B") do assert(pTACMW(m(n)) == (2L, 2L, 3L, 1.5, 2L, Vector("W1"), 3.0), n)
    assert(pTACMW(m("C")) == (2L, 3L, 3L, 1.5, 2L, Vector("W1"), 3.0))
    assert(pTACMW(m("D")) == (2L, 3L, 3L, 1.5, 2L, Vector("W4"), 3.0))
    for n <- Vector("E", "F") do assert(pTACMW(m(n)) == (2L, 2L, 3L, 1.5, 2L, Vector("W4"), 3.0), n)
    for n <- Vector("G", "H") do assert(pTACMW(m(n)) == (1L, 1L, 1L, 1.0, 1L, Vector("W6"), 1.0), n)
    assert(pTACMW(m("I")) == (1L, 0L, 0L, 0.0, 0L, Vector("W7"), 0.0))
  }

  test("W_r == T_r holds for every researcher in every window (no oversize or year-less works)") {
    val statuses = out.wtInvariant.select("status").distinct().collect().map(_.getString(0)).toSet
    assert(statuses == Set(GraphBuilder.WtEqual))
  }

  test("components: {A..F} size 6, {G,H} size 2, {I} size 1; componentId is the smallest member") {
    val rows = inWindow(out.components, AllYears)
    val groups = rows
      .groupBy(_.getAs[String]("componentId"))
      .map { (cid, rs) =>
        val members = rs.map(r => r.getAs[String]("researcherId")).toSet
        assert(cid == members.min, "componentId must be the smallest researcher ID")
        rs.foreach(r => assert(r.getAs[Long]("componentSize") == members.size.toLong))
        members.map(nameOf)
      }
      .toSet
    assert(groups == Set(Set("A", "B", "C", "D", "E", "F"), Set("G", "H"), Set("I")))
  }

  test("window summary: largest-component fraction 6/9, one singleton, converged") {
    val s = WindowSummaries.compute(out, params(), 30)
    val all = s.find(_.allYearsWindow).get
    assert(all.researcherCount == 9 && all.componentCount == 3)
    assert(all.largestComponentSize.toDouble / all.researcherCount == 6.0 / 9.0)
    assert(all.singletonComponentCount == 1 && all.coauthorEdgeCount == 8 && all.workCount == 7)
    assert(all.iterations.head.converged && all.iterations.head.iterations >= 1)
    s.foreach(x => assert(WireCodec.validate(x).isRight, x))
  }

  test("window 2010-2015: edges AB, AC, BC, CD only; D has P1 C1; E..I have no row") {
    assert(
      edgesOf(W2010to2015) == Map(
        "AB" -> (2L, 2010, 2012),
        "AC" -> (1L, 2010, 2010),
        "BC" -> (1L, 2010, 2010),
        "CD" -> (1L, 2015, 2015)
      )
    )
    val m = metricsOf(W2010to2015)
    assert(m.keySet == Set("A", "B", "C", "D"))
    assert(pTACMW(m("D")) == (1L, 1L, 1L, 1.0, 1L, Vector("W3"), 1.0))
    assert(pTACMW(m("C")) == (2L, 3L, 3L, 1.5, 2L, Vector("W1"), 3.0))
    assert(pTACMW(m("A")) == (2L, 2L, 3L, 1.5, 2L, Vector("W1"), 3.0))
    val comps =
      inWindow(out.components, W2010to2015).map(r => nameOf(r.getAs[String]("researcherId")))
    assert(comps.toSet == Set("A", "B", "C", "D"))
    inWindow(out.components, W2010to2015).foreach(r => assert(r.getAs[Long]("componentSize") == 4L))
  }

  test("edge support table lists every supporting work; representative works are capped at K") {
    val ab = researcher("A") -> researcher("B")
    val support = inWindow(out.edgeSupport, AllYears)
      .filter(r => r.getAs[String]("researcherA") == Vector(ab._1, ab._2).min)
      .filter(r => r.getAs[String]("researcherB") == Vector(ab._1, ab._2).max)
      .map(r => work(r.getAs[String]("workId")))
    assert(support.sorted == Vector("W1", "W2"))
    inWindow(out.edges, AllYears).foreach { r =>
      val reps = r.getSeq[String](r.fieldIndex("representativeWorkIds"))
      assert(reps.size == math.min(2L, r.getAs[Long]("jointPublicationCount")))
      assert(
        r.getAs[String]("supportingWorkIdsRef") == GraphBuilder.supportRef(
          r.getAs[String]("edgeId")
        )
      )
    }
  }

  test("every output row satisfies its core contract and carries run and window fields") {
    import CoreRecords.Kind
    assert(violations(out.edges, Kind.Edge).isEmpty)
    assert(violations(out.edgeSupport, Kind.EdgeSupport).isEmpty)
    assert(violations(out.metrics, Kind.Metrics).isEmpty)
    assert(violations(out.components, Kind.Component).isEmpty)
    assert(violations(out.researchers, Kind.Researcher).isEmpty)
    assert(violations(out.authored, Kind.Authorship).isEmpty)
    for df <- Vector(
        out.edges,
        out.edgeSupport,
        out.metrics,
        out.components,
        out.researchers,
        out.authored,
        out.works,
        out.wtInvariant,
        out.rejects
      )
    do
      for c <- Vector("runId", "windowStartYear", "windowEndYear", "schemaVersion") do
        assert(df.columns.contains(c), s"missing $c in ${df.columns.mkString(",")}")
    assert(out.rejects.count() == 0)
  }

  test("researchers: 9 fallback identities, no merge without an ACCEPTED link") {
    val rs = out.researchers.collect().toVector
    assert(rs.map(_.getAs[String]("researcherId")).toSet == names.map(researcher).toSet)
    assert(rs.forall(_.getAs[String]("idSource") == "SCHOLARCHAIN_FALLBACK"))
    assert(rs.forall(_.getAs[String]("identityStatus") == "UNLINKED"))
  }
