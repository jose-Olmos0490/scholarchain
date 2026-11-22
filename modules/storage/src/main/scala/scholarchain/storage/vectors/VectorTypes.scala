package scholarchain.storage.vectors

import scholarchain.core.config.{DistanceMetric, VectorIndexSettings, VectorIndexType}
import scholarchain.storage.StorageSettings
import scholarchain.storage.common.*

/** Documented S3 Vectors maximums.
  *
  * Source: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html
  * (retrieved 2026-11-21 through the aws-docs MCP), plus the PutVectors, PutInputVector and
  * QueryVectors API pages. "KB" is taken as 1000 bytes, the stricter reading, so a value on the
  * edge is rejected client-side instead of by the service.
  */
object VectorServiceLimits:
  val MaxDimension = 4096
  val MaxTotalMetadataKeys = 50
  val MaxNonFilterableKeysPerIndex = 10
  val MaxFilterableStringBytes = 2 * 1000
  val MaxPutBatch = 500
  val MaxGetBatch = 100
  val MaxTopK = 10000
  val MaxRequestPayloadBytes = 20L * 1024 * 1024
  val MaxKeyLength = 1024

  /** Byte limits that depend on the index mode (CLASSIC or ENHANCED). */
  final case class ByMode(
      totalMetadataBytes: Int,
      filterableMetadataBytes: Int,
      nonFilterableFieldBytes: Int
  )

  def forMode(t: VectorIndexType): ByMode = t match
    case VectorIndexType.Classic => ByMode(40 * 1000, 2 * 1000, 40 * 1000)
    case VectorIndexType.Enhanced => ByMode(100 * 1000, 40 * 1000, 100 * 1000)

/** A vector index as the service holds it. */
final case class IndexDefinition(
    name: String,
    dimension: Int,
    distanceMetric: DistanceMetric,
    indexType: VectorIndexType,
    nonFilterableKeys: Vector[String]
)

object IndexDefinition:
  /** Index keys that are never filterable: pointers and versions, not search dimensions. */
  val NonFilterableMetadataKeys: Vector[String] =
    Vector("textS3Uri", "textHash", "embeddingModel", "runId")

  /** The five configured indexes with their configured settings. */
  def fromSettings(s: StorageSettings): Vector[IndexDefinition] =
    val n = s.vectorIndexNames
    val c = s.runtime.vectorIndexSettings
    def d(name: String, v: VectorIndexSettings) =
      IndexDefinition(name, v.dimension, v.distanceMetric, v.indexType, NonFilterableMetadataKeys)
    Vector(
      d(n.researchers, c.researchers),
      d(n.communities, c.communities),
      d(n.abstracts, c.abstracts),
      d(n.paths, c.paths),
      d(n.topicInstitutions, c.topicInstitutions)
    )

/** Compact metadata of one vector. There is no free-text field: long text lives only in ordinary
  * S3 and is reached through `textS3Uri`; `textHash` pins the exact text the vector was made from.
  *
  * Filterable: researcherId, communityId, windowStartYear, windowEndYear, topicId, institutionId,
  * documentType, sourceDataset. Non-filterable: textS3Uri, textHash, embeddingModel, runId.
  */
final case class VectorMetadata(
    researcherId: Option[String],
    communityId: Option[String],
    windowStartYear: Option[Int],
    windowEndYear: Option[Int],
    topicId: Option[String],
    institutionId: Option[String],
    documentType: String,
    sourceDataset: String,
    textS3Uri: String,
    textHash: String,
    embeddingModel: String,
    runId: String
):
  /** Filterable entries, in a fixed order. */
  def filterable: Vector[(String, ujson.Value)] =
    Vector(
      researcherId.map("researcherId" -> ujson.Str(_)),
      communityId.map("communityId" -> ujson.Str(_)),
      windowStartYear.map("windowStartYear" -> ujson.Num(_)),
      windowEndYear.map("windowEndYear" -> ujson.Num(_)),
      topicId.map("topicId" -> ujson.Str(_)),
      institutionId.map("institutionId" -> ujson.Str(_)),
      Some("documentType" -> ujson.Str(documentType)),
      Some("sourceDataset" -> ujson.Str(sourceDataset))
    ).flatten

  def nonFilterable: Vector[(String, ujson.Value)] = Vector(
    "textS3Uri" -> ujson.Str(textS3Uri),
    "textHash" -> ujson.Str(textHash),
    "embeddingModel" -> ujson.Str(embeddingModel),
    "runId" -> ujson.Str(runId)
  )

  def toJson: ujson.Obj = ujson.Obj.from(filterable ++ nonFilterable)

object VectorMetadata:
  val FilterableKeys: Set[String] = Set(
    "researcherId",
    "communityId",
    "windowStartYear",
    "windowEndYear",
    "topicId",
    "institutionId",
    "documentType",
    "sourceDataset"
  )

  /** Decodes stored metadata; a missing or mistyped required field is a clear error. */
  def fromJson(o: ujson.Obj): Result[VectorMetadata] =
    def req(k: String): Result[String] = o.value.get(k) match
      case Some(ujson.Str(s)) => Right(s)
      case Some(other) =>
        Left(StorageError.InvalidRecord(s"metadata '$k' must be a string, got ${other.render()}"))
      case None => Left(StorageError.InvalidRecord(s"metadata '$k' is missing"))
    def optS(k: String): Result[Option[String]] = o.value.get(k) match
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Str(s)) => Right(Some(s))
      case Some(other) =>
        Left(StorageError.InvalidRecord(s"metadata '$k' must be a string, got ${other.render()}"))
    def optI(k: String): Result[Option[Int]] = o.value.get(k) match
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Num(n)) if n.isWhole => Right(Some(n.toInt))
      case Some(other) =>
        Left(StorageError.InvalidRecord(s"metadata '$k' must be an integer, got ${other.render()}"))
    for
      r <- optS("researcherId")
      c <- optS("communityId")
      ws <- optI("windowStartYear")
      we <- optI("windowEndYear")
      t <- optS("topicId")
      i <- optS("institutionId")
      dt <- req("documentType")
      sd <- req("sourceDataset")
      uri <- req("textS3Uri")
      th <- req("textHash")
      em <- req("embeddingModel")
      run <- req("runId")
    yield VectorMetadata(r, c, ws, we, t, i, dt, sd, uri, th, em, run)

final case class VectorRecord(key: String, embedding: Vector[Float], metadata: VectorMetadata)

final case class StoredVector(
    key: String,
    metadata: VectorMetadata,
    embedding: Option[Vector[Float]]
)

/** Metadata filter over filterable keys only. */
enum VectorFilter:
  case Eq(key: String, value: ujson.Value)
  case In(key: String, values: Vector[ujson.Value])
  case Gte(key: String, value: Int)
  case Lte(key: String, value: Int)
  case And(parts: Vector[VectorFilter])

  /** The S3 Vectors filter document (`$eq`, `$in`, `$gte`, `$lte`, `$and`). */
  def toJson: ujson.Value = this match
    case Eq(k, v) => ujson.Obj(k -> ujson.Obj("$eq" -> v))
    case In(k, vs) => ujson.Obj(k -> ujson.Obj("$in" -> ujson.Arr.from(vs)))
    case Gte(k, v) => ujson.Obj(k -> ujson.Obj("$gte" -> ujson.Num(v)))
    case Lte(k, v) => ujson.Obj(k -> ujson.Obj("$lte" -> ujson.Num(v)))
    case And(ps) => ujson.Obj("$and" -> ujson.Arr.from(ps.map(_.toJson)))

  def keys: Set[String] = this match
    case Eq(k, _) => Set(k)
    case In(k, _) => Set(k)
    case Gte(k, _) => Set(k)
    case Lte(k, _) => Set(k)
    case And(ps) => ps.flatMap(_.keys).toSet

final case class VectorQuery(
    embedding: Vector[Float],
    topK: Int,
    filter: Option[VectorFilter],
    returnMetadata: Boolean = true
)

final case class VectorMatch(key: String, distance: Double, metadata: Option[VectorMetadata])

/** Port over one S3 Vectors bucket. It never creates a bucket or an index (infrastructure comes
  * from Terraform): [[describe]] reads what exists. Every method enforces the documented limits
  * client-side, before any request is made.
  */
trait VectorIndex:
  def describe(indexName: String): Result[Option[IndexDefinition]]

  /** Upsert of at most [[VectorServiceLimits.MaxPutBatch]] vectors. Callers that must not
    * overwrite use [[VectorWriter]], which checks existing keys first.
    */
  def put(indexName: String, vectors: Vector[VectorRecord]): Result[Unit]

  /** Stored vectors for the keys that exist (at most 100 keys); missing keys are omitted. */
  def get(
      indexName: String,
      keys: Vector[String],
      returnData: Boolean
  ): Result[Vector[StoredVector]]

  def query(indexName: String, query: VectorQuery): Result[Vector[VectorMatch]]

  def retryStats: RetrySnapshot
