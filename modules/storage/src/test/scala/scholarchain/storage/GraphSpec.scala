package scholarchain.storage

import java.nio.file.Files
import org.scalatest.Tag
import scholarchain.core.model.*
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.*
import scholarchain.storage.objects.*

/** Reusable contract of the graph read port, over the loaded fixture graph. The fake runs it by
  * default; the tagged `NeptuneGraphIT` runs it against a real cluster.
  */
abstract class GraphReaderContract extends StorageSuite with PortGate:
  protected def reader: GraphReader
  protected def prepare(): Unit
  protected def limits: GraphReaderLimits

  import Fixture.*

  private def contract(name: String)(body: => Unit): Unit =
    test(s"graph reader contract: $name", contractTags*) {
      preconditions()
      prepare()
      body
    }

  contract("getResearcher returns the node with the metrics of the node window") {
    val r = ok(reader.getResearcher(resA.researcherId)).get
    r.researcherId shouldBe resA.researcherId
    r.displayName shouldBe "Henry Samueli"
    r.orcid shouldBe Some("0000-0003-3175-3913")
    r.runId shouldBe runId
    val m = r.metrics.get
    m.window shouldBe "2010-2013"
    (m.publicationCount, m.distinctCoauthorCount, m.totalCoauthorOccurrences) shouldBe (2L, 1L, 2L)
    (m.averageCoauthorsPerPaper, m.maximumCoauthorsOnOnePaper, m.weightedDegree) shouldBe (
      1.0,
      1L,
      2.0
    )
    m.componentId shouldBe Vector(resA, resB, resC).map(_.researcherId).min
    m.coreness shouldBe Some(1)
    m.bridgeScore shouldBe None
    m.metricVersion shouldBe MetricVersion
    ok(reader.getResearcher(resC.researcherId)).get.metrics.get.weightedDegree shouldBe 1.0
  }

  contract("getResearcher of an unknown or non-researcher ID is None") {
    ok(reader.getResearcher("researcher:orcid:" + "0" * 64)) shouldBe None
    ok(reader.getResearcher(w1.workId)) shouldBe None
  }

  contract("directCoauthors is undirected, ordered by joint publications, and window-scoped") {
    val ofB = ok(reader.directCoauthors(resB.researcherId, 2010, 2013, limit = 10))
    ofB.map(_.coauthorId) shouldBe Vector(resA.researcherId, resC.researcherId)
    ofB.map(_.edge.jointPublicationCount) shouldBe Vector(2L, 1L)
    // the edge is stored once, in canonical direction, whichever side asked
    ofB.foreach(c => (c.edge.researcherA < c.edge.researcherB) shouldBe true)
    ok(reader.directCoauthors(resA.researcherId, 2010, 2013, 10)).map(_.coauthorId) shouldBe
      Vector(resB.researcherId)
    ok(reader.directCoauthors(resC.researcherId, 2010, 2013, 10)).map(_.coauthorId) shouldBe
      Vector(resB.researcherId)
    ok(reader.directCoauthors(resB.researcherId, 2010, 2012, 10)).map(_.coauthorId) shouldBe
      Vector(resA.researcherId)
    ok(reader.directCoauthors(resB.researcherId, 1990, 1999, 10)) shouldBe empty
  }

  contract("directCoauthors honours the limit") {
    ok(reader.directCoauthors(resB.researcherId, 2010, 2013, limit = 1)).map(_.coauthorId) shouldBe
      Vector(resA.researcherId)
  }

  contract("coauthorEdge finds the edge for either argument order, with its evidence pointer") {
    val ab = ok(reader.coauthorEdge(resA.researcherId, resB.researcherId, 2010, 2013)).get
    ok(reader.coauthorEdge(resB.researcherId, resA.researcherId, 2010, 2013)) shouldBe Some(ab)
    ab shouldBe CoauthorEdgeView(
      edgeAbAll.edgeId,
      edgeAbAll.researcherA,
      edgeAbAll.researcherB,
      2,
      2010,
      2012,
      2010,
      2013,
      CoauthorEdgeSupport.supportRef(edgeAbAll.edgeId),
      runId,
      CoauthorPolicy
    )
    ok(reader.coauthorEdge(resA.researcherId, resB.researcherId, 2010, 2012)).get.edgeId shouldBe
      edgeAbEarly.edgeId
    ok(reader.coauthorEdge(resA.researcherId, resC.researcherId, 2010, 2013)) shouldBe None
  }

  contract("shortestPath returns a shortest path inside one window, bounded by depth") {
    val p =
      ok(reader.shortestPath(resA.researcherId, resC.researcherId, 2010, 2013, maxDepth = 4)).get
    p.researcherIds shouldBe Vector(resA.researcherId, resB.researcherId, resC.researcherId)
    p.edgeIds shouldBe Vector(edgeAbAll.edgeId, edgeBcAll.edgeId).map(identity)
    p.length shouldBe 2
    // B-C exists only in the 2010-2013 window
    ok(reader.shortestPath(resA.researcherId, resC.researcherId, 2010, 2012, 4)) shouldBe None
    // two hops are needed
    ok(reader.shortestPath(resA.researcherId, resC.researcherId, 2010, 2013, 1)) shouldBe None
    ok(
      reader.shortestPath(resA.researcherId, resB.researcherId, 2010, 2013, 1)
    ).get.length shouldBe 1
  }

  contract("limits from configuration are enforced and refused, not clamped") {
    err(reader.directCoauthors(resA.researcherId, 2010, 2013, limits.maxLimit + 1)) shouldBe
      StorageError.LimitExceeded("result limit", limits.maxLimit, limits.maxLimit + 1)
    err(reader.directCoauthors(resA.researcherId, 2010, 2013, 0)) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(
      reader.shortestPath(resA.researcherId, resC.researcherId, 2010, 2013, limits.maxDepth + 1)
    ) shouldBe
      StorageError.LimitExceeded("path depth", limits.maxDepth, limits.maxDepth + 1)
    err(reader.shortestPath(resA.researcherId, resC.researcherId, 2010, 2013, 0)) shouldBe
      a[StorageError.InvalidRecord]
  }

/** The whole Neptune load on fakes, plus the reader contract over the loaded graph. */
class GraphFakeSpec extends GraphReaderContract:
  protected val limits: GraphReaderLimits =
    GraphReaderLimits(TestEnv.settings.graphLimit, TestEnv.settings.maxPathDepth)
  private val graph = new InMemoryGraph
  private val objects = new InMemoryObjectStore
  protected val reader: GraphReader = new FakeGraphReader(graph, limits)
  private val stager = new LocalFileStager(FixturePaths.root)
  private val workflow =
    new GraphLoadWorkflow(
      stager,
      objects,
      new FakeGraphLoader(objects, graph),
      new InMemoryRejectSink,
      tempDir("graph-work-")
    )
  protected def prepare(): Unit =
    if graph.state.isEmpty then ok(workflow.run(manifestOf(FixturePaths.root, "neptune")))

  test("the load verifies, exports, validates, uploads under run_id=<hex>/ and starts the job") {
    val g = new InMemoryGraph
    val o = new InMemoryObjectStore
    val loader = new FakeGraphLoader(o, g)
    val w = new GraphLoadWorkflow(stager, o, loader, new InMemoryRejectSink, tempDir("graph-w2-"))
    val report = ok(w.run(manifestOf(FixturePaths.root, "neptune")))
    report.validation shouldBe ValidationSummary(nodeIds = 9, relationships = 10, files = 9)
    ok(loader.status(report.job)) shouldBe GraphLoadState.Completed
    val keys = o.snapshot.map(_._1)
    keys.foreach(_.area shouldBe ObjectArea.NeptuneExport)
    keys.foreach(_.key should startWith(s"run_id=${Fixture.runHex}/"))
    keys.map(_.key).count(_.contains("/csv/")) shouldBe 9
    keys.map(_.key) should contain(s"run_id=${Fixture.runHex}/csv-export-manifest.json")
    report.uploaded shouldBe 10
    report.skipped shouldBe 0
    g.node(Fixture.resA.researcherId).get.labels shouldBe Set("Researcher")
    g.allRels.map(_.relType).toSet shouldBe Set("AUTHORED", "HAS_TOPIC", "COAUTHORED_WITH")
    g.allRels.map(_.id).toSet should contain(Fixture.edgeAbAll.edgeId)
  }

  test("idempotent reload: loading the same run twice gives identical graph and object state") {
    val g = new InMemoryGraph
    val o = new InMemoryObjectStore
    val w = new GraphLoadWorkflow(
      stager,
      o,
      new FakeGraphLoader(o, g),
      new InMemoryRejectSink,
      tempDir("graph-w3-")
    )
    val first = ok(w.run(manifestOf(FixturePaths.root, "neptune")))
    val graphBefore = g.state
    val objectsBefore = o.snapshot
    val second = ok(w.run(manifestOf(FixturePaths.root, "neptune")))
    g.state shouldBe graphBefore
    o.snapshot shouldBe objectsBefore
    second.uploaded shouldBe 0
    second.skipped shouldBe first.uploaded
  }

  private def failedRun(
      dir: java.nio.file.Path,
      m: LoadManifest
  ): (StorageError, InMemoryGraph, InMemoryObjectStore, FakeGraphLoader) =
    val g = new InMemoryGraph
    val o = new InMemoryObjectStore
    val loader = new FakeGraphLoader(o, g)
    val w = new GraphLoadWorkflow(
      new LocalFileStager(dir),
      o,
      loader,
      new InMemoryRejectSink,
      tempDir("graph-w4-")
    )
    (err(w.run(m)), g, o, loader)

  test("a load whose edge points at a missing node is stopped before any upload or job") {
    val dir = fixtureCopy()
    val m = FixtureTamper.rewrite(dir, manifestOf(dir, "neptune"), "work", fileIndex = 0)(
      _.filterNot(_("workId").str == Fixture.w1.workId)
    )
    val (e, g, o, loader) = failedRun(dir, m)
    e.message should include("DANGLING_ENDPOINT")
    g.state shouldBe empty
    o.snapshot shouldBe empty
  }

  test("a load with a coauthor edge that has no evidence pointer is stopped") {
    val dir = fixtureCopy()
    val m =
      FixtureTamper.rewrite(dir, manifestOf(dir, "neptune"), "coauthored_with", fileIndex = 0) {
        rows =>
          rows.map { r =>
            val o = ujson.read(r.render()).obj
            o("supportingWorkIdsRef") = ujson.Null
            ujson.Obj(o)
          }
      }
    val (e, g, o, _) = failedRun(dir, m)
    e.message should include("MISSING_EVIDENCE_POINTER")
    g.state shouldBe empty
    o.snapshot shouldBe empty
  }

  test("a checksum mismatch in the manifest stops the load before the export") {
    val dir = fixtureCopy()
    val m = manifestOf(dir, "neptune")
    val p = dir.resolve(m.tables.head.files.head.path)
    val b = Files.readAllBytes(p)
    b(20) = (b(20) ^ 0x01).toByte
    Files.write(p, b)
    val (e, g, o, _) = failedRun(dir, m)
    e shouldBe a[StorageError.ChecksumMismatch]
    g.state shouldBe empty
    o.snapshot shouldBe empty
  }

  test("the CSV loaded twice into the fake graph merges: overlay properties land on the node") {
    val g = new InMemoryGraph
    val o = new InMemoryObjectStore
    ok(
      new GraphLoadWorkflow(
        stager,
        o,
        new FakeGraphLoader(o, g),
        new InMemoryRejectSink,
        tempDir("graph-w5-")
      ).run(manifestOf(FixturePaths.root, "neptune"))
    )
    val node = g.node(Fixture.resB.researcherId).get
    node.props("displayName") shouldBe ujson.Str("Josiah Carberry")
    node.props("P_r") shouldBe ujson.Num(3)
    node.props("window") shouldBe ujson.Str("2010-2013")
  }

/** Queries are constants; caller values only travel as parameters. */
class CypherTemplatesSpec extends StorageSuite:
  private val templates = new CypherTemplates(GraphReaderLimits(25, 4))
  private val injection = "x' }) DETACH DELETE n //"

  test("a template's text never contains a caller value: the same text for any input") {
    val q1 = templates.getResearcher("researcher:a")
    val q2 = templates.getResearcher(injection)
    q1.text shouldBe q2.text
    q2.text should not include "DETACH"
    q2.parameters("researcherId") shouldBe ujson.Str(injection)
    val d1 = ok(templates.directCoauthors("a", 2000, 2005, 5))
    val d2 = ok(templates.directCoauthors(injection, 1999, 2030, 25))
    d1.text shouldBe d2.text
    d2.text should not include injection
    val p1 = ok(templates.shortestPath("a", "b", 2000, 2005, 3))
    val p2 = ok(templates.shortestPath(injection, injection, 1, 2, 3))
    p1.text shouldBe p2.text
    templates.coauthorEdge("a", "b", 1, 2).text shouldBe templates
      .coauthorEdge(injection, "b", 3, 4)
      .text
  }

  test("every value a template uses is a named parameter") {
    val q = ok(templates.directCoauthors("a", 2000, 2005, 5))
    q.parameters.keySet shouldBe Set("researcherId", "windowStartYear", "windowEndYear", "limit")
    Set("$researcherId", "$windowStartYear", "$windowEndYear", "$limit").foreach(p =>
      q.text should include(p)
    )
    val s = ok(templates.shortestPath("a", "b", 2000, 2005, 3))
    s.parameters.keySet shouldBe Set(
      "source",
      "target",
      "windowStartYear",
      "windowEndYear",
      "candidateLimit"
    )
    Set("$source", "$target", "$windowStartYear", "$windowEndYear", "$candidateLimit").foreach(p =>
      s.text should include(p)
    )
  }

  test("templates are read-only and read COAUTHORED_WITH as undirected") {
    val all = Vector(
      CypherTemplates.GetResearcher,
      CypherTemplates.DirectCoauthors,
      CypherTemplates.CoauthorEdgeText,
      CypherTemplates.pathText(3)
    )
    val writes = Vector("CREATE", "DELETE", "SET ", "MERGE", "REMOVE", "DROP", "LOAD")
    all.foreach(t => writes.foreach(w => t.toUpperCase should not include w))
    Vector(CypherTemplates.DirectCoauthors, CypherTemplates.CoauthorEdgeText).foreach { t =>
      t should include("-[e:COAUTHORED_WITH]-(")
      t should not include "->"
      t should not include "<-"
    }
    CypherTemplates.pathText(3) should include("-[:COAUTHORED_WITH*1..3]-")
  }

  test("depth and limit are bounded by configuration; the path bound comes from a lookup table") {
    templates.shortestPath("a", "b", 1, 2, 4).isRight shouldBe true
    err(templates.shortestPath("a", "b", 1, 2, 5)) shouldBe StorageError.LimitExceeded(
      "path depth",
      4,
      5
    )
    err(templates.directCoauthors("a", 1, 2, 26)) shouldBe StorageError.LimitExceeded(
      "result limit",
      25,
      26
    )
    ok(templates.shortestPath("a", "b", 1, 2, 2)).text should include("*1..2")
    ok(templates.shortestPath("a", "b", 1, 2, 4)).text should include("*1..4")
  }
