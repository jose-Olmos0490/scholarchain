package scholarchain.storage

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.core.model.*
import scholarchain.storage.aws.{AwsErrors, DynamoItemStore}
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.loader.*
import scholarchain.storage.manifest.*
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

/** One in-memory item store shared by the six fake ports, with the configured table names. */
object FakeDynamo:
  val tables = TestEnv.settings.runtime.dynamoTables.copy(
    researcherMetricCache = Some("scholarchain-dev-researcher-metric-cache")
  )
  val ttlSeconds: Long = TestEnv.settings.runtime.querySession.ttlSeconds
  def ports(items: ItemStore): DynamoPorts = DynamoPorts(items, tables, ttlSeconds)

class RunLedgerFakeSpec extends RunLedgerContract:
  protected val ledger: RunLedger = FakeDynamo.ports(new InMemoryItemStore).runLedger

class EntityLinkCacheFakeSpec extends EntityLinkCacheContract:
  protected val cache: EntityLinkCache = FakeDynamo.ports(new InMemoryItemStore).entityLinkCache

class QuerySessionsFakeSpec extends QuerySessionsContract:
  protected val sessions: QuerySessions = FakeDynamo.ports(new InMemoryItemStore).querySessions

class EvidenceStoreFakeSpec extends EvidenceStoreContract:
  protected val evidence: EvidenceStore = FakeDynamo.ports(new InMemoryItemStore).evidence

class ArtifactRegistryFakeSpec extends ArtifactRegistryContract:
  protected val registry: ArtifactRegistry = FakeDynamo.ports(new InMemoryItemStore).artifacts

class MetricCacheFakeSpec extends MetricCacheContract:
  protected val metricCache: ResearcherMetricCache =
    FakeDynamo.ports(new InMemoryItemStore).metricCache.get

/** Key design facts that hold across tables, and the loader. */
class DynamoDesignSpec extends StorageSuite:

  test("TTL applies to the query session and its idempotency marker only") {
    val items = new InMemoryItemStore
    val p = FakeDynamo.ports(items)
    val created = 1_700_000_000L
    val q = Fresh.queryId()
    ok(
      p.querySessions.create(
        QuerySessionRecord(DynamoSchemas.QuerySession, q, Some("k1"), "f", created, Fresh.prov())
      )
    )
    ok(
      p.runLedger.record(
        RunLedgerEntry(
          DynamoSchemas.RunLedgerEntry,
          Fresh.runId(),
          1,
          "spark",
          1,
          LedgerState.STARTED,
          1,
          "d",
          Fresh.prov()
        )
      )
    )
    ok(
      p.entityLinkCache.put(
        LinkAdjudication(
          DynamoSchemas.LinkAdjudication,
          Sha256.of("x"),
          "p",
          "m",
          "v",
          MatchOutcome.ACCEPTED,
          0.9,
          Vector.empty,
          Fresh.prov()
        )
      )
    )
    ok(
      p.evidence.putBundle(
        Fresh.bundle(
          Fresh.queryId(),
          Vector(Fresh.graphItem(Fixture.edgeAbAll.edgeId)),
          Vector.empty
        )
      )
    )
    ok(p.artifacts.register("a", "s3://b/a", Sha256.of("a"), "v1", Fixture.runId, Fresh.prov()))
    ok(p.metricCache.get.put(Fixture.metricsAll.head))

    val t = FakeDynamo.tables
    val withTtl = (name: String) => items.items(name).filter(_.value.contains("expiresAt"))
    withTtl(t.querySession).size shouldBe 2
    withTtl(t.querySession).foreach(
      _("expiresAt").num.toLong shouldBe created + FakeDynamo.ttlSeconds
    )
    Vector(
      t.runLedger,
      t.entityLinkCache,
      t.evidenceBundle,
      t.artifactRegistry,
      t.researcherMetricCache.get
    ).foreach { table =>
      items.items(table) should not be empty
      withTtl(table) shouldBe empty
    }
  }

  test("every table uses the same generic key: string pk and string sk on every item") {
    val items = new InMemoryItemStore
    val p = FakeDynamo.ports(items)
    ok(p.metricCache.get.put(Fixture.metricsAll.head))
    ok(p.artifacts.register("a", "s3://b/a", Sha256.of("a"), "v1", Fixture.runId, Fresh.prov()))
    (items.items(FakeDynamo.tables.researcherMetricCache.get) ++ items.items(
      FakeDynamo.tables.artifactRegistry
    )).foreach { i =>
      i("pk").str should not be empty
      i("sk").str should not be empty
    }
  }

  test(
    "a conditional create keyed the same way twice fails with a typed error, on the raw item store too"
  ) {
    val items = new InMemoryItemStore
    val item = ujson.Obj("pk" -> "p", "sk" -> "s", "v" -> 1)
    ok(items.putIfAbsent("t", item))
    err(items.putIfAbsent("t", ujson.Obj("pk" -> "p", "sk" -> "s", "v" -> 2))) shouldBe StorageError
      .AlreadyExists("p|s")
    ok(items.get("t", "p", "s")).get("v") shouldBe ujson.Num(1)
  }

  test("a key beyond the DynamoDB key length limit is refused before any write") {
    val items = new InMemoryItemStore
    val p = FakeDynamo.ports(items)
    val huge = "x" * 2100
    err(
      p.artifacts.register(huge, "s3://b/a", Sha256.of("a"), "v1", Fixture.runId, Fresh.prov())
    ) shouldBe a[StorageError.LimitExceeded]
  }

  test("the artifact registry retries a lost version race with the next number") {
    val inner = new InMemoryItemStore
    // a competing writer takes version 1 right after our `latest` read
    val racing = new ItemStore:
      private var raced = false
      def putIfAbsent(table: String, item: ujson.Obj) =
        if !raced then
          raced = true
          val taken = ArtifactVersion(
            DynamoSchemas.ArtifactVersion,
            "n",
            1,
            "s3://b/other",
            Sha256.of("o"),
            "v1",
            Fixture.runId,
            Fresh.prov()
          )
          inner.putIfAbsent(
            table,
            ItemCodec.item("artifact#n", "v#000000000001", "ArtifactVersion", taken)
          )
        inner.putIfAbsent(table, item)
      def putAllIfAbsent(table: String, items: Vector[ujson.Obj]) =
        inner.putAllIfAbsent(table, items)
      def get(table: String, pk: String, sk: String) = inner.get(table, pk, sk)
      def replaceIfHash(table: String, item: ujson.Obj, h: String) =
        inner.replaceIfHash(table, item, h)
      def query(table: String, pk: String, p: Option[String], asc: Boolean, l: Option[Int]) =
        inner.query(table, pk, p, asc, l)
    val reg = new DynamoArtifactRegistry(racing, "artifacts")
    val v = ok(reg.register("n", "s3://b/x", Sha256.of("x"), "v1", Fixture.runId, Fresh.prov()))
    v.version shouldBe 2L
  }

  // ----- loader -----

  private def loadMetrics(
      chunk: Int,
      items: ItemStore = new InMemoryItemStore,
      rejects: RejectSink = new InMemoryRejectSink,
      root: java.nio.file.Path = FixturePaths.root,
      manifest: LoadManifest = manifestOf(FixturePaths.root, "dynamodb")
  ) =
    val cache = FakeDynamo.ports(items).metricCache
    new DynamoLoader(cache, chunk, rejects).load(manifest, new LocalFileStager(root))

  test(
    "the loader writes the metrics of the dynamodb manifest and reports the tables it has no home for"
  ) {
    val items = new InMemoryItemStore
    val report = ok(loadMetrics(chunk = 2, items))
    report.created shouldBe 5L
    report.unchanged shouldBe 0L
    report.rejected shouldBe 0L
    report.chunks shouldBe 3 // 5 rows in chunks of 2
    report.skippedTables.map(_.table) shouldBe Vector("coauthored_with")
    report.skippedTables.head.reason should include("no DynamoDB table")
    items.items(FakeDynamo.tables.researcherMetricCache.get).size shouldBe 5
  }

  test("idempotent reload: loading the same run twice leaves identical table state") {
    val items = new InMemoryItemStore
    ok(loadMetrics(25, items))
    val before = items.items(FakeDynamo.tables.researcherMetricCache.get)
    val second = ok(loadMetrics(25, items))
    second.created shouldBe 0L
    second.unchanged shouldBe 5L
    items.items(FakeDynamo.tables.researcherMetricCache.get) shouldBe before
  }

  test(
    "an undecodable row and a conflicting existing item are recorded as rejects, the rest still load"
  ) {
    val dir = fixtureCopy()
    val m = FixtureTamper.rewrite(
      dir,
      manifestOf(dir, "dynamodb"),
      "researcher_collaboration",
      fileIndex = 1
    ) { rows =>
      rows.updated(
        0,
        ujson.Obj({
          val o = ujson.read(rows(0).render()).obj; o("publicationCount") = ujson.Num(-5); o
        })
      )
    }
    val items = new InMemoryItemStore
    val rejects = new InMemoryRejectSink
    val report = ok(loadMetrics(25, items, rejects, dir, m))
    report.created shouldBe 4L
    report.rejected shouldBe 1L
    rejects.all.map(r => (r.store, r.reasonCode)) shouldBe Vector(("dynamodb", "ROW_DECODE"))

    // a different item already sits under a key of this run
    val clash = new InMemoryItemStore
    val existing = Fixture.metricsEarly.head
    ok(
      FakeDynamo
        .ports(clash)
        .metricCache
        .get
        .put(existing.copy(publicationCount = existing.publicationCount + 7))
    )
    val rej2 = new InMemoryRejectSink
    val r2 = ok(loadMetrics(25, clash, rej2))
    r2.created shouldBe 4L
    rej2.all.map(_.reasonCode) shouldBe Vector("CONTENT_CONFLICT")
    rej2.all.head.key shouldBe existing.metricsId
  }

  test("a checksum mismatch stops the loader before any item is written") {
    val dir = fixtureCopy()
    val m = manifestOf(dir, "dynamodb")
    val victim = dir.resolve(m.tables.head.files.head.path)
    val b = Files.readAllBytes(victim)
    b(30) = (b(30) ^ 0x01).toByte
    Files.write(victim, b)
    val items = new InMemoryItemStore
    err(loadMetrics(25, items, root = dir, manifest = m)) shouldBe a[StorageError.ChecksumMismatch]
    items.items(FakeDynamo.tables.researcherMetricCache.get) shouldBe empty
  }

  test("the loader needs the metric cache table to be configured") {
    val e = err(
      new DynamoLoader(None, 25, new InMemoryRejectSink)
        .load(manifestOf(FixturePaths.root, "dynamodb"), new LocalFileStager(FixturePaths.root))
    )
    e shouldBe a[StorageError.Misconfigured]
  }

/** The SDK adapter against a stub client: request shape, error mapping, retries. */
class DynamoItemStoreAdapterSpec extends StorageSuite:
  private val policy = RetryPolicyConfig(maxAttempts = 4, baseDelayMs = 10, maxDelayMs = 100)

  private class Stub(
      put: PutItemRequest => PutItemResponse = _ => PutItemResponse.builder().build(),
      tx: TransactWriteItemsRequest => TransactWriteItemsResponse = _ =>
        TransactWriteItemsResponse.builder().build(),
      queryFn: QueryRequest => QueryResponse = _ => QueryResponse.builder().build()
  ) extends DynamoDbClient:
    var puts = Vector.empty[PutItemRequest]
    var queries = Vector.empty[QueryRequest]
    def serviceName(): String = "dynamodb"
    def close(): Unit = ()
    override def putItem(r: PutItemRequest): PutItemResponse =
      puts :+= r
      put(r)
    override def transactWriteItems(r: TransactWriteItemsRequest): TransactWriteItemsResponse = tx(
      r
    )
    override def query(r: QueryRequest): QueryResponse =
      queries :+= r
      queryFn(r)

  private def store(stub: DynamoDbClient, sleeper: RecordingSleeper = new RecordingSleeper) =
    new DynamoItemStore(stub, new Retrier(policy, AwsErrors.isTransient, sleeper, MaxJitter))

  private val item = ujson.Obj(
    "pk" -> "run#1",
    "sk" -> "a",
    "n" -> 3,
    "d" -> 0.25,
    "ok" -> true,
    "xs" -> ujson.Arr("p", "q"),
    "m" -> ujson.Obj("k" -> "v"),
    "z" -> ujson.Null
  )

  private def details(code: String) =
    AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()

  test(
    "a conditional replace is a PutItem conditioned on the content hash; a failed condition is false"
  ) {
    val stub = new Stub()
    ok(store(stub).replaceIfHash("tbl", item, "sha256:abc")) shouldBe true
    val r = stub.puts.head
    r.conditionExpression shouldBe "contentHash = :h"
    r.expressionAttributeValues.asScala(":h").s shouldBe "sha256:abc"
    val lost =
      new Stub(put = _ => throw ConditionalCheckFailedException.builder().message("c").build())
    ok(store(lost).replaceIfHash("tbl", item, "sha256:abc")) shouldBe false
  }

  test(
    "a create is a PutItem with attribute_not_exists(pk), and every JSON type maps to an attribute"
  ) {
    val stub = new Stub()
    ok(store(stub).putIfAbsent("tbl", item))
    val r = stub.puts.head
    r.tableName shouldBe "tbl"
    r.conditionExpression shouldBe "attribute_not_exists(pk)"
    val a = r.item.asScala
    a("pk").s shouldBe "run#1"
    a("n").n shouldBe "3"
    a("d").n shouldBe "0.25"
    a("ok").bool shouldBe true
    a("xs").l.asScala.map(_.s) shouldBe Seq("p", "q")
    a("m").m.get("k").s shouldBe "v"
    a("z").nul shouldBe true
  }

  test("ConditionalCheckFailed becomes a typed AlreadyExists and is not retried") {
    val stub =
      new Stub(put = _ => throw ConditionalCheckFailedException.builder().message("exists").build())
    val sleeper = new RecordingSleeper
    err(store(stub, sleeper).putIfAbsent("tbl", item)) shouldBe StorageError.AlreadyExists(
      "run#1|a"
    )
    stub.puts.size shouldBe 1
    sleeper.delays shouldBe empty
  }

  test("throttling is retried with backoff and then succeeds; the retries are counted") {
    var calls = 0
    val stub = new Stub(put = _ =>
      calls += 1
      if calls <= 2 then
        throw ProvisionedThroughputExceededException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("ProvisionedThroughputExceededException"))
          .message("slow down")
          .build()
      PutItemResponse.builder().build())
    val sleeper = new RecordingSleeper
    val s = store(stub, sleeper)
    ok(s.putIfAbsent("tbl", item))
    stub.puts.size shouldBe 3
    sleeper.delays.toVector shouldBe Vector(10L, 20L)
  }

  test("a validation error is permanent: one attempt, no sleep") {
    val stub = new Stub(put =
      _ =>
        throw DynamoDbException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("ValidationException"))
          .message("bad")
          .build()
    )
    val sleeper = new RecordingSleeper
    err(store(stub, sleeper).putIfAbsent("tbl", item)) shouldBe a[StorageError.Permanent]
    stub.puts.size shouldBe 1
    sleeper.delays shouldBe empty
  }

  test("retries are bounded by the configured attempts") {
    val stub = new Stub(put =
      _ =>
        throw DynamoDbException
          .builder()
          .statusCode(503)
          .awsErrorDetails(details("ServiceUnavailable"))
          .message("down")
          .build()
    )
    val e = err(store(stub).putIfAbsent("tbl", item))
    e shouldBe StorageError.Transient(
      "DynamoDB put tbl",
      4,
      e.asInstanceOf[StorageError.Transient].cause
    )
    stub.puts.size shouldBe 4
  }

  test("a cancelled transaction reports the item whose condition failed") {
    val items = Vector(
      ujson.Obj("pk" -> "idem#k", "sk" -> "QUERY"),
      ujson.Obj("pk" -> "query#q", "sk" -> "SESSION")
    )
    val stub = new Stub(tx =
      _ =>
        throw TransactionCanceledException
          .builder()
          .message("cancelled")
          .cancellationReasons(
            CancellationReason.builder().code("ConditionalCheckFailed").build(),
            CancellationReason.builder().code("None").build()
          )
          .build()
    )
    err(store(stub).putAllIfAbsent("tbl", items)) shouldBe StorageError.AlreadyExists(
      "idem#k|QUERY"
    )
    err(store(stub).putAllIfAbsent("tbl", Vector.fill(101)(item))) shouldBe a[
      StorageError.LimitExceeded
    ]
  }

  test("query: begins_with prefix, direction, limit and paging") {
    val page1 = QueryResponse
      .builder()
      .items(
        java.util.List
          .of(java.util.Map.of("pk", AttributeValue.fromS("p"), "sk", AttributeValue.fromS("v#2")))
      )
      .lastEvaluatedKey(
        java.util.Map.of("pk", AttributeValue.fromS("p"), "sk", AttributeValue.fromS("v#2"))
      )
      .build()
    val page2 = QueryResponse
      .builder()
      .items(
        java.util.List
          .of(java.util.Map.of("pk", AttributeValue.fromS("p"), "sk", AttributeValue.fromS("v#1")))
      )
      .build()
    val stub = new Stub(queryFn =
      r => if r.exclusiveStartKey == null || r.exclusiveStartKey.isEmpty then page1 else page2
    )
    val rows = ok(store(stub).query("tbl", "p", Some("v#"), ascending = false, None))
    rows.map(_("sk").str) shouldBe Vector("v#2", "v#1")
    val first = stub.queries.head
    first.keyConditionExpression shouldBe "pk = :pk AND begins_with(sk, :p)"
    first.scanIndexForward shouldBe false
    first.consistentRead shouldBe true
    stub.queries.size shouldBe 2
    // a limit of 1 stops after the first page
    val one = new Stub(queryFn = _ => page1)
    ok(store(one).query("tbl", "p", None, ascending = true, Some(1))).size shouldBe 1
    one.queries.size shouldBe 1
    one.queries.head.limit shouldBe 1
  }
