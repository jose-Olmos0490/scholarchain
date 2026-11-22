package scholarchain.spark

import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.config.BenchmarkPair
import scholarchain.spark.Fixture.*
import scholarchain.spark.SparkTestSupport.*
import scholarchain.spark.graph.{Paths, Temporal}

/** Shortest paths with hop evidence, the sampled distance estimate, temporal comparison and
  * semantic facts on the hand-checkable fixture (default keys).
  */
class PathsSpec extends AnyFunSuite:
  private val names = Vector("A", "B", "C", "D", "E", "F", "G", "H", "I")
  private val nameOf: Map[String, String] = names.map(n => researcher(n) -> n).toMap
  private val work: Map[String, String] = HandCheckable.map(w => w.workId -> w.name).toMap
  private val W2016to2020 = AnalysisWindow(2016, 2020, allYears = false)
  private val windows = Vector(AllYears, W2010to2015, W2016to2020)
  private lazy val out = build(tables(HandCheckable), params(windows))
  private val bench = Vector(
    BenchmarkPair(researcher("A"), researcher("F")),
    BenchmarkPair(researcher("A"), researcher("G"))
  )
  private lazy val an = analytics(out, analyticsParams(benchmark = bench), params(windows))

  private def path(
      w: AnalysisWindow,
      s: String,
      t: String,
      df: org.apache.spark.sql.DataFrame = an.paths
  ): Row =
    inWindow(df, w)
      .find(r =>
        r.getAs[String]("sourceResearcherId") == researcher(s) && r
          .getAs[String]("targetResearcherId") == researcher(t)
      )
      .get

  test("A->F: FOUND, length 3 via A-C-D-F, every hop with weight and all supporting work IDs") {
    val r = path(AllYears, "A", "F")
    assert(r.getAs[String]("status") == "FOUND" && r.getAs[Int]("length") == 3)
    assert(r.getAs[String]("selection") == "BENCHMARK")
    val hops = r.getSeq[Row](r.fieldIndex("hops")).map { h =>
      (
        nameOf(h.getAs[String]("fromResearcherId")),
        nameOf(h.getAs[String]("toResearcherId")),
        h.getAs[Long]("jointPublicationCount"),
        h.getSeq[String](h.fieldIndex("supportingWorkIds")).map(work).toVector
      )
    }
    assert(
      hops == Seq(
        ("A", "C", 1L, Vector("W1")),
        ("C", "D", 1L, Vector("W3")),
        ("D", "F", 1L, Vector("W4"))
      )
    )
    val edgeIds = inWindow(out.edges, AllYears).map(_.getAs[String]("edgeId")).toSet
    r.getSeq[Row](r.fieldIndex("hops"))
      .foreach(h => assert(edgeIds.contains(h.getAs[String]("edgeId"))))
  }

  test("A->G: NO_PATH (different components), no hops and no length") {
    val r = path(AllYears, "A", "G")
    assert(r.getAs[String]("status") == "NO_PATH" && r.isNullAt(r.fieldIndex("length")))
    assert(r.getSeq[Row](r.fieldIndex("hops")).isEmpty)
  }

  test("an endpoint absent from the window (F in 2010-2015) gives NO_PATH") {
    assert(path(W2010to2015, "A", "F").getAs[String]("status") == "NO_PATH")
  }

  test("A->F beyond maxDepth = 2: NOT_FOUND_WITHIN_DEPTH (same component)") {
    val shallow = analytics(out, analyticsParams(benchmark = bench, maxDepth = 2), params(windows))
    val r = path(AllYears, "A", "F", shallow.paths)
    assert(r.getAs[String]("status") == "NOT_FOUND_WITHIN_DEPTH" && r.getAs[Int]("maxDepth") == 2)
    assert(path(AllYears, "A", "G", shallow.paths).getAs[String]("status") == "NO_PATH")
  }

  test("among equal-length paths the lexicographically smallest node sequence is chosen") {
    // Square P-Q-S and P-R-S: two shortest P->S paths; the middle node must be min(Q, R).
    // A longer chain X-P makes the tie appear in the middle of a 3-hop path X->S.
    val sq = Vector(
      DblpWork("PQ", Some(2011), Vector("P", "Q")),
      DblpWork("PR", Some(2011), Vector("P", "R")),
      DblpWork("QS", Some(2011), Vector("Q", "S")),
      DblpWork("RS", Some(2011), Vector("R", "S")),
      DblpWork("XP", Some(2011), Vector("X", "P"))
    )
    val o = build(tables(sq), params(Vector(AllYears)))
    val b = Vector(
      BenchmarkPair(researcher("P"), researcher("S")),
      BenchmarkPair(researcher("X"), researcher("S"))
    )
    val a = analytics(o, analyticsParams(benchmark = b), params(Vector(AllYears)))
    val mid = Vector(researcher("Q"), researcher("R")).min
    def nodes(s: String) =
      val r =
        inWindow(a.paths, AllYears).find(_.getAs[String]("sourceResearcherId") == researcher(s)).get
      r.getSeq[Row](r.fieldIndex("hops")).map(_.getAs[String]("toResearcherId")).toVector
    assert(nodes("P") == Vector(mid, researcher("S")))
    assert(nodes("X") == Vector(researcher("P"), mid, researcher("S")))
  }

  test(
    "no benchmark pairs: samplePairCount seeded pairs from the largest component, deterministic"
  ) {
    val p = params(windows)
    val q1 = Paths.queries(out.components, p, Vector.empty, 3, 17L)
    val q2 = Paths.queries(out.components, p, Vector.empty, 3, 17L)
    assert(q1 == q2)
    val all =
      q1.filter(q => q.windowStartYear == AllYears.startYear && q.windowEndYear == AllYears.endYear)
    assert(
      all.size == 3 && all.forall(_.selection == scholarchain.core.model.PathSelection.SAMPLED)
    )
    val abcdef = Set("A", "B", "C", "D", "E", "F")
    assert(
      all.forall(q => abcdef(nameOf(q.source)) && abcdef(nameOf(q.target)) && q.source != q.target)
    )
    assert(all.map(q => Set(q.source, q.target)).distinct.size == 3)
    // Window 2016-2020: largest component {D,E,F} (size 3) has exactly 3 unordered pairs.
    val w = q1.filter(_.windowStartYear == 2016)
    assert(
      w.map(q => Set(nameOf(q.source), nameOf(q.target))).toSet == Set(
        Set("D", "E"),
        Set("D", "F"),
        Set("E", "F")
      )
    )
    val sampled = analytics(out, analyticsParams(), p)
    assert(inWindow(sampled.paths, AllYears).forall(_.getAs[String]("status") == "FOUND"))
    assert(violations(sampled.paths, CoreRecords.Kind.Path).isEmpty)
  }

  test("distance estimate with k >= component size is exact: mean 27/15 = 1.8, p90 = 3") {
    val d = an.distances.find(_.windowStartYear == AllYears.startYear).get
    assert(d.exactAllSources && d.sources.size == 6 && d.componentSize == 6)
    assert(d.reachedPairs == 30 && math.abs(d.meanDistance - 1.8) < 1e-12)
    assert(d.effectiveDiameterP90 == 3 && d.maxObservedDistance == 3 && d.bfsExhausted)
    assert(d.distanceHistogram == Vector((1, 14L), (2, 8L), (3, 8L)))
    // 2010-2015: component {A,B,C,D}: 4 pairs at 1, 2 at 2 -> mean 8/6, p90 2.
    val w = an.distances.find(_.windowStartYear == 2010).get
    assert(math.abs(w.meanDistance - 8.0 / 6.0) < 1e-12 && w.effectiveDiameterP90 == 2)
  }

  test("distance estimate with k < component size is labelled an estimate and seeded") {
    val a = analytics(out, analyticsParams(benchmark = bench, diameterSources = 2), params(windows))
    val d = a.distances.find(_.windowStartYear == AllYears.startYear).get
    assert(!d.exactAllSources && d.sources.size == 2)
    val again =
      analytics(out, analyticsParams(benchmark = bench, diameterSources = 2), params(windows))
    assert(again.distances == a.distances)
  }

  test("path evidence rows satisfy the core contract") {
    assert(violations(an.paths, CoreRecords.Kind.Path).isEmpty)
  }

  test("temporal: consecutive fixed windows 2010-2015 -> 2016-2020; only sizes compared") {
    assert(Temporal.pairs(windows) == Vector((W2010to2015, W2016to2020)))
    val rows =
      an.windowChanges.get.collect().map(r => nameOf(r.getAs[String]("researcherId")) -> r).toMap
    assert(rows.keySet == names.toSet) // A..D in 2010-2015, D..I in 2016-2020
    val d = rows("D")
    val from = d.getAs[Row]("from")
    val to = d.getAs[Row]("to")
    assert(
      from.getAs[Long]("publicationCount") == 1L && from.getAs[Long]("distinctCoauthorCount") == 1L
    )
    assert(
      to.getAs[Long]("publicationCount") == 1L && to.getAs[Long]("distinctCoauthorCount") == 2L
    )
    assert(from.getAs[Long]("componentSize") == 4L && to.getAs[Long]("componentSize") == 3L)
    assert(
      rows("A").isNullAt(rows("A").fieldIndex("to")) && rows("E").isNullAt(
        rows("E").fieldIndex("from")
      )
    )
    assert(violations(an.windowChanges.get, CoreRecords.Kind.WindowChange).isEmpty)
  }

  test("semantic facts: structured values from computed data; PATH fact lists hop nodes") {
    val facts = an.semanticFacts
    assert(violations(facts, CoreRecords.Kind.Fact).isEmpty)
    val c = inWindow(facts, AllYears)
      .find(r =>
        r.getAs[String]("subjectType") == "RESEARCHER" && r
          .getAs[String]("subjectId") == researcher("C")
      )
      .get
    val m = c
      .getSeq[Row](c.fieldIndex("metrics"))
      .map(x => x.getAs[String]("metric") -> x.getAs[Double]("value"))
      .toMap
    assert(
      m("PUBLICATION_COUNT") == 2.0 && m("DISTINCT_COAUTHOR_COUNT") == 3.0 && m("CORENESS") == 2.0
    )
    assert(m("WEIGHTED_DEGREE") == 3.0 && m("COMPONENT_SIZE") == 6.0)
    // Coauthors by jointPublicationCount desc (all 1), then researcher ID (D < A < B).
    assert(c.getSeq[String](c.fieldIndex("relatedResearcherIds")).map(nameOf) == Seq("D", "A", "B"))
    // Representative works by year desc: W3 (2015), W1 (2010); titles are the source titles.
    val works = c.getSeq[Row](c.fieldIndex("representativeWorks"))
    assert(works.map(w => work(w.getAs[String]("workId"))) == Seq("W3", "W1"))
    assert(works.map(_.getAs[String]("title")) == Seq("Work W3", "Work W1"))
    val p = inWindow(facts, AllYears).find(_.getAs[String]("subjectType") == "PATH").get
    assert(
      p.getSeq[String](p.fieldIndex("relatedResearcherIds")).map(nameOf) == Seq("A", "C", "D", "F")
    )
    val types = inWindow(facts, AllYears)
      .map(_.getAs[String]("subjectType"))
      .groupBy(Predef.identity)
      .view
      .mapValues(_.size)
      .toMap
    // 9 researchers, 4 communities, 1 FOUND path; the fixture has no topics or institutions.
    assert(types == Map("RESEARCHER" -> 9, "COMMUNITY" -> 4, "PATH" -> 1))
  }
