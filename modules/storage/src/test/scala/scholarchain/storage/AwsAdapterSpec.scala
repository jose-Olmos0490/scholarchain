package scholarchain.storage

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{LoaderParallelism, RetryPolicyConfig}
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.objects.*
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  NoSuchKeyException,
  PutObjectRequest,
  PutObjectResponse,
  S3Exception
}

/** The S3 and Neptune SDK adapters against stub clients: request shape, error mapping, retries. */
class S3ObjectStoreAdapterSpec extends StorageSuite:
  private val policy = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100)
  private val layout = ok(
    ObjectLayout.fromConfig(
      TestEnv.settings.buckets,
      TestEnv.settings.runtime.s3Prefixes,
      ObjectArea.Graph
    )
  )
  private val ref = ObjectRef(ObjectArea.Reports, "run_id=abc/storage/x.json")

  private def s3Error(status: Int, code: String): S3Exception =
    S3Exception
      .builder()
      .statusCode(status)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build())
      .message(code)
      .build()
      .asInstanceOf[S3Exception]

  private class Stub extends S3Client:
    var puts = Vector.empty[(PutObjectRequest, Array[Byte])]
    var onPut: PutObjectRequest => PutObjectResponse = _ => PutObjectResponse.builder().build()
    var onHead: HeadObjectRequest => HeadObjectResponse = _ =>
      throw NoSuchKeyException.builder().message("none").build()
    var body: Array[Byte] = Array.emptyByteArray
    def serviceName(): String = "s3"
    def close(): Unit = ()
    override def putObject(r: PutObjectRequest, b: RequestBody): PutObjectResponse =
      puts :+= (r -> b.contentStreamProvider.newStream.readAllBytes)
      onPut(r)
    override def headObject(r: HeadObjectRequest): HeadObjectResponse = onHead(r)
    override def getObject(r: GetObjectRequest): ResponseInputStream[GetObjectResponse] =
      new ResponseInputStream(
        GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(body))
      )

  private def store(stub: Stub, sleeper: RecordingSleeper = new RecordingSleeper) =
    new S3ObjectStore(stub, layout, new Retrier(policy, AwsErrors.isTransient, sleeper, MaxJitter))

  test("a write is conditional (If-None-Match: *), checksummed, and tagged with its SHA-256") {
    val stub = new Stub
    ok(
      store(stub).putBytes(ref, "hello".getBytes(UTF_8), "application/json")
    ) shouldBe PutOutcome.Created
    val (req, sent) = stub.puts.head
    req.bucket shouldBe "test-graph"
    req.key shouldBe "reports/run_id=abc/storage/x.json" // configured reports prefix
    req.ifNoneMatch shouldBe "*"
    req.contentType shouldBe "application/json"
    req.metadata.get("sha256") shouldBe Sha256.of("hello")
    req.checksumSHA256 shouldBe Sha256.base64(Sha256.of("hello"))
    new String(sent, UTF_8) shouldBe "hello"
  }

  test(
    "an existing key with the same hash is a skip; a different hash is a conflict; neither overwrites"
  ) {
    val stub = new Stub
    stub.onPut = _ => throw s3Error(412, "PreconditionFailed")
    stub.onHead = _ =>
      HeadObjectResponse
        .builder()
        .contentLength(5L)
        .metadata(java.util.Map.of("sha256", Sha256.of("hello")))
        .build()
    ok(store(stub).putBytes(ref, "hello".getBytes(UTF_8), "text/plain")) shouldBe PutOutcome.Skipped
    val e = err(store(stub).putBytes(ref, "changed".getBytes(UTF_8), "text/plain"))
    e shouldBe StorageError.ConflictingContent(ref.key, Sha256.of("hello"), Sha256.of("changed"))
    stub.puts.size shouldBe 2 // two attempts, each refused by the condition; no retry of a 412
  }

  test("an existing object without a recorded hash is hashed by streaming it") {
    val stub = new Stub
    stub.onPut = _ => throw s3Error(412, "PreconditionFailed")
    stub.onHead = _ => HeadObjectResponse.builder().contentLength(5L).build()
    stub.body = "hello".getBytes(UTF_8)
    ok(store(stub).putBytes(ref, "hello".getBytes(UTF_8), "text/plain")) shouldBe PutOutcome.Skipped
    err(store(stub).putBytes(ref, "other".getBytes(UTF_8), "text/plain")) shouldBe a[
      StorageError.ConflictingContent
    ]
  }

  test("throttling and 5xx are retried with backoff; access errors are not") {
    val stub = new Stub
    var calls = 0
    stub.onPut = _ =>
      calls += 1
      if calls <= 2 then throw s3Error(503, "ServiceUnavailable")
      PutObjectResponse.builder().build()
    val sleeper = new RecordingSleeper
    ok(
      store(stub, sleeper).putBytes(ref, "x".getBytes(UTF_8), "text/plain")
    ) shouldBe PutOutcome.Created
    stub.puts.size shouldBe 3
    sleeper.delays.toVector shouldBe Vector(10L, 20L)

    val denied = new Stub
    denied.onPut = _ => throw s3Error(403, "AccessDenied")
    val s2 = new RecordingSleeper
    err(store(denied, s2).putBytes(ref, "x".getBytes(UTF_8), "text/plain")) shouldBe a[
      StorageError.Permanent
    ]
    denied.puts.size shouldBe 1
    s2.delays shouldBe empty
  }

  test("head of a missing key is None") {
    ok(store(new Stub).head(ref)) shouldBe None
  }

/** Neptune: parameterized queries through the data API, and bulk-load requests. */
class NeptuneAdapterSpec extends StorageSuite:
  private val policy = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100)
  private val limits = GraphReaderLimits(TestEnv.settings.graphLimit, TestEnv.settings.maxPathDepth)
  private val layout = ok(
    ObjectLayout.fromConfig(
      TestEnv.settings.buckets,
      TestEnv.settings.runtime.s3Prefixes,
      ObjectArea.Graph
    )
  )
  private def details(code: String) =
    AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()

  private class Stub extends NeptunedataClient:
    var queries = Vector.empty[ExecuteOpenCypherQueryRequest]
    var loads = Vector.empty[StartLoaderJobRequest]
    var onQuery: ExecuteOpenCypherQueryRequest => ExecuteOpenCypherQueryResponse = _ =>
      ExecuteOpenCypherQueryResponse
        .builder()
        .results(Document.fromList(java.util.List.of()))
        .build()
    var onLoad: StartLoaderJobRequest => StartLoaderJobResponse = _ =>
      StartLoaderJobResponse
        .builder()
        .status("200 OK")
        .payload(java.util.Map.of("loadId", "load-1"))
        .build()
    var onStatus: GetLoaderJobStatusRequest => GetLoaderJobStatusResponse = _ =>
      GetLoaderJobStatusResponse.builder().build()
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) = {
      queries :+= r; onQuery(r)
    }
    override def startLoaderJob(r: StartLoaderJobRequest) = { loads :+= r; onLoad(r) }
    override def getLoaderJobStatus(r: GetLoaderJobStatusRequest) = onStatus(r)

  private def row(entries: (String, Document)*): Document = Document.fromMap(entries.toMap.asJava)
  private def rows(rs: Document*): ExecuteOpenCypherQueryResponse =
    ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(rs.asJava)).build()
  private def str(s: String) = Document.fromString(s)
  private def num(n: Long) = Document.fromNumber(n)

  private def reader(stub: Stub, sleeper: RecordingSleeper = new RecordingSleeper) =
    new NeptuneGraphReader(
      stub,
      limits,
      new Retrier(policy, AwsErrors.isTransient, sleeper, MaxJitter)
    )

  test("a query is sent as a constant text with caller values only in the parameters document") {
    val stub = new Stub
    val hostile = "x' }) DETACH DELETE n //"
    ok(reader(stub).getResearcher(hostile))
    val q = stub.queries.head
    q.openCypherQuery shouldBe CypherTemplates.GetResearcher
    q.openCypherQuery should not include "DETACH"
    ujson.read(q.parameters) shouldBe ujson.Obj("researcherId" -> hostile)
  }

  test("directCoauthors binds window and limit as parameters and maps the rows") {
    val stub = new Stub
    stub.onQuery = _ =>
      rows(
        row(
          "coauthorId" -> str("researcher:b"),
          "edgeId" -> str("coauthor-edge:e"),
          "researcherA" -> str("researcher:a"),
          "researcherB" -> str("researcher:b"),
          "jointPublicationCount" -> num(2),
          "firstCollaborationYear" -> num(2010),
          "lastCollaborationYear" -> num(2012),
          "windowStartYear" -> num(2010),
          "windowEndYear" -> num(2013),
          "evidencePointer" -> str("coauthor-edge-support-v1:coauthor-edge:e"),
          "runId" -> str("run:r"),
          "policyVersion" -> str("p1")
        )
      )
    val out = ok(reader(stub).directCoauthors("researcher:a", 2010, 2013, 5))
    out.map(_.coauthorId) shouldBe Vector("researcher:b")
    out.head.edge shouldBe CoauthorEdgeView(
      "coauthor-edge:e",
      "researcher:a",
      "researcher:b",
      2,
      2010,
      2012,
      2010,
      2013,
      "coauthor-edge-support-v1:coauthor-edge:e",
      "run:r",
      "p1"
    )
    ujson.read(stub.queries.head.parameters) shouldBe
      ujson.Obj(
        "researcherId" -> "researcher:a",
        "windowStartYear" -> 2010,
        "windowEndYear" -> 2013,
        "limit" -> 5
      )
  }

  test("shortestPath keeps the shortest candidates and breaks ties lexicographically") {
    val stub = new Stub
    def path(ids: String*) =
      row(
        "researcherIds" -> Document.fromList(ids.map(str).asJava),
        "edgeIds" -> Document.fromList(ids.tail.map(i => str(s"e-$i")).asJava),
        "length" -> num(ids.size - 1L)
      )
    stub.onQuery = _ => rows(path("a", "z", "c"), path("a", "q", "c"), path("a", "m", "n", "c"))
    val p = ok(reader(stub).shortestPath("a", "c", 2010, 2013, 3)).get
    p.researcherIds shouldBe Vector("a", "q", "c")
    p.length shouldBe 2
    stub.queries.head.openCypherQuery should include("*1..3")
    ujson.read(stub.queries.head.parameters)("candidateLimit") shouldBe ujson.Num(limits.maxLimit)
    stub.onQuery = _ => rows()
    ok(reader(stub).shortestPath("a", "c", 2010, 2013, 3)) shouldBe None
  }

  test("limits are checked before any call; throttling is retried; a bad query is permanent") {
    val stub = new Stub
    err(reader(stub).directCoauthors("a", 1, 2, limits.maxLimit + 1)) shouldBe a[
      StorageError.LimitExceeded
    ]
    err(reader(stub).shortestPath("a", "b", 1, 2, limits.maxDepth + 1)) shouldBe a[
      StorageError.LimitExceeded
    ]
    stub.queries shouldBe empty

    var calls = 0
    stub.onQuery = _ =>
      calls += 1
      if calls == 1 then
        throw TooManyRequestsException
          .builder()
          .statusCode(429)
          .awsErrorDetails(details("TooManyRequestsException"))
          .message("slow")
          .build()
      rows()
    val sleeper = new RecordingSleeper
    ok(reader(stub, sleeper).getResearcher("r")) shouldBe None
    stub.queries.size shouldBe 2
    sleeper.delays.toVector shouldBe Vector(10L)

    val bad = new Stub
    bad.onQuery = _ =>
      throw MalformedQueryException
        .builder()
        .statusCode(400)
        .awsErrorDetails(details("MalformedQueryException"))
        .message("syntax")
        .build()
    err(reader(bad).getResearcher("r")) shouldBe a[StorageError.Permanent]
    bad.queries.size shouldBe 1
  }

  test(
    "a bulk load request uses format opencypher, the configured role and parallelism, and user-provided edge IDs"
  ) {
    val stub = new Stub
    val roleArn = "arn:aws:iam::123456789012:role/test-neptune-loader"
    val loader = new NeptuneBulkLoader(
      stub,
      layout,
      roleArn,
      "eu-west-1",
      LoaderParallelism.Medium,
      new Retrier(policy, AwsErrors.isTransient, new RecordingSleeper, MaxJitter)
    )
    val prefix = ObjectRef(ObjectArea.NeptuneExport, s"run_id=${Fixture.runHex}/csv/")
    val job = ok(loader.startLoad(GraphLoadRequest(Fixture.runId, prefix)))
    job shouldBe GraphLoadJob("load-1", Fixture.runId)
    val r = stub.loads.head
    r.source shouldBe s"s3://test-graph/neptune-export/run_id=${Fixture.runHex}/csv/"
    r.format.toString shouldBe "opencypher"
    r.iamRoleArn shouldBe roleArn
    r.s3BucketRegion.toString shouldBe "eu-west-1"
    r.mode.toString shouldBe "NEW"
    r.failOnError shouldBe true
    r.parallelism.toString shouldBe "MEDIUM"
    r.userProvidedEdgeIds shouldBe true
    r.queueRequest shouldBe true
  }

  test("loader status maps the overall status of the job") {
    val stub = new Stub
    val loader = new NeptuneBulkLoader(
      stub,
      layout,
      "arn:aws:iam::123456789012:role/r",
      "eu-west-1",
      LoaderParallelism.Low,
      new Retrier(policy, AwsErrors.isTransient, new RecordingSleeper, MaxJitter)
    )
    def status(s: String) =
      stub.onStatus = _ =>
        GetLoaderJobStatusResponse
          .builder()
          .payload(
            Document.fromMap(
              java.util.Map.of(
                "overallStatus",
                Document.fromMap(java.util.Map.of("status", Document.fromString(s)))
              )
            )
          )
          .build()
      ok(loader.status(GraphLoadJob("load-1", Fixture.runId)))
    status("LOAD_COMPLETED") shouldBe GraphLoadState.Completed
    status("LOAD_IN_PROGRESS") shouldBe GraphLoadState.InProgress
    status("LOAD_IN_QUEUE") shouldBe GraphLoadState.Queued
    status("LOAD_FAILED") shouldBe GraphLoadState.Failed("LOAD_FAILED")
  }
