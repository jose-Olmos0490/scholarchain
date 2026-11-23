package scholarchain.storage.objects

import java.nio.file.Path
import scholarchain.core.config.{S3PrefixConfig, StorageConfig}
import scholarchain.storage.common.*

/** Logical areas of ordinary S3 (the authoritative text and object store). */
enum ObjectArea(val wire: String):
  case Raw extends ObjectArea("raw")
  case Curated extends ObjectArea("curated")
  case Graph extends ObjectArea("graph")
  case Rag extends ObjectArea("rag")
  case Reports extends ObjectArea("reports")
  case Logs extends ObjectArea("logs")
  case NeptuneExport extends ObjectArea("neptune-export")

/** An object address: an area and a key relative to that area's prefix. */
final case class ObjectRef(area: ObjectArea, key: String)

object ObjectRef:
  /** S3 object keys are at most 1,024 bytes of UTF-8
    * (https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html).
    */
  val MaxKeyBytes = 1024

  /** Parses `s3://<bucket>/<key>` into an address inside the configured layout. The URI is
    * untrusted (it comes from retrieved vector metadata), so anything that does not name an object
    * in a configured bucket, under the key prefix configured for one of that bucket's areas, is
    * refused: a foreign bucket, another scheme, a missing key, query or fragment characters,
    * backslashes, control characters, empty or dot path segments, and over-long keys. When
    * several areas share the bucket, the area with the longest matching key prefix is chosen, so
    * the same URI always resolves to the same address.
    */
  def fromS3Uri(layout: ObjectLayout, uri: String): Result[ObjectRef] =
    def refuse(why: String): Result[ObjectRef] =
      Left(StorageError.InvalidRecord(s"not a readable S3 location: $why"))
    val Scheme = "s3://"
    if uri == null || !uri.startsWith(Scheme) then refuse("the scheme must be s3://")
    else
      val rest = uri.drop(Scheme.length)
      val slash = rest.indexOf('/')
      if slash <= 0 then refuse("bucket and key are required")
      else
        val bucket = rest.take(slash)
        val key = rest.drop(slash + 1)
        val segments = key.split("/", -1)
        if key.isEmpty then refuse("the key is empty")
        else if key.exists(c => c < ' ' || c == '\u007f' || c == '\\' || c == '?' || c == '#') then
          refuse("the key has a character that is not allowed")
        else if segments.exists(sg => sg.isEmpty || sg == "." || sg == "..") then
          refuse("the key has an empty or dot path segment")
        else if key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxKeyBytes then
          refuse(s"the key is longer than $MaxKeyBytes bytes")
        else
          val inBucket =
            ObjectArea.values.toVector.filter(a => layout.buckets.get(a).contains(bucket))
          if inBucket.isEmpty then refuse("the bucket is not in the configured layout")
          else
            val matching = inBucket
              .map(a => (a, layout.prefixes.getOrElse(a, "")))
              .filter((_, prefix) => key.startsWith(prefix) && key.length > prefix.length)
            if matching.isEmpty then refuse("the key is outside the configured prefixes")
            else
              val (area, prefix) = matching.maxBy((a, p) => (p.length, -a.ordinal))
              Right(ObjectRef(area, key.drop(prefix.length)))

/** Where each area lives: a bucket name and a key prefix inside it. */
final case class ObjectLayout(buckets: Map[ObjectArea, String], prefixes: Map[ObjectArea, String]):
  def bucket(area: ObjectArea): String = buckets(area)
  def fullKey(ref: ObjectRef): String = prefixes.getOrElse(ref.area, "") + ref.key
  def s3Uri(ref: ObjectRef): String = s"s3://${bucket(ref.area)}/${fullKey(ref)}"

object ObjectLayout:
  /** raw, curated, graph and rag are the four configured buckets (empty key prefix). The reports,
    * logs and neptune-export areas have configured key prefixes but no configured bucket of their
    * own: the caller must say which of the four buckets holds them. There is deliberately no
    * default (open decision for the human architect).
    */
  def fromConfig(
      storage: StorageConfig,
      prefixes: S3PrefixConfig,
      auxiliaryAreasLiveIn: ObjectArea
  ): Result[ObjectLayout] =
    import ObjectArea.*
    val base = Map(
      Raw -> storage.rawBucket,
      Curated -> storage.curatedBucket,
      Graph -> storage.graphBucket,
      Rag -> storage.ragBucket
    )
    base.get(auxiliaryAreasLiveIn) match
      case None =>
        Left(
          StorageError.Misconfigured(
            s"auxiliary areas must live in raw, curated, graph or rag, not $auxiliaryAreasLiveIn"
          )
        )
      case Some(aux) =>
        Right(
          ObjectLayout(
            base ++ Map(Reports -> aux, Logs -> aux, NeptuneExport -> aux),
            Map(
              Reports -> prefixes.reports,
              Logs -> prefixes.logs,
              NeptuneExport -> prefixes.neptuneExport
            )
          )
        )

final case class ObjectMeta(ref: ObjectRef, sizeBytes: Long, sha256: String)

enum PutOutcome:
  /** The object did not exist and was written. */
  case Created

  /** The key already held identical content (same SHA-256); nothing was written. */
  case Skipped

/** Port for ordinary S3. Objects are immutable: there is no overwrite and no delete. A put of an
  * existing key with identical content is a skip; with different content it is
  * [[StorageError.ConflictingContent]].
  */
trait ObjectStore:
  def putBytes(ref: ObjectRef, bytes: Array[Byte], contentType: String): Result[PutOutcome]

  /** `sha256` and `sizeBytes` describe `file` and were verified by the caller. */
  def putFile(ref: ObjectRef, file: Path, sha256: String, sizeBytes: Long): Result[PutOutcome]

  def head(ref: ObjectRef): Result[Option[ObjectMeta]]

  def getBytes(ref: ObjectRef): Result[Option[Array[Byte]]]

  /** Copies the object into `target`; `None` when it does not exist. */
  def downloadTo(ref: ObjectRef, target: Path): Result[Option[ObjectMeta]]

  /** Objects whose key starts with `keyPrefix`, sorted by key. */
  def list(area: ObjectArea, keyPrefix: String): Result[Vector[ObjectMeta]]

/** Key construction. Every key of a load sits under the run directory `run_id=<hex>/`. */
object ObjectKeys:
  private val RunIdPattern = "run:([0-9a-f]{64})".r

  def runHex(runId: String): Result[String] = runId match
    case RunIdPattern(h) => Right(h)
    case _ => Left(StorageError.InvalidRecord(s"'$runId' is not a run ID (run:<64 hex>)"))

  def runDir(runHex: String): String = s"run_id=$runHex/"

  /** `run_id=<hex>/<tableName>/<relative>`, the key of one Spark output file. */
  def tableFile(runHex: String, tableName: String, relative: String): String =
    s"${runDir(runHex)}$tableName/${relative.stripPrefix("/")}"

  /** `run_id=<hex>/storage/<name>`, the key of a storage-side report. */
  def runReport(runHex: String, name: String): String = s"${runDir(runHex)}storage/$name"
