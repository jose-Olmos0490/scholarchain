package scholarchain.storage.vectors

import java.nio.charset.StandardCharsets
import scholarchain.core.config.DistanceMetric
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.storage.common.*

/** Why a vector was refused, as a stable code and a readable detail. */
final case class VectorProblem(code: String, detail: String)

/** Project-level metadata rules on top of the service limits: the filterable byte budget and the
  * caps on the two list fields. Nothing is ever truncated to fit: an over-budget record is
  * rejected with a reason code.
  */
final case class MetadataPolicy(
    maxTopicIds: Int,
    maxInstitutionIds: Int,
    filterableBudgetBytes: Int
)

object MetadataPolicy:
  /** Service limits only (what an adapter can enforce without ingestion settings). */
  val ServiceOnly: MetadataPolicy =
    MetadataPolicy(Int.MaxValue, Int.MaxValue, Int.MaxValue)

/** Client-side checks against the documented limits. Used by every [[VectorIndex]] implementation
  * before a request is built, and by [[VectorIngestor]] to find the records that must be rejected.
  */
object VectorGuards:
  import VectorServiceLimits.*

  private def utf8(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /** Estimated serialized size of one metadata entry: key plus rendered JSON value. */
  private def entryBytes(e: (String, ujson.Value)): Int = utf8(e._1) + utf8(e._2.render())

  /** Bytes of the filterable metadata, as the validator counts them. */
  def filterableBytes(m: VectorMetadata): Int = m.filterable.map(entryBytes).sum

  def metadataProblems(
      index: IndexDefinition,
      m: VectorMetadata,
      policy: MetadataPolicy = MetadataPolicy.ServiceOnly
  ): Vector[VectorProblem] =
    val limits = forMode(index.indexType)
    val b = Vector.newBuilder[VectorProblem]
    def blank(name: String, v: String): Unit =
      if v == null || v.trim.isEmpty then
        b += VectorProblem("MISSING_METADATA", s"$name is required")
    blank("entityId", m.entityId)
    blank("documentType", m.documentType)
    blank("sourceDataset", m.sourceDataset)
    blank("textS3Uri", m.textS3Uri)
    blank("textHash", m.textHash)
    blank("embeddingModel", m.embeddingModel)
    blank("embeddingModelVersion", m.embeddingModelVersion)
    blank("runId", m.runId)
    blank("schemaVersion", m.schemaVersion)
    if m.schemaVersion != null && m.schemaVersion.trim.nonEmpty &&
      m.schemaVersion != MetadataSchema.Version
    then
      b += VectorProblem(
        "SCHEMA_VERSION_MISMATCH",
        s"schemaVersion '${m.schemaVersion}', expected ${MetadataSchema.Version}"
      )
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
    if m.windowStartYear > m.windowEndYear then
      b += VectorProblem(
        "MALFORMED_METADATA",
        s"window ${m.windowStartYear}-${m.windowEndYear} is inverted"
      )
    if m.topicIds.size > policy.maxTopicIds then
      b += VectorProblem(
        "TOO_MANY_TOPIC_IDS",
        s"${m.topicIds.size} topicIds, cap ${policy.maxTopicIds}"
      )
    if m.institutionIds.size > policy.maxInstitutionIds then
      b += VectorProblem(
        "TOO_MANY_INSTITUTION_IDS",
        s"${m.institutionIds.size} institutionIds, cap ${policy.maxInstitutionIds}"
      )
    if (m.topicIds ++ m.institutionIds).exists(i => i == null || i.trim.isEmpty) then
      b += VectorProblem("MALFORMED_METADATA", "topicIds and institutionIds must not hold blanks")
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
    val budget = math.min(limits.filterableMetadataBytes, policy.filterableBudgetBytes)
    val filterableTotal = filterable.map(entryBytes).sum
    if filterableTotal > budget then
      b += VectorProblem(
        "FILTERABLE_METADATA_TOO_LARGE",
        s"$filterableTotal bytes, limit $budget on ${index.indexType.wire}"
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

  def recordProblems(
      index: IndexDefinition,
      r: VectorRecord,
      policy: MetadataPolicy = MetadataPolicy.ServiceOnly
  ): Vector[VectorProblem] =
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
    b ++= metadataProblems(index, r.metadata, policy)
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
