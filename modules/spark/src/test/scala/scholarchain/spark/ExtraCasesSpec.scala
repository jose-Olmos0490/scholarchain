package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{CoauthorPairCandidate, SourceType}
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.*
import scholarchain.spark.graph.{GraphBuilder, WindowSummaries}

/** The work order's extra cases: self-pair, duplicate pair rows, a 50-author paper, the empty
  * graph, oversize-team works set aside by Hadoop, plus year-less works and a detected invariant
  * failure.
  */
class ExtraCasesSpec extends AnyFunSuite:

  private def metric(out: graph.GraphOutputs, key: String, w: AnalysisWindow = AllYears): Row =
    inWindow(out.metrics, w).find(_.getAs[String]("researcherId") == researcher(key)).get

  private def wt(out: graph.GraphOutputs, key: String, w: AnalysisWindow = AllYears): String =
    inWindow(out.wtInvariant, w)
      .find(_.getAs[String]("researcherId") == researcher(key))
      .get
      .getAs[String]("status")

  test("self-pair: two mentions resolving to one researcher drop their pair, paper counted once") {
    // DBLP key A appears twice on S (Hadoop would deduplicate; the builder must still be safe).
    val out = build(tables(Vector(DblpWork("S", Some(2011), Vector("A", "A", "B")))))
    val all = WindowSummaries.compute(out, params(), 30).find(_.allYearsWindow).get
    assert(all.selfPairsDropped == 1)
    assert(out.selfPairs.count() == 2) // the one self-pair row, in both windows containing 2011
    val edges = inWindow(out.edges, AllYears)
    assert(edges.size == 1)
    assert(edges.head.getAs[Long]("jointPublicationCount") == 1L) // two mention pairs, one work
    val a = metric(out, "A")
    assert(a.getAs[Long]("publicationCount") == 1L)
    assert(a.getAs[Long]("totalCoauthorOccurrences") == 1L) // n_p counts DISTINCT researchers
    assert(a.getAs[Long]("distinctCoauthorCount") == 1L)
    assert(a.getAs[Double]("weightedDegree") == 1.0)
    assert(wt(out, "A") == GraphBuilder.WtEqual)
  }

  test("duplicate pair rows of one work count once in jointPublicationCount") {
    val ws = HandCheckable
    val w2 = ws.find(_.name == "W2").get
    val dup = WireCodec.encode(
      ok(
        CoauthorPairCandidate.fromPositions(
          w2.workId,
          1,
          2,
          2012,
          2,
          prov(SourceType.DBLP, "conf/fx/W2")
        )
      )
    )
    val out = build(tables(ws, extraPairs = Vector(dup, dup)))
    val ab = inWindow(out.edges, AllYears)
      .find(r =>
        Set(r.getAs[String]("researcherA"), r.getAs[String]("researcherB")) ==
          Set(researcher("A"), researcher("B"))
      )
      .get
    assert(ab.getAs[Long]("jointPublicationCount") == 2L)
    assert(metric(out, "A").getAs[Double]("weightedDegree") == 3.0)
    assert(wt(out, "A") == GraphBuilder.WtEqual)
  }

  test("a 50-author paper yields 1225 edges and M = 49 for every author") {
    val keys = (1 to 50).map(i => f"K$i%02d").toVector
    val out = build(tables(Vector(DblpWork("BIG", Some(2014), keys))))
    assert(inWindow(out.edges, AllYears).size == 1225)
    val ms = inWindow(out.metrics, AllYears)
    assert(ms.size == 50)
    ms.foreach { r =>
      assert(r.getAs[Long]("maximumCoauthorsOnOnePaper") == 49L)
      assert(r.getAs[Long]("distinctCoauthorCount") == 49L)
      assert(r.getAs[Long]("totalCoauthorOccurrences") == 49L)
      assert(r.getAs[Double]("weightedDegree") == 49.0)
      assert(r.getAs[Long]("componentSize") == 50L)
    }
  }

  test("empty graph: no rows, zero summaries, converged components") {
    val out = build(tables(Vector.empty))
    assert(out.edges.count() == 0 && out.metrics.count() == 0 && out.components.count() == 0)
    assert(out.researchers.count() == 0 && out.rejects.count() == 0)
    val s = WindowSummaries.compute(out, params(), 30)
    assert(s.size == 2)
    s.foreach { x =>
      assert(x.researcherCount == 0 && x.componentCount == 0 && x.largestComponentSize == 0)
      assert(x.iterations.head.converged)
      assert(WireCodec.validate(x).isRight)
    }
  }

  test("oversize-team work: counted in P/T/M, no edges, W_r/T_r exception (not a failure)") {
    val out = build(
      tables(
        Vector(
          DblpWork("O", Some(2013), Vector("P", "Q", "R"), oversize = true),
          DblpWork("N", Some(2013), Vector("P", "Z"))
        )
      )
    )
    val p = metric(out, "P")
    assert(p.getAs[Long]("publicationCount") == 2L)
    assert(p.getAs[Long]("totalCoauthorOccurrences") == 3L)
    assert(p.getAs[Long]("maximumCoauthorsOnOnePaper") == 2L)
    assert(p.getAs[Long]("distinctCoauthorCount") == 1L) // only Z: no pairs exist for O
    assert(p.getAs[Double]("weightedDegree") == 1.0)
    assert(wt(out, "P") == GraphBuilder.WtExceptionOversize)
    assert(wt(out, "Q") == GraphBuilder.WtExceptionOversize)
    assert(wt(out, "Z") == GraphBuilder.WtEqual)
    assert(out.wtInvariant.where(col("status") === GraphBuilder.WtFailure).count() == 0)
    val all = WindowSummaries.compute(out, params(), 30).find(_.allYearsWindow).get
    assert(all.oversizeTeamWorkCount == 1)
    // Q and R have no edge, so they are singleton components.
    assert(all.singletonComponentCount == 2)
  }

  test("year-less work: only in the all-years window, counted, W_r/T_r exception") {
    val out = build(
      tables(
        Vector(DblpWork("Y", None, Vector("U", "V")), DblpWork("X", Some(2011), Vector("U", "T")))
      )
    )
    val s = WindowSummaries.compute(out, params(), 30)
    val all = s.find(_.allYearsWindow).get
    val w = s.find(!_.allYearsWindow).get
    assert(all.workCount == 2 && all.yearlessWorkCount == 1)
    assert(w.workCount == 1 && w.yearlessWorkCount == 0)
    val u = metric(out, "U")
    assert(
      u.getAs[Long]("publicationCount") == 2L && u.getAs[Long]("totalCoauthorOccurrences") == 2L
    )
    assert(u.getAs[Double]("weightedDegree") == 1.0)
    assert(wt(out, "U") == GraphBuilder.WtExceptionYearless)
    assert(metric(out, "U", W2010to2015).getAs[Long]("publicationCount") == 1L)
    assert(
      inWindow(out.metrics, W2010to2015).forall(_.getAs[String]("researcherId") != researcher("V"))
    )
    // No CoauthorEdge can carry a year-less work (firstCollaborationYear is an Int in v1).
    assert(inWindow(out.edges, AllYears).size == 1)
  }

  test("a pair row with no year is rejected, never turned into an edge") {
    val y = DblpWork("Y", None, Vector("U", "V"))
    val bad = WireCodec
      .encode(
        ok(
          CoauthorPairCandidate
            .fromPositions(y.workId, 1, 2, 2011, 2, prov(SourceType.DBLP, "conf/fx/Y"))
        )
      )
      .replace("\"year\":2011", "\"year\":null")
    val out = build(tables(Vector(y), extraPairs = Vector(bad)))
    assert(out.edges.count() == 0)
    val reasons = out.rejects.select("reasonCode").collect().map(_.getString(0)).toVector
    assert(reasons == Vector("PAIR_MISSING_YEAR"))
    assert(violations(out.rejects, CoreRecords.Kind.Reject).isEmpty)
  }

  test("a missing pair row is detected as a W_r/T_r invariant failure") {
    val out = build(tables(HandCheckable, dropPairs = Set("W1:1-3")))
    assert(wt(out, "A") == GraphBuilder.WtFailure)
    assert(wt(out, "C") == GraphBuilder.WtFailure)
    assert(wt(out, "B") == GraphBuilder.WtEqual)
  }

  test("integrity: authorship of a missing work and a pair of an unknown mention are rejected") {
    val w = DblpWork("W1", Some(2010), Vector("A", "B"))
    val t = tables(Vector(w))
    val orphanWork = DblpWork("GONE", Some(2010), Vector("A", "B"))
    val orphan = tables(Vector(orphanWork))
    val withOrphans = t.copy(
      authorPaper = t.authorPaper ++ orphan.authorPaper,
      coauthorPairs = t.coauthorPairs ++ orphan.coauthorPairs
    )
    val out = build(withOrphans)
    val reasons = out.rejects
      .groupBy("reasonCode")
      .count()
      .collect()
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap
    assert(reasons == Map("AUTHORED_WORK_MISSING" -> 2L, "PAIR_MENTION_NOT_AUTHORED" -> 1L))
    assert(violations(out.rejects, CoreRecords.Kind.Reject).isEmpty)
    // Every AUTHORED endpoint exists.
    assert(out.authored.join(out.researchers, Seq("researcherId"), "left_anti").count() == 0)
    assert(out.authored.join(out.works, Seq("workId"), "left_anti").count() == 0)
  }
