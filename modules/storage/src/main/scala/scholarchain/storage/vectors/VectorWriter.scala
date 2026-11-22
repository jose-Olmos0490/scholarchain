package scholarchain.storage.vectors

import java.nio.charset.StandardCharsets
import scholarchain.core.config.DistanceMetric
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.storage.common.*

/** Why a vector was refused, as a stable code and a readable detail. */
final case class VectorProblem(code: String, detail: String)

/** Client-side checks against the documented limits. Used by every [[VectorIndex]] implementation
  * before a request is built, and by [[VectorWriter]] to find the records that must be rejected.
  */
object VectorGuards:
  import VectorServiceLimits.*

  private def utf8(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /** Estimated serialized size of one metadata entry: key plus rendered JSON value. */
  private def entryBytes(e: (String, ujson.Value)): Int = utf8(e._1) + utf8(e._2.render())

  def metadataProblems(index: IndexDefinition, m: VectorMetadata): Vector[VectorProblem] =
    val limits = forMode(index.indexType)
    val b = Vector.newBuilder[VectorProblem]
    def blank(name: String, v: String): Unit =
      if v == null || v.trim.isEmpty then
        b += VectorProblem("MISSING_METADATA", s"$name is required")
    blank("documentType", m.documentType)
    blank("sourceDataset", m.sourceDataset)
    blank("textS3Uri", m.textS3Uri)
    blank("textHash", m.textHash)
    blank("embeddingModel", m.embeddingModel)
    blank("runId", m.runId)
    if m.textHash != null && m.textHash.trim.nonEmpty && !Sha256.isWellFormed(m.textHash) then
      b += VectorProblem("MALFORMED_METADATA", s"textHash '${m.textHash}' is not sha256:<64 hex>")
    if m.textS3Uri != null && m.textS3Uri.trim.nonEmpty && !m.textS3Uri.matches("s3://[^/]+/.+")
    then
      b += VectorProblem(
        "MALFORMED_METADATA",
        s"textS3Uri '${m.textS3Uri}' is not s3://<bucket>/<key>"
      )
    if m.runId != null && m.runId.trim.nonEmpty && !StableId.isValid(m.runId, IdKind.Run) then
      b += VectorProblem("MALFORMED_METADATA", s"runId '${m.runId}' is not a run ID")
    if m.windowStartYear.isDefined != m.windowEndYear.isDefined then
      b += VectorProblem(
        "MALFORMED_METADATA",
        "windowStartYear and windowEndYear must be set together"
      )
    for s <- m.windowStartYear; e <- m.windowEndYear if s > e do
      b += VectorProblem("MALFORMED_METADATA", s"window $s-$e is inverted")
    if m.researcherId.isEmpty && m.communityId.isEmpty && m.topicId.isEmpty && m.institutionId.isEmpty
    then
      b += VectorProblem(
        "MISSING_METADATA",
        "one of researcherId, communityId, topicId, institutionId is required"
      )
    if index.nonFilterableKeys.size > MaxNonFilterableKeysPerIndex then
      b += VectorProblem(
        "TOO_MANY_NON_FILTERABLE_KEYS",
        s"index ${index.name} has ${index.nonFilterableKeys.size} non-filterable keys, limit $MaxNonFilterableKeysPerIndex"
      )

    val filterable = m.filterable
    val nonFilterable = m.nonFilterable
    if filterable.size + nonFilterable.size > MaxTotalMetadataKeys then
      b += VectorProblem(
        "TOO_MANY_METADATA_KEYS",
        s"${filterable.size + nonFilterable.size} keys, limit $MaxTotalMetadataKeys"
      )
    filterable.foreach { e =>
      e._2 match
        case ujson.Str(s) if utf8(s) > MaxFilterableStringBytes =>
          b += VectorProblem(
            "FILTERABLE_VALUE_TOO_LARGE",
            s"${e._1} is ${utf8(s)} bytes, limit $MaxFilterableStringBytes"
          )
        case _ => ()
    }
    val filterableBytes = filterable.map(entryBytes).sum
    if filterableBytes > limits.filterableMetadataBytes then
      b += VectorProblem(
        "FILTERABLE_METADATA_TOO_LARGE",
        s"$filterableBytes bytes, limit ${limits.filterableMetadataBytes} on ${index.indexType.wire}"
      )
    nonFilterable.foreach { e =>
      if entryBytes(e) > limits.nonFilterableFieldBytes then
        b += VectorProblem(
          "NON_FILTERABLE_FIELD_TOO_LARGE",
          s"${e._1} is ${entryBytes(e)} bytes, limit ${limits.nonFilterableFieldBytes}"
        )
    }
    val total = (filterable ++ nonFilterable).map(entryBytes).sum
    if total > limits.totalMetadataBytes then
      b += VectorProblem(
        "METADATA_TOO_LARGE",
        s"$total bytes, limit ${limits.totalMetadataBytes} on ${index.indexType.wire}"
      )
    b.result()

  def recordProblems(index: IndexDefinition, r: VectorRecord): Vector[VectorProblem] =
    val b = Vector.newBuilder[VectorProblem]
    if r.key == null || r.key.isEmpty || r.key.length > MaxKeyLength then
      b += VectorProblem("INVALID_KEY", s"key length must be 1..$MaxKeyLength")
    if r.embedding.size != index.dimension then
      b += VectorProblem(
        "DIMENSION_MISMATCH",
        s"embedding has ${r.embedding.size} values, index ${index.name} has ${index.dimension}"
      )
    else if r.embedding.exists(f => f.isNaN || f.isInfinite) then
      b += VectorProblem("NON_FINITE_VALUE", "embedding contains NaN or Infinity")
    else if index.distanceMetric == DistanceMetric.Cosine && r.embedding.forall(_ == 0.0f) then
      b += VectorProblem("ZERO_VECTOR", "a zero vector is not allowed with the cosine metric")
    b ++= metadataProblems(index, r.metadata)
    b.result()

  /** Errors that make a whole request invalid (not a single record). */
  def checkPut(index: IndexDefinition, vectors: Vector[VectorRecord]): Result[Unit] =
    if vectors.isEmpty then Left(StorageError.InvalidRecord("PutVectors needs at least one vector"))
    else if vectors.size > MaxPutBatch then
      Left(StorageError.LimitExceeded("vectors per PutVectors call", MaxPutBatch, vectors.size))
    else
      val bad =
        vectors.flatMap(v => recordProblems(index, v).map(p => s"${v.key}: ${p.code} ${p.detail}"))
      if bad.nonEmpty then Left(StorageError.ValidationFailed(bad)) else Right(())

  def checkGet(keys: Vector[String]): Result[Unit] =
    if keys.isEmpty then Left(StorageError.InvalidRecord("GetVectors needs at least one key"))
    else if keys.size > MaxGetBatch then
      Left(StorageError.LimitExceeded("keys per GetVectors call", MaxGetBatch, keys.size))
    else Right(())

  def checkQuery(index: IndexDefinition, q: VectorQuery): Result[Unit] =
    if q.topK < 1 then Left(StorageError.InvalidRecord(s"topK must be >= 1, got ${q.topK}"))
    else if q.topK > MaxTopK then Left(StorageError.LimitExceeded("topK", MaxTopK, q.topK))
    else if q.embedding.size != index.dimension then
      Left(
        StorageError.InvalidRecord(
          s"query has ${q.embedding.size} values, index ${index.name} has ${index.dimension}"
        )
      )
    else
      val unknown = q.filter.map(_.keys.diff(VectorMetadata.FilterableKeys)).getOrElse(Set.empty)
      if unknown.nonEmpty then
        Left(
          StorageError.InvalidRecord(
            s"filter on non-filterable key(s): ${unknown.toVector.sorted.mkString(", ")}"
          )
        )
      else Right(())

  /** Rough payload of one record: float32 data plus metadata JSON plus the key. */
  def payloadBytes(r: VectorRecord): Long =
    r.embedding.size.toLong * 4 + utf8(r.metadata.toJson.render()) + utf8(r.key) + 64

final case class VectorWriteReport(
    indexName: String,
    requested: Int,
    written: Int,
    unchanged: Int,
    rejected: Int,
    putBatches: Int,
    getBatches: Int,
    retries: RetrySnapshot
)

/** Writes embeddings without ever letting a key silently refer to different text.
  *
  * For each record: invalid records go to the reject sink (code and detail). For records whose
  * key already exists in the index: same `textHash` and same `embeddingModel` is a no-op;
  * a different `textHash` is rejected (`TEXT_HASH_CONFLICT`); the same text embedded by a
  * different model is rejected (`MODEL_VERSION_MISMATCH`). The rest are written in batches of at
  * most `putBatchSize` (and at most the 20 MiB request limit).
  *
  * Residual race: PutVectors is an unconditional upsert, so a writer that creates the same key
  * between the check and the put is not detected. Run one embedding writer per index.
  */
final class VectorWriter(
    index: VectorIndex,
    expected: IndexDefinition,
    putBatchSize: Int,
    getBatchSize: Int,
    rejects: RejectSink,
    runId: String
):
  require(putBatchSize >= 1 && putBatchSize <= VectorServiceLimits.MaxPutBatch)
  require(getBatchSize >= 1 && getBatchSize <= VectorServiceLimits.MaxGetBatch)

  private def reject(key: String, code: String, detail: String): Unit =
    rejects.record(StoreReject("s3vectors", s"${expected.name}/$key", code, detail, runId))

  def write(records: Iterator[VectorRecord]): Result[VectorWriteReport] =
    index.describe(expected.name).flatMap {
      case None => Left(StorageError.NotFound(s"vector index ${expected.name} does not exist"))
      case Some(actual) if actual != expected =>
        Left(
          StorageError.Misconfigured(
            s"vector index ${expected.name} differs from configuration: deployed $actual, configured $expected"
          )
        )
      case Some(_) => writeValidated(records)
    }

  private def writeValidated(records: Iterator[VectorRecord]): Result[VectorWriteReport] =
    var requested = 0
    var rejected = 0
    var unchanged = 0
    var written = 0
    var putBatches = 0
    var getBatches = 0
    val seenInRun = scala.collection.mutable.Map.empty[String, String] // key -> textHash
    var failure: Option[StorageError] = None

    def rej(key: String, code: String, detail: String): Unit =
      rejected += 1
      reject(key, code, detail)

    val checked = records.flatMap { r =>
      requested += 1
      val problems = VectorGuards.recordProblems(expected, r)
      if problems.nonEmpty then
        problems.foreach(p => rej(r.key, p.code, p.detail))
        None
      else
        seenInRun.get(r.key) match
          case Some(h) if h == r.metadata.textHash =>
            unchanged += 1
            None
          case Some(h) =>
            rej(
              r.key,
              "TEXT_HASH_CONFLICT",
              s"key repeated in the input with different text: $h vs ${r.metadata.textHash}"
            )
            None
          case None =>
            seenInRun(r.key) = r.metadata.textHash
            Some(r)
    }

    // Records that passed the checks wait here until a put batch is full (or the input ends), so
    // puts are as large as the configured batch size allows, whatever the existence-check size is.
    var pending = Vector.empty[VectorRecord]
    var pendingBytes = 0L

    def flush(): Unit =
      if pending.nonEmpty && failure.isEmpty then
        val batch = pending
        pending = Vector.empty
        pendingBytes = 0L
        index.put(expected.name, batch) match
          case Right(_) =>
            written += batch.size
            putBatches += 1
          case Left(e) =>
            batch.foreach(r => reject(r.key, "PUT_FAILED", e.message))
            failure = Some(e)

    def enqueue(r: VectorRecord): Unit =
      val size = VectorGuards.payloadBytes(r)
      if pending.nonEmpty &&
        (pending.size >= putBatchSize || pendingBytes + size > VectorServiceLimits.MaxRequestPayloadBytes)
      then flush()
      if failure.isEmpty then
        pending :+= r
        pendingBytes += size

    val groups = checked.grouped(getBatchSize)
    while failure.isEmpty && groups.hasNext do
      val group = groups.next().toVector
      getBatches += 1
      index.get(expected.name, group.map(_.key), returnData = false) match
        case Left(e) => failure = Some(e)
        case Right(existing) =>
          val byKey = existing.map(s => s.key -> s.metadata).toMap
          group.foreach { r =>
            byKey.get(r.key) match
              case None => enqueue(r)
              case Some(old) if old.textHash != r.metadata.textHash =>
                rej(
                  r.key,
                  "TEXT_HASH_CONFLICT",
                  s"index holds text ${old.textHash}, record has ${r.metadata.textHash}"
                )
              case Some(old) if old.embeddingModel != r.metadata.embeddingModel =>
                rej(
                  r.key,
                  "MODEL_VERSION_MISMATCH",
                  s"index holds model ${old.embeddingModel}, record has ${r.metadata.embeddingModel}"
                )
              case Some(_) => unchanged += 1
          }
    flush()

    failure match
      case Some(e) => Left(e)
      case None =>
        Right(
          VectorWriteReport(
            expected.name,
            requested,
            written,
            unchanged,
            rejected,
            putBatches,
            getBatches,
            index.retryStats
          )
        )
