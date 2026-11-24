package scholarchain.storage

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.aws.{AwsErrors, DynamoItemStore}
import scholarchain.storage.common.*
import scholarchain.storage.directory.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import upickle.default.*

/** The write side of the read model contract suites, shared by the in-memory specs and the AWS
  * suites: how a record becomes an item. The real adapters are seeded through the same item
  * builders the loader uses, so a contract proves that the loader's keys are the readers' keys.
  */
object ReadModelSeed:
  private def okR[A](r: Result[A]): A =
    r.fold(e => throw new IllegalStateException(e.message), identity)

  private def put(items: ItemStore, table: String, item: ujson.Obj): Unit =
    okR(ItemCodec.createOrSame(items, table, item))
    ()

  def directory(
      items: ItemStore,
      table: String,
      run: String,
      name: String,
      c: NameCandidate
  ): Unit =
    import ReadModelKeys.*
    val conf = okR(invertedDouble(c.confidence))
    put(
      items,
      table,
      okR(
        make(
          directoryPk(run, name),
          s"c#$conf#${c.researcherId}",
          "ResearcherDirectoryEntry",
          DirectoryEntry(ReadModelSchemas.DirectoryEntry, name, c, RM.prov(run, c.researcherId))
        )
      )
    )

  def support(items: ItemStore, table: String, run: String, s: CoauthorEdgeSupport): Unit =
    put(items, table, okR(ReadModelItems.support(s, run)))

  def component(items: ItemStore, table: String, run: String, m: ComponentMembership): Unit =
    put(items, table, okR(ReadModelItems.component(m, run)))

  def community(items: ItemStore, table: String, run: String, m: CommunityMembership): Unit =
    put(items, table, okR(ReadModelItems.community(m, run)))

  def cross(items: ItemStore, table: String, run: String, e: CrossCommunityEdge): Unit =
    okR(ReadModelItems.crossEdge(e, run, RM.prov(run, run))).foreach(put(items, table, _))

  def marker(items: ItemStore, table: String, run: String, c: String, ws: Int, we: Int): Unit =
    put(
      items,
      table,
      okR(ReadModelItems.marker(RM.community(c, c, 1, ws, we, run), run, RM.prov(run, run)))
    )

// ---- the fakes ----

class DirectoryFakeSpec extends ResearcherDirectoryContract:
  protected val runId: String = Fixture.runId
  private val fake = new FakeResearcherDirectory(maxLimit)
  protected def directory: ResearcherDirectory = fake
  protected def seed(name: String, c: NameCandidate): Unit = ok(fake.put(name, c))

class EdgeSupportFakeSpec extends EdgeSupportReaderContract:
  protected val runId: String = Fixture.runId
  private val fake = new FakeEdgeSupportReader
  protected def reader: EdgeSupportReader = fake
  private val rows = mutable.Map.empty[(String, String), Set[String]]
  protected def seed(row: CoauthorEdgeSupport): Unit =
    val key = (CoauthorEdgeSupport.supportRef(row.edgeId), row.runId)
    rows(key) = rows.getOrElse(key, Set.empty) + row.workId
    fake.put(key._1, key._2, rows(key))

class AnalyticsFakeSpec extends AnalyticsReaderContract:
  protected val runId: String = Fixture.runId
  private val limits = GraphReaderLimits(maxLimit, 4, 3000L)
  private val fake = new FakeAnalyticsReader(limits)
  private val crossEdges = mutable.ArrayBuffer.empty[CrossCommunityEdge]
  protected def reader: AnalyticsReader = fake
  protected def seedComponent(m: ComponentMembership): Unit = fake.putComponent(m)
  protected def seedCommunity(m: CommunityMembership): Unit = fake.putCommunity(m)
  protected def seedCross(e: CrossCommunityEdge): Unit =
    crossEdges += e
    fake.putCrossEdge(e)
  protected def commitConnectivity(c: String, ws: Int, we: Int): Unit =
    val mine = crossEdges.filter(e =>
      e.windowStartYear == ws && e.windowEndYear == we && (e.communityA == c || e.communityB == c)
    )
    val others = mine.map(e => if e.communityA == c then e.communityB else e.communityA).distinct
    fake.putConnectivity(
      CommunityConnectivity(
        c,
        ws,
        we,
        mine.size.toLong,
        others.size.toLong,
        RM.MetricVersion,
        runId
      )
    )

// ---- the DynamoDB adapters over the in-memory item store ----

object ReadModelTable:
  val Name = "scholarchain-test-graph-read-model"

class DynamoDirectorySpec extends ResearcherDirectoryContract:
  protected val runId: String = Fixture.runId
  private val items = new InMemoryItemStore
  protected def directory: ResearcherDirectory =
    new DynamoResearcherDirectory(items, ReadModelTable.Name, runId, maxLimit)
  protected def seed(name: String, c: NameCandidate): Unit =
    ReadModelSeed.directory(items, ReadModelTable.Name, runId, name, c)

  contract("directory (dynamo): a candidate stored for another run is never returned") {
    val name = s"other run ${RM.nextN()}"
    val other = Fresh.runId()
    ReadModelSeed.directory(
      items,
      ReadModelTable.Name,
      other,
      name,
      RM.candidate(RM.nextN(), "X", 0.5, other)
    )
    ok(directory.candidatesByName(name, 3)) shouldBe empty
  }

  contract(
    "directory (dynamo): a row whose candidate belongs to another run than its key is corrupt"
  ) {
    val name = s"corrupt ${RM.nextN()}"
    ReadModelSeed.directory(
      items,
      ReadModelTable.Name,
      runId,
      name,
      RM.candidate(RM.nextN(), "X", 0.5, Fresh.runId())
    )
    err(directory.candidatesByName(name, 3)) shouldBe a[StorageError.InvalidRecord]
  }

class DynamoEdgeSupportSpec extends EdgeSupportReaderContract:
  protected val runId: String = Fixture.runId
  private val items = new InMemoryItemStore
  protected def reader: EdgeSupportReader =
    new DynamoEdgeSupportReader(items, ReadModelTable.Name, maxRowsPerEdge = 4)
  protected def seed(row: CoauthorEdgeSupport): Unit =
    ReadModelSeed.support(items, ReadModelTable.Name, runId, row)

  contract("edge support (dynamo): more rows than the bound is refused, never a truncated list") {
    val x = RM.researcherId(RM.nextN())
    val y = RM.researcherId(RM.nextN())
    (1 to 5).foreach(_ => seed(RM.support(x, y, 2010, 2013, RM.workId(RM.nextN()), runId)))
    err(reader.supportingWorkIds(RM.edgeView(x, y, 2010, 2013, 5, runId))) shouldBe
      a[StorageError.LimitExceeded]
    // exactly the bound is fine
    val p = RM.researcherId(RM.nextN())
    (1 to 4).foreach(_ => seed(RM.support(x, p, 2010, 2013, RM.workId(RM.nextN()), runId)))
    ok(reader.supportingWorkIds(RM.edgeView(x, p, 2010, 2013, 4, runId))).size shouldBe 4
  }

class DynamoAnalyticsSpec extends AnalyticsReaderContract:
  protected val runId: String = Fixture.runId
  private val items = new InMemoryItemStore
  private val limits = GraphReaderLimits(maxLimit, 4, 3000L)
  private val T = ReadModelTable.Name
  protected def reader: AnalyticsReader = new DynamoAnalyticsReader(items, T, runId, limits)
  protected def seedComponent(m: ComponentMembership): Unit =
    ReadModelSeed.component(items, T, runId, m)
  protected def seedCommunity(m: CommunityMembership): Unit =
    ReadModelSeed.community(items, T, runId, m)
  protected def seedCross(e: CrossCommunityEdge): Unit = ReadModelSeed.cross(items, T, runId, e)
  protected def commitConnectivity(c: String, ws: Int, we: Int): Unit =
    ReadModelSeed.marker(items, T, runId, c, ws, we)

  contract("analytics (dynamo): records of another run are invisible") {
    val r = RM.researcherId(RM.nextN())
    val other = Fresh.runId()
    ReadModelSeed.component(items, T, other, RM.component(r, r, 1, 2010, 2013, other))
    ok(reader.componentOf(r, 2010, 2013)) shouldBe None
  }

  contract("analytics (dynamo): a year outside the key format is refused") {
    err(reader.componentOf(RM.researcherId(RM.nextN()), 2010, 10000)) shouldBe
      a[StorageError.InvalidRecord]
  }

class NameFoldSpec extends StorageSuite:
  test("the fold is the resolver's fold: case, marks, invisible characters and punctuation") {
    // the same cases are asserted against the resolver's NameNormalizer in the rag module
    NameFold.fold("  Wei  ZHANG ") shouldBe "wei zhang"
    NameFold.fold("José Müller-Smith") shouldBe "jose muller smith"
    NameFold.fold("O'Neil, Sean") shouldBe "o neil sean"
    NameFold.fold("​Ada Lovelace‍") shouldBe "ada lovelace"
    NameFold.fold(null) shouldBe ""
  }

  test("a researcher is indexed under each DBLP key as written and without its homonym number") {
    val r = RM.researcher(
      RM.nextN(),
      "Wei Zhang 0001",
      0.9,
      Fixture.runId,
      Vector("Wei Zhang 0001", "Wei Zhang 0002")
    )
    NameFold.namesOf(r) shouldBe Vector("wei zhang", "wei zhang 0001", "wei zhang 0002")
  }

  test("a number that is not a four-digit homonym suffix is part of the name") {
    val r = RM.researcher(RM.nextN(), "Henry 8", 0.9, Fixture.runId)
    NameFold.namesOf(r) shouldBe Vector("henry 8")
  }

class ItemStoreCountSpec extends StorageSuite:
  test(
    "the in-memory count is the number of items of the partition, optionally by sort-key prefix"
  ) {
    val items = new InMemoryItemStore
    Vector("e#1", "e#2", "o#1").foreach(sk =>
      ok(items.putIfAbsent("t", ujson.Obj("pk" -> "p", "sk" -> sk)))
    )
    ok(items.putIfAbsent("t", ujson.Obj("pk" -> "q", "sk" -> "e#1")))
    ok(items.count("t", "p", None)) shouldBe 3L
    ok(items.count("t", "p", Some("e#"))) shouldBe 2L
    ok(items.count("t", "nobody", None)) shouldBe 0L
  }

  test("the SDK count is a paginated Select=COUNT query with strongly consistent reads") {
    var calls = Vector.empty[QueryRequest]
    val stub = new DynamoDbClient:
      def serviceName(): String = "dynamodb"
      def close(): Unit = ()
      override def query(r: QueryRequest): QueryResponse =
        calls :+= r
        if r.exclusiveStartKey == null || r.exclusiveStartKey.isEmpty then
          QueryResponse
            .builder()
            .count(7)
            .lastEvaluatedKey(java.util.Map.of("pk", AttributeValue.fromS("p")))
            .build()
        else QueryResponse.builder().count(5).build()
    val policy = RetryPolicyConfig(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 2)
    val retrier = new Retrier(policy, AwsErrors.isTransient, new RecordingSleeper, MaxJitter)
    val store = new DynamoItemStore(stub, retrier)
    ok(store.count("t", "p", Some("e#"))) shouldBe 12L
    calls.size shouldBe 2
    calls.foreach { r =>
      r.select shouldBe Select.COUNT
      r.consistentRead shouldBe true
      r.keyConditionExpression shouldBe "pk = :pk AND begins_with(sk, :p)"
    }
  }

// ---- the loader, end to end through real Parquet files ----

object ReadModelFixture:
  import PType.*
  private val provenanceType = Struct(
    Vector(
      "schemaVersion" -> Str,
      "sourceDataset" -> Str,
      "sourceRecordId" -> Str,
      "snapshot" -> Str,
      "runId" -> Str,
      "policyVersion" -> Str,
      "modelVersion" -> Str,
      "promptVersion" -> Str,
      "derivedFrom" -> StrList
    )
  )
  private def cols(c: (String, PType)*) = c.toVector :+ ("provenance" -> provenanceType)
  private def js[A: ReadWriter](a: A): ujson.Obj = writeJs(a).obj

  private def table[A: ReadWriter](
      name: String,
      recordType: String,
      schemaVersion: String,
      location: String,
      schema: Vector[(String, PType)],
      rows: Vector[A]
  ): FixtureTable =
    FixtureTable(
      name,
      recordType,
      schemaVersion,
      s"graph/$location",
      Vector("run_id"),
      schema,
      Vector(
        s"graph/$location/run_id=${Fixture.runHex}/part-00000.snappy.parquet" -> rows.map(js(_))
      )
    )

  def researchers(rows: Vector[CanonicalResearcher]): FixtureTable =
    val t = Fixture.tableNamed("researcher")
    table("researcher", t.recordType, t.schemaVersion, "nodes/type=researcher", t.schema, rows)

  def support(rows: Vector[CoauthorEdgeSupport]): FixtureTable =
    table(
      "coauthored_with_support",
      "CoauthorEdgeSupport",
      SV.CoauthorEdgeSupport,
      "edges/type=coauthored_with_support",
      cols(
        "schemaVersion" -> Str,
        "edgeId" -> Str,
        "researcherA" -> Str,
        "researcherB" -> Str,
        "windowStartYear" -> I32,
        "windowEndYear" -> I32,
        "workId" -> Str,
        "workYear" -> I32,
        "policyVersion" -> Str,
        "runId" -> Str
      ),
      rows
    )

  def components(rows: Vector[ComponentMembership]): FixtureTable =
    table(
      "components",
      "ComponentMembership",
      SV.ComponentMembership,
      "metrics/type=components",
      cols(
        "schemaVersion" -> Str,
        "researcherId" -> Str,
        "componentId" -> Str,
        "componentSize" -> I64,
        "windowStartYear" -> I32,
        "windowEndYear" -> I32,
        "metricVersion" -> Str,
        "runId" -> Str
      ),
      rows
    )

  def communities(rows: Vector[CommunityMembership]): FixtureTable =
    table(
      "community_membership",
      "CommunityMembership",
      SV.CommunityMembership,
      "metrics/type=communities",
      cols(
        "schemaVersion" -> Str,
        "researcherId" -> Str,
        "communityId" -> Str,
        "communitySize" -> I64,
        "windowStartYear" -> I32,
        "windowEndYear" -> I32,
        "metricVersion" -> Str,
        "runId" -> Str
      ),
      rows
    )

  def crossEdges(rows: Vector[CrossCommunityEdge]): FixtureTable =
    table(
      "cross_community_edge",
      "CrossCommunityEdge",
      SV.CrossCommunityEdge,
      "edges/type=cross_community",
      cols(
        "schemaVersion" -> Str,
        "edgeId" -> Str,
        "researcherA" -> Str,
        "researcherB" -> Str,
        "communityA" -> Str,
        "communityB" -> Str,
        "windowStartYear" -> I32,
        "windowEndYear" -> I32,
        "jointPublicationCount" -> I64,
        "policyVersion" -> Str,
        "metricVersion" -> Str,
        "runId" -> Str
      ),
      rows
    )

  /** Writes the Parquet files under `root` and returns the manifest that lists them. */
  def write(
      root: Path,
      tables: Vector[FixtureTable],
      target: LoadTarget = LoadTarget.DYNAMODB
  ): LoadManifest =
    tables.foreach { t =>
      t.files.foreach((rel, rows) => ParquetFixtureWriter.write(root.resolve(rel), t.schema, rows))
    }
    val base = Fixture.manifest(root, target, Vector.empty)
    base.copy(tables = tables.map { t =>
      LoadTable(
        t.name,
        t.recordType,
        t.schemaVersion,
        t.location,
        "parquet",
        t.partitionColumns,
        t.rowCount,
        t.files.map(_._1).sorted.map { rel =>
          val p = root.resolve(rel)
          LoadFile(rel, Files.size(p), Sha256.ofFile(p))
        }
      )
    })

class GraphReadModelLoaderSpec extends StorageSuite:
  private val run = Fixture.runId
  private val T = ReadModelTable.Name
  private val limits = GraphReaderLimits(5, 4, 3000L)

  // researchers: the three fixture researchers and one DBLP-homonym researcher
  private val decisionId =
    scholarchain.core.ids.StableIds
      .matchDecisionId(Fixture.resA.researcherId, Fixture.resB.researcherId, "policy-test")
      .fold(e => throw new IllegalStateException(e), identity)
  private val wei = RM.researcher(
    RM.nextN(),
    "Wei Zhang 0001",
    0.9,
    run,
    Vector("Wei Zhang 0001"),
    Vector(decisionId)
  )
  private val researchers = Fixture.researchers :+ wei

  private val ra = Fixture.resA.researcherId
  private val rb = Fixture.resB.researcherId
  private val rc = Fixture.resC.researcherId
  private val works = Vector(RM.workId(RM.nextN()), RM.workId(RM.nextN()))
  private val support = works.map(w => RM.support(ra, rb, 2010, 2013, w, run))
  private val smallestAB = Vector(ra, rb).min
  private val components = Vector(
    RM.component(ra, smallestAB, 2, 2010, 2013, run),
    RM.component(rb, smallestAB, 2, 2010, 2013, run),
    RM.component(rc, rc, 1, 2010, 2013, run)
  )
  // communities: a and b in community a; c alone. One cross edge b-c.
  private val communities = Vector(
    RM.community(ra, ra, 2, 2010, 2013, run),
    RM.community(rb, ra, 2, 2010, 2013, run),
    RM.community(rc, rc, 1, 2010, 2013, run)
  )
  private val crossBC = RM.cross(rb, rc, ra, rc, 4, 2010, 2013, run)

  private def full: Vector[FixtureTable] = Vector(
    ReadModelFixture.researchers(researchers),
    ReadModelFixture.support(support),
    ReadModelFixture.components(components),
    ReadModelFixture.crossEdges(Vector(crossBC)),
    ReadModelFixture.communities(communities)
  )

  private def loader(items: ItemStore, chunk: Int, rejects: RejectSink, target: LoadTarget) =
    new GraphReadModelLoader(items, T, chunk, rejects, target)

  private def load(
      tables: Vector[FixtureTable],
      items: ItemStore = new InMemoryItemStore,
      chunk: Int = 25,
      rejects: RejectSink = new InMemoryRejectSink,
      root: Path = tempDir()
  ) =
    val manifest = ReadModelFixture.write(root, tables)
    loader(items, chunk, rejects, LoadTarget.DYNAMODB).load(manifest, new LocalFileStager(root))

  test("the loader writes every handled table and reports what each contributed") {
    val items = new InMemoryItemStore
    val rj = new InMemoryRejectSink
    val report = ok(load(full, items, chunk = 2, rejects = rj))
    withClue(rj.all.toString) { report.rejected shouldBe 0L }
    report.runId shouldBe run
    report.rejected shouldBe 0L
    val by = report.tables.map(t => t.table -> t).toMap
    // fixture researchers: one name each; wei: "wei zhang" and "wei zhang 0001"
    by("researcher").rows shouldBe 4L
    by("researcher").created shouldBe 5L
    by("coauthored_with_support").created shouldBe 2L
    by("components").created shouldBe 3L
    // one cross edge: two edge items and two link items
    by("cross_community_edge").created shouldBe 4L
    // three community rows and two markers: a and b share one community, hence one marker
    by("community_membership").created shouldBe 5L
    by("community_membership").unchanged shouldBe 1L
    report.connectivityStored shouldBe true
    report.connectivityNote shouldBe None
    report.chunks shouldBe (2 + 1 + 2 + 1 + 2) // 4, 2, 3, 1, 3 rows in chunks of 2
    items.items(T).size shouldBe 5 + 2 + 3 + 4 + 5
  }

  test("what the loader writes is exactly what the three adapters read") {
    val items = new InMemoryItemStore
    ok(load(full, items))
    val directory = new DynamoResearcherDirectory(items, T, run, 5)
    val got = ok(directory.candidatesByName("wei zhang", 5))
    got.map(_.researcherId) shouldBe Vector(wei.researcherId)
    got.head.confidence shouldBe 0.9
    got.head.matchDecisionIds shouldBe Vector(decisionId)
    got.head.sourceIds shouldBe
      Vector("dblp:Wei Zhang 0001", s"openAlex:${wei.openAlexAuthorId.get}")
    got.head.affiliations shouldBe wei.affiliationInstitutionIds
    got.head.coauthorSample shouldBe empty
    got.head.runId shouldBe run
    ok(directory.candidatesByName("wei zhang 0001", 5)).map(_.researcherId) shouldBe
      Vector(wei.researcherId)
    ok(directory.candidatesByName("henry samueli", 5)).map(_.researcherId) shouldBe Vector(ra)

    val reader = new DynamoEdgeSupportReader(items, T, 10)
    ok(reader.supportingWorkIds(RM.edgeView(ra, rb, 2010, 2013, 2, run))) shouldBe works.sorted

    val analytics = new DynamoAnalyticsReader(items, T, run, limits)
    ok(analytics.componentOf(rb, 2010, 2013)) shouldBe Some(components(1))
    ok(analytics.communityOf(rc, 2010, 2013)) shouldBe Some(communities(2))
    ok(analytics.crossCommunityEdges(ra, 2010, 2013, 5)) shouldBe Vector(crossBC)
    ok(analytics.crossCommunityEdgeCount(ra, 2010, 2013)) shouldBe 1L
    ok(analytics.communitiesConnected(rc, 2010, 2013)) shouldBe 1L
  }

  test("idempotent reload: the second load creates nothing and leaves the table unchanged") {
    val items = new InMemoryItemStore
    val root = tempDir()
    ok(load(full, items, root = root))
    val before = items.items(T)
    val second = ok(load(full, items, root = root))
    second.tables.map(_.created).sum shouldBe 0L
    // the marker shared by two researchers of one community is met twice
    second.tables.map(_.unchanged).sum shouldBe before.size.toLong + 1L
    items.items(T) shouldBe before
  }

  test("every item carries provenance and a content hash, and none carries a TTL") {
    val items = new InMemoryItemStore
    ok(load(full, items))
    items.items(T).foreach { i =>
      val prov = i("record")("provenance")
      prov("runId").str shouldBe run
      prov("sourceRecordId").str should not be empty
      i("kind").str should not be empty
      i("contentHash").str should startWith("sha256:")
      i.value.contains("expiresAt") shouldBe false // nothing here is disposable
    }
  }

  test("an item that exists with different content is a recorded conflict and stays untouched") {
    val items = new InMemoryItemStore
    // a stale support row at the key of a real row, with a different year
    val stale = ok(ReadModelItems.support(support.head.copy(workYear = Some(2011)), run))
    ok(items.putIfAbsent(T, stale))
    val rejects = new InMemoryRejectSink
    val report = ok(load(full, items, rejects = rejects))
    rejects.all.map(r => (r.store, r.reasonCode, r.runId)) shouldBe
      Vector(("dynamodb", "CONTENT_CONFLICT", run))
    rejects.all.head.key should include(support.head.workId)
    report.tables.find(_.table == "coauthored_with_support").get.rejected shouldBe 1L
    ok(items.get(T, stale("pk").str, stale("sk").str)) shouldBe Some(stale)
    // the other row of the edge was still loaded
    ok(
      new DynamoEdgeSupportReader(items, T, 10)
        .supportingWorkIds(RM.edgeView(ra, rb, 2010, 2013, 2, run))
    ).size shouldBe 2
  }

  test("a row of another run is rejected with a reason and does not stop the load") {
    val otherRun = Fresh.runId()
    val foreignId = RM.researcherId(RM.nextN())
    val foreign = RM.component(foreignId, foreignId, 1, 2010, 2013, otherRun)
    val rejects = new InMemoryRejectSink
    val tables = full.map(t =>
      if t.name == "components" then ReadModelFixture.components(components :+ foreign) else t
    )
    val report = ok(load(tables, rejects = rejects))
    val comp = report.tables.find(_.table == "components").get
    comp.rejected shouldBe 1L
    comp.created shouldBe 3L
    withClue(rejects.all.toString) {
      rejects.all.map(_.reasonCode) shouldBe Vector("INVALID_RECORD")
    }
    rejects.all.head.detail should include(otherRun)
  }

  test("an undecodable row is a ROW_DECODE reject with its file and row index") {
    val rejects = new InMemoryRejectSink
    val good = ReadModelFixture.components(components)
    val broken = good.copy(files =
      good.files.map((f, rows) => (f, rows :+ ujson.Obj("researcherId" -> "not-an-id")))
    )
    val tables = full.map(t => if t.name == "components" then broken else t)
    val report = ok(load(tables, rejects = rejects))
    report.tables.find(_.table == "components").get.rejected shouldBe 1L
    rejects.all.map(_.reasonCode) shouldBe Vector("ROW_DECODE")
    rejects.all.head.key should endWith("#3")
  }

  test("without a cross_community_edge table no marker is written and the counts stay NotFound") {
    val items = new InMemoryItemStore
    val report = ok(load(full.filterNot(_.name == "cross_community_edge"), items))
    report.connectivityStored shouldBe false
    report.connectivityNote.get should include("cross_community_edge")
    report.tables.find(_.table == "community_membership").get.created shouldBe 3L
    val analytics = new DynamoAnalyticsReader(items, T, run, limits)
    err(analytics.crossCommunityEdgeCount(ra, 2010, 2013)) shouldBe a[StorageError.NotFound]
    ok(analytics.communityOf(ra, 2010, 2013)) shouldBe Some(communities.head)
  }

  test("a rejected cross-community edge withholds every marker: no count over an incomplete set") {
    val otherRun = Fresh.runId()
    val foreign = RM.cross(
      RM.researcherId(RM.nextN()),
      RM.researcherId(RM.nextN()),
      ra,
      rc,
      1,
      2010,
      2013,
      otherRun
    )
    val items = new InMemoryItemStore
    val tables = full.map(t =>
      if t.name == "cross_community_edge" then ReadModelFixture.crossEdges(Vector(crossBC, foreign))
      else t
    )
    val report = ok(load(tables, items))
    report.connectivityStored shouldBe false
    report.connectivityNote.get should include("withheld")
    val analytics = new DynamoAnalyticsReader(items, T, run, limits)
    err(analytics.crossCommunityEdgeCount(ra, 2010, 2013)) shouldBe a[StorageError.NotFound]
  }

  test("tables that are not part of the read model are reported as skipped, never dropped") {
    val root = tempDir()
    Fixture.generate(root) // the fixture's own tables; the read model tables are written after
    val manifest = ReadModelFixture.write(root, full)
    val extra = Fixture.manifest(root, LoadTarget.DYNAMODB, Vector("coauthored_with"))
    val merged = manifest.copy(tables = manifest.tables ++ extra.tables)
    val report = ok(
      loader(new InMemoryItemStore, 25, new InMemoryRejectSink, LoadTarget.DYNAMODB)
        .load(merged, new LocalFileStager(root))
    )
    report.skippedTables.map(_.table) shouldBe Vector("coauthored_with")
    report.skippedTables.head.reason should include("another loader")
  }

  test("DynamoLoader reports the read model tables as owned by GraphReadModelLoader") {
    val root = tempDir()
    Fixture.generate(root)
    val manifest = Fixture.manifest(
      root,
      LoadTarget.DYNAMODB,
      Vector("researcher_collaboration", "researcher", "coauthored_with")
    )
    val cache = FakeDynamo.ports(new InMemoryItemStore).metricCache
    val report =
      ok(
        new DynamoLoader(cache, 25, new InMemoryRejectSink)
          .load(manifest, new LocalFileStager(root))
      )
    val reasons = report.skippedTables.map(t => t.table -> t.reason).toMap
    reasons("researcher") should include("GraphReadModelLoader")
    reasons("coauthored_with") should include("no DynamoDB table")
  }

  test("a damaged file fails the whole load before anything is written") {
    val items = new InMemoryItemStore
    val root = tempDir()
    val manifest = ReadModelFixture.write(root, full)
    val file = root.resolve(full.find(_.name == "components").get.files.head._1)
    Files.write(file, Files.readAllBytes(file) :+ 0.toByte)
    val e = err(
      loader(items, 25, new InMemoryRejectSink, LoadTarget.DYNAMODB)
        .load(manifest, new LocalFileStager(root))
    )
    e shouldBe a[StorageError.SizeMismatch]
    items.items(T) shouldBe empty
  }

  test("a manifest for another target is refused unless the loader is told to expect it") {
    val root = tempDir()
    val manifest = ReadModelFixture.write(root, full, LoadTarget.S3)
    val stager = new LocalFileStager(root)
    err(
      loader(new InMemoryItemStore, 25, new InMemoryRejectSink, LoadTarget.DYNAMODB)
        .load(manifest, stager)
    ) shouldBe a[StorageError.InvalidManifest]
    ok(
      loader(new InMemoryItemStore, 25, new InMemoryRejectSink, LoadTarget.S3)
        .load(manifest, stager)
    ).connectivityStored shouldBe true
  }

  test("a loader with a non-positive chunk size is misconfigured") {
    val root = tempDir()
    val manifest = ReadModelFixture.write(root, full)
    err(
      loader(new InMemoryItemStore, 0, new InMemoryRejectSink, LoadTarget.DYNAMODB)
        .load(manifest, new LocalFileStager(root))
    ) shouldBe a[StorageError.Misconfigured]
  }

class StorageSettingsReadModelSpec extends StorageSuite:
  test("the read model table defaults to <prefix>-<environment>-graph-read-model") {
    val runLedger = TestEnv.settings.runtime.dynamoTables.runLedger
    TestEnv.settings.graphReadModelTable shouldBe
      Some(runLedger.stripSuffix("-run-ledger") + "-graph-read-model")
  }

  test("settings built without the table are a configuration error when the table is required") {
    err(TestEnv.settings.copy(graphReadModelTable = None).requireGraphReadModelTable) shouldBe
      a[StorageError.Misconfigured]
  }
