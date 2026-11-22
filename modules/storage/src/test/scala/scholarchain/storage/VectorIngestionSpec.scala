package scholarchain.storage

import scala.collection.mutable
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.core.model.SourceType
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.*

/** Simulated time: sleeping advances the clock, so rate-limit and backoff tests never wait. */
final class SimTime extends MonotonicClock, Sleeper:
  var nowNanos = 0L
  val sleeps = mutable.ArrayBuffer.empty[Long]
  def nanos: Long = nowNanos
  def sleep(millis: Long): Unit =
    sleeps += millis
    nowNanos += millis * 1_000_000L

/** Wraps the fake index: fails the first `putFailures` puts with a transient error. */
final class FlakyIndex(val inner: InMemoryVectorIndex, var putFailures: Int = 0)
    extends VectorIndex:
  var putCalls = 0
  var getCalls = 0
  def describe(n: String) = inner.describe(n)
  def put(n: String, vs: Vector[VectorRecord]) =
    putCalls += 1
    if putFailures > 0 then
      putFailures -= 1
      Left(
        StorageError.Transient("S3 Vectors PutVectors", 1, "TooManyRequestsException: slow down")
      )
    else inner.put(n, vs)
  def get(n: String, ks: Vector[String], d: Boolean) =
    getCalls += 1
    inner.get(n, ks, d)
  def query(n: String, q: VectorQuery) = inner.query(n, q)
  def retryStats = inner.retryStats

/** Everything the ingestion path needs, wired to fakes. */
final class IngestRig(
    putBatch: Int = 500,
    getBatch: Int = 100,
    retry: RetryPolicyConfig =
      RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100),
    ratePerSecond: Int = 1000,
    modelVersion: String = "2026-01",
    maxTopicIds: Int = 3,
    putFailures: Int = 0,
    var embedder: Option[Embedder] = None
):
  val defs: Vector[IndexDefinition] = IndexDefinition.fromSettings(TestEnv.settings)
  val classic: IndexDefinition = defs.head
  val inner = new InMemoryVectorIndex(defs)
  val index = new FlakyIndex(inner, putFailures)
  val objects = new InMemoryObjectStore
  val items = new InMemoryItemStore
  val ports: DynamoPorts = FakeDynamo.ports(items)
  val rejects = new InMemoryRejectSink
  val time = new SimTime
  val embedCalls = mutable.ArrayBuffer.empty[Int]
  val layout: ObjectLayout =
    ObjectLayout
      .fromConfig(
        TestEnv.settings.buckets,
        TestEnv.settings.runtime.s3Prefixes,
        ObjectArea.Rag
      )
      .fold(e => throw new IllegalStateException(e.message), identity)

  def settings: IngestionSettings = IngestionSettings(
    putBatchSize = putBatch,
    getBatchSize = getBatch,
    retry = retry,
    rateLimitPerSecond = defs.map(_.name -> ratePerSecond).toMap,
    maxTopicIds = maxTopicIds,
    maxInstitutionIds = 3,
    embeddingModel = Vectors.Model,
    embeddingModelVersion = modelVersion,
    ledgerSequence = 7
  )

  private val defaultEmbedder: Embedder = texts =>
    embedCalls += texts.size
    texts.map { t =>
      val h = math.abs(t.hashCode)
      Array(1f, (h % 7).toFloat, (h % 11).toFloat, (h % 13).toFloat)
    }

  def ingestor(s: IngestionSettings = settings): VectorIngestor =
    new VectorIngestor(
      index,
      defs,
      new DocumentStore(objects, layout),
      new ManifestStore(objects, layout),
      objects,
      layout,
      ports.runLedger,
      ports.artifacts,
      rejects,
      embedder.getOrElse(defaultEmbedder),
      s,
      new RateLimiter(s.rateLimitPerSecond, time, time),
      time,
      MaxJitter
    )

  val run: IngestionRun = IngestionRun(Fixture.runId, Fixture.Snapshot)

  /** Another run (its own manifest): `run:` plus the SHA-256 of a label. */
  def runNamed(label: String): IngestionRun =
    IngestionRun("run:" + Sha256.hex(label.getBytes("UTF-8")), Fixture.Snapshot)

  def doc(
      i: Int,
      text: String,
      ordinal: Int = 0,
      topics: Vector[String] = Vector("topic:openalex:t1"),
      entity: Option[String] = None
  ): RenderedDocument =
    RenderedDocument(
      indexName = classic.name,
      entityId = entity.getOrElse(s"researcher:sc:e$i"),
      documentType = "researcher-profile",
      windowStartYear = 2010,
      windowEndYear = 2013,
      ordinal = ordinal,
      text = text,
      topicIds = topics,
      institutionIds = Vector("institution:ror:i1"),
      sourceDataset = "SCHOLARCHAIN",
      communityId = Some("community:c1"),
      provenance = Fixture.prov(SourceType.SCHOLARCHAIN, s"summary-$i")
    )

  def stageAndIngest(
      docs: Vector[RenderedDocument],
      s: IngestionSettings = settings,
      forRun: IngestionRun = run
  ) =
    val ing = ingestor(s)
    val staged =
      ing.stage(forRun, docs.iterator).fold(e => throw new AssertionError(e.message), identity)
    val report =
      ing.ingest(forRun, staged.manifest).fold(e => throw new AssertionError(e.message), identity)
    (staged, report)

class VectorIngestionSpec extends StorageSuite:

  private def stored(r: IngestRig) = r.inner.stored(r.classic.name)

  // ---- metadata schema and validator -------------------------------------------------------

  private def meta(sourceDataset: String): VectorMetadata =
    Vectors.meta(Vectors.text()).copy(sourceDataset = sourceDataset)

  test("metadata validator: filterable metadata of 2000 bytes passes, 2001 bytes is rejected") {
    val rig = new IngestRig
    val base = VectorGuards.filterableBytes(meta(""))
    val atLimit = meta("s" * (2000 - base))
    VectorGuards.filterableBytes(atLimit) shouldBe 2000
    val policy = rig.settings.metadataPolicy
    VectorGuards.metadataProblems(rig.classic, atLimit, policy) shouldBe empty
    val over = meta("s" * (2001 - base))
    VectorGuards.filterableBytes(over) shouldBe 2001
    VectorGuards.metadataProblems(rig.classic, over, policy).map(_.code) shouldBe
      Vector("FILTERABLE_METADATA_TOO_LARGE")
  }

  test("the 2 KB filterable budget also applies to an ENHANCED index (project rule)") {
    val rig = new IngestRig
    val enhanced = rig.defs(1)
    val over = meta("s" * 2100)
    VectorGuards.metadataProblems(enhanced, over, rig.settings.metadataPolicy).map(_.code) should
      contain("FILTERABLE_METADATA_TOO_LARGE")
  }

  test("capped lists are rejected with a reason code, never truncated") {
    val rig = new IngestRig(maxTopicIds = 3)
    val (_, report) =
      rig.stageAndIngest(Vector(rig.doc(1, "text one", topics = Vector("t1", "t2", "t3", "t4"))))
    report.results.map(r => (r.status, r.reasonCode)) shouldBe
      Vector((ItemStatus.Rejected, Some("TOO_MANY_TOPIC_IDS")))
    stored(rig) shouldBe empty
    rig.rejects.all.map(_.reasonCode) shouldBe Vector("TOO_MANY_TOPIC_IDS")
  }

  test(
    "metadata schema: eight filterable keys, six non-filterable keys (at most 10), no free text"
  ) {
    MetadataSchema.FilterableKeyList.toSet shouldBe VectorMetadata.FilterableKeys
    IndexDefinition.NonFilterableMetadataKeys shouldBe Vector(
      "textS3Uri",
      "textHash",
      "embeddingModel",
      "embeddingModelVersion",
      "runId",
      "schemaVersion"
    )
    IndexDefinition.NonFilterableMetadataKeys.size should be <= 10
    val rig = new IngestRig
    rig.defs.foreach(_.nonFilterableKeys shouldBe IndexDefinition.NonFilterableMetadataKeys)
    val schema = MetadataSchema.forIndex(rig.classic, rig.settings)
    schema.version shouldBe "vector-metadata-v1"
    schema.nonFilterableKeys shouldBe IndexDefinition.NonFilterableMetadataKeys
    val m = Vectors.meta(Vectors.text())
    m.filterable.map(_._1).toSet.subsetOf(VectorMetadata.FilterableKeys) shouldBe true
    m.nonFilterable.map(_._1) shouldBe IndexDefinition.NonFilterableMetadataKeys
    VectorMetadata.fromJson(m.toJson) shouldBe Right(m)
  }

  // ---- key formula and collisions ------------------------------------------------------------

  test("vector key is deterministic, matches the documented formula, and ignores text") {
    val k = ok(VectorKeys.of("researcher:sc:e1", "researcher-profile", 2010, 2013, 0))
    k shouldBe "66b2ce929d9505aa4fcff268774bc4dfb5d5addb282d0bd602fd0010bf19326a"
    ok(VectorKeys.of("researcher:sc:e1", "researcher-profile", 2010, 2013, 0)) shouldBe k
    ok(VectorKeys.of("researcher:sc:e1", "researcher-profile", 2010, 2013, 1)) shouldBe
      "2053eec64911d8d62275747e89323a2ea94e04088c9385c6dbacb1584dc6c887"
    ok(VectorKeys.of("researcher:sc:e1", "researcher-profile", 2010, 2014, 0)) should not be k
    err(VectorKeys.of("a|b", "t", 2010, 2013, 0)) shouldBe a[StorageError.InvalidRecord]
    err(VectorKeys.of("a", "t", 2013, 2010, 0)) shouldBe a[StorageError.InvalidRecord]
    err(VectorKeys.of("a", "t", 2010, 2013, -1)) shouldBe a[StorageError.InvalidRecord]
  }

  test("the key comes only from identity: changing the text keeps the key") {
    val rig = new IngestRig
    rig.stageAndIngest(Vector(rig.doc(1, "first text")))
    val key = stored(rig).head.key
    rig.stageAndIngest(Vector(rig.doc(1, "second text")), forRun = rig.runNamed("second"))
    stored(rig).map(_.key) shouldBe Vector(key)
    stored(rig).head.metadata.textHash shouldBe Sha256.of("second text")
  }

  test("two different texts with the same key in one run: both rejected, nothing written") {
    val rig = new IngestRig
    val (_, report) = rig.stageAndIngest(
      Vector(rig.doc(1, "text A"), rig.doc(1, "text B"), rig.doc(2, "unrelated"))
    )
    val collided = report.results.filter(_.status == ItemStatus.RejectedKeyCollision)
    collided.size shouldBe 2
    collided.map(_.reasonCode).toSet shouldBe Set(Some("REJECTED_KEY_COLLISION"))
    collided.map(_.entityId).toSet shouldBe Set("researcher:sc:e1")
    stored(rig).map(_.metadata.entityId) shouldBe Vector(
      "researcher:sc:e2"
    ) // e1 not written at all
    rig.rejects.all.count(_.reasonCode == "REJECTED_KEY_COLLISION") shouldBe 2
  }

  test("the same text listed twice for one key is one write") {
    val rig = new IngestRig
    val (_, report) = rig.stageAndIngest(Vector(rig.doc(1, "same"), rig.doc(1, "same")))
    report.count(ItemStatus.Created) shouldBe 1
    stored(rig).size shouldBe 1
  }

  // ---- skip, update, model change ------------------------------------------------------------

  test("unchanged text and model is skipped; changed text updates and records old and new hash") {
    val rig = new IngestRig
    rig.stageAndIngest(Vector(rig.doc(1, "v1 text"), rig.doc(2, "stable")))
    val putsBefore = rig.index.putCalls
    val run2 = rig.runNamed("v2")
    val (_, report) =
      rig.stageAndIngest(Vector(rig.doc(1, "v2 text"), rig.doc(2, "stable")), forRun = run2)
    report.results.map(r => (r.entityId, r.status)).toSet shouldBe Set(
      ("researcher:sc:e1", ItemStatus.Updated),
      ("researcher:sc:e2", ItemStatus.SkippedUnchanged)
    )
    rig.index.putCalls shouldBe putsBefore + 1
    val oldH = Sha256.of("v1 text")
    val newH = Sha256.of("v2 text")
    val e1 = stored(rig).find(_.metadata.entityId == "researcher:sc:e1").get
    e1.metadata.textHash shouldBe newH
    report.results.find(_.status == ItemStatus.Updated).get.oldTextHash shouldBe Some(oldH)
    val entries = ok(rig.ports.runLedger.list(run2.runId))
    val updateEntry = entries.map(e => ujson.read(e.detail)).find(_("updated").num == 1).get
    val u = updateEntry("updates")(0)
    (u("oldTextHash").str, u("newTextHash").str) shouldBe (oldH, newH)
    u("key").str shouldBe e1.key
  }

  test(
    "a new embedding model version re-embeds the same text (UPDATED) and records both versions"
  ) {
    val first = new IngestRig(modelVersion = "2026-01")
    first.stageAndIngest(Vector(first.doc(1, "model text")))
    // a second rig sharing the stores, but configured with another model version
    val second = new IngestRig(modelVersion = "2026-06")
    first.inner
      .stored(first.classic.name)
      .foreach(r => second.inner.put(first.classic.name, Vector(r)))
    val (_, report) = second.stageAndIngest(Vector(second.doc(1, "model text")))
    report.results.map(_.status) shouldBe Vector(ItemStatus.Updated)
    stored(second).head.metadata.embeddingModelVersion shouldBe "2026-06"
    val u = ujson.read(ok(second.ports.runLedger.list(second.run.runId)).head.detail)("updates")(0)
    (u("oldEmbeddingModelVersion").str, u("newEmbeddingModelVersion").str) shouldBe
      ("2026-01", "2026-06")
    u("oldTextHash").str shouldBe u("newTextHash").str
  }

  // ---- batching, rate limit, retries ---------------------------------------------------------

  test("1,201 items are written in PutVectors batches of 500, 500 and 201") {
    val rig = new IngestRig
    val docs = (1 to 1201).map(i => rig.doc(i, s"profile text number $i")).toVector
    val (_, report) = rig.stageAndIngest(docs)
    rig.inner.putSizes shouldBe Vector(500, 500, 201)
    report.count(ItemStatus.Created) shouldBe 1201
    report.indexes.head.putBatches shouldBe 3
    rig.embedCalls.toVector shouldBe Vector(500, 500, 201)
    // existence checks go out in GetVectors batches of at most 100
    report.indexes.head.getCalls shouldBe 13
  }

  test("batch sizes come from configuration (3 and 2 in the test environment)") {
    val rig = new IngestRig(
      putBatch = TestEnv.settings.runtime.batch.putVectors,
      getBatch = TestEnv.settings.runtime.batch.getVectors
    )
    rig.stageAndIngest((1 to 7).map(i => rig.doc(i, s"cfg text $i")).toVector)
    rig.inner.putSizes shouldBe Vector(3, 3, 1)
  }

  test("the rate limiter spaces PutVectors requests per index") {
    val rig = new IngestRig(putBatch = 1, ratePerSecond = 4) // one request per 250 ms
    rig.stageAndIngest((1 to 4).map(i => rig.doc(i, s"rate text $i")).toVector)
    rig.index.putCalls shouldBe 4
    rig.time.sleeps.toVector shouldBe Vector(250L, 250L, 250L)
    val limiter = new RateLimiter(Map("a" -> 2, "b" -> 2), rig.time, rig.time)
    limiter.acquire("a") shouldBe 0L
    limiter.acquire("b") shouldBe 0L // another index has its own budget
  }

  test("throttling is retried with bounded backoff and then succeeds") {
    val rig = new IngestRig(putFailures = 2)
    val (_, report) = rig.stageAndIngest(Vector(rig.doc(1, "throttled text")))
    rig.index.putCalls shouldBe 3
    report.indexes.head.putAttempts shouldBe 3
    report.count(ItemStatus.Created) shouldBe 1
    rig.time.sleeps.toVector shouldBe Vector(10L, 20L) // MaxJitter: the exact bound 10, then 20
    ujson
      .read(ok(rig.ports.runLedger.list(rig.run.runId)).head.detail)("putAttempts")
      .num shouldBe 3
    ok(rig.ports.runLedger.list(rig.run.runId)).head.state shouldBe LedgerState.SUCCEEDED
  }

  test("giving up after the maximum attempts is recorded in RunLedger and the rejected output") {
    val rig = new IngestRig(putFailures = 99)
    val (_, report) = rig.stageAndIngest(Vector(rig.doc(1, "a"), rig.doc(2, "b")))
    rig.index.putCalls shouldBe 3 // maxAttempts
    report.count(ItemStatus.Rejected) shouldBe 2
    report.results.map(_.reasonCode).toSet shouldBe Set(Some("PUT_FAILED_AFTER_RETRIES"))
    stored(rig) shouldBe empty
    val e = ok(rig.ports.runLedger.list(rig.run.runId)).head
    e.state shouldBe LedgerState.FAILED
    val d = ujson.read(e.detail)
    (d("putAttempts").num, d("items").num, d("reasonCode").str) shouldBe
      (3.0, 2.0, "PUT_FAILED_AFTER_RETRIES")
    rig.rejects.all.map(_.reasonCode) shouldBe Vector(
      "PUT_FAILED_AFTER_RETRIES",
      "PUT_FAILED_AFTER_RETRIES"
    )
    report.rejectsUri.isDefined shouldBe true
    report.ledgerErrors shouldBe empty
    // the rerun after the outage finishes the job
    rig.index.putFailures = 0
    val again = ok(
      rig
        .ingestor()
        .ingest(rig.run, ok(new ManifestStore(rig.objects, rig.layout).read(rig.run.runId)))
    )
    again.count(ItemStatus.Created) shouldBe 2
    ok(rig.ports.runLedger.list(rig.run.runId)).map(e => (e.attempt, e.state)) shouldBe
      Vector((1, LedgerState.FAILED), (2, LedgerState.SUCCEEDED))
  }

  test("a permanent error is not retried") {
    val rig = new IngestRig
    val failing = new VectorIndex:
      def describe(n: String) = rig.inner.describe(n)
      def put(n: String, v: Vector[VectorRecord]) =
        Left(StorageError.Permanent("put", "AccessDenied"))
      def get(n: String, k: Vector[String], d: Boolean) = rig.inner.get(n, k, d)
      def query(n: String, q: VectorQuery) = rig.inner.query(n, q)
      def retryStats = rig.inner.retryStats
    val ing = new VectorIngestor(
      failing,
      rig.defs,
      new DocumentStore(rig.objects, rig.layout),
      new ManifestStore(rig.objects, rig.layout),
      rig.objects,
      rig.layout,
      rig.ports.runLedger,
      rig.ports.artifacts,
      rig.rejects,
      _ => Seq(Array(1f, 0f, 0f, 0f)),
      rig.settings,
      new RateLimiter(rig.settings.rateLimitPerSecond, rig.time, rig.time),
      rig.time,
      MaxJitter
    )
    val staged = ok(ing.stage(rig.run, Iterator(rig.doc(1, "denied"))))
    val report = ok(ing.ingest(rig.run, staged.manifest))
    report.results.map(_.reasonCode) shouldBe Vector(Some("PUT_REJECTED"))
    report.indexes.head.putAttempts shouldBe 1
    rig.time.sleeps shouldBe empty
  }

  // ---- idempotency ---------------------------------------------------------------------------

  test("rerunning the same manifest writes nothing new anywhere") {
    val rig = new IngestRig
    val docs = (1 to 25).map(i => rig.doc(i, s"idempotent text $i")).toVector
    val (staged, first) = rig.stageAndIngest(docs)
    first.count(ItemStatus.Created) shouldBe 25
    val vectors = stored(rig)
    val objectsBefore = rig.objects.snapshot
    val ledgerBefore = ok(rig.ports.runLedger.list(rig.run.runId))
    val artifactBefore =
      ok(rig.ports.artifacts.latest(s"vector-ingestion-manifest-${Fixture.runHex}"))
    val puts = rig.index.putCalls
    val sizes = rig.inner.putSizes
    val embeds = rig.embedCalls.size

    val ing = rig.ingestor()
    val again = ok(ing.ingest(rig.run, staged.manifest))
    again.count(ItemStatus.SkippedUnchanged) shouldBe 25
    again.results.count(_.status != ItemStatus.SkippedUnchanged) shouldBe 0
    // and re-staging the same documents is also a no-op
    val restaged = ok(ing.stage(rig.run, docs.iterator))
    restaged.manifest shouldBe staged.manifest

    rig.index.putCalls shouldBe puts
    rig.inner.putSizes shouldBe sizes
    rig.embedCalls.size shouldBe embeds // nothing re-embedded
    stored(rig) shouldBe vectors
    rig.objects.snapshot shouldBe objectsBefore
    ok(rig.ports.runLedger.list(rig.run.runId)) shouldBe ledgerBefore
    ok(
      rig.ports.artifacts.latest(s"vector-ingestion-manifest-${Fixture.runHex}")
    ) shouldBe artifactBefore
    artifactBefore.map(_.version) shouldBe Some(1L)
  }

  test("a changed manifest for the same run ID is refused (manifests are immutable)") {
    val rig = new IngestRig
    rig.stageAndIngest(Vector(rig.doc(1, "one")))
    val ing = rig.ingestor()
    err(ing.stage(rig.run, Iterator(rig.doc(1, "one"), rig.doc(2, "two")))) shouldBe
      a[StorageError.ConflictingContent]
  }

  // ---- document store and pointers -----------------------------------------------------------

  test(
    "documents live at <index>/<type>/<entity>/<hash>.json and every textS3Uri resolves to its text"
  ) {
    val rig = new IngestRig
    val docs = (1 to 6).map(i => rig.doc(i, s"pointer text $i ünïcode")).toVector
    rig.stageAndIngest(docs)
    val vectors = stored(rig)
    vectors.size shouldBe 6
    val store = new DocumentStore(rig.objects, rig.layout)
    vectors.foreach { v =>
      val m = v.metadata
      m.textS3Uri should startWith(
        s"s3://${TestEnv.settings.buckets.ragBucket}/${rig.classic.name}/researcher-profile/${m.entityId}/"
      )
      m.textS3Uri should endWith(s"/${m.textHash.stripPrefix("sha256:")}.json")
      val key = m.textS3Uri.stripPrefix(s"s3://${TestEnv.settings.buckets.ragBucket}/")
      val bytes = ok(rig.objects.getBytes(ObjectRef(ObjectArea.Rag, key))).get
      val doc = upickle.default.read[StoredDocument](new String(bytes, "UTF-8"))
      Sha256.of(doc.text) shouldBe m.textHash // the stored text hashes to the vector's textHash
      doc.textHash shouldBe m.textHash
      doc.provenance.sourceDataset shouldBe SourceType.SCHOLARCHAIN // full text plus provenance
      ok(store.read(ObjectRef(ObjectArea.Rag, key), m.textHash)).text shouldBe doc.text
      m.toJson.render() should not include doc.text // no long text in vector metadata
    }
  }

  test("a damaged or missing document object is rejected, not embedded") {
    val rig = new IngestRig
    val ing = rig.ingestor()
    val staged = ok(ing.stage(rig.run, Iterator(rig.doc(1, "present"), rig.doc(2, "gone"))))
    // a fresh object store that holds only the first document
    val other = new IngestRig
    val ing2 = other.ingestor()
    ok(ing2.stage(other.run, Iterator(other.doc(1, "present"))))
    val manifest = IngestionManifest.of(other.run.runId, staged.manifest.items)
    val report = ok(ing2.ingest(other.run, manifest))
    report.results.map(r => (r.entityId, r.status, r.reasonCode)).toSet shouldBe Set(
      ("researcher:sc:e1", ItemStatus.Created, None),
      ("researcher:sc:e2", ItemStatus.Rejected, Some("DOCUMENT_MISSING"))
    )
    other.inner.stored(other.classic.name).map(_.metadata.entityId) shouldBe Vector(
      "researcher:sc:e1"
    )
  }

  test("an unsafe entity ID is rejected at staging (no path traversal in S3 keys)") {
    val rig = new IngestRig
    val (staged, report) = rig.stageAndIngest(
      Vector(
        rig.doc(1, "ok"),
        rig.doc(2, "evil", entity = Some("../../etc")),
        rig.doc(3, "pipe", entity = Some("a|b"))
      )
    )
    staged.rejectedDocuments shouldBe 2
    staged.manifest.items.size shouldBe 1
    rig.rejects.all.map(r => (r.key, r.reasonCode)).toSet shouldBe Set(
      (s"${rig.classic.name}/../../etc", "INVALID_DOCUMENT"),
      (s"${rig.classic.name}/a|b", "INVALID_KEY_COMPONENT")
    )
    report.count(ItemStatus.Created) shouldBe 1
    rig.objects.snapshot.map(_._1.key).exists(_.contains("..")) shouldBe false
  }

  // ---- embedding function and index guards ---------------------------------------------------

  test("embedder failures and wrong-sized output are rejects, not crashes") {
    val boom = new IngestRig(embedder = Some(_ => throw new RuntimeException("model offline")))
    val (_, r1) = boom.stageAndIngest(Vector(boom.doc(1, "x")))
    r1.results.map(_.reasonCode) shouldBe Vector(Some("EMBEDDING_FAILED"))
    val short = new IngestRig(embedder = Some(_ => Seq.empty))
    val (_, r2) = short.stageAndIngest(Vector(short.doc(1, "x")))
    r2.results.map(_.reasonCode) shouldBe Vector(Some("EMBEDDING_FAILED"))
    stored(boom) shouldBe empty
  }

  test("bad embeddings are rejected: wrong dimension, NaN, zero vector under cosine") {
    def codes(v: Array[Float]): Vector[Option[String]] =
      val rig = new IngestRig(embedder = Some(_ => Seq(v)))
      rig.stageAndIngest(Vector(rig.doc(1, "x")))._2.results.map(_.reasonCode)
    codes(Array(1f, 0f)) shouldBe Vector(Some("DIMENSION_MISMATCH"))
    codes(Array(Float.NaN, 0f, 0f, 0f)) shouldBe Vector(Some("NON_FINITE_VALUE"))
    codes(Array(0f, 0f, 0f, 0f)) shouldBe Vector(Some("ZERO_VECTOR"))
  }

  test(
    "an index that differs from configuration, or is missing, rejects its items and writes nothing"
  ) {
    val rig = new IngestRig
    val drifted = new InMemoryVectorIndex(Vector(rig.classic.copy(dimension = 8)))
    val ing = new VectorIngestor(
      drifted,
      rig.defs,
      new DocumentStore(rig.objects, rig.layout),
      new ManifestStore(rig.objects, rig.layout),
      rig.objects,
      rig.layout,
      rig.ports.runLedger,
      rig.ports.artifacts,
      rig.rejects,
      _ => Seq(Array(1f, 0f, 0f, 0f)),
      rig.settings,
      new RateLimiter(rig.settings.rateLimitPerSecond, rig.time, rig.time),
      rig.time,
      MaxJitter
    )
    val staged = ok(ing.stage(rig.run, Iterator(rig.doc(1, "x"))))
    val report = ok(ing.ingest(rig.run, staged.manifest))
    report.results.map(_.reasonCode) shouldBe Vector(Some("INDEX_UNAVAILABLE"))
    drifted.stored(rig.classic.name) shouldBe empty
  }

  test("a document for an unconfigured index is rejected at staging") {
    val rig = new IngestRig
    val (staged, _) = rig.stageAndIngest(Vector(rig.doc(1, "x").copy(indexName = "no-such-index")))
    staged.rejectedDocuments shouldBe 1
    rig.rejects.all.map(_.reasonCode) shouldBe Vector("UNKNOWN_INDEX")
  }

  // ---- configuration -------------------------------------------------------------------------

  test("ingestion settings come from configuration; missing values name the variable") {
    val s = TestEnv.settings
    val env = Map(
      IngestionSettings.RateLimitDefaultVar -> "5",
      "SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_PATHS" -> "2",
      IngestionSettings.MaxTopicIdsVar -> "20",
      IngestionSettings.MaxInstitutionIdsVar -> "10",
      IngestionSettings.LedgerSequenceVar -> "80",
      IngestionSettings.ModelVersionVar -> "2026-01"
    )
    val loaded = ok(IngestionSettings.load(s, env))
    loaded.putBatchSize shouldBe s.runtime.batch.putVectors
    loaded.getBatchSize shouldBe s.runtime.batch.getVectors
    loaded.retry shouldBe s.runtime.retry
    loaded.rateLimitPerSecond(s.vectorIndexNames.paths) shouldBe 2
    loaded.rateLimitPerSecond(s.vectorIndexNames.researchers) shouldBe 5
    loaded.embeddingModel shouldBe s.embeddingModelId
    val e = err(IngestionSettings.load(s, Map.empty))
    e shouldBe a[StorageError.Misconfigured]
    e.message should (include(IngestionSettings.RateLimitDefaultVar) and include(
      IngestionSettings.ModelVersionVar
    ))
    err(
      IngestionSettings.load(s, env.updated(IngestionSettings.LedgerSequenceVar, "100000"))
    ) shouldBe
      a[StorageError.Misconfigured]
  }
