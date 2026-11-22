package scholarchain.adversarial.spark

import org.apache.spark.sql.functions.col
import scholarchain.adversarial.spark.SparkAdv.*
import scholarchain.core.config.BenchmarkPair
import scholarchain.core.json.WireCodec
import scholarchain.spark.CoreRecords.Kind
import scholarchain.spark.graph.WindowSummaries

/** Adversarial graph shapes: the empty graph (cases 1), a disconnected graph with isolated
  * researchers (case 2), a 50-author team (case 6) and a skewed hub researcher (case 9). Every
  * shape is checked end to end (PART A and PART B) against the independent reference in
  * [[Oracle]], plus shape-specific expectations.
  */
class SparkGraphShapeSpec extends SparkSuite:

  private val CommunityIters = 40

  private def noViolations(
      out: scholarchain.spark.graph.GraphOutputs,
      an: scholarchain.spark.graph.AnalyticsOutputs
  ): Unit =
    val checks = Vector(
      "edges" -> violations(out.edges, Kind.Edge),
      "support" -> violations(out.edgeSupport, Kind.EdgeSupport),
      "metrics" -> violations(an.metrics, Kind.Metrics),
      "components" -> violations(out.components, Kind.Component),
      "researchers" -> violations(out.researchers, Kind.Researcher),
      "authored" -> violations(out.authored, Kind.Authorship),
      "communities" -> violations(an.communities, Kind.Community),
      "crossEdges" -> violations(an.crossEdges, Kind.CrossEdge),
      "bridges" -> violations(an.bridges, Kind.Bridge),
      "coreness" -> violations(an.coreness, Kind.Coreness),
      "paths" -> violations(an.paths, Kind.Path),
      "facts" -> violations(an.semanticFacts, Kind.Fact)
    ) ++ an.windowChanges.toVector.map(df => "windowChanges" -> violations(df, Kind.WindowChange))
    val bad = checks.filter(_._2.nonEmpty)
    assert(bad.isEmpty, bad.map((n, v) => s"$n: ${v.take(3).mkString("; ")}").mkString("\n"))

  // ------------------------------------------------------------------ case 1: empty graph

  test("case 1: empty graph through PART A and PART B: no rows, NO_PATH benchmarks, converged") {
    val bench = Vector(BenchmarkPair(rid("Nobody 1"), rid("Nobody 2")))
    val out = build(tables(Vector.empty))
    val an = analytics(out, analyticsParams(benchmark = bench))
    Vector(
      "edges" -> out.edges,
      "support" -> out.edgeSupport,
      "metrics" -> an.metrics,
      "components" -> out.components,
      "researchers" -> out.researchers,
      "authored" -> out.authored,
      "communities" -> an.communities,
      "crossEdges" -> an.crossEdges,
      "bridges" -> an.bridges,
      "coreness" -> an.coreness,
      "facts" -> an.semanticFacts,
      "rejects" -> out.rejects,
      "conflicts" -> out.identity.conflicts
    ).foreach((n, df) => assert(df.count() == 0L, s"$n must be empty"))
    // One benchmark query per window; none can be found in an empty graph.
    val paths = an.paths.collect().toVector
    assert(paths.size == ThreeWindows.size)
    assert(paths.forall(r => str(r, "status") == "NO_PATH" && r.isNullAt(r.fieldIndex("length"))))
    assert(an.distances.isEmpty)
    assert(an.kcore.unpeeled.isEmpty)
    assert(an.communityRun.forall(_.converged) && an.kcore.groups.forall(_.converged))
    an.windowChanges.foreach(df => assert(df.count() == 0L))
    val s = WindowSummaries.compute(
      out,
      params(),
      60,
      Some(WindowSummaries.PartB(an, CommunityIters, 200))
    )
    assert(s.size == 3)
    s.foreach { x =>
      assert(x.researcherCount == 0 && x.coauthorEdgeCount == 0 && x.componentCount == 0)
      assert(x.communityCount.contains(0L) && x.communitySizeHistogram.isEmpty)
      assert(x.maxCoreness.contains(0))
      assert(x.iterations.forall(_.converged), x.iterations.toString)
      assert(WireCodec.validate(x).isRight)
    }
    noViolations(out, an)
  }

  // ------------------------------------------------------------------ case 2: disconnected

  private val disconnected = Vector(
    // triangle T1-T2-T3
    Work("D-T", Some(2012), Vector("T1", "T2", "T3")),
    // path P1-P2-P3-P4 across both fixed windows
    Work("D-P1", Some(2011), Vector("P1", "P2")),
    Work("D-P2", Some(2015), Vector("P2", "P3")),
    Work("D-P3", Some(2016), Vector("P3", "P4")),
    // clique K4
    Work("D-K", Some(2018), Vector("K1", "K2", "K3", "K4")),
    // a lone pair, written twice
    Work("D-L1", Some(2013), Vector("L1", "L2")),
    Work("D-L2", Some(2014), Vector("L2", "L1")),
    // isolated single-author researchers
    Work("D-S1", Some(2012), Vector("S1")),
    Work("D-S2", Some(2019), Vector("S2"))
  )
  private val discOut = memo(build(tables(disconnected)))
  private val discBench = Vector(
    BenchmarkPair(rid("T1"), rid("K4")),
    BenchmarkPair(rid("P1"), rid("P4")),
    BenchmarkPair(rid("S1"), rid("S2")),
    BenchmarkPair(rid("L2"), rid("T3"))
  )
  private val discAn = memo(analytics(discOut(), analyticsParams(benchmark = discBench)))

  test("case 2: disconnected graph matches the reference in every window (PART A and B)") {
    val errs = Oracle.compare(
      disconnected,
      ThreeWindows,
      discOut(),
      Some(discAn()),
      CommunityIters,
      discBench
    )
    assert(errs.isEmpty, errs.mkString("\n"))
    noViolations(discOut(), discAn())
  }

  test("case 2: component counts, singletons and cross-component paths in the all-years window") {
    val comps = inWindow(discOut().components, AllYears)
    val sizes = comps
      .map(r => str(r, "componentId") -> long(r, "componentSize"))
      .distinct
      .map(_._2)
      .sorted
    assert(sizes == Vector(1L, 1L, 2L, 3L, 4L, 4L))
    // Isolated researchers: own component, own community, coreness 0, one community connected.
    Vector("S1", "S2").foreach { k =>
      val c =
        inWindow(discAn().communities, AllYears).find(r => str(r, "researcherId") == rid(k)).get
      assert(str(c, "communityId") == rid(k) && long(c, "communitySize") == 1L)
      val b = inWindow(discAn().bridges, AllYears).find(r => str(r, "researcherId") == rid(k)).get
      assert(long(b, "degree") == 0L && long(b, "communitiesConnected") == 1L)
      assert(dbl(b, "participationCoefficient") == 0.0)
      val kc = inWindow(discAn().coreness, AllYears).find(r => str(r, "researcherId") == rid(k)).get
      assert(long(kc, "coreness") == 0L)
    }
    // No edge crosses components, so no cross-community edge can either.
    val comp = comps.map(r => str(r, "researcherId") -> str(r, "componentId")).toMap
    inWindow(discAn().crossEdges, AllYears).foreach(r =>
      assert(comp(str(r, "researcherA")) == comp(str(r, "researcherB")))
    )
    val status = inWindow(discAn().paths, AllYears)
      .map(r => (str(r, "sourceResearcherId"), str(r, "targetResearcherId")) -> str(r, "status"))
      .toMap
    assert(status((rid("T1"), rid("K4"))) == "NO_PATH")
    assert(status((rid("S1"), rid("S2"))) == "NO_PATH")
    assert(status((rid("L2"), rid("T3"))) == "NO_PATH")
    assert(status((rid("P1"), rid("P4"))) == "FOUND")
    // The path P1..P4 is broken in both fixed windows (2015 | 2016 boundary).
    assert(
      inWindow(discAn().paths, W2010to2015)
        .find(r => str(r, "sourceResearcherId") == rid("P1"))
        .map(str(_, "status"))
        .contains("NO_PATH")
    )
    // Distance estimate only on the largest component (ties: smallest componentId).
    val d = discAn().distances.find(_.windowStartYear == AllYears.startYear).get
    val largestIds = comps.filter(r => long(r, "componentSize") == 4L).map(str(_, "componentId"))
    assert(d.componentId == largestIds.min && d.componentSize == 4L)
  }

  test("case 2: a graph with researchers but no edge at all: all singletons, converged") {
    val solo = Vector(
      Work("E-1", Some(2011), Vector("Solo 1")),
      Work("E-2", Some(2017), Vector("Solo 2")),
      Work("E-3", None, Vector("Solo 3"))
    )
    val out = build(tables(solo))
    val an = analytics(out)
    val errs = Oracle.compare(solo, ThreeWindows, out, Some(an), CommunityIters)
    assert(errs.isEmpty, errs.mkString("\n"))
    assert(out.edges.count() == 0L)
    assert(inWindow(out.components, AllYears).size == 3)
    assert(inWindow(an.communities, AllYears).forall(r => long(r, "communitySize") == 1L))
    assert(an.communityRun.forall(_.converged) && an.kcore.groups.forall(_.converged))
  }

  // ------------------------------------------------------------------ case 6: 50-author team

  private val team50Keys = (1 to 50).map(i => f"Team $i%02d").toVector
  private val team50 = Vector(
    Work("BIG50", Some(2014), team50Keys),
    // one extra small paper of a team member with an outsider
    Work("SMALL", Some(2017), Vector("Team 01", "Outsider"))
  )
  private val t50Out = memo(build(tables(team50)))
  private val t50Bench = Vector(
    BenchmarkPair(rid("Team 50"), rid("Team 02")),
    BenchmarkPair(rid("Outsider"), rid("Team 37"))
  )
  private val t50An = memo(analytics(t50Out(), analyticsParams(benchmark = t50Bench)))

  test("case 6: 50-author team: 1225 edges and support rows, M = 49 for every member") {
    assert(inWindow(t50Out().edges, AllYears).size == 1225 + 1)
    assert(inWindow(t50Out().edges, W2010to2015).size == 1225)
    assert(inWindow(t50Out().edgeSupport, W2010to2015).size == 1225)
    val big = team50.head.workId
    inWindow(t50Out().metrics, W2010to2015).foreach { r =>
      assert(long(r, "maximumCoauthorsOnOnePaper") == 49L)
      assert(strs(r, "maximumTeamWorkIds") == Vector(big))
      assert(long(r, "publicationCount") == 1L && long(r, "componentSize") == 50L)
    }
    // Team 01 also has a 2-author paper: M stays 49 from BIG50 only; C = 50 in all years.
    val t01 =
      inWindow(t50Out().metrics, AllYears).find(r => str(r, "researcherId") == rid("Team 01")).get
    assert(long(t01, "maximumCoauthorsOnOnePaper") == 49L)
    assert(strs(t01, "maximumTeamWorkIds") == Vector(big))
    assert(
      long(t01, "distinctCoauthorCount") == 50L && long(t01, "totalCoauthorOccurrences") == 50L
    )
    assert(t50Out().wtInvariant.where(col("status") =!= "EQUAL").count() == 0L)
  }

  test("case 6: 50-author team in PART B: one community, coreness 49, paths of length <= 2") {
    val errs =
      Oracle.compare(team50, ThreeWindows, t50Out(), Some(t50An()), CommunityIters, t50Bench)
    assert(errs.isEmpty, errs.mkString("\n"))
    val comm = inWindow(t50An().communities, W2010to2015)
    assert(comm.map(str(_, "communityId")).distinct.size == 1)
    assert(comm.forall(r => long(r, "communitySize") == 50L))
    assert(inWindow(t50An().coreness, W2010to2015).forall(r => long(r, "coreness") == 49L))
    assert(inWindow(t50An().crossEdges, W2010to2015).isEmpty)
    val p = inWindow(t50An().paths, AllYears)
      .find(r => str(r, "sourceResearcherId") == rid("Outsider"))
      .get
    assert(str(p, "status") == "FOUND" && long(p, "length") == 2L)
    noViolations(t50Out(), t50An())
  }

  // ------------------------------------------------------------------ case 9: skewed hub

  private val spokes = (1 to 150).map(i => f"Spoke $i%03d").toVector
  private val hubWorks: Vector[Work] =
    spokes.zipWithIndex.map((s, i) => Work(s"HUB-$i", Some(2011 + i % 10), Vector("Hub", s))) ++
      Vector(
        Work("HUB-TRI", Some(2012), Vector("Hub", "Spoke 001", "Spoke 002")),
        Work("HUB-SS", Some(2013), Vector("Spoke 002", "Spoke 003")),
        // the hub writes with Spoke 004 three more times: weight must not sway LPA
        Work("HUB-R1", Some(2014), Vector("Spoke 004", "Hub")),
        Work("HUB-R2", Some(2015), Vector("Spoke 004", "Hub")),
        Work("HUB-R3", Some(2019), Vector("Hub", "Spoke 004"))
      )
  private val hubBench = Vector(
    BenchmarkPair(rid("Spoke 150"), rid("Spoke 077")),
    BenchmarkPair(rid("Spoke 003"), rid("Spoke 001")),
    BenchmarkPair(rid("Spoke 010"), rid("Spoke 020"))
  )
  private val hubOut = memo(build(tables(hubWorks)))
  private val hubAn = memo(analytics(hubOut(), analyticsParams(benchmark = hubBench)))

  test("case 9: skewed hub matches the reference in every window (PART A and B)") {
    val errs =
      Oracle.compare(hubWorks, ThreeWindows, hubOut(), Some(hubAn()), CommunityIters, hubBench)
    assert(errs.isEmpty, errs.mkString("\n"))
    noViolations(hubOut(), hubAn())
  }

  test("case 9: hub degree, weight, coreness and paths through the hub") {
    val h = inWindow(hubOut().metrics, AllYears).find(r => str(r, "researcherId") == rid("Hub")).get
    assert(long(h, "distinctCoauthorCount") == 150L)
    // 150 spoke papers + HUB-TRI (2 coauthors) + 3 repeat papers with Spoke 004.
    assert(long(h, "publicationCount") == 154L)
    assert(long(h, "totalCoauthorOccurrences") == 150L + 2L + 3L)
    assert(dbl(h, "weightedDegree") == 155.0)
    val e4 = inWindow(hubOut().edges, AllYears)
      .find(r =>
        Set(str(r, "researcherA"), str(r, "researcherB")) == Set(rid("Hub"), rid("Spoke 004"))
      )
      .get
    assert(long(e4, "jointPublicationCount") == 4L)
    val core = inWindow(hubAn().coreness, AllYears)
      .map(r => str(r, "researcherId") -> long(r, "coreness"))
      .toMap
    assert(core(rid("Hub")) == 2L && core(rid("Spoke 001")) == 2L && core(rid("Spoke 002")) == 2L)
    assert(core(rid("Spoke 003")) == 2L) // Spoke 002 - Spoke 003 - Hub - Spoke 002 is a triangle
    assert(core(rid("Spoke 150")) == 1L)
    val p = inWindow(hubAn().paths, AllYears)
      .find(r => str(r, "sourceResearcherId") == rid("Spoke 150"))
      .get
    assert(str(p, "status") == "FOUND" && long(p, "length") == 2L)
    val hops = p.getSeq[org.apache.spark.sql.Row](p.fieldIndex("hops"))
    assert(str(hops.head, "toResearcherId") == rid("Hub"))
  }
