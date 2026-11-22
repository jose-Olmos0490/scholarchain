package scholarchain.adversarial.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions.col
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.model.MatchOutcome
import scholarchain.spark.CoreRecords.Kind
import scholarchain.spark.graph.{GraphBuilder, GraphOutputs, WindowSummaries}

/** Pair-level and window-level attacks: self-pairs after resolution (case 3), duplicate pair rows
  * (case 4), works with no year (case 7) and window boundary years (case 8).
  */
class SparkPairsWindowsSpec extends SparkSuite:

  private def metric(out: GraphOutputs, key: String, w: scholarchain.spark.AnalysisWindow): Row =
    inWindow(out.metrics, w)
      .find(r => str(r, "researcherId") == rid(key))
      .getOrElse(fail(s"no metrics row for $key in $w"))

  private def edge(out: GraphOutputs, a: String, b: String, w: scholarchain.spark.AnalysisWindow) =
    inWindow(out.edges, w).find(r =>
      Set(str(r, "researcherA"), str(r, "researcherB")) == Set(rid(a), rid(b))
    )

  private def wt(out: GraphOutputs, key: String, w: scholarchain.spark.AnalysisWindow): String =
    inWindow(out.wtInvariant, w)
      .find(r => str(r, "researcherId") == rid(key))
      .map(str(_, "status"))
      .get

  private def reasons(out: GraphOutputs): Map[String, Long] =
    out.rejects
      .groupBy("reasonCode")
      .count()
      .collect()
      .map(r => r.getString(0) -> r.getLong(1))
      .toMap

  // ------------------------------------------------------------------ case 3: self-pairs

  private val selfWorks = Vector(
    // DBLP key A twice on one paper (positions 1 and 2), plus B and C.
    Work("SP-1", Some(2012), Vector("SelfA", "SelfA", "SelfB", "SelfC")),
    Work("SP-2", Some(2017), Vector("SelfA", "SelfB"))
  )

  test("case 3: two mentions resolving to one researcher: pair dropped and counted, paper once") {
    val out = build(tables(selfWorks))
    // The A-A self pair of SP-1 (2012) lies in the all-years and 2010-2015 windows.
    val s = WindowSummaries.compute(out, params(), 60)
    def summary(w: scholarchain.spark.AnalysisWindow) =
      s.find(x => x.windowStartYear == w.startYear && x.windowEndYear == w.endYear).get
    assert(summary(AllYears).selfPairsDropped == 1L)
    assert(summary(W2010to2015).selfPairsDropped == 1L)
    assert(summary(W2016to2020).selfPairsDropped == 0L)
    assert(out.selfPairs.count() == 2L)
    assert(
      inWindow(out.edges, AllYears).forall(r => str(r, "researcherA") != str(r, "researcherB"))
    )
    // A: SP-1 counted ONCE (n_p = 3 distinct researchers), SP-2.
    val a = metric(out, "SelfA", AllYears)
    assert(long(a, "publicationCount") == 2L)
    assert(long(a, "totalCoauthorOccurrences") == 2L + 1L)
    assert(long(a, "maximumCoauthorsOnOnePaper") == 2L)
    assert(strs(a, "maximumTeamWorkIds") == Vector(selfWorks.head.workId))
    assert(long(a, "distinctCoauthorCount") == 2L)
    // Two mention pairs (A1-B, A2-B) on SP-1 plus SP-2: the A-B edge has exactly 2 distinct works.
    assert(long(edge(out, "SelfA", "SelfB", AllYears).get, "jointPublicationCount") == 2L)
    assert(long(edge(out, "SelfA", "SelfC", AllYears).get, "jointPublicationCount") == 1L)
    assert(dbl(a, "weightedDegree") == 3.0)
    assert(wt(out, "SelfA", AllYears) == GraphBuilder.WtEqual)
    assert(wt(out, "SelfB", AllYears) == GraphBuilder.WtEqual)
    val errs = Oracle.compare(selfWorks, ThreeWindows, out, None, 40)
    assert(errs.isEmpty, errs.mkString("\n"))
  }

  test(
    "case 3: a duplicated key linked to an external author splits (SAME_WORK), still no self-edge"
  ) {
    val o = OaMention(3001, 1, "SelfA", Some("A3001"), Some(orcidFrom("000000021111222")))
    val w = selfWorks.head
    val out = build(
      tables(
        selfWorks,
        Vector(o),
        Vector(decision(w.mention(1), o.mentionId, MatchOutcome.ACCEPTED))
      )
    )
    val cs = out.identity.conflicts.collect().toVector
    assert(cs.size == 1)
    val rs = cs.head.getSeq[Row](cs.head.fieldIndex("details")).map(str(_, "reason")).toVector
    assert(rs.contains("SAME_WORK_MENTIONS"))
    // All three A mentions keep the one fallback ID: the self pair is still dropped, not an edge.
    val aIds = out.authored
      .where(col("mentionId").isin(w.mention(1), w.mention(2), selfWorks(1).mention(1)))
      .select("researcherId")
      .distinct()
      .collect()
      .map(_.getString(0))
      .toVector
    assert(aIds == Vector(rid("SelfA")))
    assert(
      out.researchers
        .where(col("researcherId") === orcidRid(orcidFrom("000000021111222")))
        .count() == 0L
    )
    assert(out.selfPairs.count() == 2L)
    assert(long(metric(out, "SelfA", AllYears), "publicationCount") == 2L)
    assert(violations(out.identity.conflicts, Kind.Conflict).isEmpty)
  }

  // ------------------------------------------------------------------ case 4: duplicate pairs

  test(
    "case 4: duplicate, swapped and repeated pair rows: jointPublicationCount = distinct works"
  ) {
    val w1 = Work("DUP-1", Some(2012), Vector("DupA", "DupB", "DupC"))
    val w2 = Work("DUP-2", Some(2013), Vector("DupB", "DupA"))
    val w3 = Work("DUP-3", Some(2018), Vector("DupA", "DupB"))
    val works = Vector(w1, w2, w3)
    val extra = Vector(
      pairLine(w1, 1, 2, 2012),
      pairLine(w1, 1, 2, 2012),
      pairLine(w1, 1, 2, 2012),
      swappedPairLine(w1, 1, 2, 2012),
      swappedPairLine(w2, 1, 2, 2013),
      pairLine(w3, 1, 2, 2018)
    )
    val out = build(tables(works, extraPairs = extra))
    val ab = edge(out, "DupA", "DupB", AllYears).get
    assert(long(ab, "jointPublicationCount") == 3L)
    assert(strs(ab, "representativeWorkIds") == Vector(w1, w2, w3).map(_.workId).sorted)
    assert(long(edge(out, "DupA", "DupB", W2010to2015).get, "jointPublicationCount") == 2L)
    assert(long(edge(out, "DupA", "DupB", W2016to2020).get, "jointPublicationCount") == 1L)
    // Support rows are one per distinct work, never one per pair row.
    val sup = inWindow(out.edgeSupport, AllYears).filter(r => str(r, "edgeId") == str(ab, "edgeId"))
    assert(sup.map(str(_, "workId")).sorted == Vector(w1, w2, w3).map(_.workId).sorted)
    // Weighted degree is not inflated by the duplicates: W == T for everyone.
    assert(dbl(metric(out, "DupA", AllYears), "weightedDegree") == 4.0)
    assert(out.wtInvariant.where(col("status") =!= GraphBuilder.WtEqual).count() == 0L)
    val errs = Oracle.compare(works, ThreeWindows, out, None, 40)
    assert(errs.isEmpty, errs.mkString("\n"))
    assert(violations(out.edges, Kind.Edge).isEmpty)
    assert(violations(out.edgeSupport, Kind.EdgeSupport).isEmpty)
  }

  // ------------------------------------------------------------------ case 7: no year

  private val yearless = Vector(
    Work("YL-1", None, Vector("YlU", "YlV")),
    Work("YL-2", Some(2011), Vector("YlU", "YlT")),
    Work("YL-3", None, Vector("YlV", "YlW", "YlU"))
  )

  test("case 7: works with no year exist only in the all-years window and make no edges") {
    // A forged pair row claiming 2011 for the year-less YL-1 must be rejected, not become an edge.
    val forged = pairLine(yearless.head, 1, 2, 2011)
    val out = build(tables(yearless, extraPairs = Vector(forged)))
    val an = analytics(out)
    assert(reasons(out) == Map("PAIR_YEAR_MISMATCH" -> 1L))
    ThreeWindows.foreach(w => assert(edge(out, "YlU", "YlV", w).isEmpty, s"U-V edge in $w"))
    assert(out.edges.count() == 2L) // U-T in all-years and in 2010-2015
    val u = metric(out, "YlU", AllYears)
    assert(long(u, "publicationCount") == 3L)
    assert(long(u, "totalCoauthorOccurrences") == 1L + 1L + 2L)
    assert(dbl(u, "weightedDegree") == 1.0)
    assert(wt(out, "YlU", AllYears) == GraphBuilder.WtExceptionYearless)
    // V and W have only year-less works: all-years rows only, singleton everywhere in PART B.
    Vector("YlV", "YlW").foreach { k =>
      assert(long(metric(out, k, AllYears), "publicationCount") >= 1L)
      assert(long(metric(out, k, AllYears), "distinctCoauthorCount") == 0L)
      assert(long(metric(out, k, AllYears), "componentSize") == 1L)
      Vector(W2010to2015, W2016to2020).foreach(w =>
        assert(!inWindow(out.metrics, w).exists(r => str(r, "researcherId") == rid(k)))
      )
      val kc = inWindow(an.coreness, AllYears).find(r => str(r, "researcherId") == rid(k)).get
      assert(long(kc, "coreness") == 0L && long(kc, "degree") == 0L)
      val c = inWindow(an.communities, AllYears).find(r => str(r, "researcherId") == rid(k)).get
      assert(str(c, "communityId") == rid(k))
    }
    val s = WindowSummaries.compute(out, params(), 60)
    val all = s.find(_.allYearsWindow).get
    assert(all.workCount == 3L && all.yearlessWorkCount == 2L)
    s.filterNot(_.allYearsWindow).foreach(x => assert(x.yearlessWorkCount == 0L))
    val errs = Oracle.compare(yearless, ThreeWindows, out, Some(an), 40)
    assert(errs.isEmpty, errs.mkString("\n"))
  }

  // ------------------------------------------------------------------ case 8: boundaries

  private val years = Vector(2000, 2009, 2010, 2015, 2016, 2020, 2021, 2025)
  private val boundaryWorks: Vector[Work] =
    years.map(y => Work(s"BY-$y", Some(y), Vector(s"Ba $y", s"Bb $y"))) ++ Vector(
      Work("BR-2015", Some(2015), Vector("Br R", "Br S")),
      Work("BR-2016", Some(2016), Vector("Br S", "Br R"))
    )

  test("case 8: window boundary years are inclusive at both ends") {
    val out = build(tables(boundaryWorks))
    def has(y: Int, w: scholarchain.spark.AnalysisWindow) =
      edge(out, s"Ba $y", s"Bb $y", w).isDefined
    years.foreach(y => assert(has(y, AllYears), s"$y must be in the all-years window 2000-2025"))
    assert(years.filter(has(_, W2010to2015)) == Vector(2010, 2015))
    assert(years.filter(has(_, W2016to2020)) == Vector(2016, 2020))
    // One pair with works in 2015 and 2016: split exactly at the boundary.
    val rs = (w: scholarchain.spark.AnalysisWindow) => edge(out, "Br R", "Br S", w).get
    assert(long(rs(AllYears), "jointPublicationCount") == 2L)
    assert(long(rs(AllYears), "firstCollaborationYear") == 2015L)
    assert(long(rs(AllYears), "lastCollaborationYear") == 2016L)
    assert(long(rs(W2010to2015), "jointPublicationCount") == 1L)
    assert(long(rs(W2010to2015), "lastCollaborationYear") == 2015L)
    assert(long(rs(W2016to2020), "jointPublicationCount") == 1L)
    assert(long(rs(W2016to2020), "firstCollaborationYear") == 2016L)
    // Edge IDs are window-scoped.
    assert(
      Vector(AllYears, W2010to2015, W2016to2020).map(w => str(rs(w), "edgeId")).distinct.size == 3
    )
    // Metrics follow the same rule.
    assert(long(metric(out, "Ba 2010", W2010to2015), "publicationCount") == 1L)
    assert(long(metric(out, "Ba 2020", W2016to2020), "publicationCount") == 1L)
    assert(
      !inWindow(out.metrics, W2010to2015).exists(r => str(r, "researcherId") == rid("Ba 2009"))
    )
    assert(
      !inWindow(out.metrics, W2016to2020).exists(r => str(r, "researcherId") == rid("Ba 2021"))
    )
    val errs = Oracle.compare(boundaryWorks, ThreeWindows, out, None, 40)
    assert(errs.isEmpty, errs.mkString("\n"))
  }
