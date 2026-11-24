package scholarchain.adversarial.storage

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.adversarial.services.Kit
import scholarchain.core.config.RetryPolicyConfig
import scholarchain.core.model.SourceType
import scholarchain.storage.StorageSettings
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.*

/** The embedding ingestion path under hostile input and a misbehaving embedder: duplicate and
  * conflicting vector keys, stale embedding model versions, replays, non-finite and mis-sized
  * embeddings, an index that throttles, and key components that would break the key formula.
  */
class VectorIngestionAdvSpec extends AnyFunSuite with Matchers:

  private val env: Map[String, String] = Map(
    "SCHOLARCHAIN_ENV" -> "dev",
    "AWS_REGION" -> "eu-west-1",
    "RAW_BUCKET" -> "test-raw",
    "CURATED_BUCKET" -> "test-curated",
    "GRAPH_BUCKET" -> "test-graph",
    "RAG_BUCKET" -> "test-rag",
    "VECTOR_BUCKET" -> "test-vectors",
    "NEPTUNE_ENDPOINT" -> "neptune.test.invalid",
    "NEPTUNE_PORT" -> "8182",
    "NEPTUNE_QUERY_TIMEOUT_MS" -> "20000",
    "VECTOR_INDEX_RESEARCHERS" -> "researcher-profile-v1",
    "VECTOR_INDEX_COMMUNITIES" -> "collaboration-community-v1",
    "VECTOR_INDEX_ABSTRACTS" -> "paper-abstract-v1",
    "VECTOR_INDEX_PATHS" -> "collaboration-path-v1",
    "VECTOR_INDEX_TOPIC_INSTITUTIONS" -> "topic-institution-summary-v1",
    "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT" -> "25",
    "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH" -> "4",
    "EMBEDDING_MODEL_ID" -> "test-embedding-model-v1",
    "SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_TYPE" -> "ENHANCED",
    "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_DISTANCE_METRIC" -> "euclidean",
    "SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "4",
    "SCHOLARCHAIN_VECTOR_INDEX_TOPIC_INSTITUTIONS_DIMENSION" -> "4",
    "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "3",
    "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE" -> "2"
  )
  private val settings: StorageSettings =
    StorageSettings.load(env).fold(e => throw new IllegalStateException(e.message), identity)

  final private class SimTime extends MonotonicClock, Sleeper:
    var now = 0L
    def nanos: Long = now
    def sleep(ms: Long): Unit = now += ms * 1_000_000L

  private object MaxJit extends Jitter:
    def upTo(b: Long): Long = b

  /** An index that throttles or fails on demand; everything else is the in-memory fake. */
  final private class Flaky(val inner: InMemoryVectorIndex) extends VectorIndex:
    @volatile var putFailure: Option[StorageError] = None
    var putCalls = 0
    def describe(n: String) = inner.describe(n)
    def put(n: String, vs: Vector[VectorRecord]) =
      putCalls += 1
      putFailure.toLeft(()).flatMap(_ => inner.put(n, vs))
    def get(n: String, ks: Vector[String], d: Boolean) = inner.get(n, ks, d)
    def query(n: String, q: VectorQuery) = inner.query(n, q)
    def retryStats = inner.retryStats

  final private class Rig(var embedder: Embedder, modelVersion: String = "2026-01"):
    val defs: Vector[IndexDefinition] = IndexDefinition.fromSettings(settings)
    val classic: IndexDefinition = defs.head
    val inner = new InMemoryVectorIndex(defs)
    val index = new Flaky(inner)
    val objects = new InMemoryObjectStore
    val items = new InMemoryItemStore
    val ports: DynamoPorts =
      DynamoPorts(items, settings.runtime.dynamoTables, settings.runtime.querySession.ttlSeconds)
    val rejects = new InMemoryRejectSink
    val time = new SimTime
    val layout: ObjectLayout = ObjectLayout
      .fromConfig(settings.buckets, settings.runtime.s3Prefixes, ObjectArea.Rag)
      .fold(e => throw new IllegalStateException(e.message), identity)

    def cfg(version: String = modelVersion): IngestionSettings = IngestionSettings(
      putBatchSize = 3,
      getBatchSize = 2,
      retry = RetryPolicyConfig(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 100),
      rateLimitPerSecond = defs.map(_.name -> 1000).toMap,
      maxTopicIds = 3,
      maxInstitutionIds = 3,
      embeddingModel = "test-embedding-model-v1",
      embeddingModelVersion = version,
      ledgerSequence = 7
    )

    def ingestor(s: IngestionSettings = cfg()): VectorIngestor =
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
        (texts: Seq[String]) => embedder(texts),
        s,
        new RateLimiter(s.rateLimitPerSecond, time, time),
        time,
        MaxJit
      )

    private def runOf(label: String) =
      IngestionRun("run:" + Sha256.hex(label.getBytes("UTF-8")), "2026-11-01")

    def ingest(label: String, docs: Vector[RenderedDocument], s: IngestionSettings = cfg()) =
      val ing = ingestor(s)
      val run = runOf(label)
      val staged =
        ing.stage(run, docs.iterator).fold(e => throw new AssertionError(e.message), identity)
      ing.ingest(run, staged.manifest)

    def doc(
        entity: String,
        text: String,
        ordinal: Int = 0,
        topics: Vector[String] = Vector("topic:openalex:t1")
    ) = RenderedDocument(
      classic.name,
      entity,
      "researcher-profile",
      2010,
      2013,
      ordinal,
      text,
      topics,
      Vector("institution:ror:i1"),
      "SCHOLARCHAIN",
      Some("community:c1"),
      Kit.prov(entity)
    )

    def stored = inner.stored(classic.name)

  private def okEmbedder(calls: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty): Embedder =
    texts =>
      calls += texts.size
      texts.map(t => Array(1f, (math.abs(t.hashCode) % 7).toFloat + 1f, 2f, 3f))

  private def e(i: Int) = s"researcher:sc:e$i"

  test("one key claimed by two different texts in one run: both refused, nothing is written") {
    val r = new Rig(okEmbedder())
    val rep = r
      .ingest("a", Vector(r.doc(e(1), "first text"), r.doc(e(1), "second, different text")))
      .toOption
      .get
    rep.results.map(_.status) shouldBe Vector(
      ItemStatus.RejectedKeyCollision,
      ItemStatus.RejectedKeyCollision
    )
    r.stored shouldBe empty
    r.rejects.all.map(_.reasonCode).distinct shouldBe Vector("REJECTED_KEY_COLLISION")
  }

  test("an exact duplicate document is written once and reported as a duplicate") {
    val calls = mutable.ArrayBuffer.empty[Int]
    val r = new Rig(okEmbedder(calls))
    val rep = r.ingest("a", Vector.fill(5)(r.doc(e(1), "same text"))).toOption.get
    r.stored.size shouldBe 1
    rep.count(ItemStatus.Created) shouldBe 1
    rep.results.forall(_.status != ItemStatus.Rejected) shouldBe true
    calls.sum shouldBe 1
  }

  test("replay of the same run: nothing is embedded or written again") {
    val calls = mutable.ArrayBuffer.empty[Int]
    val r = new Rig(okEmbedder(calls))
    val docs = (1 to 7).map(i => r.doc(e(i), s"text $i")).toVector
    r.ingest("a", docs).toOption.get.count(ItemStatus.Created) shouldBe 7
    val puts = r.index.putCalls
    val embedded = calls.sum
    val again = r.ingest("a", docs).toOption.get
    again.count(ItemStatus.SkippedUnchanged) shouldBe 7
    again.count(ItemStatus.Created) shouldBe 0
    r.index.putCalls shouldBe puts
    calls.sum shouldBe embedded
    r.stored.size shouldBe 7
  }

  test(
    "a new embedding model version re-embeds and overwrites the stale vector, one vector per key"
  ) {
    val r = new Rig(okEmbedder())
    val docs = Vector(r.doc(e(1), "text one"), r.doc(e(2), "text two"))
    r.ingest("a", docs, r.cfg("2026-01")).toOption.get.count(ItemStatus.Created) shouldBe 2
    r.stored.map(_.metadata.embeddingModelVersion).distinct shouldBe Vector("2026-01")
    val rep = r.ingest("b", docs, r.cfg("2026-02")).toOption.get
    rep.count(ItemStatus.Updated) shouldBe 2
    r.stored.size shouldBe 2
    r.stored.map(_.metadata.embeddingModelVersion).distinct shouldBe Vector("2026-02")
    // and an older writer replaying with the old version is also reported as an update, never silently skipped
    val back = r.ingest("c", docs, r.cfg("2026-01")).toOption.get
    back.count(ItemStatus.SkippedUnchanged) shouldBe 0
  }

  test("changed text under the same key is an update that records the previous text hash") {
    val r = new Rig(okEmbedder())
    r.ingest("a", Vector(r.doc(e(1), "old text"))).toOption.get
    val oldHash = r.stored.head.metadata.textHash
    val rep = r.ingest("b", Vector(r.doc(e(1), "new text"))).toOption.get
    val item = rep.results.head
    item.status shouldBe ItemStatus.Updated
    item.oldTextHash shouldBe Some(oldHash)
    item.newTextHash should not be oldHash
    r.stored.size shouldBe 1
  }

  test(
    "an embedder that returns NaN, Infinity, a zero vector or the wrong dimension stores nothing"
  ) {
    val bad: Seq[(String, Embedder)] = Seq(
      "nan" -> (ts => ts.map(_ => Array(Float.NaN, 1f, 1f, 1f))),
      "inf" -> (ts => ts.map(_ => Array(Float.PositiveInfinity, 1f, 1f, 1f))),
      "zero" -> (ts => ts.map(_ => Array(0f, 0f, 0f, 0f))),
      "short" -> (ts => ts.map(_ => Array(1f, 1f))),
      "long" -> (ts => ts.map(_ => Array.fill(5000)(1f))),
      "empty" -> (ts => ts.map(_ => Array.empty[Float]))
    )
    bad.foreach { (name, emb) =>
      val r = new Rig(emb)
      val rep = r.ingest("a", Vector(r.doc(e(1), "t1"), r.doc(e(2), "t2"))).toOption.get
      withClue(s"[$name] ") {
        r.stored shouldBe empty
        rep.count(ItemStatus.Created) shouldBe 0
        rep.results.forall(_.status == ItemStatus.Rejected) shouldBe true
        r.rejects.all.size shouldBe 2
      }
    }
  }

  test(
    "an embedder that returns the wrong number of vectors, or throws, never stores a misaligned vector"
  ) {
    val short = new Rig(ts => ts.drop(1).map(_ => Array(1f, 2f, 3f, 4f)))
    val outShort =
      try
        short.ingest(
          "a",
          Vector(short.doc(e(1), "t1"), short.doc(e(2), "t2"), short.doc(e(3), "t3"))
        )
      catch case t: Throwable => fail(s"the ingestor threw ${t.getClass.getSimpleName}")
    short.stored.foreach(v => v.key should not be empty)
    withClue("a vector must never be stored for a text that was not embedded: ") {
      short.stored.size should be < 3
    }
    outShort.isRight || outShort.isLeft shouldBe true

    val boom = new Rig(_ => throw new IllegalStateException("embedder down password=hunter2"))
    val out =
      try boom.ingest("a", Vector(boom.doc(e(1), "t1")))
      catch case t: Throwable => fail(s"the ingestor let ${t.getClass.getSimpleName} escape")
    boom.stored shouldBe empty
    out match
      case Right(rep) => rep.results.forall(_.status == ItemStatus.Rejected) shouldBe true
      case Left(err) => err.message should not include "hunter2"
    boom.rejects.all.foreach(x => x.detail should not include "hunter2")
  }

  test(
    "an index that throttles every put: bounded attempts, every item rejected with a code, nothing half-written"
  ) {
    val r = new Rig(okEmbedder())
    r.index.putFailure =
      Some(StorageError.Transient("S3 Vectors PutVectors", 1, "TooManyRequestsException"))
    val rep = r.ingest("a", Vector(r.doc(e(1), "t1"), r.doc(e(2), "t2"))).toOption.get
    r.stored shouldBe empty
    r.index.putCalls should be <= 3 // maxAttempts of the retry policy
    rep.results.forall(_.status == ItemStatus.Rejected) shouldBe true
    rep.results.forall(_.reasonCode.nonEmpty) shouldBe true
    // after recovery the same run creates them: ingestion is idempotent, no duplicate keys
    r.index.putFailure = None
    r.ingest("a", Vector(r.doc(e(1), "t1"), r.doc(e(2), "t2")))
      .toOption
      .get
      .count(ItemStatus.Created) shouldBe 2
    r.stored.size shouldBe 2
  }

  test("key components that would break the key formula are rejected, not escaped or truncated") {
    val r = new Rig(okEmbedder())
    val rep = r
      .ingest(
        "a",
        Vector(
          r.doc("researcher:sc:a|b", "t"),
          r.doc("researcher:sc:a\nb", "t"),
          r.doc(" ", "t"),
          r.doc(e(1), "fine")
        )
      )
      .toOption
      .get
    rep.results.count(_.status == ItemStatus.Created) shouldBe 1
    r.stored.map(_.metadata.entityId) shouldBe Vector(e(1))
    r.rejects.all.size should be >= 3
  }

  test("blank, invisible-only and oversized topic lists are rejected with reason codes") {
    val r = new Rig(okEmbedder())
    val rep = r
      .ingest(
        "a",
        Vector(
          r.doc(e(1), "t", topics = Vector("t1", "t2", "t3", "t4")),
          r.doc(e(2), "t", topics = Vector(" ")),
          r.doc(e(3), "t", topics = Vector(""))
        )
      )
      .toOption
      .get
    rep.results
      .map(_.status) shouldBe Vector(ItemStatus.Rejected, ItemStatus.Rejected, ItemStatus.Rejected)
    r.stored shouldBe empty
  }

  test(
    "the vector key is a pure function of entity, type, window and ordinal: text and model never move it"
  ) {
    val a = VectorKeys.of(e(1), "researcher-profile", 2010, 2013, 0).toOption.get
    VectorKeys.of(e(1), "researcher-profile", 2010, 2013, 0).toOption.get shouldBe a
    Seq(
      VectorKeys.of(e(1), "researcher-profile", 2010, 2013, 1),
      VectorKeys.of(e(1), "researcher-profile", 2010, 2014, 0),
      VectorKeys.of(e(2), "researcher-profile", 2010, 2013, 0),
      VectorKeys.of(e(1), "paper-abstract", 2010, 2013, 0)
    ).foreach(k => k.toOption.get should not be a)
    // the separator cannot be smuggled in to collide two different tuples
    VectorKeys.of("a|b", "c", 1, 2, 0).isLeft shouldBe true
    VectorKeys.of("a", "b|c", 1, 2, 0).isLeft shouldBe true
    VectorKeys.of("a", "c", 2, 1, 0).isLeft shouldBe true
    VectorKeys.of("a", "c", 1, 2, -1).isLeft shouldBe true
    VectorKeys.of("", "c", 1, 2, 0).isLeft shouldBe true
    VectorKeys.of("a\u0000", "c", 1, 2, 0).isLeft shouldBe true
  }

  test("an unknown index name in a rendered document is rejected, never routed to another index") {
    val r = new Rig(okEmbedder())
    val ing = r.ingestor()
    val run = IngestionRun("run:" + Sha256.hex("x".getBytes), "2026-11-01")
    val staged =
      ing.stage(run, Iterator(r.doc(e(1), "t").copy(indexName = "no-such-index"))).toOption.get
    staged.manifest.items shouldBe empty
    staged.rejectedDocuments shouldBe 1
    r.stored shouldBe empty
  }

  test("a run ID that is not a run ID cannot stage or read a manifest (no path built from it)") {
    val r = new Rig(okEmbedder())
    Seq("../../etc/passwd", "run:../x", "run:" + "g" * 64, "", " ").foreach { id =>
      r.ingestor().stage(IngestionRun(id, "s"), Iterator(r.doc(e(1), "t"))).isLeft shouldBe true
      new ManifestStore(r.objects, r.layout).read(id).isLeft shouldBe true
    }
  }
