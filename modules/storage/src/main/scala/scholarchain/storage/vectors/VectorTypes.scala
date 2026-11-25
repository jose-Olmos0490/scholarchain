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

  /** Project rule (work order, Phase 8): filterable metadata of one vector never exceeds 2 KB, on
    * every index mode. "KB" is read as 1000 bytes, as for the service limit above.
    */
  val IngestFilterableBudgetBytes = 2 * 1000
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
):
  /** Whether `deployed` is the same index as this one. The non-filterable keys are compared as a
    * set: GetIndex does not return them in the order they were configured. Use this, never `==`, to
    * check a deployed index against configuration.
    */
  def matches(deployed: IndexDefinition): Boolean =
    name == deployed.name &&
      dimension == deployed.dimension &&
      distanceMetric == deployed.distanceMetric &&
      indexType == deployed.indexType &&
      nonFilterableKeys.toSet == deployed.nonFilterableKeys.toSet

object IndexDefinition:
  /** Index keys that are never filterable: pointers and versions, not search dimensions. */
  val NonFilterableMetadataKeys: Vector[String] =
    Vector(
      "textS3Uri",
      "textHash",
      "embeddingModel",
      "embeddingModelVersion",
      "runId",
      "schemaVersion"
    )

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

/** Compact metadata of one vector (schema [[MetadataSchema.Version]]). There is no free-text field:
  * long text lives only in ordinary S3 and is reached through `textS3Uri`; `textHash` pins the
  * exact text the vector was made from.
  *
  * Filterable: entityId, documentType, windowStartYear, windowEndYear, topicIds, institutionIds,
  * sourceDataset, communityId (when applicable). Non-filterable: textS3Uri, textHash,
  * embeddingModel, embeddingModelVersion, runId, schemaVersion.
  */
final case class VectorMetadata(
    entityId: String,
    documentType: String,
    windowStartYear: Int,
    windowEndYear: Int,
    topicIds: Vector[String],
    institutionIds: Vector[String],
    sourceDataset: String,
    communityId: Option[String],
    textS3Uri: String,
    textHash: String,
    embeddingModel: String,
    embeddingModelVersion: String,
    runId: String,
    schemaVersion: String
):
  /** Filterable entries, in a fixed order. Empty lists and an absent communityId are omitted. */
  def filterable: Vector[(String, ujson.Value)] =
    Vector(
      Some("entityId" -> ujson.Str(entityId)),
      Some("documentType" -> ujson.Str(documentType)),
      Some("windowStartYear" -> ujson.Num(windowStartYear)),
      Some("windowEndYear" -> ujson.Num(windowEndYear)),
      Option.when(topicIds.nonEmpty)("topicIds" -> ujson.Arr.from(topicIds.map(ujson.Str(_)))),
      Option.when(institutionIds.nonEmpty)(
        "institutionIds" -> ujson.Arr.from(institutionIds.map(ujson.Str(_)))
      ),
      Some("sourceDataset" -> ujson.Str(sourceDataset)),
      communityId.map("communityId" -> ujson.Str(_))
    ).flatten

  def nonFilterable: Vector[(String, ujson.Value)] = Vector(
    "textS3Uri" -> ujson.Str(textS3Uri),
    "textHash" -> ujson.Str(textHash),
    "embeddingModel" -> ujson.Str(embeddingModel),
    "embeddingModelVersion" -> ujson.Str(embeddingModelVersion),
    "runId" -> ujson.Str(runId),
    "schemaVersion" -> ujson.Str(schemaVersion)
  )

  def toJson: ujson.Obj = ujson.Obj.from(filterable ++ nonFilterable)

object VectorMetadata:
  val FilterableKeys: Set[String] = Set(
    "entityId",
    "documentType",
    "windowStartYear",
    "windowEndYear",
    "topicIds",
    "institutionIds",
    "sourceDataset",
    "communityId"
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
    def reqI(k: String): Result[Int] = o.value.get(k) match
      case Some(ujson.Num(n)) if n.isWhole => Right(n.toInt)
      case Some(other) =>
        Left(StorageError.InvalidRecord(s"metadata '$k' must be an integer, got ${other.render()}"))
      case None => Left(StorageError.InvalidRecord(s"metadata '$k' is missing"))
    def strings(k: String): Result[Vector[String]] = o.value.get(k) match
      case None | Some(ujson.Null) => Right(Vector.empty)
      case Some(ujson.Arr(items)) =>
        items.foldLeft[Result[Vector[String]]](Right(Vector.empty)) {
          case (acc, ujson.Str(s)) => acc.map(_ :+ s)
          case (_, other) =>
            Left(StorageError.InvalidRecord(s"metadata '$k' holds a non-string ${other.render()}"))
        }
      case Some(other) =>
        Left(StorageError.InvalidRecord(s"metadata '$k' must be a list, got ${other.render()}"))
    for
      e <- req("entityId")
      dt <- req("documentType")
      ws <- reqI("windowStartYear")
      we <- reqI("windowEndYear")
      topics <- strings("topicIds")
      insts <- strings("institutionIds")
      sd <- req("sourceDataset")
      c <- optS("communityId")
      uri <- req("textS3Uri")
      th <- req("textHash")
      em <- req("embeddingModel")
      emv <- req("embeddingModelVersion")
      run <- req("runId")
      sv <- req("schemaVersion")
    yield VectorMetadata(e, dt, ws, we, topics, insts, sd, c, uri, th, em, emv, run, sv)

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
