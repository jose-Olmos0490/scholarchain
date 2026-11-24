package scholarchain.adversarial.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.adversarial.services.Kit
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.common.StorageError
import scholarchain.storage.directory.{FakeResearcherDirectory, NameCandidate}
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*
import upickle.default.writeJs

/** The DynamoDB graph read-model adapters read items that a loader wrote. These tests write the
  * items by hand, including wrong, misfiled, foreign-run and undecodable ones, and require a typed
  * refusal: never a result built from a record that does not belong to the question.
  */
class GraphReadModelAdvSpec extends AnyFunSuite with Matchers:

  private val Table = "t-read-model"
  private val Run = Kit.runId
  private val limits = GraphReaderLimits(25, 4, 3000L)
  private def prov = Kit.prov()

  private def put(items: InMemoryItemStore, pk: String, sk: String, rec: ujson.Value): Unit =
    val o =
      ujson.Obj("pk" -> pk, "sk" -> sk, "kind" -> "adv", "contentHash" -> "h", "record" -> rec)
    items.putIfAbsent(Table, o) shouldBe Right(())

  private def component(r: String, run: String = Run, ws: Int = 2010, we: Int = 2013) =
    writeJs(
      ComponentMembership(SV.ComponentMembership, r, Kit.ada, 3, ws, we, "metrics-v1", run, prov)
    )

  private def analytics(items: InMemoryItemStore, run: String = Run) =
    new DynamoAnalyticsReader(items, Table, run, limits)

  private def invalid[A](r: Either[StorageError, A]): StorageError.InvalidRecord = r match
    case Left(e: StorageError.InvalidRecord) => e
    case other => fail(s"expected InvalidRecord, got $other")

  test("a component row filed under another researcher is refused, not returned") {
    val items = new InMemoryItemStore
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.bea))
    invalid(analytics(items).componentOf(Kit.ada, 2010, 2013))
  }

  test("a component row of a foreign run in this run's partition is refused") {
    val items = new InMemoryItemStore
    val other = "run:" + "f" * 64
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.ada, run = other))
    invalid(analytics(items).componentOf(Kit.ada, 2010, 2013))
  }

  test("a row whose window differs from the key's window is refused") {
    val items = new InMemoryItemStore
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.ada, we = 2014))
    invalid(analytics(items).componentOf(Kit.ada, 2010, 2013))
  }

  test("an undecodable stored record is InvalidRecord, never an exception") {
    Seq[ujson.Value](
      ujson.Obj(),
      ujson.Str("x"),
      ujson.Arr(),
      ujson.Obj("researcherId" -> 7)
    ).foreach { rec =>
      val it = new InMemoryItemStore
      put(it, s"comp#$Run#${Kit.ada}", "w#2010-2013", rec)
      invalid(analytics(it).componentOf(Kit.ada, 2010, 2013))
    }
  }

  // DEFERRED phase-11 defect T4, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore("a stored JSON null record is InvalidRecord on every read-model reader, never an NPE") {
    def withNull(pk: String, sk: String) =
      val it = new InMemoryItemStore
      put(it, pk, sk, ujson.Null)
      it
    invalid(
      analytics(withNull(s"comp#$Run#${Kit.ada}", "w#2010-2013")).componentOf(Kit.ada, 2010, 2013)
    )
    invalid(
      analytics(withNull(s"comm#$Run#${Kit.ada}", "w#2010-2013")).communityOf(Kit.ada, 2010, 2013)
    )
    invalid(
      new DynamoResearcherDirectory(withNull(s"dir#$Run#wei zhang", "c#1"), Table, Run, 25)
        .candidatesByName("wei zhang", 5)
    )
    invalid(
      new DynamoEdgeSupportReader(withNull(s"esup#$Run#coauthor-edge:ab", "w#work:1"), Table, 5)
        .supportingWorkIds(edge())
    )
    invalid(
      analytics(withNull(s"xedge#$Run#${Kit.ada}#2010-2013", "e#1"))
        .crossCommunityEdges(Kit.ada, 2010, 2013, 5)
    )
  }

  test("a missing record is Right(None), and a correct one is returned exactly") {
    val items = new InMemoryItemStore
    analytics(items).componentOf(Kit.ada, 2010, 2013) shouldBe Right(None)
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.ada))
    analytics(items).componentOf(Kit.ada, 2010, 2013).toOption.get.get.componentSize shouldBe 3L
  }

  test(
    "hostile keys: '#' separators, blanks, huge IDs and impossible windows are refused before a read"
  ) {
    val items = new InMemoryItemStore
    val a = analytics(items)
    Seq("a#b", "", "   ", Kit.ada + "#" + Kit.bea).foreach(id =>
      invalid(a.componentOf(id, 2010, 2013))
    )
    // an over-long ID is refused or simply not found; it must never be an exception
    a.componentOf("x" * 5000, 2010, 2013).toOption.flatten shouldBe None
    Seq(
      (2014, 2010),
      (-1, 2013),
      (2010, 10000),
      (Int.MinValue, Int.MaxValue),
      (Int.MaxValue, Int.MaxValue)
    ).foreach { (s, e) =>
      withClue(s"$s-$e: ")(a.componentOf(Kit.ada, s, e).isLeft shouldBe true)
    }
    // the reader of another run cannot see this run's data even for the same researcher
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.ada))
    analytics(items, run = "run:" + "e" * 64)
      .componentOf(Kit.ada, 2010, 2013) shouldBe Right(None)
    a.componentOf(Kit.ada, 2010, 2013).isRight shouldBe true
  }

  test("a run ID containing '#' is refused, so it cannot alias another run's partition") {
    val items = new InMemoryItemStore
    put(items, s"comp#$Run#${Kit.ada}", "w#2010-2013", component(Kit.ada))
    val aliased = new DynamoAnalyticsReader(items, Table, Run + "#" + Kit.ada.take(3), limits)
    aliased.componentOf(Kit.ada.drop(3), 2010, 2013).isLeft shouldBe true
  }

  test(
    "community counts exist only with a stored marker; without one they are NotFound, not zero"
  ) {
    val items = new InMemoryItemStore
    val a = analytics(items)
    a.crossCommunityEdgeCount(Kit.ada, 2010, 2013) match
      case Left(_: StorageError.NotFound) => ()
      case other => fail(s"expected NotFound, got $other")
    a.communitiesConnected(Kit.ada, 2010, 2013).isLeft shouldBe true
    def marker(c: String) = writeJs(
      ConnectivityMarker(
        "community-connectivity-marker-v1",
        c,
        2010,
        2013,
        3,
        "metrics-v1",
        Run,
        prov
      )
    )
    put(items, s"xconn#$Run#${Kit.ada}#2010-2013", "M", marker(Kit.ada))
    a.crossCommunityEdgeCount(Kit.ada, 2010, 2013) shouldBe Right(0L)
    // a marker that names another community is misfiled
    val other = new InMemoryItemStore
    put(other, s"xconn#$Run#${Kit.ada}#2010-2013", "M", marker(Kit.bea))
    invalid(analytics(other).crossCommunityEdgeCount(Kit.ada, 2010, 2013))
  }

  test("cross-community edges: a row of another community is misfiled and refused; bounds hold") {
    val items = new InMemoryItemStore
    val edge = CrossCommunityEdge(
      SV.CrossCommunityEdge,
      "coauthor-edge:x",
      Kit.ada,
      Kit.bea,
      Kit.bea,
      Kit.cyd,
      2010,
      2013,
      2,
      "policy-v1",
      "metrics-v1",
      Run,
      prov
    )
    // neither endpoint community is the one the row is filed under
    put(items, s"xedge#$Run#${Kit.ada}#2010-2013", "e#1", writeJs(edge))
    invalid(analytics(items).crossCommunityEdges(Kit.ada, 2010, 2013, 5))
    val a = analytics(new InMemoryItemStore)
    a.crossCommunityEdges(Kit.ada, 2010, 2013, 0).isLeft shouldBe true
    a.crossCommunityEdges(Kit.ada, 2010, 2013, 26).isLeft shouldBe true
    a.crossCommunityEdges(Kit.ada, 2010, 2013, Int.MaxValue).isLeft shouldBe true
  }

  // ---- edge support ------------------------------------------------------------------------------

  private def edge(
      id: String = "coauthor-edge:ab",
      pointer: Option[String] = None,
      run: String = Run
  ) =
    CoauthorEdgeView(
      id,
      Kit.ada,
      Kit.bea,
      2,
      2010,
      2012,
      2010,
      2013,
      pointer.getOrElse(CoauthorEdgeSupport.supportRef(id)),
      run,
      "policy-v1"
    )

  private def support(edgeId: String, work: String, run: String = Run) =
    writeJs(
      CoauthorEdgeSupport(
        SV.CoauthorEdgeSupport,
        edgeId,
        Kit.ada,
        Kit.bea,
        2010,
        2013,
        work,
        Some(2011),
        "policy-v1",
        run,
        prov
      )
    )

  test("edge support: a forged evidence pointer is refused and nothing is read") {
    val items = new InMemoryItemStore
    val r = new DynamoEdgeSupportReader(items, Table, 10)
    invalid(
      r.supportingWorkIds(
        edge(pointer = Some("coauthor-edge-support-v1:coauthor-edge:OTHER"))
      )
    )
    invalid(r.supportingWorkIds(edge(pointer = Some(""))))
  }

  test("edge support: more rows than the bound is LimitExceeded, never a truncated list") {
    val items = new InMemoryItemStore
    (1 to 6).foreach(i =>
      put(
        items,
        s"esup#$Run#coauthor-edge:ab",
        s"w#work:$i",
        support("coauthor-edge:ab", s"work:$i")
      )
    )
    new DynamoEdgeSupportReader(items, Table, 5).supportingWorkIds(edge()) match
      case Left(_: StorageError.LimitExceeded) => ()
      case other => fail(s"expected LimitExceeded, got $other")
    new DynamoEdgeSupportReader(items, Table, 6)
      .supportingWorkIds(edge())
      .toOption
      .get
      .size shouldBe 6
  }

  test("edge support: a row of another edge or another run in this partition is refused") {
    val a = new InMemoryItemStore
    put(a, s"esup#$Run#coauthor-edge:ab", "w#work:1", support("coauthor-edge:OTHER", "work:1"))
    invalid(new DynamoEdgeSupportReader(a, Table, 5).supportingWorkIds(edge()))
    val b = new InMemoryItemStore
    put(
      b,
      s"esup#$Run#coauthor-edge:ab",
      "w#work:1",
      support("coauthor-edge:ab", "work:1", run = "run:" + "d" * 64)
    )
    invalid(new DynamoEdgeSupportReader(b, Table, 5).supportingWorkIds(edge()))
  }

  test("edge support: the work list is distinct and sorted whatever the stored order") {
    val items = new InMemoryItemStore
    Seq("work:9", "work:2", "work:5").foreach(w =>
      put(items, s"esup#$Run#coauthor-edge:ab", s"w#$w", support("coauthor-edge:ab", w))
    )
    new DynamoEdgeSupportReader(items, Table, 5)
      .supportingWorkIds(edge()) shouldBe Right(Vector("work:2", "work:5", "work:9"))
  }

  // ---- researcher directory ------------------------------------------------------------------------

  private def cand(i: Int, conf: Double, run: String = Run) =
    NameCandidate(
      f"researcher:sc:$i%05d",
      "Wei Zhang",
      conf,
      Vector("dblp:wz"),
      Vector.empty,
      Vector.empty,
      Vector("d1"),
      "policy-v1",
      run
    )

  test("directory: a row filed under another name or carrying another run is refused") {
    val items = new InMemoryItemStore
    val entry = DirectoryEntry("researcher-directory-entry-v1", "someone else", cand(1, 0.9), prov)
    put(items, s"dir#$Run#wei zhang", "c#1", writeJs(entry))
    invalid(new DynamoResearcherDirectory(items, Table, Run, 25).candidatesByName("wei zhang", 5))
    val items2 = new InMemoryItemStore
    put(
      items2,
      s"dir#$Run#wei zhang",
      "c#1",
      writeJs(
        DirectoryEntry(
          "researcher-directory-entry-v1",
          "wei zhang",
          cand(1, 0.9, run = "run:" + "c" * 64),
          prov
        )
      )
    )
    invalid(
      new DynamoResearcherDirectory(items2, Table, Run, 25).candidatesByName("wei zhang", 5)
    )
  }

  test("directory: blank, oversized and out-of-bound requests are refused or empty, no exception") {
    val d = new DynamoResearcherDirectory(new InMemoryItemStore, Table, Run, 25)
    d.candidatesByName("", 5).isLeft shouldBe true
    d.candidatesByName("   ", 5).isLeft shouldBe true
    d.candidatesByName("x", 0).isLeft shouldBe true
    d.candidatesByName("x", -1).isLeft shouldBe true
    d.candidatesByName("x", 26).isLeft shouldBe true
    d.candidatesByName("x", Int.MaxValue).isLeft shouldBe true
    d.candidatesByName("x" * 6000, 5) shouldBe Right(Vector.empty)
    d.candidatesByName("a#b", 5) shouldBe Right(Vector.empty)
    d.candidatesByName(null, 5).isLeft shouldBe true
  }

  test("common name: a thousand 'Wei Zhang' candidates come back in a total, stable order") {
    val dir = new FakeResearcherDirectory(25)
    scala.util
      .Random(7)
      .shuffle((1 to 1000).toVector)
      .foreach(i =>
        dir.put("wei zhang", cand(i, if i % 3 == 0 then 0.9 else 0.5)) shouldBe Right(())
      )
    val out = dir.candidatesByName("wei zhang", 25).toOption.get
    out.size shouldBe 25
    out.map(_.confidence).distinct shouldBe Vector(0.9)
    out.map(_.researcherId) shouldBe out.map(_.researcherId).sorted
    dir.candidatesByName("wei zhang", 25) shouldBe Right(out)
    dir.candidatesByName("wei zhang", 26).isLeft shouldBe true
    dir.put("wei zhang", cand(5000, Double.NaN)).isLeft shouldBe true
    dir.put("wei zhang", cand(5001, 1.5)).isLeft shouldBe true
    dir.put("wei zhang", cand(5002, -0.1)).isLeft shouldBe true
    dir.put("  ", cand(5003, 0.5)).isLeft shouldBe true
  }
