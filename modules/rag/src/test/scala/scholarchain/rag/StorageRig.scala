package scholarchain.rag

import scala.collection.mutable
import scholarchain.core.model.*
import scholarchain.rag.common.*
import scholarchain.rag.embed.*
import scholarchain.rag.ingest.*
import scholarchain.rag.render.PaperInput
import scholarchain.storage.StorageSettings
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.{DynamoPorts, InMemoryItemStore}
import scholarchain.storage.objects.*
import scholarchain.storage.vectors.{
  DocumentStore,
  InMemoryVectorIndex,
  IndexDefinition,
  IngestionRun,
  IngestionSettings,
  ManifestStore,
  MonotonicClock,
  RateLimiter,
  VectorFilter,
  VectorIngestor,
  VectorMetadata,
  VectorQuery
}
import RagFixtures.*

/** Configuration for the storage-backed tests: the five indexes at the fake embedder's dimension,
  * plus the ingestion, chunking and model settings. Everything comes from this map; nothing is
  * read from the process environment.
  */
object SmokeEnv:
  val vars: Map[String, String] = Map(
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
    "VECTOR_INDEX_RESEARCHERS" -> indexNames.researchers,
    "VECTOR_INDEX_COMMUNITIES" -> indexNames.communities,
    "VECTOR_INDEX_ABSTRACTS" -> indexNames.abstracts,
    "VECTOR_INDEX_PATHS" -> indexNames.paths,
    "VECTOR_INDEX_TOPIC_INSTITUTIONS" -> indexNames.topicInstitutions,
    "SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT" -> "25",
    "SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH" -> "4",
    "EMBEDDING_MODEL_ID" -> modelSpec.modelId,
    "EMBEDDING_MODEL_VERSION" -> modelSpec.version,
    "SCHOLARCHAIN_EMBEDDING_NORMALIZE" -> "true",
    "SCHOLARCHAIN_VECTOR_INDEX_RESEARCHERS_DIMENSION" -> "256",
    "SCHOLARCHAIN_VECTOR_INDEX_COMMUNITIES_DIMENSION" -> "256",
    "SCHOLARCHAIN_VECTOR_INDEX_ABSTRACTS_DIMENSION" -> "256",
    "SCHOLARCHAIN_VECTOR_INDEX_PATHS_DIMENSION" -> "256",
    "SCHOLARCHAIN_VECTOR_INDEX_TOPIC_INSTITUTIONS_DIMENSION" -> "256",
    "SCHOLARCHAIN_STORAGE_PUT_VECTORS_BATCH_SIZE" -> "5",
    "SCHOLARCHAIN_STORAGE_GET_VECTORS_BATCH_SIZE" -> "10",
    "SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_PER_SECOND" -> "1000",
    "SCHOLARCHAIN_VECTOR_INGEST_MAX_TOPIC_IDS" -> "3",
    "SCHOLARCHAIN_VECTOR_INGEST_MAX_INSTITUTION_IDS" -> "3",
    "SCHOLARCHAIN_VECTOR_INGEST_LEDGER_SEQUENCE" -> "1",
    "SCHOLARCHAIN_RAG_MAX_TOKENS" -> "200",
    "SCHOLARCHAIN_RAG_CHUNK_TOKENS" -> "100",
    "SCHOLARCHAIN_RAG_CHUNK_OVERLAP_TOKENS" -> "20",
    "SCHOLARCHAIN_RAG_MAX_UNTRUSTED_CODE_POINTS" -> "200",
    "SCHOLARCHAIN_RAG_MAX_ABSTRACT_CODE_POINTS" -> "2000"
  )

  lazy val settings: StorageSettings =
    StorageSettings.load(vars).fold(e => throw new IllegalStateException(e.message), identity)

/** Simulated time: sleeping advances the clock, so rate limiting never waits. */
final class SimClock extends MonotonicClock, Sleeper:
  private var now = 0L
  def nanos: Long = now
  def sleep(millis: Long): Unit = now += millis * 1_000_000L

/** The storage module's fakes (vector index, object store, item store) wired to the real
  * [[VectorIngestor]], driven through the rag [[IngestionRunner]].
  */
final class StorageRig:
  private val s = SmokeEnv.settings
  val defs: Vector[IndexDefinition] = IndexDefinition.fromSettings(s)
  val index = new InMemoryVectorIndex(defs)
  val objects = new InMemoryObjectStore
  val items = new InMemoryItemStore
  val rejects = new InMemoryRejectSink
  val bindings = new InMemoryIndexBindings
  val embedCalls = mutable.ArrayBuffer.empty[Int]
  private val ports =
    DynamoPorts(items, s.runtime.dynamoTables, s.runtime.querySession.ttlSeconds)
  val layout: ObjectLayout = ObjectLayout
    .fromConfig(s.buckets, s.runtime.s3Prefixes, ObjectArea.Rag)
    .fold(e => throw new IllegalStateException(e.message), identity)
  val documents = new DocumentStore(objects, layout)
  private val ing =
    IngestionSettings
      .load(s, SmokeEnv.vars)
      .fold(e => throw new IllegalStateException(e.message), identity)
  val pipeline = new RenderPipeline(indexNames, renderSettings)
  val runner = new IngestionRunner(pipeline, defs, bindings, 3)
  val embedder: Embedder = new FakeEmbedder(modelSpec)
  val ingestRun: IngestionRun = IngestionRun(runId, snapshot)

  var ingestorsBuilt = 0

  def ingestorFor(fn: Seq[String] => Seq[Array[Float]]): VectorIngestor =
    ingestorsBuilt += 1
    val time = new SimClock
    new VectorIngestor(
      index,
      defs,
      documents,
      new ManifestStore(objects, layout),
      objects,
      layout,
      ports.runLedger,
      ports.artifacts,
      rejects,
      texts =>
        embedCalls += texts.size
        fn(texts)
      ,
      ing,
      new RateLimiter(ing.rateLimitPerSecond, time, time),
      time,
      Jitter.random(Some(7L))
    )

  def ingest(
      o: IngestionOptions,
      inputs: IngestionInputs,
      emb: Embedder = embedder,
      forRun: IngestionRun = ingestRun
  ): RResult[IngestionOutcome] =
    runner.run(o, inputs, Right(forRun), Right(emb), ingestorFor)

  final case class Hit(key: String, distance: Double, meta: VectorMetadata)

  def query(
      indexName: String,
      text: String,
      topK: Int,
      filter: Option[VectorFilter] = None
  ): Vector[Hit] =
    val q = embedder.embed(Seq(text)).head.toVector
    index
      .query(indexName, VectorQuery(q, topK, filter))
      .fold(e => throw new AssertionError(e.message), identity)
      .map(m => Hit(m.key, m.distance, m.metadata.get))

  /** Fetches the stored object a vector points to and checks it hashes to the vector's textHash. */
  def fetchText(meta: VectorMetadata): String =
    val ref = documents
      .ref(
        // the index is not in the metadata: it is encoded in the object key, so look it up
        indexOf(meta),
        meta.documentType,
        meta.entityId,
        meta.textHash
      )
      .fold(e => throw new AssertionError(e.message), identity)
    if documents.uri(ref) != meta.textS3Uri then
      throw new AssertionError(
        s"textS3Uri ${meta.textS3Uri} is not the object key ${documents.uri(ref)}"
      )
    documents
      .read(ref, meta.textHash)
      .fold(e => throw new AssertionError(e.message), _.text)

  private def indexOf(meta: VectorMetadata): String =
    defs
      .map(_.name)
      .find(n => index.stored(n).exists(_.metadata == meta))
      .getOrElse(throw new AssertionError("vector not found in any index"))
