package scholarchain.storage

import java.util.Base64
import scholarchain.core.model.CollaborationMetric
import scholarchain.storage.common.*
import scholarchain.storage.graph.*

/** Contract of the metric ranking port, over the fake. Bounds are refused, never clamped. */
class MetricReaderFakeSpec extends StorageSuite:
  import TinyGraph.*

  private val limits = GraphReaderLimits(5, 4, 3000L)

  // components: cA = r1,r2,r3 (3), cB = r4,r5 (2), cC = r6 (1). publications tie r1 = r2 = 5.
  private val graph = TinyGraph.build(
    Vector(
      R("r3", 7, "cA", Some("k1"), Some(2)),
      R("r1", 5, "cA", Some("k1"), Some(2)),
      R("r2", 5, "cA", Some("k2"), Some(1)),
      R("r4", 3, "cB", Some("k2"), Some(1)),
      R("r5", 3, "cB", Some("k2")),
      R("r6", 1, "cC")
    ),
    works = Vector(
      ("w1", Vector("r4", "r5"), Vector("t1")),
      ("w2", Vector("r5", "r6"), Vector("t1", "t2")),
      ("w3", Vector("r1"), Vector("t3"))
    )
  )
  private val reader = new FakeMetricReader(graph, limits)
  private def top(
      m: CollaborationMetric,
      limit: Int = 5,
      filter: ResearcherFilter = ResearcherFilter.Unfiltered
  ) = ok(reader.topK(m, 2010, 2013, filter, limit))

  test("ranking is exactly by the metric, ties by researcherId ascending") {
    val ranked = top(CollaborationMetric.PUBLICATION_COUNT)
    ranked.map(_.researcherId) shouldBe Vector("r3", "r1", "r2", "r4", "r5")
    ranked.map(_.metricValue) shouldBe Vector(7.0, 5.0, 5.0, 3.0, 3.0)
    ranked.head.displayName shouldBe "Name r3"
    ranked.head.metrics.window shouldBe "2010-2013"
    ranked.head.metrics.publicationCount shouldBe 7L
    top(CollaborationMetric.PUBLICATION_COUNT, limit = 2).map(_.researcherId) shouldBe
      Vector("r3", "r1")
  }

  test("component and community size rank by the number of window members sharing the label") {
    val byComponent = top(CollaborationMetric.COMPONENT_SIZE)
    byComponent.map(r => (r.researcherId, r.metricValue)) shouldBe
      Vector(("r1", 3.0), ("r2", 3.0), ("r3", 3.0), ("r4", 2.0), ("r5", 2.0))
    // k1 = {r1, r3}, k2 = {r2, r4, r5}; r6 has no community and is not ranked
    val byCommunity = top(CollaborationMetric.COMMUNITY_SIZE)
    byCommunity.map(r => (r.researcherId, r.metricValue)) shouldBe
      Vector(("r2", 3.0), ("r4", 3.0), ("r5", 3.0), ("r1", 2.0), ("r3", 2.0))
  }

  test("a researcher with no stored value for the metric is not ranked") {
    top(CollaborationMetric.CORENESS).map(r => (r.researcherId, r.metricValue)) shouldBe
      Vector(("r1", 2.0), ("r3", 2.0), ("r2", 1.0), ("r4", 1.0))
  }

  test("the topic filter keeps researchers who authored a work with the topic, once each") {
    top(
      CollaborationMetric.PUBLICATION_COUNT,
      filter = ResearcherFilter(topicIds = Vector("t1"))
    ).map(_.researcherId) shouldBe Vector("r4", "r5", "r6")
    // r5 has two matching works and still appears once
    top(
      CollaborationMetric.PUBLICATION_COUNT,
      filter = ResearcherFilter(topicIds = Vector("t1", "t2"))
    ).map(_.researcherId) shouldBe Vector("r4", "r5", "r6")
    // a filtered size ranking still counts the whole component
    top(
      CollaborationMetric.COMPONENT_SIZE,
      filter = ResearcherFilter(topicIds = Vector("t1"))
    ).map(r => (r.researcherId, r.metricValue)) shouldBe
      Vector(("r4", 2.0), ("r5", 2.0), ("r6", 1.0))
  }

  test("metrics that no store holds are refused, not answered with a guess") {
    import CollaborationMetric.*
    val refused = Set(
      SHORTEST_PATH_LENGTH,
      APPROX_BETWEENNESS,
      CROSS_COMMUNITY_DEGREE,
      PARTICIPATION_COEFFICIENT,
      COMMUNITIES_CONNECTED
    )
    refused.foreach { m =>
      err(reader.topK(m, 2010, 2013, ResearcherFilter.Unfiltered, 3)) shouldBe a[
        StorageError.Unsupported
      ]
    }
    // every metric is either ranked or refused: nothing falls through
    CollaborationMetric.values.toSet shouldBe (MetricRanking.supported ++ refused)
  }

  test("venue and institution filters are refused because their relationships are not loaded") {
    err(
      reader.topK(
        CollaborationMetric.PUBLICATION_COUNT,
        2010,
        2013,
        ResearcherFilter(venueIds = Vector("venue:x")),
        3
      )
    ) shouldBe a[StorageError.Unsupported]
    err(
      reader.topK(
        CollaborationMetric.PUBLICATION_COUNT,
        2010,
        2013,
        ResearcherFilter(institutionIds = Vector("institution:x")),
        3
      )
    ) shouldBe a[StorageError.Unsupported]
  }

  test("bounds are refused, not clamped") {
    val m = CollaborationMetric.PUBLICATION_COUNT
    err(reader.topK(m, 2010, 2013, ResearcherFilter.Unfiltered, 6)) shouldBe
      StorageError.LimitExceeded("result limit", 5, 6)
    err(reader.topK(m, 2010, 2013, ResearcherFilter.Unfiltered, 0)) shouldBe
      a[StorageError.InvalidRecord]
    err(
      reader.topK(
        m,
        2010,
        2013,
        ResearcherFilter(topicIds = (1 to 6).map(i => s"t$i").toVector),
        3
      )
    ) shouldBe StorageError.LimitExceeded("topic filter ids", 5, 6)
    err(reader.topK(m, 2013, 2010, ResearcherFilter.Unfiltered, 3)) shouldBe
      a[StorageError.InvalidRecord]
  }

  test("a window that is not stored on the nodes is refused with the loaded windows named") {
    ok(reader.loadedWindows) shouldBe Vector((2010, 2013))
    val e = err(
      reader.topK(
        CollaborationMetric.PUBLICATION_COUNT,
        2000,
        2005,
        ResearcherFilter.Unfiltered,
        3
      )
    )
    e shouldBe a[StorageError.Unsupported]
    e.message should include("2000-2005")
    e.message should include("2010-2013")
    e.retryable shouldBe false
  }

  test("a loaded window with no match is an empty ranking, not an error") {
    top(
      CollaborationMetric.PUBLICATION_COUNT,
      filter = ResearcherFilter(topicIds = Vector("no-such-topic"))
    ) shouldBe empty
  }

/** Cursor paging of direct coauthors over ties, bounds, and opaqueness. */
class CoauthorPagingSpec extends StorageSuite:
  import TinyGraph.*

  private val limits = GraphReaderLimits(5, 4, 3000L)
  // r1's coauthors: r2, r3, r4 tie at 3; r5, r6 tie at 2; r7 has 1. The pair (r8, r9) is unrelated.
  private val graph = TinyGraph.build(
    (1 to 9).map(i => R(s"r$i", 1, "c")).toVector,
    Vector(
      E("r1", "r2", 3),
      E("r1", "r3", 3),
      E("r4", "r1", 3), // stored in the other direction
      E("r1", "r5", 2),
      E("r1", "r6", 2),
      E("r1", "r7", 1),
      E("r8", "r9", 9),
      E("r1", "r2", 8, window = (2010, 2012))
    )
  )
  private val reader = new FakeGraphReader(graph, limits)
  private val all = Vector("r2", "r3", "r4", "r5", "r6", "r7")

  private def pages(size: Int): Vector[Page[CoauthorView]] =
    Iterator
      .unfold[Page[CoauthorView], Option[Option[String]]](Some(None)) {
        case None => None
        case Some(cursor) =>
          val p = ok(reader.directCoauthorsPage("r1", 2010, 2013, PageRequest(size, cursor)))
          Some((p, p.nextCursor.map(Some(_))))
      }
      .toVector

  test("pages concatenate to the full ordered list, across ties on the page boundary") {
    val twos = pages(2)
    twos.map(_.items.map(_.coauthorId)) shouldBe
      Vector(Vector("r2", "r3"), Vector("r4", "r5"), Vector("r6", "r7"))
    // the first page ends inside the tie at 3: r4 (same count) must open the second page
    twos(1).items.head.edge.jointPublicationCount shouldBe 3L
    twos.flatMap(_.items.map(_.coauthorId)) shouldBe all
    twos.last.nextCursor shouldBe None
    pages(5).map(_.items.size) shouldBe Vector(5, 1)
  }

  test("a page that exactly fits the remaining rows has no next cursor") {
    pages(3).map(p => (p.items.size, p.nextCursor.isDefined)) shouldBe
      Vector((3, true), (3, false))
    val one = ok(reader.directCoauthorsPage("r9", 2010, 2013, PageRequest(5)))
    one.items.map(_.coauthorId) shouldBe Vector("r8")
    one.nextCursor shouldBe None
  }

  test("the cursor is opaque: it does not contain the coauthor ID or the count in the clear") {
    val c = pages(2).head.nextCursor.get
    c should not include "r3"
    c should fullyMatch regex "[A-Za-z0-9_-]+"
    // it decodes to a versioned document, which callers must not rely on
    ujson.read(new String(Base64.getUrlDecoder.decode(c), "UTF-8"))("v") shouldBe ujson.Num(1)
  }

  test("a cursor is bound to its researcher and window; malformed ones are refused") {
    val c = pages(2).head.nextCursor.get
    err(reader.directCoauthorsPage("r8", 2010, 2013, PageRequest(2, Some(c)))) shouldBe
      a[StorageError.InvalidRecord]
    err(reader.directCoauthorsPage("r1", 2010, 2012, PageRequest(2, Some(c)))) shouldBe
      a[StorageError.InvalidRecord]
    val bad = Vector(
      "",
      "not-base64!!",
      "e30",
      Base64.getUrlEncoder.encodeToString("[]".getBytes("UTF-8"))
    )
    bad.foreach { b =>
      err(reader.directCoauthorsPage("r1", 2010, 2013, PageRequest(2, Some(b)))) shouldBe
        a[StorageError.InvalidRecord]
    }
  }

  test("page limits are refused outside 1..configured limit") {
    err(reader.directCoauthorsPage("r1", 2010, 2013, PageRequest(6))) shouldBe
      StorageError.LimitExceeded("result limit", 5, 6)
    err(reader.directCoauthorsPage("r1", 2010, 2013, PageRequest(0))) shouldBe
      a[StorageError.InvalidRecord]
  }

  test("the paging template is a constant; position values travel only as parameters") {
    val t = new CypherTemplates(limits)
    val hostile = "x' }) MATCH (z) RETURN z //"
    val first = ok(t.directCoauthorsPage("r1", 2010, 2013, PageRequest(2)))
    first.name shouldBe "directCoauthorsPage/first"
    first.parameters.keySet shouldBe
      Set("researcherId", "windowStartYear", "windowEndYear", "fetchLimit")
    first.parameters("fetchLimit") shouldBe ujson.Num(3)
    val token = PageCursor.encode(PageCursor.scope(hostile, 1, 2), 3, hostile)
    val after = ok(t.directCoauthorsPage(hostile, 1, 2, PageRequest(2, Some(token))))
    after.name shouldBe "directCoauthorsPage/after"
    after.text should not include hostile
    after.parameters("afterCoauthorId") shouldBe ujson.Str(hostile)
    after.parameters("afterJointPublicationCount") shouldBe ujson.Num(3)
    Set("$afterJointPublicationCount", "$afterCoauthorId", "$fetchLimit").foreach(p =>
      after.text should include(p)
    )
  }

/** Bounded path search with limits, deterministic order, timeout and supporting works. */
class PathsFakeSpec extends StorageSuite:
  import TinyGraph.*

  private val limits = GraphReaderLimits(5, 4, 3000L)
  private val ok3 = PathQueryLimits(maxDepth = 3, resultLimit = 5, timeoutMillis = 1000)

  // s-a-t, s-b-t (length 2), s-c-d-t (length 3); triangle s-x-y; t-z hangs off t
  private val graph = TinyGraph.build(
    Vector("s", "a", "b", "c", "d", "t", "x", "y", "z").map(R(_, 1, "c")),
    Vector(
      E("s", "a", 1),
      E("a", "t", 2),
      E("b", "s", 1),
      E("b", "t", 1),
      E("c", "s", 1),
      E("c", "d", 1),
      E("d", "t", 1),
      E("s", "a", 4, window = (2010, 2012)),
      E("x", "s", 1),
      E("x", "y", 1),
      E("s", "y", 1),
      E("t", "z", 1)
    )
  )
  private val reader = new FakeGraphReader(graph, limits)

  test("paths are ordered by length, then researcher IDs, then edge IDs, and cut at the limit") {
    val all = ok(reader.paths("s", "t", 2010, 2013, ok3))
    all.map(_.researcherIds) shouldBe Vector(
      Vector("s", "a", "t"),
      Vector("s", "b", "t"),
      Vector("s", "c", "d", "t")
    )
    all.head.edges.map(_.edgeId) shouldBe Vector(edgeId("s", "a"), edgeId("a", "t"))
    all.head.edges.map(_.evidencePointer) shouldBe all.head.edges.map(e => s"$Evidence${e.edgeId}")
    all.map(_.length) shouldBe Vector(2, 2, 3)
    ok(reader.paths("s", "t", 2010, 2013, ok3.copy(resultLimit = 2))).map(_.researcherIds) shouldBe
      Vector(Vector("s", "a", "t"), Vector("s", "b", "t"))
    ok(reader.paths("s", "t", 2010, 2013, ok3.copy(maxDepth = 2))).map(_.length) shouldBe
      Vector(2, 2)
  }

  test("paths are simple and stay inside one window") {
    val deep = ok(reader.paths("s", "t", 2010, 2013, ok3.copy(maxDepth = 4, resultLimit = 5)))
    deep.foreach(p => p.researcherIds.distinct.size shouldBe p.researcherIds.size)
    ok(reader.paths("s", "t", 2010, 2012, ok3)) shouldBe empty
    ok(reader.paths("s", "z", 2010, 2013, ok3.copy(maxDepth = 3))).map(_.researcherIds) shouldBe
      Vector(Vector("s", "a", "t", "z"), Vector("s", "b", "t", "z"))
    ok(reader.paths("s", "nobody", 2010, 2013, ok3)) shouldBe empty
  }

  test("bounds are refused, not clamped") {
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(maxDepth = 5))) shouldBe
      StorageError.LimitExceeded("path depth", 4, 5)
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(maxDepth = 0))) shouldBe
      a[StorageError.InvalidRecord]
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(resultLimit = 6))) shouldBe
      StorageError.LimitExceeded("result limit", 5, 6)
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(resultLimit = 0))) shouldBe
      a[StorageError.InvalidRecord]
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(timeoutMillis = 3001))) shouldBe
      StorageError.LimitExceeded("path timeout millis", 3000, 3001)
    err(reader.paths("s", "t", 2010, 2013, ok3.copy(timeoutMillis = 0))) shouldBe
      a[StorageError.InvalidRecord]
    err(reader.paths("s", "s", 2010, 2013, ok3)) shouldBe a[StorageError.InvalidRecord]
    err(reader.paths("s", "t", 2013, 2010, ok3)) shouldBe a[StorageError.InvalidRecord]
  }

  test("a search that runs past its limit is a typed, retryable Timeout") {
    val slow = new FakeGraphReader(graph, limits, maxExpansions = 3)
    val e = err(slow.paths("s", "t", 2010, 2013, ok3))
    e shouldBe StorageError.Timeout("paths/3", 1000L)
    e.retryable shouldBe true
    e.message should include("1000 ms")
    // the same request succeeds with the budget restored: Timeout is the caller's to retry
    ok(reader.paths("s", "t", 2010, 2013, ok3)) should not be empty
  }

  test("the path template is a constant: hostile IDs and the limits never change its shape") {
    val t = new CypherTemplates(limits)
    val hostile = "x' }) MATCH (z) RETURN z //"
    val a = ok(t.paths("s", "t", 2010, 2013, ok3))
    val b = ok(t.paths(hostile, hostile + "2", 1, 2, ok3.copy(timeoutMillis = 2500)))
    a.name shouldBe "paths/3"
    a.text should startWith("USING QUERY:TIMEOUTMILLISECONDS 1000\n")
    b.text should startWith("USING QUERY:TIMEOUTMILLISECONDS 2500\n")
    a.text.linesIterator.drop(1).mkString("\n") shouldBe b.text.linesIterator.drop(1).mkString("\n")
    b.text should not include hostile
    a.timeoutMillis shouldBe Some(1000L)
    a.parameters.keySet shouldBe
      Set("source", "target", "windowStartYear", "windowEndYear", "resultLimit")
    a.text should include("*1..3")
    Set("$source", "$target", "$windowStartYear", "$windowEndYear", "$resultLimit").foreach(p =>
      a.text should include(p)
    )
    // depth comes from the lookup table, one text per configured depth
    (1 to 4).foreach(d =>
      ok(t.paths("s", "t", 1, 2, ok3.copy(maxDepth = d))).text should include(s"*1..$d")
    )
  }

/** The composed path port: topology plus the supporting works of every hop. */
class SupportedPathSpec extends StorageSuite:
  import TinyGraph.*

  private val limits = GraphReaderLimits(5, 4, 3000L)
  private val q = PathQueryLimits(3, 5, 1000)
  private val graph = TinyGraph.build(
    Vector("s", "m", "t").map(R(_, 1, "c")),
    Vector(E("s", "m", 2), E("m", "t", 1))
  )
  private val graphReader = new FakeGraphReader(graph, limits)

  private def support(): FakeEdgeSupportReader =
    val sup = new FakeEdgeSupportReader
    sup.put(s"$Evidence${edgeId("s", "m")}", "run:test", Set("work:b", "work:a"))
    sup.put(s"$Evidence${edgeId("m", "t")}", "run:test", Set("work:c"))
    sup

  test("every hop carries its edge, its direction of travel and its sorted supporting works") {
    val paths = ok(new SupportedPathReader(graphReader, support()).paths("t", "s", 2010, 2013, q))
    paths should have size 1
    val p = paths.head
    p.researcherIds shouldBe Vector("t", "m", "s")
    p.hops.map(h => (h.fromResearcherId, h.toResearcherId)) shouldBe
      Vector(("t", "m"), ("m", "s"))
    p.hops.map(_.supportingWorkIds) shouldBe Vector(Vector("work:c"), Vector("work:a", "work:b"))
    p.hops.map(_.edge.edgeId) shouldBe Vector(edgeId("m", "t"), edgeId("s", "m"))
    p.hops.foreach(h => h.supportingWorkIds.size.toLong shouldBe h.edge.jointPublicationCount)
  }

  test("incomplete support fails the whole call instead of returning a hop without evidence") {
    val sup = new FakeEdgeSupportReader
    sup.put(s"$Evidence${edgeId("s", "m")}", "run:test", Set("work:a")) // count is 2
    sup.put(s"$Evidence${edgeId("m", "t")}", "run:test", Set("work:c"))
    val e = err(new SupportedPathReader(graphReader, sup).paths("s", "t", 2010, 2013, q))
    e shouldBe a[StorageError.InvalidRecord]
    e.message should include(edgeId("s", "m"))
    // support of another run does not count
    val other = new FakeEdgeSupportReader
    other.put(s"$Evidence${edgeId("s", "m")}", "run:other", Set("work:a", "work:b"))
    err(new SupportedPathReader(graphReader, other).paths("s", "t", 2010, 2013, q)) shouldBe
      a[StorageError.InvalidRecord]
  }

  test("a pointer that is not a support reference is refused") {
    val sup = new FakeEdgeSupportReader
    val bad =
      CoauthorEdgeView("e", "a", "b", 1, 2010, 2010, 2010, 2013, "s3://x/y", "run:test", "p")
    err(sup.supportingWorkIds(bad)) shouldBe a[StorageError.InvalidRecord]
  }

  test("a graph timeout and a graph refusal pass through untouched") {
    val slow = new FakeGraphReader(graph, limits, maxExpansions = 1)
    err(new SupportedPathReader(slow, support()).paths("s", "t", 2010, 2013, q)) shouldBe
      a[StorageError.Timeout]
    err(
      new SupportedPathReader(graphReader, support())
        .paths("s", "t", 2010, 2013, q.copy(maxDepth = 9))
    ) shouldBe a[StorageError.LimitExceeded]
  }

/** Every template is read-only and keeps caller values out of its text. */
class RuntimeTemplatesSpec extends StorageSuite:
  private val limits = GraphReaderLimits(25, 4, 3000L)
  private val templates = new CypherTemplates(limits)
  private val hostile = "x' }) MATCH (z) RETURN z //"

  private val writes = Vector("CREATE", "DELETE", "SET ", "MERGE", "REMOVE", "LOAD")

  test("the template ID list is exactly the documented one") {
    val metrics = MetricRanking.supported.toVector.sortBy(_.toString)
    val ids = Vector(
      templates.getResearcher("r").name,
      ok(templates.directCoauthors("r", 1, 2, 3)).name,
      ok(templates.directCoauthorsPage("r", 1, 2, PageRequest(3))).name,
      templates.coauthorEdge("a", "b", 1, 2).name,
      ok(templates.shortestPath("a", "b", 1, 2, 2)).name,
      ok(templates.paths("a", "b", 1, 2, PathQueryLimits(2, 3, 100))).name,
      templates.loadedWindows().name
    ) ++ metrics.flatMap { m =>
      Vector(
        ok(templates.topK(m, 1, 2, ResearcherFilter.Unfiltered, 3)).name,
        ok(templates.topK(m, 1, 2, ResearcherFilter(topicIds = Vector("t")), 3)).name
      )
    }
    ids should contain allOf (
      "getResearcher",
      "directCoauthors",
      "directCoauthorsPage/first",
      "coauthorEdge",
      "shortestPath/2",
      "paths/2",
      "loadedWindows",
      "topK/PUBLICATION_COUNT",
      "topK/PUBLICATION_COUNT/topics",
      "topK/COMMUNITY_SIZE/topics"
    )
    ids.distinct.size shouldBe ids.size
    ids.count(_.startsWith("topK/")) shouldBe 18
  }

  test("every ranking text is read-only, parameterized and free of the caller's values") {
    for
      m <- MetricRanking.supported
      withTopics <- Vector(false, true)
    do
      val filter =
        if withTopics then ResearcherFilter(topicIds = Vector(hostile))
        else ResearcherFilter.Unfiltered
      val q = ok(templates.topK(m, 2010, 2013, filter, 5))
      writes.foreach(w => q.text.toUpperCase should not include w)
      q.text should not include hostile
      q.text should include("$window")
      q.text should include("$limit")
      q.text should include("id(r) ASC")
      q.parameters("window") shouldBe ujson.Str("2010-2013")
      withTopics shouldBe q.parameters.contains("topicIds")
      if withTopics then
        q.text should include("$topicIds")
        q.parameters("topicIds") shouldBe ujson.Arr(hostile)
  }

  test("the ranked property comes from the metric table, one text per metric") {
    val texts = MetricRanking.supported.map(m =>
      ok(templates.topK(m, 1, 2, ResearcherFilter.Unfiltered, 3)).text
    )
    texts.size shouldBe MetricRanking.supported.size
    ok(
      templates.topK(CollaborationMetric.WEIGHTED_DEGREE, 1, 2, ResearcherFilter.Unfiltered, 3)
    ).text should include("ORDER BY r.W_r DESC, id(r) ASC")
  }

  test("the new fixed texts are read-only and read COAUTHORED_WITH as undirected") {
    val all = Vector(
      CypherTemplates.DirectCoauthorsFirst,
      CypherTemplates.DirectCoauthorsAfter,
      CypherTemplates.pathsText(3),
      CypherTemplates.LoadedWindows
    )
    all.foreach(t => writes.foreach(w => t.toUpperCase should not include w))
    Vector(CypherTemplates.DirectCoauthorsFirst, CypherTemplates.DirectCoauthorsAfter).foreach {
      t =>
        t should include("-[e:COAUTHORED_WITH]-(")
        t should not include "->"
    }
    CypherTemplates.pathsText(3) should include("-[:COAUTHORED_WITH*1..3]-")
  }
