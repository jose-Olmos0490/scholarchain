package scholarchain.storage

import java.util.UUID
import org.scalatest.Tag
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*

/** Fresh, valid IDs so a contract can run repeatedly against a shared (real) table. */
object Fresh:
  private def ok(e: Either[String, String]): String =
    e.fold(m => throw new IllegalStateException(m), identity)

  def runId(): String =
    ok(
      StableIds.runId(
        Fixture.runManifest.copy(parameters = Map("nonce" -> UUID.randomUUID.toString))
      )
    )

  def queryId(): String = ok(StableIds.queryId(UUID.randomUUID.toString))

  def prov(run: String = Fixture.runId): Provenance =
    Fixture.prov(SourceType.SCHOLARCHAIN, run, Some("contract-test")).copy(runId = run)

  def graphItem(ref: String, run: String = Fixture.runId): EvidenceItem =
    item(EvidenceType.COAUTHOR_EDGE, Vector(ref), run)

  def metricItem(ref: String, run: String = Fixture.runId): EvidenceItem =
    item(EvidenceType.METRIC_RECORD, Vector(ref), run)

  def semanticItem(text: String, run: String = Fixture.runId): EvidenceItem =
    val hash = ok(StableIds.textHash(text))
    val chunk = ok(StableIds.chunkId(Fixture.resA.researcherId, "v1", "abstract", 0, hash))
    val id = ok(StableIds.evidenceId(EvidenceType.TEXT_CHUNK, Vector(chunk), run))
    EvidenceItem(
      SV.EvidenceItem,
      id,
      EvidenceType.TEXT_CHUNK,
      Vector(chunk),
      None,
      "s3://test-rag/chunk",
      Some(chunk),
      None,
      Some(0.5),
      Some(hash),
      Some(text),
      prov(run)
    )

  private def item(t: EvidenceType, refs: Vector[String], run: String): EvidenceItem =
    EvidenceItem(
      SV.EvidenceItem,
      ok(StableIds.evidenceId(t, refs, run)),
      t,
      refs,
      None,
      "s3://test-graph/x",
      None,
      None,
      None,
      None,
      None,
      prov(run)
    )

  def bundle(
      queryId: String,
      graph: Vector[EvidenceItem],
      semantic: Vector[EvidenceItem]
  ): EvidenceBundle =
    EvidenceBundle(
      SV.EvidenceBundle,
      ok(StableIds.bundleId(queryId, (graph ++ semantic).map(_.evidenceId))),
      queryId,
      graph,
      semantic,
      Map(Fixture.resA.researcherId -> 1.0),
      prov()
    )

/** Base of every contract: tag and precondition hooks (the AWS suites cancel unless enabled). */
abstract class PortContract extends StorageSuite with PortGate:
  protected def contract(name: String)(body: => Unit): Unit =
    test(s"$name", contractTags*) {
      preconditions()
      body
    }

abstract class RunLedgerContract extends PortContract:
  protected def ledger: RunLedger

  private def entry(run: String, seq: Int, stage: String, attempt: Int, state: LedgerState) =
    RunLedgerEntry(
      DynamoSchemas.RunLedgerEntry,
      run,
      seq,
      stage,
      attempt,
      state,
      1000L * seq + attempt,
      s"$stage $state",
      Fresh.prov(run)
    )

  contract("run ledger: all stages of a run list in pipeline order, attempts and events in order") {
    val run = Fresh.runId()
    val other = Fresh.runId()
    // inserted out of order; sequence 10 must sort after 2 (zero padded keys)
    val entries = Vector(
      entry(run, 10, "load-neptune", 1, LedgerState.STARTED),
      entry(run, 2, "spark", 1, LedgerState.SUCCEEDED),
      entry(run, 2, "spark", 1, LedgerState.STARTED),
      entry(run, 1, "hadoop", 1, LedgerState.STARTED),
      entry(run, 1, "hadoop", 1, LedgerState.FAILED),
      entry(run, 1, "hadoop", 2, LedgerState.STARTED),
      entry(run, 1, "hadoop", 2, LedgerState.SUCCEEDED),
      entry(other, 1, "hadoop", 1, LedgerState.STARTED)
    )
    entries.foreach(e => ok(ledger.record(e)))
    ok(ledger.list(run)).map(e => (e.sequence, e.stage, e.attempt, e.state)) shouldBe Vector(
      (1, "hadoop", 1, LedgerState.STARTED),
      (1, "hadoop", 1, LedgerState.FAILED),
      (1, "hadoop", 2, LedgerState.STARTED),
      (1, "hadoop", 2, LedgerState.SUCCEEDED),
      (2, "spark", 1, LedgerState.STARTED),
      (2, "spark", 1, LedgerState.SUCCEEDED),
      (10, "load-neptune", 1, LedgerState.STARTED)
    )
    ok(ledger.list(other)).size shouldBe 1
  }

  contract(
    "run ledger: an audit record is never overwritten; a second create fails with a typed error"
  ) {
    val run = Fresh.runId()
    val first = entry(run, 1, "spark", 1, LedgerState.STARTED)
    ok(ledger.record(first))
    val again = first.copy(detail = "rewritten history")
    err(ledger.record(again)) shouldBe a[StorageError.AlreadyExists]
    ok(ledger.list(run)).map(_.detail) shouldBe Vector(first.detail)
  }

  contract("run ledger: malformed keys are refused before any write") {
    val run = Fresh.runId()
    err(ledger.record(entry(run, 1, "bad#stage", 1, LedgerState.STARTED))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(ledger.record(entry(run, -1, "spark", 1, LedgerState.STARTED))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(ledger.record(entry(run, 1, "spark", 0, LedgerState.STARTED))) shouldBe a[
      StorageError.InvalidRecord
    ]
    ok(ledger.list(run)) shouldBe empty
  }

abstract class EntityLinkCacheContract extends PortContract:
  protected def cache: EntityLinkCache

  private def adjudication(
      hash: String,
      prompt: String,
      model: String,
      policy: String,
      outcome: MatchOutcome
  ) =
    LinkAdjudication(
      DynamoSchemas.LinkAdjudication,
      hash,
      prompt,
      model,
      policy,
      outcome,
      0.9,
      Vector("REASON"),
      Fresh.prov()
    )

  contract("entity link cache: get by (inputHash, promptVersion, modelVersion, policyVersion)") {
    val hash = Sha256.of(UUID.randomUUID.toString)
    val stored = adjudication(hash, "p1", "m1", "er-v1", MatchOutcome.ACCEPTED)
    ok(cache.put(stored))
    ok(cache.get(hash, "p1", "m1", "er-v1")) shouldBe Some(stored)
    // any of the four parts differing is a miss: a cached answer never crosses versions
    ok(cache.get(hash, "p2", "m1", "er-v1")) shouldBe None
    ok(cache.get(hash, "p1", "m2", "er-v1")) shouldBe None
    ok(cache.get(hash, "p1", "m1", "er-v2")) shouldBe None
    ok(cache.get(Sha256.of("other"), "p1", "m1", "er-v1")) shouldBe None
  }

  contract(
    "entity link cache: the same input under another model is a separate entry; a create never overwrites"
  ) {
    val hash = Sha256.of(UUID.randomUUID.toString)
    val first = adjudication(hash, "p1", "m1", "er-v1", MatchOutcome.ACCEPTED)
    val second = adjudication(hash, "p1", "m2", "er-v1", MatchOutcome.REJECTED)
    ok(cache.put(first)); ok(cache.put(second))
    ok(cache.get(hash, "p1", "m1", "er-v1")).get.outcome shouldBe MatchOutcome.ACCEPTED
    ok(cache.get(hash, "p1", "m2", "er-v1")).get.outcome shouldBe MatchOutcome.REJECTED
    err(cache.put(first.copy(outcome = MatchOutcome.CONFLICT))) shouldBe a[
      StorageError.AlreadyExists
    ]
    ok(cache.get(hash, "p1", "m1", "er-v1")).get.outcome shouldBe MatchOutcome.ACCEPTED
  }

  contract("entity link cache: invalid input is refused") {
    err(cache.put(adjudication("not-a-hash", "p", "m", "v", MatchOutcome.ACCEPTED))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(cache.put(adjudication(Sha256.of("x"), "p#", "m", "v", MatchOutcome.ACCEPTED))) shouldBe a[
      StorageError.InvalidRecord
    ]
    err(
      cache.put(
        adjudication(Sha256.of("x"), "p", "m", "v", MatchOutcome.ACCEPTED).copy(confidence = 1.5)
      )
    ) shouldBe a[StorageError.InvalidRecord]
  }

abstract class QuerySessionsContract extends PortContract:
  protected def sessions: QuerySessions

  private def session(q: String, key: Option[String], at: Long = 1_700_000_000L) =
    QuerySessionRecord(DynamoSchemas.QuerySession, q, key, "fingerprint", at, Fresh.prov())

  contract("query session: create once per queryId, get by queryId") {
    val q = Fresh.queryId()
    val s = session(q, None)
    ok(sessions.create(s))
    ok(sessions.get(q)) shouldBe Some(s)
    ok(sessions.get(Fresh.queryId())) shouldBe None
    err(sessions.create(s.copy(requestFingerprint = "different"))) shouldBe StorageError
      .AlreadyExists(q)
    ok(sessions.get(q)) shouldBe Some(s)
  }

  contract("query session: a repeated Idempotency-Key is detected and names the first query") {
    val key = s"idem-${UUID.randomUUID}"
    val q1 = Fresh.queryId()
    ok(sessions.create(session(q1, Some(key))))
    ok(sessions.queryIdForIdempotencyKey(key)) shouldBe Some(q1)
    val q2 = Fresh.queryId()
    err(sessions.create(session(q2, Some(key)))) shouldBe StorageError.DuplicateIdempotencyKey(
      key,
      q1
    )
    // the second session was not created: creation is all or nothing
    ok(sessions.get(q2)) shouldBe None
    // an exact replay (same query, same key) reports the repeated key too
    err(sessions.create(session(q1, Some(key)))) shouldBe StorageError.DuplicateIdempotencyKey(
      key,
      q1
    )
    ok(sessions.queryIdForIdempotencyKey(s"unused-${UUID.randomUUID}")) shouldBe None
  }

abstract class EvidenceStoreContract extends PortContract:
  protected def evidence: EvidenceStore

  contract("evidence: get one item by evidenceId; list all evidence of a query") {
    val q = Fresh.queryId()
    val run = Fresh.runId()
    val g1 = Fresh.graphItem(Fixture.edgeAbAll.edgeId, run)
    val g2 = Fresh.metricItem(Fixture.metricsAll.head.metricsId, run)
    val s1 = Fresh.semanticItem(s"abstract ${UUID.randomUUID}", run)
    ok(evidence.putBundle(Fresh.bundle(q, Vector(g1, g2), Vector(s1))))
    ok(evidence.getItem(g1.evidenceId)) shouldBe Some(g1)
    ok(evidence.getItem(s1.evidenceId)) shouldBe Some(s1)
    ok(evidence.getItem(Fresh.graphItem(Fixture.edgeBcAll.edgeId, run).evidenceId)) shouldBe None
    ok(evidence.listForQuery(q)).map(_.evidenceId).toSet shouldBe Set(
      g1.evidenceId,
      g2.evidenceId,
      s1.evidenceId
    )
    ok(evidence.listForQuery(q)).map(_.evidenceId) shouldBe
      ok(evidence.listForQuery(q)).map(_.evidenceId).sorted
    ok(evidence.listForQuery(Fresh.queryId())) shouldBe empty
  }

  contract("evidence: an item shared by two queries is stored once; a bundle is created once") {
    val run = Fresh.runId()
    val shared = Fresh.graphItem(Fixture.edgeAbAll.edgeId, run)
    val q1 = Fresh.queryId()
    val q2 = Fresh.queryId()
    ok(evidence.putBundle(Fresh.bundle(q1, Vector(shared), Vector.empty)))
    ok(evidence.putBundle(Fresh.bundle(q2, Vector(shared), Vector.empty)))
    ok(evidence.listForQuery(q1)).map(_.evidenceId) shouldBe Vector(shared.evidenceId)
    ok(evidence.listForQuery(q2)).map(_.evidenceId) shouldBe Vector(shared.evidenceId)
    err(evidence.putBundle(Fresh.bundle(q1, Vector(shared), Vector.empty))) shouldBe a[
      StorageError.AlreadyExists
    ]
  }

  contract(
    "evidence: an existing evidence ID with different content is a conflict, never an overwrite"
  ) {
    val run = Fresh.runId()
    val original = Fresh.semanticItem("original abstract text " + UUID.randomUUID, run)
    ok(evidence.putBundle(Fresh.bundle(Fresh.queryId(), Vector.empty, Vector(original))))
    val tampered = original.copy(score = Some(0.99))
    val e = err(evidence.putBundle(Fresh.bundle(Fresh.queryId(), Vector.empty, Vector(tampered))))
    e shouldBe a[StorageError.ConflictingContent]
    ok(evidence.getItem(original.evidenceId)) shouldBe Some(original)
  }

  contract("evidence: a bundle that breaks its core contract is refused") {
    val run = Fresh.runId()
    val graph = Fresh.graphItem(Fixture.edgeAbAll.edgeId, run)
    val misplaced =
      Fresh.bundle(Fresh.queryId(), Vector.empty, Vector(graph)) // graph fact in semantic section
    err(evidence.putBundle(misplaced)) shouldBe a[StorageError.InvalidRecord]
    ok(evidence.getItem(graph.evidenceId)) shouldBe None
  }

abstract class ArtifactRegistryContract extends PortContract:
  protected def registry: ArtifactRegistry

  private def register(name: String, location: String) =
    registry.register(
      name,
      location,
      Sha256.of(location),
      "artifact-schema-v1",
      Fixture.runId,
      Fresh.prov()
    )

  contract("artifact registry: latest and a specific version of a named artifact") {
    val name = s"embedding-queue-${UUID.randomUUID}"
    ok(registry.latest(name)) shouldBe None
    val v1 = ok(register(name, "s3://b/one"))
    val v2 = ok(register(name, "s3://b/two"))
    val v3 = ok(register(name, "s3://b/three"))
    (v1.version, v2.version, v3.version) shouldBe (1L, 2L, 3L)
    ok(registry.latest(name)) shouldBe Some(v3)
    ok(registry.version(name, 2)) shouldBe Some(v2)
    ok(registry.version(name, 1)).get.location shouldBe "s3://b/one"
    ok(registry.version(name, 4)) shouldBe None
    ok(registry.latest(s"other-${UUID.randomUUID}")) shouldBe None
    err(registry.version(name, 0)) shouldBe a[StorageError.InvalidRecord]
  }

  contract("artifact registry: versions are numbered past 9 and 10 in numeric order") {
    val name = s"many-${UUID.randomUUID}"
    (1 to 12).foreach(i => ok(register(name, s"s3://b/$i")))
    ok(registry.latest(name)).get.version shouldBe 12L
    ok(registry.version(name, 10)).get.location shouldBe "s3://b/10"
  }

abstract class MetricCacheContract extends PortContract:
  protected def metricCache: ResearcherMetricCache

  import Fixture.*

  contract("metric cache: get metrics by researcherId and window") {
    val m = metricsAll.head
    Vector(CreateOutcome.Created, CreateOutcome.Unchanged) should contain(ok(metricCache.put(m)))
    ok(metricCache.get(m.researcherId, m.windowStartYear, m.windowEndYear)) shouldBe Some(m)
    ok(metricCache.get(m.researcherId, 1990, 1999)) shouldBe None
    metricsEarly.find(_.researcherId == m.researcherId).foreach { e =>
      ok(metricCache.put(e))
      ok(metricCache.listWindows(m.researcherId))
        .map(x => (x.windowStartYear, x.windowEndYear)) shouldBe
        Vector((2010, 2012), (2010, 2013))
    }
  }

  contract(
    "metric cache: the same metrics are unchanged; different content under the same key is a conflict"
  ) {
    val m = metricsAll(1)
    ok(metricCache.put(m))
    ok(metricCache.put(m)) shouldBe CreateOutcome.Unchanged
    val changed = m.copy(publicationCount = m.publicationCount + 1)
    err(metricCache.put(changed)) shouldBe a[StorageError.ConflictingContent]
    ok(metricCache.get(m.researcherId, m.windowStartYear, m.windowEndYear)) shouldBe Some(m)
  }

  contract("metric cache: a record that breaks its core contract is refused") {
    err(metricCache.put(metricsAll.head.copy(publicationCount = -1))) shouldBe a[
      StorageError.InvalidRecord
    ]
  }
