package scholarchain.storage

import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.storage.common.*
import scholarchain.storage.directory.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*
import scholarchain.storage.objects.*

/** Contract of the per-query audit log. The fake runs it by default; `QueryAuditLogIT` runs it
  * against the real `querySession` table under the integration profile.
  */
abstract class QueryAuditLogContract extends PortContract:
  protected def audit: QueryAuditLog

  protected def record(
      queryId: String = Fresh.queryId(),
      answerText: String = "answer",
      planJson: String = """{"operation":"RESEARCHER_RANKING"}"""
  ): QueryAuditRecord =
    val run = Fresh.runId()
    QueryAuditRecord(
      SchemaVersions.QueryAuditRecord,
      queryId,
      run,
      planJson,
      Vector(Fixture.resA.researcherId, Fixture.resB.researcherId),
      Vector(Fresh.graphItem(Fixture.edgeAbAll.edgeId, run).evidenceId),
      Vector("metrics-v1"),
      Vector("query-planner-prompt-v1"),
      Vector("test-model-v1"),
      inputTokens = 1200,
      outputTokens = 240,
      latencyMillis = 850,
      VerificationStatus.VERIFIED,
      Sha256.of(answerText),
      Fresh.prov(run)
    )

  contract("audit: append once, get by queryId, every field round-trips") {
    val r = record()
    ok(audit.append(r))
    ok(audit.get(r.queryId)) shouldBe Some(r)
    ok(audit.get(Fresh.queryId())) shouldBe None
  }

  contract(
    "audit: a duplicate append is refused, whether identical or different, and changes nothing"
  ) {
    val r = record()
    ok(audit.append(r))
    err(audit.append(r)) shouldBe StorageError.AlreadyExists(s"AUDIT#${r.queryId}")
    err(audit.append(r.copy(answerHash = Sha256.of("another answer")))) shouldBe
      StorageError.AlreadyExists(s"AUDIT#${r.queryId}")
    ok(audit.get(r.queryId)) shouldBe Some(r)
  }

  contract("audit: a record that breaks its core contract is refused before any write") {
    val r = record()
    err(audit.append(r.copy(answerHash = "not-a-hash"))) shouldBe a[StorageError.InvalidRecord]
    err(audit.append(r.copy(schemaVersion = "query-audit-v0"))) shouldBe
      a[StorageError.InvalidRecord]
    err(audit.append(r.copy(inputTokens = -1))) shouldBe a[StorageError.InvalidRecord]
    err(audit.append(r.copy(queryId = "query#x"))) shouldBe a[StorageError.InvalidRecord]
    ok(audit.get(r.queryId)) shouldBe None
  }

  contract("audit: a record above the DynamoDB item size limit is refused, not truncated") {
    val big = record(planJson = "x" * (400 * 1024))
    err(audit.append(big)) shouldBe a[StorageError.LimitExceeded]
    ok(audit.get(big.queryId)) shouldBe None
  }

class QueryAuditLogFakeSpec extends QueryAuditLogContract:
  private val items = new InMemoryItemStore
  protected val audit: QueryAuditLog = FakeDynamo.ports(items).queryAudit

  test("the audit item lives in the querySession table next to the session, without a TTL") {
    val items = new InMemoryItemStore
    val ports = FakeDynamo.ports(items)
    val q = Fresh.queryId()
    ok(
      ports.querySessions.create(
        QuerySessionRecord(
          DynamoSchemas.QuerySession,
          q,
          None,
          "fingerprint",
          1_700_000_000L,
          Fresh.prov()
        )
      )
    )
    val r = record(queryId = q)
    ok(ports.queryAudit.append(r))
    val rows = items.items(FakeDynamo.tables.querySession)
    rows.map(i => (i("pk").str, i("sk").str)).toSet shouldBe
      Set((s"query#$q", "SESSION"), (s"query#$q", s"AUDIT#$q"))
    val auditItem = rows.find(_("sk").str.startsWith("AUDIT#")).get
    auditItem("kind").str shouldBe "QueryAudit"
    auditItem.value.contains("expiresAt") shouldBe false
    rows.filter(_.value.contains("expiresAt")).map(_("sk").str) shouldBe Vector("SESSION")
    // other tables are untouched
    items.items(FakeDynamo.tables.evidenceBundle) shouldBe empty
    ok(ports.querySessions.get(q)).isDefined shouldBe true
  }

/** `s3://` URIs from retrieved metadata are untrusted: only the configured layout is readable. */
class ObjectRefUriSpec extends StorageSuite:
  private val layout = ok(
    ObjectLayout.fromConfig(
      TestEnv.settings.buckets,
      TestEnv.settings.runtime.s3Prefixes,
      ObjectArea.Rag
    )
  )
  private def parse(uri: String) = ObjectRef.fromS3Uri(layout, uri)

  test("a URI inside a configured bucket resolves to the area and the key relative to its prefix") {
    ok(parse("s3://test-rag/chunks/a.txt")) shouldBe ObjectRef(ObjectArea.Rag, "chunks/a.txt")
    ok(parse("s3://test-raw/x/y.json")) shouldBe ObjectRef(ObjectArea.Raw, "x/y.json")
    ok(parse("s3://test-graph/run_id=ab/t/part-0.parquet")) shouldBe
      ObjectRef(ObjectArea.Graph, "run_id=ab/t/part-0.parquet")
    ok(parse("s3://test-curated/k")) shouldBe ObjectRef(ObjectArea.Curated, "k")
  }

  test("the address round-trips through the layout") {
    Vector("s3://test-rag/chunks/a.txt", "s3://test-raw/x/y.json").foreach { uri =>
      layout.s3Uri(ok(parse(uri))) shouldBe uri
    }
    // an auxiliary area that shares a bucket resolves by its configured prefix
    val reportsPrefix = layout.prefixes(ObjectArea.Reports)
    val uri = s"s3://test-rag/${reportsPrefix}run.json"
    val ref = ok(parse(uri))
    ref shouldBe ObjectRef(ObjectArea.Reports, "run.json")
    layout.s3Uri(ref) shouldBe uri
    // the same bucket without that prefix is the text area
    ok(parse("s3://test-rag/other/run.json")).area shouldBe ObjectArea.Rag
  }

  test("a bucket outside the configured layout is refused") {
    err(parse("s3://someone-elses-bucket/secret.txt")) shouldBe a[StorageError.InvalidRecord]
    err(parse("s3://test-vectors/anything")) shouldBe a[
      StorageError.InvalidRecord
    ] // not an object area
    err(parse("s3://test-rag-evil/x")) shouldBe a[StorageError.InvalidRecord]
    err(parse("s3://TEST-RAG/x")) shouldBe a[StorageError.InvalidRecord]
  }

  test("other schemes, missing parts and ambiguous or unsafe keys are refused") {
    Vector(
      null,
      "",
      "https://test-rag.s3.amazonaws.com/x",
      "file:///etc/passwd",
      "/test-rag/x",
      "s3:/test-rag/x",
      "s3://",
      "s3://test-rag",
      "s3://test-rag/",
      "s3:///x",
      "s3://test-rag/a//b",
      "s3://test-rag/../x",
      "s3://test-rag/a/./b",
      "s3://test-rag/a/..",
      "s3://test-rag/x?versionId=1",
      "s3://test-rag/x#frag",
      "s3://test-rag/a\\b",
      "s3://test-rag/a\u0000b",
      "s3://test-rag/a\nb",
      "s3://test-rag/" + "k" * (ObjectRef.MaxKeyBytes + 1)
    ).foreach(uri =>
      withClue(s"uri: $uri") { err(parse(uri)) shouldBe a[StorageError.InvalidRecord] }
    )
    ok(parse("s3://test-rag/" + "k" * ObjectRef.MaxKeyBytes)).key.length shouldBe
      ObjectRef.MaxKeyBytes
  }

/** The analytics port: stored numbers are returned, never derived from a truncated list. */
class AnalyticsReaderFakeSpec extends StorageSuite:
  private val limits = GraphReaderLimits(3, 4, 3000L)
  private val run = Fixture.runId
  private val prov = Fresh.prov(run)
  private val reader = new FakeAnalyticsReader(limits)

  private def cross(id: String, a: String, b: String, ca: String, cb: String, n: Long) =
    CrossCommunityEdge(
      SchemaVersions.CrossCommunityEdge,
      id,
      a,
      b,
      ca,
      cb,
      2010,
      2013,
      n,
      "p1",
      "metrics-v1",
      run,
      prov
    )

  // community c1 has 5 cross edges to 3 other communities; the port limit is 3
  reader.putComponent(
    ComponentMembership(
      SchemaVersions.ComponentMembership,
      "r2",
      "r1",
      4,
      2010,
      2013,
      "metrics-v1",
      run,
      prov
    )
  )
  reader.putCommunity(
    CommunityMembership(
      SchemaVersions.CommunityMembership,
      "r2",
      "c1",
      2,
      2010,
      2013,
      "metrics-v1",
      run,
      prov
    )
  )
  Vector(
    cross("e1", "r1", "r5", "c1", "c2", 2),
    cross("e2", "r1", "r6", "c1", "c3", 5),
    cross("e3", "r2", "r7", "c1", "c4", 5),
    cross("e4", "r8", "r2", "c2", "c1", 1),
    cross("e5", "r9", "r2", "c3", "c1", 9),
    cross("e6", "r9", "r10", "c3", "c4", 1)
  ).foreach(reader.putCrossEdge)
  reader.putConnectivity(CommunityConnectivity("c1", 2010, 2013, 5, 3, "metrics-v1", run))

  test("membership lookups are per researcher and window") {
    ok(reader.componentOf("r2", 2010, 2013)).map(_.componentId) shouldBe Some("r1")
    ok(reader.communityOf("r2", 2010, 2013)).map(_.communityId) shouldBe Some("c1")
    ok(reader.componentOf("r2", 2000, 2005)) shouldBe None
    ok(reader.communityOf("nobody", 2010, 2013)) shouldBe None
    err(reader.componentOf("r2", 2013, 2010)) shouldBe a[StorageError.InvalidRecord]
  }

  test(
    "cross-community edges are ordered by joint publications then edge ID and cut at the limit"
  ) {
    ok(reader.crossCommunityEdges("c1", 2010, 2013, 3)).map(_.edgeId) shouldBe
      Vector("e5", "e2", "e3")
    ok(reader.crossCommunityEdges("c1", 2010, 2013, 1)).map(_.edgeId) shouldBe Vector("e5")
    ok(reader.crossCommunityEdges("c4", 2010, 2013, 3)).map(_.edgeId) shouldBe Vector("e3", "e6")
    ok(reader.crossCommunityEdges("c1", 2000, 2005, 3)) shouldBe empty
  }

  test("the counts are the stored numbers, not the size of the truncated edge list") {
    ok(reader.crossCommunityEdges("c1", 2010, 2013, 3)) should have size 3
    ok(reader.crossCommunityEdgeCount("c1", 2010, 2013)) shouldBe 5L
    ok(reader.communitiesConnected("c1", 2010, 2013)) shouldBe 3L
  }

  test("a count that was never stored is NotFound, not zero") {
    err(reader.crossCommunityEdgeCount("c2", 2010, 2013)) shouldBe a[StorageError.NotFound]
    err(reader.communitiesConnected("c1", 2000, 2005)) shouldBe a[StorageError.NotFound]
  }

  test("limits are refused, not clamped") {
    err(reader.crossCommunityEdges("c1", 2010, 2013, 4)) shouldBe
      StorageError.LimitExceeded("result limit", 3, 4)
    err(reader.crossCommunityEdges("c1", 2010, 2013, 0)) shouldBe a[StorageError.InvalidRecord]
  }

/** The name directory port: the stored confidence comes back as stored. */
class ResearcherDirectoryFakeSpec extends StorageSuite:
  private def cand(id: String, conf: Double, name: String = "Ada Lovelace") =
    NameCandidate(
      id,
      name,
      conf,
      Vector(s"src:$id"),
      Vector("institution:x"),
      Vector("researcher:z"),
      Vector(s"decision:$id"),
      "er-policy-v1",
      Fixture.runId
    )

  test("candidates are ordered by confidence then researcherId and carry the stored confidence") {
    val d = new FakeResearcherDirectory(maxLimit = 3)
    ok(d.put("ada lovelace", cand("r3", 0.8731)))
    ok(d.put("ada lovelace", cand("r1", 0.95)))
    ok(d.put("ada lovelace", cand("r2", 0.8731)))
    ok(d.put("grace hopper", cand("r9", 1.0, "Grace Hopper")))
    val out = ok(d.candidatesByName("ada lovelace", 3))
    out.map(_.researcherId) shouldBe Vector("r1", "r2", "r3")
    out.map(_.confidence) shouldBe Vector(0.95, 0.8731, 0.8731) // exactly as stored
    out.head.matchDecisionIds shouldBe Vector("decision:r1")
    out.head.policyVersion shouldBe "er-policy-v1"
    ok(d.candidatesByName("ada lovelace", 2)).map(_.researcherId) shouldBe Vector("r1", "r2")
    ok(d.candidatesByName("unknown person", 3)) shouldBe empty
  }

  test("the name is matched exactly: the caller normalizes, the directory does not guess") {
    val d = new FakeResearcherDirectory(3)
    ok(d.put("ada lovelace", cand("r1", 0.9)))
    ok(d.candidatesByName("Ada Lovelace", 3)) shouldBe empty
    ok(d.candidatesByName("ada lovelac", 3)) shouldBe empty
  }

  test("bounds and blank input are refused; an out-of-range confidence is not stored") {
    val d = new FakeResearcherDirectory(3)
    err(d.candidatesByName("ada", 4)) shouldBe StorageError.LimitExceeded("result limit", 3, 4)
    err(d.candidatesByName("ada", 0)) shouldBe a[StorageError.InvalidRecord]
    err(d.candidatesByName(" ", 1)) shouldBe a[StorageError.InvalidRecord]
    err(d.put("ada", cand("r1", 1.01))) shouldBe a[StorageError.InvalidRecord]
    err(d.put("ada", cand("r1", Double.NaN))) shouldBe a[StorageError.InvalidRecord]
    ok(d.candidatesByName("ada", 3)) shouldBe empty
  }
