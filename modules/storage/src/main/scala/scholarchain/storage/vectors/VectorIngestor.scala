package scholarchain.storage.vectors

import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.core.model.{Provenance, SourceType}
import scholarchain.core.schema.SchemaVersions
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.objects.*

/** The injected embedding function: one vector per input text, same order. Owned by `rag-engineer`;
  * storage never chooses or calls a model itself.
  */
type Embedder = Seq[String] => Seq[Array[Float]]

enum ItemStatus(val code: String):
  case Created extends ItemStatus("CREATED")
  case Updated extends ItemStatus("UPDATED")
  case SkippedUnchanged extends ItemStatus("SKIPPED_UNCHANGED")
  case RejectedKeyCollision extends ItemStatus("REJECTED_KEY_COLLISION")
  case Rejected extends ItemStatus("REJECTED")

final case class ItemResult(
    indexName: String,
    entityId: String,
    documentType: String,
    key: Option[String],
    status: ItemStatus,
    reasonCode: Option[String],
    detail: String,
    oldTextHash: Option[String],
    newTextHash: String
)

final case class IndexReport(
    indexName: String,
    manifestItems: Int,
    created: Int,
    updated: Int,
    skippedUnchanged: Int,
    rejected: Int,
    getCalls: Int,
    putBatches: Int,
    putAttempts: Int,
    rateLimitWaitMs: Long
)

final case class IngestionReport(
    runId: String,
    indexes: Vector[IndexReport],
    results: Vector[ItemResult],
    rejectsUri: Option[String],
    ledgerErrors: Vector[String]
):
  def count(s: ItemStatus): Int = results.count(_.status == s)

/** Identity of one ingestion run. `snapshot` is the dataset snapshot label for provenance. */
final case class IngestionRun(runId: String, snapshot: String)

final case class StagedIngestion(
    manifest: IngestionManifest,
    manifestUri: String,
    manifestSha256: String,
    rejectedDocuments: Int,
    rejectsUri: Option[String]
)

/** The S3 Vectors ingestion path.
  *
  * Stage 1, [[stage]]: each already-rendered document is written to ordinary S3 (content-addressed)
  * and listed in an immutable per-run manifest.
  * Stage 2, [[ingest]]: driven only by the manifest, per index: derive the key
  * ([[VectorKeys]]), refuse same-key/different-text within the run, compare with what the index
  * holds (GetVectors), then read the text from S3, embed, validate and PutVectors in batches.
  *
  * Residual race: PutVectors is an unconditional upsert, so a second writer on the same index
  * between the lookup and the put is not detected. Run one ingestor per index.
  *
  * Retry ownership: this class retries [[StorageError.Transient]]. Build the [[VectorIndex]]
  * adapter with a single-attempt retry policy (`retry.copy(maxAttempts = 1)`), or attempts multiply.
  */
final class VectorIngestor(
    index: VectorIndex,
    indexDefinitions: Vector[IndexDefinition],
    documents: DocumentStore,
    manifests: ManifestStore,
    objects: ObjectStore,
    layout: ObjectLayout,
    ledger: RunLedger,
    registry: ArtifactRegistry,
    rejects: RejectSink,
    embed: Embedder,
    settings: IngestionSettings,
    limiter: RateLimiter,
    sleeper: Sleeper,
    jitter: Jitter
):
  require(
    settings.putBatchSize >= 1 && settings.putBatchSize <= VectorServiceLimits.MaxPutBatch,
    "putBatchSize must be 1..500"
  )
  require(
    settings.getBatchSize >= 1 && settings.getBatchSize <= VectorServiceLimits.MaxGetBatch,
    "getBatchSize must be 1..100"
  )

  private val definitions = indexDefinitions.map(d => d.name -> d).toMap

  // -------------------------------------------------------------------------
  // Shared helpers
  // -------------------------------------------------------------------------

  /** Records rejects (sink + a durable content-addressed copy in ordinary S3). */
  final private class Rejector(runId: String):
    private val buf = mutable.ArrayBuffer.empty[StoreReject]
    def record(store: String, key: String, code: String, detail: String): Unit =
      val r = StoreReject(store, key, code, detail, runId)
      buf += r
      rejects.record(r)
    def isEmpty: Boolean = buf.isEmpty
    def flush(label: String): Result[Option[String]] =
      if buf.isEmpty then Right(None)
      else
        for
          hex <- ObjectKeys.runHex(runId)
          bytes = buf
            .map(r => upickle.default.write(r))
            .mkString("", "\n", "\n")
            .getBytes(StandardCharsets.UTF_8)
          ref = ObjectRef(
            ObjectArea.Rag,
            s"ingestion-rejects/$hex/$label-${Sha256.hex(bytes)}.jsonl"
          )
          _ <- objects.putBytes(ref, bytes, "application/x-ndjson")
        yield Some(layout.s3Uri(ref))

  private def provenance(run: IngestionRun): Provenance =
    Provenance(
      SchemaVersions.Provenance,
      SourceType.SCHOLARCHAIN,
      run.runId,
      run.snapshot,
      run.runId,
      None,
      Some(s"${settings.embeddingModel}@${settings.embeddingModelVersion}"),
      None,
      Vector.empty
    )

  // -------------------------------------------------------------------------
  // Stage 1: documents and manifest
  // -------------------------------------------------------------------------

  def stage(run: IngestionRun, input: Iterator[RenderedDocument]): Result[StagedIngestion] =
    ObjectKeys.runHex(run.runId).flatMap { hex =>
      val rej = new Rejector(run.runId)
      val items = mutable.LinkedHashSet.empty[ManifestItem]
      var rejected = 0
      input.foreach { d =>
        val outcome: Either[(String, String), ManifestItem] =
          for
            _ <- Either.cond(
              definitions.contains(d.indexName),
              (),
              ("UNKNOWN_INDEX", s"index '${d.indexName}' is not configured")
            )
            _ <- VectorKeys
              .of(d.entityId, d.documentType, d.windowStartYear, d.windowEndYear, d.ordinal)
              .left
              .map(e => ("INVALID_KEY_COMPONENT", e.message))
            w <- documents.write(d).left.map {
              case e: StorageError.InvalidRecord => ("INVALID_DOCUMENT", e.message)
              case e => ("DOCUMENT_WRITE_FAILED", e.message)
            }
          yield ManifestItem(
            d.indexName,
            d.entityId,
            d.documentType,
            d.windowStartYear,
            d.windowEndYear,
            d.ordinal,
            Sha256.of(d.text)
          )
        outcome match
          case Right(item) => items += item
          case Left((code, detail)) =>
            rejected += 1
            rej.record("s3vectors", s"${d.indexName}/${d.entityId}", code, detail)
      }
      val manifest = IngestionManifest.of(run.runId, items.toVector)
      for
        written <- manifests.write(manifest)
        (uri, sha) = written
        name = s"vector-ingestion-manifest-$hex"
        latest <- registry.latest(name)
        _ <- latest match
          case Some(v) if v.sha256 == sha => Right(())
          case Some(v) => Left(StorageError.ConflictingContent(name, v.sha256, sha))
          case None =>
            registry
              .register(name, uri, sha, IngestionManifest.Version, run.runId, provenance(run))
              .map(_ => ())
        rejectsUri <- rej.flush("stage")
      yield StagedIngestion(manifest, uri, sha, rejected, rejectsUri)
    }

  // -------------------------------------------------------------------------
  // Stage 2: manifest-driven ingestion
  // -------------------------------------------------------------------------

  final private class RunState(
      val run: IngestionRun,
      val rej: Rejector,
      val attempts: mutable.Map[String, Int]
  ):
    val results = mutable.ArrayBuffer.empty[ItemResult]
    val ledgerErrors = mutable.ArrayBuffer.empty[String]

  final private case class Existing(textHash: String, model: String, modelVersion: String)
  final private case class Planned(
      item: ManifestItem,
      key: String,
      status: ItemStatus,
      old: Option[Existing]
  )

  def ingest(run: IngestionRun, manifest: IngestionManifest): Result[IngestionReport] =
    for
      _ <- ObjectKeys.runHex(run.runId)
      _ <-
        if manifest.schemaVersion == IngestionManifest.Version then Right(())
        else Left(StorageError.InvalidManifest(s"schemaVersion ${manifest.schemaVersion}"))
      _ <-
        if manifest.runId == run.runId then Right(())
        else
          Left(
            StorageError.InvalidManifest(s"manifest is for ${manifest.runId}, not ${run.runId}")
          )
      existing <- ledger.list(run.runId)
    yield
      val attempts = mutable.Map.empty[String, Int]
      existing.foreach(e => attempts(e.stage) = math.max(attempts.getOrElse(e.stage, 0), e.attempt))
      val st = new RunState(run, new Rejector(run.runId), attempts)
      val byIndex = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[ManifestItem]]
      manifest.items.foreach(i =>
        byIndex.getOrElseUpdate(i.indexName, mutable.ArrayBuffer.empty) += i
      )
      val reports =
        byIndex.iterator.map((name, its) => ingestIndex(st, name, its.toVector)).toVector
      val rejectsUri = st.rej
        .flush("ingest")
        .fold(
          e =>
            st.ledgerErrors += s"rejected-vectors output not written: ${e.message}"
            None
          ,
          identity
        )
      IngestionReport(run.runId, reports, st.results.toVector, rejectsUri, st.ledgerErrors.toVector)

  private def result(
      st: RunState,
      item: ManifestItem,
      key: Option[String],
      status: ItemStatus,
      code: Option[String],
      detail: String,
      old: Option[String] = None
  ): Unit =
    st.results += ItemResult(
      item.indexName,
      item.entityId,
      item.documentType,
      key,
      status,
      code,
      detail,
      old,
      item.textHash
    )
    if status == ItemStatus.Rejected || status == ItemStatus.RejectedKeyCollision then
      st.rej.record(
        "s3vectors",
        s"${item.indexName}/${key.getOrElse(item.entityId)}",
        code.getOrElse(status.code),
        detail
      )

  private def reject(
      st: RunState,
      item: ManifestItem,
      key: Option[String],
      code: String,
      detail: String
  ) =
    result(st, item, key, ItemStatus.Rejected, Some(code), detail)

  private def ingestIndex(st: RunState, name: String, items: Vector[ManifestItem]): IndexReport =
    val before = st.results.size
    var getCalls = 0
    var putBatches = 0
    var putAttempts = 0
    var waitMs = 0L

    def finish(): IndexReport =
      val mine = st.results.drop(before)
      def n(s: ItemStatus) = mine.count(_.status == s)
      IndexReport(
        name,
        items.size,
        n(ItemStatus.Created),
        n(ItemStatus.Updated),
        n(ItemStatus.SkippedUnchanged),
        n(ItemStatus.Rejected) + n(ItemStatus.RejectedKeyCollision),
        getCalls,
        putBatches,
        putAttempts,
        waitMs
      )

    definitions.get(name) match
      case None =>
        items.foreach(reject(st, _, None, "UNKNOWN_INDEX", s"index '$name' is not configured"))
        finish()
      case Some(defn) if !limiter.hasRate(name) =>
        items.foreach(reject(st, _, None, "NO_RATE_LIMIT", s"no rate limit configured for $name"))
        finish()
      case Some(defn) =>
        index.describe(name) match
          case Right(Some(actual)) if defn.matches(actual) => ()
          case other =>
            val why = other match
              case Right(Some(actual)) => s"deployed $actual differs from configured $defn"
              case Right(None) => s"index $name does not exist"
              case Left(e) => e.message
            items.foreach(reject(st, _, None, "INDEX_UNAVAILABLE", why))
            return finish()

        // 1. keys; invalid components are rejected
        val keyed = items.flatMap { i =>
          VectorKeys.of(
            i.entityId,
            i.documentType,
            i.windowStartYear,
            i.windowEndYear,
            i.ordinal
          ) match
            case Right(k) => Some(i -> k)
            case Left(e) =>
              reject(st, i, None, "INVALID_KEY_COMPONENT", e.message)
              None
        }

        // 2. same key, different text in one run: write neither
        val byKey = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[ManifestItem]]
        keyed.foreach((i, k) => byKey.getOrElseUpdate(k, mutable.ArrayBuffer.empty) += i)
        val candidates = Vector.newBuilder[(ManifestItem, String)]
        byKey.foreach { (k, group) =>
          val hashes = group.map(_.textHash).distinct
          if hashes.size > 1 then
            group.foreach(i =>
              result(
                st,
                i,
                Some(k),
                ItemStatus.RejectedKeyCollision,
                Some("REJECTED_KEY_COLLISION"),
                s"key $k is claimed by ${hashes.size} different texts in this run: ${hashes.mkString(", ")}"
              )
            )
          else
            candidates += (group.head -> k)
            group.tail.foreach(i =>
              result(
                st,
                i,
                Some(k),
                ItemStatus.SkippedUnchanged,
                None,
                "duplicate manifest line for the same key and text"
              )
            )
        }

        // 3. compare with what the index holds
        val toWrite = Vector.newBuilder[Planned]
        candidates.result().grouped(settings.getBatchSize).foreach { chunk =>
          getCalls += 1
          val keys = chunk.map(_._2)
          val (res, _) = Backoff.run(settings.retry, sleeper, jitter)(
            index.get(name, keys, returnData = false)
          )
          res match
            case Left(e) =>
              chunk.foreach((i, k) => reject(st, i, Some(k), "LOOKUP_FAILED", e.message))
            case Right(found) =>
              val have = found.map(s => s.key -> s.metadata).toMap
              chunk.foreach { (i, k) =>
                have.get(k) match
                  case None => toWrite += Planned(i, k, ItemStatus.Created, None)
                  case Some(m)
                      if m.textHash == i.textHash && m.embeddingModel == settings.embeddingModel &&
                        m.embeddingModelVersion == settings.embeddingModelVersion =>
                    result(
                      st,
                      i,
                      Some(k),
                      ItemStatus.SkippedUnchanged,
                      None,
                      "same text hash and embedding model version"
                    )
                  case Some(m) =>
                    toWrite += Planned(
                      i,
                      k,
                      ItemStatus.Updated,
                      Some(Existing(m.textHash, m.embeddingModel, m.embeddingModelVersion))
                    )
              }
        }

        // 4. read, embed, validate, put
        toWrite.result().grouped(settings.putBatchSize).zipWithIndex.foreach { (batch, n) =>
          val stats = writeBatch(st, defn, batch, n + 1)
          putBatches += stats._1
          putAttempts += stats._2
          waitMs += stats._3
        }
        finish()

  /** Returns (put batches sent, put attempts, rate-limit wait ms). */
  private def writeBatch(
      st: RunState,
      defn: IndexDefinition,
      batch: Vector[Planned],
      batchNo: Int
  ): (Int, Int, Long) =
    // a. text from ordinary S3, verified against the manifest hash
    val loaded = batch.flatMap { p =>
      val i = p.item
      val read = documents
        .ref(i.indexName, i.documentType, i.entityId, i.textHash)
        .flatMap(r => documents.read(r, i.textHash).map(d => (r, d)))
      read match
        case Left(e) =>
          val code = e match
            case _: StorageError.NotFound => "DOCUMENT_MISSING"
            case _: StorageError.ConflictingContent => "DOCUMENT_HASH_MISMATCH"
            case _ => "DOCUMENT_UNREADABLE"
          reject(st, i, Some(p.key), code, e.message)
          None
        case Right((r, d))
            if d.entityId != i.entityId || d.documentType != i.documentType ||
              d.windowStartYear != i.windowStartYear || d.windowEndYear != i.windowEndYear ||
              d.ordinal != i.ordinal || d.indexName != i.indexName =>
          reject(
            st,
            i,
            Some(p.key),
            "DOCUMENT_MISMATCH",
            "document identity differs from the manifest line"
          )
          None
        case Right((r, d)) => Some((p, r, d))
    }
    if loaded.isEmpty then return (0, 0, 0L)

    // b. embed (the injected function; its failures are rejects, not crashes)
    val embedded: Option[Vector[Vector[Float]]] =
      try
        val out = embed(loaded.map(_._3.text)).toVector
        if out.size == loaded.size then Some(out.map(_.toVector))
        else
          loaded.foreach((p, _, _) =>
            reject(
              st,
              p.item,
              Some(p.key),
              "EMBEDDING_FAILED",
              s"embedder returned ${out.size} vectors for ${loaded.size} texts"
            )
          )
          None
      catch
        case NonFatal(e) =>
          loaded.foreach((p, _, _) =>
            reject(
              st,
              p.item,
              Some(p.key),
              "EMBEDDING_FAILED",
              s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
            )
          )
          None
    if embedded.isEmpty then return (0, 0, 0L)

    // c. validate every record; nothing is truncated
    val policy = settings.metadataPolicy
    val ready = loaded.zip(embedded.get).flatMap { case ((p, r, d), emb) =>
      val meta = VectorMetadata(
        entityId = d.entityId,
        documentType = d.documentType,
        windowStartYear = d.windowStartYear,
        windowEndYear = d.windowEndYear,
        topicIds = d.topicIds,
        institutionIds = d.institutionIds,
        sourceDataset = d.sourceDataset,
        communityId = d.communityId,
        textS3Uri = documents.uri(r),
        textHash = d.textHash,
        embeddingModel = settings.embeddingModel,
        embeddingModelVersion = settings.embeddingModelVersion,
        runId = st.run.runId,
        schemaVersion = MetadataSchema.Version
      )
      val rec = VectorRecord(p.key, emb, meta)
      val problems = VectorGuards.recordProblems(defn, rec, policy)
      if problems.nonEmpty then
        problems.foreach(pr => reject(st, p.item, Some(p.key), pr.code, pr.detail))
        None
      else Some((p, rec))
    }
    if ready.isEmpty then return (0, 0, 0L)

    // d. rate-limited put with bounded retries
    var waited = 0L
    val (res, attempts) = Backoff.run(settings.retry, sleeper, jitter) {
      waited += limiter.acquire(defn.name)
      index.put(defn.name, ready.map(_._2))
    }
    val stage = ledgerStage(defn.name, batchNo)
    res match
      case Right(_) =>
        ready.foreach((p, _) =>
          result(
            st,
            p.item,
            Some(p.key),
            p.status,
            None,
            if p.status == ItemStatus.Updated then "text or model changed; overwritten"
            else "new vector",
            p.old.map(_.textHash)
          )
        )
        val updates = ready.collect { case (p, _) if p.old.isDefined => p }
        recordLedger(
          st,
          stage,
          LedgerState.SUCCEEDED,
          ujson.Obj(
            "index" -> defn.name,
            "batch" -> batchNo,
            "putAttempts" -> attempts,
            "created" -> (ready.size - updates.size),
            "updated" -> updates.size,
            "updates" -> ujson.Arr.from(updates.map { p =>
              ujson.Obj(
                "key" -> p.key,
                "entityId" -> p.item.entityId,
                "oldTextHash" -> p.old.get.textHash,
                "newTextHash" -> p.item.textHash,
                "oldEmbeddingModel" -> p.old.get.model,
                "newEmbeddingModel" -> settings.embeddingModel,
                "oldEmbeddingModelVersion" -> p.old.get.modelVersion,
                "newEmbeddingModelVersion" -> settings.embeddingModelVersion
              )
            })
          )
        )
      case Left(e) =>
        val code = e match
          case _: StorageError.Transient => "PUT_FAILED_AFTER_RETRIES"
          case _ => "PUT_REJECTED"
        ready.foreach((p, _) => reject(st, p.item, Some(p.key), code, e.message))
        recordLedger(
          st,
          stage,
          LedgerState.FAILED,
          ujson.Obj(
            "index" -> defn.name,
            "batch" -> batchNo,
            "putAttempts" -> attempts,
            "reasonCode" -> code,
            "error" -> e.message,
            "items" -> ready.size,
            "keys" -> ujson.Arr.from(ready.map(r => ujson.Str(r._1.key)))
          )
        )
    (1, attempts, waited)

  /** `vector-ingest-<index>-b<NNNNN>`, within the ledger's `[a-z][a-z0-9_-]{0,63}` stage pattern. */
  private def ledgerStage(indexName: String, batchNo: Int): String =
    val suffix = f"-b$batchNo%05d"
    val clean = indexName.toLowerCase.replaceAll("[^a-z0-9_-]", "-")
    val room = 64 - "vector-ingest-".length - suffix.length
    s"vector-ingest-${clean.take(room)}$suffix"

  private def recordLedger(
      st: RunState,
      stage: String,
      state: LedgerState,
      detail: ujson.Obj
  ): Unit =
    val attempt = st.attempts.getOrElse(stage, 0) + 1
    st.attempts(stage) = attempt
    val entry = RunLedgerEntry(
      DynamoSchemas.RunLedgerEntry,
      st.run.runId,
      settings.ledgerSequence,
      stage,
      attempt,
      state,
      0L,
      detail.render(),
      provenance(st.run)
    )
    ledger.record(entry) match
      case Right(_) => ()
      case Left(e) => st.ledgerErrors += s"$stage attempt $attempt ($state): ${e.message}"
