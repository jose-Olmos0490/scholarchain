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
      VectorRecord(
        t.key,
        Vector(1f, 0f, 0f, 0f),
        Vectors.meta(t).copy(topicIds = Vector("x" * 3000))
      )
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

  test("describe of an index listing its keys alphabetically still matches configuration") {
    val sorted = classic.nonFilterableKeys.sorted
    sorted should not be classic.nonFilterableKeys
    val stub = new Stub
    stub.onIndex = _ =>
      GetIndexResponse
        .builder()
        .index(
          software.amazon.awssdk.services.s3vectors.model.Index
            .builder()
            .indexName(classic.name)
            .dimension(classic.dimension)
            .distanceMetric("cosine")
            .indexMode("CLASSIC")
            .metadataConfiguration(
              MetadataConfiguration.builder().nonFilterableMetadataKeys(sorted.asJava).build()
            )
            .build()
        )
        .build()
    val deployed = ok(index(stub).describe(classic.name)).get
    deployed.nonFilterableKeys shouldBe sorted
    classic.matches(deployed) shouldBe true
  }

  test("matches ignores key order but not name, dimension, metric, index type or key set") {
    val keys = classic.nonFilterableKeys
    classic.matches(classic.copy(nonFilterableKeys = keys.reverse)) shouldBe true
    Vector(
      classic.copy(name = "other"),
      classic.copy(dimension = classic.dimension + 1),
      classic.copy(distanceMetric = DistanceMetric.Euclidean),
      classic.copy(indexType = VectorIndexType.Enhanced),
      classic.copy(nonFilterableKeys = keys.tail),
      classic.copy(nonFilterableKeys = keys :+ "extra"),
      classic.copy(nonFilterableKeys = keys.tail :+ "extra"),
      classic.copy(nonFilterableKeys = Vector.empty)
    ).foreach(d => withClue(s"$d: ")(classic.matches(d) shouldBe false))
  }
