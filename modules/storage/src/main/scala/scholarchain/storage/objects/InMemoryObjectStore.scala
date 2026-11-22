package scholarchain.storage.objects

import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scholarchain.storage.common.*
import scholarchain.storage.manifest.{FileStager, StagedFile}

/** Local fake of [[ObjectStore]] with the same conflict and skip semantics as the S3 adapter. */
final class InMemoryObjectStore extends ObjectStore:
  private val data = TrieMap.empty[ObjectRef, (Array[Byte], String)]

  private def put(ref: ObjectRef, bytes: Array[Byte], hash: String): Result[PutOutcome] =
    data.synchronized {
      data.get(ref) match
        case None =>
          data.put(ref, (bytes, hash))
          Right(PutOutcome.Created)
        case Some((_, existing)) if existing == hash => Right(PutOutcome.Skipped)
        case Some((_, existing)) =>
          Left(StorageError.ConflictingContent(ref.key, existing, hash))
    }

  def putBytes(ref: ObjectRef, bytes: Array[Byte], contentType: String): Result[PutOutcome] =
    put(ref, bytes.clone(), Sha256.of(bytes))

  def putFile(ref: ObjectRef, file: Path, sha256: String, sizeBytes: Long): Result[PutOutcome] =
    val bytes = Files.readAllBytes(file)
    val actual = Sha256.of(bytes)
    if actual != sha256 || bytes.length.toLong != sizeBytes then
      Left(StorageError.ChecksumMismatch(file.toString, sha256, actual))
    else put(ref, bytes, actual)

  def head(ref: ObjectRef): Result[Option[ObjectMeta]] =
    Right(data.get(ref).map((b, h) => ObjectMeta(ref, b.length.toLong, h)))

  def getBytes(ref: ObjectRef): Result[Option[Array[Byte]]] =
    Right(data.get(ref).map(_._1.clone()))

  def downloadTo(ref: ObjectRef, target: Path): Result[Option[ObjectMeta]] =
    data.get(ref) match
      case None => Right(None)
      case Some((b, h)) =>
        Files.write(target, b)
        Right(Some(ObjectMeta(ref, b.length.toLong, h)))

  def list(area: ObjectArea, keyPrefix: String): Result[Vector[ObjectMeta]] =
    Right(
      data.toVector
        .collect {
          case (r, (b, h)) if r.area == area && r.key.startsWith(keyPrefix) =>
            ObjectMeta(r, b.length.toLong, h)
        }
        .sortBy(_.ref.key)
    )

  /** Test inspection: every object (area, key, hash), sorted. */
  def snapshot: Vector[(ObjectRef, String)] =
    data.toVector.map((r, v) => (r, v._2)).sortBy(x => (x._1.area.wire, x._1.key))

/** Stages manifest paths of the form `<area>/<key>` (area = [[ObjectArea]] wire name) by
  * downloading them through an [[ObjectStore]] into `tempDir`. Used when Spark output was already
  * copied into ordinary S3.
  */
final class ObjectStoreFileStager(store: ObjectStore, tempDir: Path) extends FileStager:
  def stage(path: String): Result[StagedFile] =
    val idx = path.indexOf('/')
    val area = if idx > 0 then ObjectArea.values.find(_.wire == path.take(idx)) else None
    area match
      case None =>
        Left(StorageError.Misconfigured(s"$path does not start with an object area name"))
      case Some(a) =>
        val target = Files.createTempFile(tempDir, "stage-", ".parquet")
        store.downloadTo(ObjectRef(a, path.drop(idx + 1)), target).flatMap {
          case None =>
            Files.deleteIfExists(target)
            Left(StorageError.MissingFile(path))
          case Some(_) =>
            Right(StagedFile(target, () => { Files.deleteIfExists(target); () }))
        }
