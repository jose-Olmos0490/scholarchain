package scholarchain.storage

import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{DistanceMetric, RetryPolicyConfig, VectorIndexType}
import scholarchain.storage.aws.{AwsErrors, Docs, S3VectorsIndex}
import scholarchain.storage.common.*
import scholarchain.storage.vectors.*
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.*

/** The embedding write path: no key ever silently refers to different text. */
class VectorWriterSpec extends StorageSuite:
  private val defs = IndexDefinition.fromSettings(TestEnv.settings)
  private val classic = defs(0) // researcher-profile-v1: CLASSIC, cosine, 4 dimensions
  private val enhanced = defs(1) // collaboration-community-v1: ENHANCED
  private val putBatch = TestEnv.settings.runtime.batch.putVectors // 3 in the test environment
  private val getBatch = TestEnv.settings.runtime.batch.getVectors // 2

  private def writer(
      idx: VectorIndex,
      d: IndexDefinition = classic,
      rejects: RejectSink = new InMemoryRejectSink
  ) =
    new VectorWriter(idx, d, putBatch, getBatch, rejects, Fixture.runId)

  private def rec(
      t: Vectors.Text,
      v: Vector[Float] = Vector(1f, 0f, 0f, 0f),
      model: String = Vectors.Model
  ) =
    VectorRecord(t.key, v, Vectors.meta(t, model = model))

  /** Counts get calls and records put sizes, delegating to the fake. */
  private class Counting(inner: InMemoryVectorIndex, failPutFrom: Int = Int.MaxValue)
      extends VectorIndex:
    var gets = Vector.empty[Int]
    var puts = 0
    def describe(n: String) = inner.describe(n)
    def put(n: String, vs: Vector[VectorRecord]) =
      puts += 1
      if puts >= failPutFrom then Left(StorageError.Permanent("put", "boom")) else inner.put(n, vs)
    def get(n: String, ks: Vector[String], d: Boolean) =
      gets :+= ks.size
      inner.get(n, ks, d)
    def query(n: String, q: VectorQuery) = inner.query(n, q)
    def retryStats = inner.retryStats

  test("batch sizes come from configuration: puts of at most 3, existence checks of at most 2") {
    val inner = new InMemoryVectorIndex(defs)
    val idx = new Counting(inner)
    val ts = (1 to 7).map(i => Vectors.text(s"p$i")).toVector
    val report = ok(writer(idx).write(ts.iterator.map(t => rec(t))))
    report.written shouldBe 7
    report.putBatches shouldBe 3
    report.getBatches shouldBe 4
    inner.putSizes shouldBe Vector(3, 3, 1)
    idx.gets shouldBe Vector(2, 2, 2, 1)
    inner.stored(classic.name).map(_.key).toSet shouldBe ts.map(_.key).toSet
  }

  test("same key and same text hash is a no-op: the second write changes nothing") {
    val inner = new InMemoryVectorIndex(defs)
    val ts = (1 to 4).map(i => Vectors.text(s"p$i")).toVector
    ok(writer(inner).write(ts.iterator.map(t => rec(t))))
    val before = inner.stored(classic.name)
    val sizes = inner.putSizes
    val again = ok(writer(inner).write(ts.iterator.map(t => rec(t, Vector(0f, 1f, 0f, 0f)))))
    again.written shouldBe 0
    again.unchanged shouldBe 4
    again.rejected shouldBe 0
    inner.stored(classic.name) shouldBe before
    inner.putSizes shouldBe sizes
  }

  test(
    "same key with a different text hash is rejected and recorded; the stored vector is untouched"
  ) {
    val inner = new InMemoryVectorIndex(defs)
    val t = Vectors.text("original")
    ok(writer(inner).write(Iterator(rec(t))))
    val rejects = new InMemoryRejectSink
    val other = Vectors.text("other text")
    val swapped = VectorRecord(t.key, Vector(0f, 0f, 0f, 1f), Vectors.meta(other))
    val report = ok(writer(inner, rejects = rejects).write(Iterator(swapped)))
    report.rejected shouldBe 1
    report.written shouldBe 0
    rejects.all.map(r => (r.store, r.reasonCode, r.key)) shouldBe
      Vector(("s3vectors", "TEXT_HASH_CONFLICT", s"${classic.name}/${t.key}"))
    rejects.all.head.detail should (include(t.hash) and include(other.hash))
    val kept = ok(inner.get(classic.name, Vector(t.key), returnData = true)).head
    kept.metadata.textHash shouldBe t.hash
    kept.embedding shouldBe Some(Vector(1f, 0f, 0f, 0f))
  }

  test("the same text embedded by another model is rejected, not mixed into the index") {
    val inner = new InMemoryVectorIndex(defs)
    val t = Vectors.text("model text")
    ok(writer(inner).write(Iterator(rec(t))))
    val rejects = new InMemoryRejectSink
    val r = ok(writer(inner, rejects = rejects).write(Iterator(rec(t, model = "other-model-v2"))))
    r.rejected shouldBe 1
    rejects.all.map(_.reasonCode) shouldBe Vector("MODEL_VERSION_MISMATCH")
    ok(
      inner.get(classic.name, Vector(t.key), returnData = false)
    ).head.metadata.embeddingModel shouldBe Vectors.Model
  }

  test("a key repeated in one input: the same hash is deduplicated, a different hash is rejected") {
    val inner = new InMemoryVectorIndex(defs)
    val t = Vectors.text("dup")
    val clash = VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), Vectors.meta(Vectors.text("clash")))
    val rejects = new InMemoryRejectSink
    val r = ok(writer(inner, rejects = rejects).write(Iterator(rec(t), rec(t), clash)))
    r.written shouldBe 1
    r.unchanged shouldBe 1
    r.rejected shouldBe 1
    rejects.all.map(_.reasonCode) shouldBe Vector("TEXT_HASH_CONFLICT")
  }

  private def reasons(r: VectorRecord, d: IndexDefinition = classic): Vector[String] =
    val rejects = new InMemoryRejectSink
    val inner = new InMemoryVectorIndex(defs)
    val report = ok(writer(inner, d, rejects).write(Iterator(r)))
    report.written shouldBe 0
    inner.stored(d.name) shouldBe empty
    rejects.all.map(_.reasonCode)

  test("missing and malformed metadata are rejected with a clear reason") {
    val t = Vectors.text()
    val good = Vectors.meta(t)
    def withMeta(m: VectorMetadata) = VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), m)
    reasons(withMeta(good.copy(textHash = ""))) should contain("MISSING_METADATA")
    reasons(withMeta(good.copy(textHash = "md5:abc"))) shouldBe Vector("MALFORMED_METADATA")
    reasons(withMeta(good.copy(textS3Uri = ""))) should contain("MISSING_METADATA")
    reasons(withMeta(good.copy(textS3Uri = "https://x/y"))) shouldBe Vector("MALFORMED_METADATA")
    reasons(withMeta(good.copy(embeddingModel = " "))) should contain("MISSING_METADATA")
    reasons(withMeta(good.copy(runId = "run:short"))) shouldBe Vector("MALFORMED_METADATA")
    reasons(withMeta(good.copy(documentType = ""))) should contain("MISSING_METADATA")
    reasons(withMeta(good.copy(researcherId = None, communityId = None))) should contain(
      "MISSING_METADATA"
    )
    reasons(withMeta(good.copy(windowEndYear = None))) shouldBe Vector("MALFORMED_METADATA")
    reasons(withMeta(good.copy(windowStartYear = Some(2014)))) shouldBe Vector("MALFORMED_METADATA")
  }

  test("a bad embedding is rejected: wrong dimension, NaN, zero vector under cosine, empty key") {
    val t = Vectors.text()
    reasons(rec(t, Vector(1f, 0f))) shouldBe Vector("DIMENSION_MISMATCH")
    reasons(rec(t, Vector(Float.NaN, 0f, 0f, 0f))) shouldBe Vector("NON_FINITE_VALUE")
    reasons(rec(t, Vector(Float.PositiveInfinity, 0f, 0f, 0f))) shouldBe Vector("NON_FINITE_VALUE")
    reasons(rec(t, Vector(0f, 0f, 0f, 0f))) shouldBe Vector("ZERO_VECTOR")
    reasons(VectorRecord("", Vector(1f, 0f, 0f, 0f), Vectors.meta(t))) shouldBe Vector(
      "INVALID_KEY"
    )
    // a zero vector is allowed under the euclidean metric (the abstracts index)
    val abstracts = defs(2)
    abstracts.distanceMetric shouldBe DistanceMetric.Euclidean
    val inner = new InMemoryVectorIndex(defs)
    ok(writer(inner, abstracts).write(Iterator(rec(t, Vector(0f, 0f, 0f, 0f))))).written shouldBe 1
  }

  test("oversized metadata goes to the rejected output, by index type") {
    val t = Vectors.text()
    val m = Vectors.meta(t)
    // one filterable string over 2 KB is refused on every index type
    val longValue = "x" * 2500
    reasons(VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), m.copy(topicId = Some(longValue)))) should
      contain("FILTERABLE_VALUE_TOO_LARGE")
    // several values that each fit but together exceed the 2 KB CLASSIC filterable budget
    val bulky = m.copy(
      topicId = Some("t" * 900),
      institutionId = Some("i" * 900),
      researcherId = Some("r" * 900)
    )
    reasons(VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), bulky)) shouldBe Vector(
      "FILTERABLE_METADATA_TOO_LARGE"
    )
    // the same record fits an ENHANCED index (40 KB filterable)
    val inner = new InMemoryVectorIndex(defs)
    ok(
      writer(inner, enhanced).write(Iterator(VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), bulky)))
    ).written shouldBe 1
    // a non-filterable field over 40 KB (CLASSIC) is refused ...
    val bigPointer = m.copy(textS3Uri = "s3://b/" + "k" * 45000)
    val classicReasons = reasons(VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), bigPointer))
    classicReasons should contain("NON_FILTERABLE_FIELD_TOO_LARGE")
    classicReasons should contain("METADATA_TOO_LARGE")
    // ... but fits ENHANCED (100 KB) while 110 KB does not
    ok(
      writer(inner, enhanced).write(
        Iterator(VectorRecord(t.key + "x", Vector(1f, 0f, 0f, 0f), bigPointer))
      )
    ).written shouldBe 1
    reasons(
      VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), m.copy(textS3Uri = "s3://b/" + "k" * 110000)),
      enhanced
    ) should
      contain("METADATA_TOO_LARGE")
  }

  test("at most 10 non-filterable keys per index") {
    val tooMany = classic.copy(nonFilterableKeys = (1 to 11).map(i => s"k$i").toVector)
    val t = Vectors.text()
    VectorGuards.metadataProblems(tooMany, Vectors.meta(t)).map(_.code) should contain(
      "TOO_MANY_NON_FILTERABLE_KEYS"
    )
    VectorGuards.metadataProblems(classic, Vectors.meta(t)) shouldBe empty
    IndexDefinition.NonFilterableMetadataKeys.size should be <= 10
  }

  test("metadata has no free-text field: only IDs, years, labels, a pointer, a hash and versions") {
    classOf[VectorMetadata].getDeclaredFields.map(_.getName).toSet shouldBe Set(
      "researcherId",
      "communityId",
      "windowStartYear",
      "windowEndYear",
      "topicId",
      "institutionId",
      "documentType",
      "sourceDataset",
      "textS3Uri",
      "textHash",
      "embeddingModel",
      "runId"
    )
    VectorMetadata.FilterableKeys shouldBe Set(
      "researcherId",
      "communityId",
      "windowStartYear",
      "windowEndYear",
      "topicId",
      "institutionId",
      "documentType",
      "sourceDataset"
    )
    Vectors
      .meta(Vectors.text())
      .nonFilterable
      .map(_._1) shouldBe IndexDefinition.NonFilterableMetadataKeys
  }

  test("a deployed index that differs from configuration, or is missing, stops the write") {
    val drift = classic.copy(dimension = 8)
    val inner = new InMemoryVectorIndex(Vector(drift))
    err(writer(inner).write(Iterator(rec(Vectors.text())))) shouldBe a[StorageError.Misconfigured]
    inner.stored(classic.name) shouldBe empty
    val mode = classic.copy(indexType = VectorIndexType.Enhanced)
    err(
      writer(new InMemoryVectorIndex(Vector(mode))).write(Iterator(rec(Vectors.text())))
    ) shouldBe a[StorageError.Misconfigured]
    err(
      writer(new InMemoryVectorIndex(Vector.empty)).write(Iterator(rec(Vectors.text())))
    ) shouldBe a[StorageError.NotFound]
  }

  test("a failed put stops the write and records the unwritten batch") {
    val inner = new InMemoryVectorIndex(defs)
    val idx = new Counting(inner, failPutFrom = 2)
    val rejects = new InMemoryRejectSink
    val ts = (1 to 7).map(i => Vectors.text(s"p$i")).toVector
    val e = err(writer(idx, rejects = rejects).write(ts.iterator.map(t => rec(t))))
    e shouldBe a[StorageError.Permanent]
    inner.stored(classic.name).size shouldBe 3 // the first batch landed
    rejects.all.map(_.reasonCode) shouldBe Vector("PUT_FAILED", "PUT_FAILED", "PUT_FAILED")
    // a rerun finishes the job without duplicating the first batch
    val rerun = ok(writer(inner).write(ts.iterator.map(t => rec(t))))
    rerun.unchanged shouldBe 3
    rerun.written shouldBe 4
  }

  test(
    "index definitions come from configuration: five indexes, names, dimensions, metrics, types"
  ) {
    defs.map(_.name) shouldBe Vector(
      "researcher-profile-v1",
      "collaboration-community-v1",
      "paper-abstract-v1",
      "collaboration-path-v1",
      "topic-institution-summary-v1"
    )
    defs.map(_.dimension).toSet shouldBe Set(4)
    defs(1).indexType shouldBe VectorIndexType.Enhanced
    defs(0).indexType shouldBe VectorIndexType.Classic
    defs(2).distanceMetric shouldBe DistanceMetric.Euclidean
    defs.foreach(
      _.nonFilterableKeys shouldBe Vector("textS3Uri", "textHash", "embeddingModel", "runId")
    )
  }

/** The SDK adapter against a stub client: request shape, errors, retries, limits before calls. */
class S3VectorsIndexAdapterSpec extends StorageSuite:
  private val defs = IndexDefinition.fromSettings(TestEnv.settings)
  private val classic = defs(0)
  private val policy = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100)
  private def details(code: String) =
    AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()
  private def throttled = TooManyRequestsException
    .builder()
    .statusCode(429)
    .awsErrorDetails(details("TooManyRequestsException"))
    .message("slow")
    .build()
  private def invalid = ValidationException
    .builder()
    .statusCode(400)
    .awsErrorDetails(details("ValidationException"))
    .message("bad")
    .build()

  private class Stub extends S3VectorsClient:
    var putCalls = Vector.empty[PutVectorsRequest]
    var getCalls = Vector.empty[GetVectorsRequest]
    var queryCalls = Vector.empty[QueryVectorsRequest]
    var onPut: PutVectorsRequest => PutVectorsResponse = _ => PutVectorsResponse.builder().build()
    var onGet: GetVectorsRequest => GetVectorsResponse = _ => GetVectorsResponse.builder().build()
    var onQuery: QueryVectorsRequest => QueryVectorsResponse = _ =>
      QueryVectorsResponse.builder().build()
    var onIndex: GetIndexRequest => GetIndexResponse = _ =>
      throw NotFoundException.builder().message("none").build()
    def serviceName(): String = "s3vectors"
    def close(): Unit = ()
    override def putVectors(r: PutVectorsRequest) = { putCalls :+= r; onPut(r) }
    override def getVectors(r: GetVectorsRequest) = { getCalls :+= r; onGet(r) }
    override def queryVectors(r: QueryVectorsRequest) = { queryCalls :+= r; onQuery(r) }
    override def getIndex(r: GetIndexRequest) = onIndex(r)

  private def index(stub: Stub, sleeper: RecordingSleeper = new RecordingSleeper) =
    new S3VectorsIndex(
      stub,
      TestEnv.settings.buckets.vectorBucket,
      new Retrier(policy, AwsErrors.isTransient, sleeper, MaxJitter),
      defs
    )

  test(
    "put sends the bucket and index from configuration, float32 data and filterable plus non-filterable metadata"
  ) {
    val stub = new Stub
    val t = Vectors.text()
    val r = Vectors.record(t, Vector(0.5f, 0.25f, 0f, 1f))
    ok(index(stub).put(classic.name, Vector(r)))
    val req = stub.putCalls.head
    req.vectorBucketName shouldBe "test-vectors"
    req.indexName shouldBe "researcher-profile-v1"
    val v = req.vectors.asScala.head
    v.key shouldBe t.key
    v.data.float32.asScala.map(_.floatValue).toVector shouldBe Vector(0.5f, 0.25f, 0f, 1f)
    Docs.toJson(v.metadata) shouldBe r.metadata.toJson
    v.metadata.asMap.keySet.asScala should contain allOf (
      "textS3Uri",
      "textHash",
      "embeddingModel",
      "runId",
      "windowStartYear"
    )
  }

  test("throttling is retried with backoff, then succeeds; retry counts are recorded") {
    val stub = new Stub
    var calls = 0
    stub.onPut = _ =>
      calls += 1
      if calls <= 2 then throw throttled
      PutVectorsResponse.builder().build()
    val sleeper = new RecordingSleeper
    val idx = index(stub, sleeper)
    ok(idx.put(classic.name, Vector(Vectors.record(Vectors.text(), Vector(1f, 0f, 0f, 0f)))))
    stub.putCalls.size shouldBe 3
    sleeper.delays.toVector shouldBe Vector(10L, 20L)
    idx.retryStats shouldBe RetrySnapshot(
      operations = 1,
      retries = 2,
      exhausted = 0,
      permanentFailures = 0
    )
  }

  test("a validation error is permanent: one call, nothing slept") {
    val stub = new Stub
    stub.onPut = _ => throw invalid
    val sleeper = new RecordingSleeper
    err(
      index(stub, sleeper)
        .put(classic.name, Vector(Vectors.record(Vectors.text(), Vector(1f, 0f, 0f, 0f))))
    ) shouldBe
      a[StorageError.Permanent]
    stub.putCalls.size shouldBe 1
    sleeper.delays shouldBe empty
  }

  test("limits are enforced before any request: batch size, dimension, oversize metadata") {
    val stub = new Stub
    val idx = index(stub)
    val tooMany =
      (1 to 501).map(_ => Vectors.record(Vectors.text(), Vector(1f, 0f, 0f, 0f))).toVector
    err(idx.put(classic.name, tooMany)) shouldBe a[StorageError.LimitExceeded]
    err(idx.put(classic.name, Vector(Vectors.record(Vectors.text(), Vector(1f))))) shouldBe a[
      StorageError.ValidationFailed
    ]
    val t = Vectors.text()
    val big =
      VectorRecord(t.key, Vector(1f, 0f, 0f, 0f), Vectors.meta(t).copy(topicId = Some("x" * 3000)))
    err(idx.put(classic.name, Vector(big))) shouldBe a[StorageError.ValidationFailed]
    err(idx.get(classic.name, (1 to 101).map(_.toString).toVector, returnData = false)) shouldBe a[
      StorageError.LimitExceeded
    ]
    err(idx.query(classic.name, VectorQuery(Vector(1f, 0f, 0f, 0f), 10001, None))) shouldBe a[
      StorageError.LimitExceeded
    ]
    err(idx.put("unconfigured-index", Vector(tooMany.head))) shouldBe a[StorageError.Misconfigured]
    stub.putCalls shouldBe empty
    stub.getCalls shouldBe empty
    stub.queryCalls shouldBe empty
  }

  test(
    "query sends the filter document, topK and metadata flags, and decodes distances and metadata"
  ) {
    val stub = new Stub
    val t = Vectors.text()
    val m = Vectors.meta(t)
    stub.onQuery = _ =>
      QueryVectorsResponse
        .builder()
        .vectors(
          QueryOutputVector
            .builder()
            .key(t.key)
            .distance(0.25f)
            .metadata(Docs.toDocument(m.toJson))
            .build()
        )
        .build()
    val filter = VectorFilter.And(
      Vector(
        VectorFilter.Eq("communityId", ujson.Str("c1")),
        VectorFilter.Gte("windowStartYear", 2010)
      )
    )
    val out =
      ok(index(stub).query(classic.name, VectorQuery(Vector(1f, 0f, 0f, 0f), 5, Some(filter))))
    out shouldBe Vector(VectorMatch(t.key, 0.25, Some(m)))
    val req = stub.queryCalls.head
    req.topK shouldBe 5
    req.returnMetadata shouldBe true
    req.returnDistance shouldBe true
    Docs.toJson(req.filter) shouldBe filter.toJson
    Docs.toJson(req.filter) shouldBe ujson.read(
      """{"$and":[{"communityId":{"$eq":"c1"}},{"windowStartYear":{"$gte":2010}}]}"""
    )
  }

  test(
    "get decodes metadata, asks for data only when requested, and rejects metadata that is not an object"
  ) {
    val stub = new Stub
    val t = Vectors.text()
    val m = Vectors.meta(t)
    stub.onGet = _ =>
      GetVectorsResponse
        .builder()
        .vectors(
          GetOutputVector
            .builder()
            .key(t.key)
            .metadata(Docs.toDocument(m.toJson))
            .data(VectorData.fromFloat32(java.util.List.of(1f, 0f, 0f, 0f)))
            .build()
        )
        .build()
    val got = ok(index(stub).get(classic.name, Vector(t.key), returnData = true))
    got shouldBe Vector(StoredVector(t.key, m, Some(Vector(1f, 0f, 0f, 0f))))
    stub.getCalls.head.returnData shouldBe true
    ok(
      index(stub).get(classic.name, Vector(t.key), returnData = false)
    ).head.embedding shouldBe None
    stub.getCalls.last.returnData shouldBe false
    stub.onGet = _ =>
      GetVectorsResponse
        .builder()
        .vectors(GetOutputVector.builder().key(t.key).metadata(Document.fromString("nope")).build())
        .build()
    err(index(stub).get(classic.name, Vector(t.key), returnData = false)) shouldBe a[
      StorageError.InvalidRecord
    ]
  }

  test("describe reads the deployed index (including its mode) and never creates one") {
    val stub = new Stub
    ok(index(stub).describe(classic.name)) shouldBe None // NotFound from the service
    stub.onIndex = _ =>
      GetIndexResponse
        .builder()
        .index(
          software.amazon.awssdk.services.s3vectors.model.Index
            .builder()
            .indexName(classic.name)
            .dimension(4)
            .distanceMetric("cosine")
            .indexMode("CLASSIC")
            .metadataConfiguration(
              MetadataConfiguration
                .builder()
                .nonFilterableMetadataKeys(classic.nonFilterableKeys.asJava)
                .build()
            )
            .build()
        )
        .build()
    ok(index(stub).describe(classic.name)) shouldBe Some(classic)
  }
