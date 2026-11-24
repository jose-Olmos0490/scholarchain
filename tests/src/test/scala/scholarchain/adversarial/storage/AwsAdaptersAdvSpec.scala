package scholarchain.adversarial.storage

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.ItemStore
import scholarchain.storage.graph.*
import scholarchain.storage.vectors.{IndexDefinition, VectorQuery}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.{ApiCallTimeoutException, SdkClientException}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  CancellationReason,
  ConditionalCheckFailedException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  TransactWriteItemsRequest,
  TransactWriteItemsResponse,
  TransactionCanceledException
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  ThrottlingException,
  TimeLimitExceededException
}
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.*

/** The AWS adapters against stub SDK clients: Neptune timeout, S3 Vectors throttling and
  * transient failure, DynamoDB conditional-write conflicts, and rows that the stores should
  * never have produced. No network, no credentials.
  */
class AwsAdaptersAdvSpec extends AnyFunSuite with Matchers:

  private val policy = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100)

  final private class Sleeps extends Sleeper:
    val delays = mutable.ArrayBuffer.empty[Long]
    def sleep(millis: Long): Unit = delays += millis
  private object MaxJit extends Jitter:
    def upTo(b: Long): Long = b

  private def retrier(s: Sleeps) = new Retrier(policy, AwsErrors.isTransient, s, MaxJit)
  private def details(code: String) =
    AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()

  // ---- Neptune -----------------------------------------------------------------------------------

  private val glimits = GraphReaderLimits(5, 4, 3000L)

  final private class NStub extends NeptunedataClient:
    val queries = mutable.ArrayBuffer.empty[ExecuteOpenCypherQueryRequest]
    var onQuery: ExecuteOpenCypherQueryRequest => ExecuteOpenCypherQueryResponse = _ =>
      ExecuteOpenCypherQueryResponse
        .builder()
        .results(Document.fromList(java.util.List.of()))
        .build()
    def serviceName(): String = "neptune-db"
    def close(): Unit = ()
    override def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) =
      queries += r
      onQuery(r)

  private def doc(entries: (String, Document)*) = Document.fromMap(entries.toMap.asJava)
  private def rows(rs: Document*) =
    ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(rs.asJava)).build()
  private def str(s: String) = Document.fromString(s)
  private def num(n: Long) = Document.fromNumber(n)

  private val Ada = "researcher:sc:ada"

  test(
    "Neptune timeout is a typed Timeout, is not retried inside the adapter, on every query path"
  ) {
    val stub = new NStub
    stub.onQuery = _ => throw TimeLimitExceededException.builder().message("slow").build()
    val s = new Sleeps
    val reader = new NeptuneGraphReader(stub, glimits, retrier(s))
    val outs = Seq[Result[Any]](
      reader.getResearcher(Ada),
      reader.directCoauthors(Ada, 2010, 2013, 3),
      reader.shortestPath(Ada, "researcher:sc:bea", 2010, 2013, 3),
      reader.paths(Ada, "researcher:sc:bea", 2010, 2013, PathQueryLimits(3, 3, 1000))
    )
    outs.foreach {
      case Left(_: StorageError.Timeout) => ()
      case other => fail(s"expected Timeout, got $other")
    }
    stub.queries.size shouldBe outs.size // one call per read, never a retry
    s.delays shouldBe empty
    outs.foreach(_.left.toOption.get.retryable shouldBe true)
  }

  test(
    "a client-side call timeout is also a Timeout, and a connection failure is retried then Transient"
  ) {
    val stub = new NStub
    stub.onQuery = _ => throw ApiCallTimeoutException.create(1500L)
    val reader = new NeptuneGraphReader(stub, glimits, retrier(new Sleeps))
    reader.getResearcher(Ada) match
      case Left(_: StorageError.Timeout) => ()
      case other => fail(s"expected Timeout, got $other")
    stub.queries.clear()
    stub.onQuery = _ =>
      throw SdkClientException
        .builder()
        .message("conn reset")
        .cause(new java.io.IOException("x"))
        .build()
    val s = new Sleeps
    val r2 = new NeptuneGraphReader(stub, glimits, retrier(s)).getResearcher(Ada)
    r2 match
      case Left(_: StorageError.Transient) => ()
      case other => fail(s"expected Transient, got $other")
    stub.queries.size shouldBe policy.maxAttempts
    s.delays.size shouldBe policy.maxAttempts - 1
  }

  test("Neptune throttling is retried with bounded backoff, then surfaces as retryable Transient") {
    val stub = new NStub
    stub.onQuery = _ => throw ThrottlingException.builder().message("busy").statusCode(429).build()
    val s = new Sleeps
    val out = new NeptuneGraphReader(stub, glimits, retrier(s)).getResearcher(Ada)
    out match
      case Left(t: StorageError.Transient) => t.attempts shouldBe policy.maxAttempts
      case other => fail(s"expected Transient, got $other")
    stub.queries.size shouldBe policy.maxAttempts
    s.delays.forall(_ <= policy.maxDelayMs) shouldBe true
  }

  test("caller values travel only in the parameters document: a Cypher injection string is inert") {
    val stub = new NStub
    val attack = "x' }) MATCH (n) DETACH DELETE n //"
    new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).getResearcher(attack)
    new NeptuneGraphReader(stub, glimits, retrier(new Sleeps))
      .directCoauthors(attack, 2010, 2013, 3)
    stub.queries.foreach { q =>
      q.openCypherQuery should not include "DETACH"
      q.openCypherQuery should not include attack
      q.parameters should include("DETACH") // data, in the bound parameters only
    }
    val plain = new NStub
    new NeptuneGraphReader(plain, glimits, retrier(new Sleeps)).getResearcher("researcher:sc:z")
    stub.queries.head.openCypherQuery shouldBe plain.queries.head.openCypherQuery
  }

  test("reads outside the configured bounds are refused before any Neptune call") {
    val stub = new NStub
    val r = new NeptuneGraphReader(stub, glimits, retrier(new Sleeps))
    withClue("limit 0: ")(r.directCoauthors(Ada, 2010, 2013, 0).isLeft shouldBe true)
    withClue("limit 6: ")(r.directCoauthors(Ada, 2010, 2013, 6).isLeft shouldBe true)
    withClue("limit Int.MaxValue: ")(
      r.directCoauthors(Ada, 2010, 2013, Int.MaxValue).isLeft shouldBe true
    )
    withClue("paths depth 99: ")(
      r.paths(Ada, "b", 2010, 2013, PathQueryLimits(99, 3, 1000)).isLeft shouldBe true
    )
    withClue("paths timeout 999999: ")(
      r.paths(Ada, "b", 2010, 2013, PathQueryLimits(3, 3, 999999)).isLeft shouldBe true
    )
    withClue("paths inverted window: ")(
      r.paths(Ada, "b", 2013, 2010, PathQueryLimits(3, 3, 1000)).isLeft shouldBe true
    )
    stub.queries shouldBe empty
  }

  // DEFERRED phase-11 defect T3, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore("an inverted window is refused by directCoauthors like every other windowed read") {
    val stub = new NStub
    val r = new NeptuneGraphReader(stub, glimits, retrier(new Sleeps))
    val out = r.directCoauthors(Ada, 2013, 2010, 3)
    withClue(s"observed $out after ${stub.queries.size} Neptune call(s): ") {
      out.isLeft shouldBe true
      stub.queries shouldBe empty
    }
    withClue("coauthorEdge: ") {
      val e = r.coauthorEdge(Ada, "researcher:sc:bea", 2013, 2010)
      e.isLeft shouldBe true
    }
  }

  test("a non-array Neptune result is InvalidRecord, a null result is an empty answer") {
    val stub = new NStub
    stub.onQuery = _ => ExecuteOpenCypherQueryResponse.builder().results(str("surprise")).build()
    new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).getResearcher(Ada) match
      case Left(_: StorageError.InvalidRecord) => ()
      case other => fail(s"expected InvalidRecord, got $other")
    stub.onQuery = _ => ExecuteOpenCypherQueryResponse.builder().build()
    new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).getResearcher(Ada) shouldBe Right(
      None
    )
  }

  // DEFERRED phase-11 defect T2, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore(
    "a coauthor row without its edge ID, run ID or evidence pointer is refused, not defaulted to blanks"
  ) {
    val stub = new NStub
    stub.onQuery = _ =>
      rows(
        doc(
          "coauthorId" -> str("researcher:sc:bea"),
          "researcherA" -> str(Ada),
          "researcherB" -> str("researcher:sc:bea"),
          "jointPublicationCount" -> num(3),
          "firstCollaborationYear" -> num(2010),
          "lastCollaborationYear" -> num(2012),
          "windowStartYear" -> num(2010),
          "windowEndYear" -> num(2013)
        )
      )
    val out =
      new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).directCoauthors(Ada, 2010, 2013, 3)
    withClue(
      s"observed $out: an edge with no edgeId, runId and evidencePointer has no provenance: "
    ) {
      out.isLeft shouldBe true
    }
  }

  // DEFERRED phase-11 defect T2, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore("a coauthor row with a non-numeric joint publication count is refused, not read as zero") {
    val stub = new NStub
    stub.onQuery = _ =>
      rows(
        doc(
          "coauthorId" -> str("researcher:sc:bea"),
          "edgeId" -> str("coauthor-edge:e"),
          "researcherA" -> str(Ada),
          "researcherB" -> str("researcher:sc:bea"),
          "jointPublicationCount" -> str("three"),
          "firstCollaborationYear" -> num(2010),
          "lastCollaborationYear" -> num(2012),
          "windowStartYear" -> num(2010),
          "windowEndYear" -> num(2013),
          "evidencePointer" -> str("coauthor-edge-support-v1:coauthor-edge:e"),
          "runId" -> str("run:r"),
          "policyVersion" -> str("p")
        )
      )
    val out =
      new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).directCoauthors(Ada, 2010, 2013, 3)
    withClue(s"observed $out: a count of 'three' must not become 0 joint publications: ") {
      out.isLeft shouldBe true
    }
  }

  // ---- S3 Vectors ------------------------------------------------------------------------------------

  private val Bucket = "test-vectors"
  private val dim = 4
  private val IndexName = "researcher-profile-v1"
  private val definition = IndexDefinition(
    IndexName,
    dim,
    scholarchain.core.config.DistanceMetric.Cosine,
    scholarchain.core.config.VectorIndexType.Classic,
    IndexDefinition.NonFilterableMetadataKeys
  )

  final private class VStub extends S3VectorsClient:
    val puts = mutable.ArrayBuffer.empty[PutVectorsRequest]
    val gets = mutable.ArrayBuffer.empty[GetVectorsRequest]
    val queries = mutable.ArrayBuffer.empty[QueryVectorsRequest]
    var onPut: PutVectorsRequest => PutVectorsResponse = _ => PutVectorsResponse.builder().build()
    var onGet: GetVectorsRequest => GetVectorsResponse = _ => GetVectorsResponse.builder().build()
    var onQuery: QueryVectorsRequest => QueryVectorsResponse = _ =>
      QueryVectorsResponse.builder().build()
    def serviceName(): String = "s3vectors"
    def close(): Unit = ()
    override def putVectors(r: PutVectorsRequest) = { puts += r; onPut(r) }
    override def getVectors(r: GetVectorsRequest) = { gets += r; onGet(r) }
    override def queryVectors(r: QueryVectorsRequest) = { queries += r; onQuery(r) }

  private def vindex(stub: VStub, s: Sleeps = new Sleeps) =
    new S3VectorsIndex(stub, Bucket, retrier(s), Vector(definition))

  private val RunId = "run:" + "a" * 64
  private def meta(
      entity: String = "researcher:sc:ada",
      uri: String = "s3://test-rag/docs/a.json",
      modelVersion: String = "2026-01"
  ) = scholarchain.storage.vectors.VectorMetadata(
    entity,
    "researcher-profile",
    2010,
    2013,
    Vector("topic:t1"),
    Vector.empty,
    "SCHOLARCHAIN",
    None,
    uri,
    "sha256:" + "b" * 64,
    "model-x",
    modelVersion,
    RunId,
    scholarchain.storage.vectors.MetadataSchema.Version
  )
  private def rec(
      key: String = "k1",
      emb: Vector[Float] = Vector(1f, 0f, 0f, 0f),
      m: scholarchain.storage.vectors.VectorMetadata = meta()
  ) =
    scholarchain.storage.vectors.VectorRecord(key, emb, m)

  test(
    "S3 Vectors throttling on put: bounded retries with capped backoff, then a retryable Transient"
  ) {
    val stub = new VStub
    stub.onPut = _ =>
      throw TooManyRequestsException
        .builder()
        .statusCode(429)
        .awsErrorDetails(details("TooManyRequestsException"))
        .message("slow down")
        .build()
    val s = new Sleeps
    val out = vindex(stub, s).put(IndexName, Vector(rec()))
    out match
      case Left(t: StorageError.Transient) =>
        t.retryable shouldBe true
        t.attempts shouldBe policy.maxAttempts
      case other => fail(s"expected Transient, got $other")
    stub.puts.size shouldBe policy.maxAttempts
    s.delays.size shouldBe policy.maxAttempts - 1
    s.delays.forall(_ <= policy.maxDelayMs) shouldBe true
  }

  test("S3 Vectors transient 503 then success: one write lands, nothing is duplicated") {
    val stub = new VStub
    var calls = 0
    stub.onPut = _ =>
      calls += 1
      if calls < 3 then
        throw ServiceUnavailableException
          .builder()
          .statusCode(503)
          .awsErrorDetails(details("ServiceUnavailableException"))
          .message("down")
          .build()
      PutVectorsResponse.builder().build()
    vindex(stub).put(IndexName, Vector(rec())) shouldBe Right(())
    stub.puts.size shouldBe 3
  }

  test("a validation error (400) is permanent: one attempt, no backoff") {
    val stub = new VStub
    stub.onPut = _ =>
      throw ValidationException
        .builder()
        .statusCode(400)
        .awsErrorDetails(details("ValidationException"))
        .message("bad")
        .build()
    val s = new Sleeps
    vindex(stub, s).put(IndexName, Vector(rec())) match
      case Left(_: StorageError.Permanent) => ()
      case other => fail(s"expected Permanent, got $other")
    stub.puts.size shouldBe 1
    s.delays shouldBe empty
  }

  test(
    "invalid vectors never reach the service: NaN, Infinity, zero (cosine), wrong dimension, bad keys, batch size"
  ) {
    val stub = new VStub
    val idx = vindex(stub)
    val bad = Seq(
      rec(emb = Vector(Float.NaN, 0f, 0f, 0f)),
      rec(emb = Vector(Float.PositiveInfinity, 0f, 0f, 0f)),
      rec(emb = Vector(0f, 0f, 0f, 0f)),
      rec(emb = Vector(1f, 0f)),
      rec(emb = Vector.fill(5000)(1f)),
      rec(key = ""),
      rec(key = "k" * 1025),
      rec(m = meta(uri = "not-a-uri")),
      rec(m = meta(entity = " "))
    )
    bad.foreach(r => idx.put(IndexName, Vector(r)).isLeft shouldBe true)
    idx.put(IndexName, Vector.empty).isLeft shouldBe true
    idx.put(IndexName, Vector.fill(501)(rec())).isLeft shouldBe true
    idx.put("not-an-index", Vector(rec())).isLeft shouldBe true
    stub.puts shouldBe empty
  }

  test(
    "queries: a non-filterable filter key, topK bounds and a wrong-size query vector never reach the service"
  ) {
    val stub = new VStub
    val idx = vindex(stub)
    import scholarchain.storage.vectors.*
    val e = Vector(1f, 0f, 0f, 0f)
    idx
      .query(IndexName, VectorQuery(e, 5, Some(VectorFilter.Eq("textS3Uri", ujson.Str("x")))))
      .isLeft shouldBe true
    idx
      .query(IndexName, VectorQuery(e, 5, Some(VectorFilter.Eq("runId", ujson.Str("x")))))
      .isLeft shouldBe true
    idx.query(IndexName, VectorQuery(e, 0, None)).isLeft shouldBe true
    idx.query(IndexName, VectorQuery(e, -1, None)).isLeft shouldBe true
    idx.query(IndexName, VectorQuery(e, 10001, None)).isLeft shouldBe true
    idx.query(IndexName, VectorQuery(Vector(1f), 5, None)).isLeft shouldBe true
    idx.get(IndexName, Vector.empty, false).isLeft shouldBe true
    idx.get(IndexName, Vector.fill(101)("k"), false).isLeft shouldBe true
    stub.queries shouldBe empty
    stub.gets shouldBe empty
  }

  test(
    "a stored vector whose metadata is missing or malformed is InvalidRecord, never an exception"
  ) {
    val stub = new VStub
    stub.onGet =
      _ => GetVectorsResponse.builder().vectors(GetOutputVector.builder().key("k1").build()).build()
    val idx = vindex(stub)
    val out =
      try idx.get(IndexName, Vector("k1"), false)
      catch case t: Throwable => fail(s"the port threw ${t.getClass.getSimpleName}")
    out match
      case Left(_: StorageError.InvalidRecord) => ()
      case other => fail(s"expected InvalidRecord, got $other")
    stub.onQuery = _ =>
      QueryVectorsResponse
        .builder()
        .vectors(QueryOutputVector.builder().key("k1").distance(0.5f).build())
        .build()
    val q =
      try idx.query(IndexName, VectorQuery(Vector(1f, 0f, 0f, 0f), 3, None))
      catch case t: Throwable => fail(s"the port threw ${t.getClass.getSimpleName}")
    q.isLeft shouldBe true
  }

  test(
    "a stored vector with a stale embedding model version is decodable and reports that version"
  ) {
    val stub = new VStub
    val m = meta(modelVersion = "2024-OLD")
    stub.onGet = _ =>
      GetVectorsResponse
        .builder()
        .vectors(
          GetOutputVector
            .builder()
            .key("k1")
            .metadata(Docs.toDocument(m.toJson))
            .build()
        )
        .build()
    val got = vindex(stub).get(IndexName, Vector("k1"), false).toOption.get
    got.head.metadata.embeddingModelVersion shouldBe "2024-OLD"
  }

  // ---- DynamoDB conditional writes ---------------------------------------------------------------

  final private class DStub extends DynamoDbClient:
    val puts = mutable.ArrayBuffer.empty[PutItemRequest]
    val txs = mutable.ArrayBuffer.empty[TransactWriteItemsRequest]
    var onPut: PutItemRequest => PutItemResponse = _ => PutItemResponse.builder().build()
    var onTx: TransactWriteItemsRequest => TransactWriteItemsResponse = _ =>
      TransactWriteItemsResponse.builder().build()
    def serviceName(): String = "dynamodb"
    def close(): Unit = ()
    override def putItem(r: PutItemRequest) = { puts += r; onPut(r) }
    override def transactWriteItems(r: TransactWriteItemsRequest) = { txs += r; onTx(r) }

  private def dstore(stub: DStub, s: Sleeps = new Sleeps): ItemStore =
    new DynamoItemStore(stub, retrier(s))

  private val item =
    ujson.Obj("pk" -> "job#1", "sk" -> "JOB", "contentHash" -> "h", "record" -> ujson.Obj())

  test("DynamoDB: a conditional-put conflict is AlreadyExists, one call, never retried") {
    val stub = new DStub
    stub.onPut = _ => throw ConditionalCheckFailedException.builder().message("exists").build()
    val s = new Sleeps
    dstore(stub, s).putIfAbsent("t", item) match
      case Left(StorageError.AlreadyExists(k)) => k shouldBe "job#1|JOB"
      case other => fail(s"expected AlreadyExists, got $other")
    stub.puts.size shouldBe 1
    s.delays shouldBe empty
  }

  test("DynamoDB: a failed content-hash condition is Right(false), one call") {
    val stub = new DStub
    stub.onPut = _ => throw ConditionalCheckFailedException.builder().message("changed").build()
    dstore(stub).replaceIfHash("t", item, "h0") shouldBe Right(false)
    stub.puts.size shouldBe 1
  }

  test("DynamoDB: provisioned-throughput throttling is retried to success") {
    val stub = new DStub
    var n = 0
    stub.onPut = _ =>
      n += 1
      if n < 3 then
        throw software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("ProvisionedThroughputExceededException"))
          .message("slow")
          .build()
      PutItemResponse.builder().build()
    dstore(stub).putIfAbsent("t", item) shouldBe Right(())
    stub.puts.size shouldBe 3
  }

  test("DynamoDB: a transaction cancelled by a conditional check names the conflicting item") {
    val stub = new DStub
    stub.onTx = _ =>
      throw TransactionCanceledException
        .builder()
        .message("cancelled")
        .cancellationReasons(
          CancellationReason.builder().code("None").build(),
          CancellationReason.builder().code("ConditionalCheckFailed").build()
        )
        .build()
    val second = ujson.Obj("pk" -> "jobq#q", "sk" -> "JOBQUERY")
    dstore(stub).putAllIfAbsent("t", Vector(item, second)) match
      case Left(StorageError.AlreadyExists(k)) => k shouldBe "jobq#q|JOBQUERY"
      case other => fail(s"expected AlreadyExists, got $other")
    stub.txs.size shouldBe 1
  }

  // DEFERRED phase-11 defect T1, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore(
    "DynamoDB: a transaction cancelled by TransactionConflict is a retryable condition, retried"
  ) {
    val stub = new DStub
    var n = 0
    stub.onTx = _ =>
      n += 1
      if n == 1 then
        throw TransactionCanceledException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("TransactionCanceledException"))
          .message("cancelled")
          .cancellationReasons(CancellationReason.builder().code("TransactionConflict").build())
          .build()
      TransactWriteItemsResponse.builder().build()
    val out = dstore(stub).putAllIfAbsent("t", Vector(item))
    withClue(
      s"observed $out after ${stub.txs.size} call(s); DynamoDB documents TransactionConflict as retryable: "
    ) {
      out shouldBe Right(())
    }
  }

  // DEFERRED phase-11 defect T1, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore("DynamoDB: a transaction cancelled by ThrottlingError is classified retryable") {
    val stub = new DStub
    stub.onTx = _ =>
      throw TransactionCanceledException
        .builder()
        .statusCode(400)
        .awsErrorDetails(details("TransactionCanceledException"))
        .message("cancelled")
        .cancellationReasons(CancellationReason.builder().code("ThrottlingError").build())
        .build()
    val out = dstore(stub).putAllIfAbsent("t", Vector(item))
    out match
      case Left(e) => withClue(s"observed $e: ")(e.retryable shouldBe true)
      case Right(_) => fail("expected a failure")
  }

  test("DynamoDB: more than 100 items in a transaction is refused before any call") {
    val stub = new DStub
    dstore(stub).putAllIfAbsent("t", Vector.fill(101)(item)).isLeft shouldBe true
    stub.txs shouldBe empty
  }

  // ---- error text -------------------------------------------------------------------------------------

  // DEFERRED phase-11 defect T5, owner storage-engineer, see docs/agent-runs/phase-11/human-gate.md
  ignore("storage errors built from an SDK exception do not repeat credential-shaped text") {
    val secret = "AKIAIOSFODNN7EXAMPLE"
    val stub = new NStub
    stub.onQuery =
      _ => throw new IllegalStateException(s"signature mismatch for $secret secret=hunter2")
    val out = new NeptuneGraphReader(stub, glimits, retrier(new Sleeps)).getResearcher(Ada)
    val msg = out.left.toOption.get.message
    withClue(s"error message: $msg ") {
      msg should not include secret
      msg should not include "hunter2"
    }
  }

  test(
    "ObjectRef.fromS3Uri refuses every hostile location a retrieved vector's metadata could carry"
  ) {
    import scholarchain.storage.objects.*
    val layout = ObjectLayout(
      Map(ObjectArea.Rag -> "test-rag", ObjectArea.Raw -> "test-raw"),
      Map(ObjectArea.Rag -> "rag/", ObjectArea.Raw -> "raw/")
    )
    Seq(
      "s3://other-bucket/rag/x",
      "s3://test-rag/other-prefix/x",
      "s3://test-rag/rag/../raw/secret",
      "s3://test-rag/rag/./x",
      "s3://test-rag/rag//x",
      "s3://test-rag/rag/x?versionId=1",
      "s3://test-rag/rag/x#frag",
      "s3://test-rag/rag/x\u0000y",
      "s3://test-rag/rag/x\\y",
      "https://test-rag/rag/x",
      "file:///etc/passwd",
      "s3://test-rag",
      "s3://test-rag/",
      "s3:///rag/x",
      "s3://test-rag/rag/" + "a" * 1100,
      "",
      null
    ).foreach(u => withClue(String.valueOf(u))(ObjectRef.fromS3Uri(layout, u).isLeft shouldBe true))
    ObjectRef.fromS3Uri(layout, "s3://test-rag/rag/docs/a.json") shouldBe Right(
      ObjectRef(ObjectArea.Rag, "docs/a.json")
    )
  }
