package scholarchain.storage.aws

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.storage.common.*
import scholarchain.storage.objects.*
import software.amazon.awssdk.core.sync.{RequestBody, ResponseTransformer}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

/** AWS SDK v2 adapter for [[ObjectStore]].
  *
  * Writes use `If-None-Match: *` (S3 conditional writes), so an existing key is never replaced,
  * and carry the SHA-256 both as the S3 checksum (verified by S3) and as user metadata `sha256`.
  * When the key exists, the existing hash is compared: equal is a skip, different is
  * [[StorageError.ConflictingContent]]. Retries (throttling, timeouts, 5xx) go through `retrier`.
  *
  * Conditional writes: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html
  */
final class S3ObjectStore(client: S3Client, layout: ObjectLayout, retrier: Retrier)
    extends ObjectStore:

  private val HashKey = "sha256"

  private def bucket(ref: ObjectRef) = layout.bucket(ref.area)
  private def key(ref: ObjectRef) = layout.fullKey(ref)

  private def put(
      ref: ObjectRef,
      body: => RequestBody,
      sha: String,
      contentType: String
  ): Result[PutOutcome] =
    retrier.run(s"S3 put ${key(ref)}") {
      try
        client.putObject(
          PutObjectRequest
            .builder()
            .bucket(bucket(ref))
            .key(key(ref))
            .ifNoneMatch("*")
            .contentType(contentType)
            .checksumSHA256(Sha256.base64(sha))
            .metadata(java.util.Map.of(HashKey, sha))
            .build(),
          body
        )
        PutOutcome.Created
      catch
        case e: S3Exception if e.statusCode() == 412 =>
          existingHash(ref) match
            case h if h == sha => PutOutcome.Skipped
            case h => throw new StorageException(StorageError.ConflictingContent(ref.key, h, sha))
    }

  /** SHA-256 of the object already at `ref`: the recorded metadata, else computed by streaming. */
  private def existingHash(ref: ObjectRef): String =
    val head =
      client.headObject(HeadObjectRequest.builder().bucket(bucket(ref)).key(key(ref)).build())
    Option(head.metadata().get(HashKey)).getOrElse {
      val in =
        client.getObject(GetObjectRequest.builder().bucket(bucket(ref)).key(key(ref)).build())
      try Sha256.of(in)
      finally in.close()
    }

  def putBytes(ref: ObjectRef, bytes: Array[Byte], contentType: String): Result[PutOutcome] =
    put(ref, RequestBody.fromBytes(bytes), Sha256.of(bytes), contentType)

  def putFile(ref: ObjectRef, file: Path, sha256: String, sizeBytes: Long): Result[PutOutcome] =
    put(ref, RequestBody.fromFile(file), sha256, "application/octet-stream")

  def head(ref: ObjectRef): Result[Option[ObjectMeta]] =
    retrier.run(s"S3 head ${key(ref)}") {
      try
        val h =
          client.headObject(HeadObjectRequest.builder().bucket(bucket(ref)).key(key(ref)).build())
        Some(
          ObjectMeta(
            ref,
            h.contentLength(),
            Option(h.metadata().get(HashKey)).getOrElse(existingHash(ref))
          )
        )
      catch
        case _: NoSuchKeyException => None
        case e: S3Exception if e.statusCode() == 404 => None
    }

  def getBytes(ref: ObjectRef): Result[Option[Array[Byte]]] =
    retrier.run(s"S3 get ${key(ref)}") {
      try
        Some(
          client
            .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket(ref)).key(key(ref)).build())
            .asByteArray()
        )
      catch case _: NoSuchKeyException => None
    }

  def downloadTo(ref: ObjectRef, target: Path): Result[Option[ObjectMeta]] =
    retrier.run(s"S3 download ${key(ref)}") {
      try
        val out = Files.newOutputStream(target)
        try
          client.getObject(
            GetObjectRequest.builder().bucket(bucket(ref)).key(key(ref)).build(),
            ResponseTransformer.toOutputStream(out)
          )
        finally out.close()
        Some(ObjectMeta(ref, Files.size(target), Sha256.ofFile(target)))
      catch case _: NoSuchKeyException => None
    }

  def list(area: ObjectArea, keyPrefix: String): Result[Vector[ObjectMeta]] =
    val prefix = layout.fullKey(ObjectRef(area, keyPrefix))
    val strip = layout.fullKey(ObjectRef(area, ""))
    retrier
      .run(s"S3 list $prefix") {
        client
          .listObjectsV2Paginator(
            ListObjectsV2Request.builder().bucket(layout.bucket(area)).prefix(prefix).build()
          )
          .contents()
          .asScala
          .map(o => o.key().stripPrefix(strip))
          .toVector
          .sorted
      }
      .flatMap { keys =>
        keys.foldLeft[Result[Vector[ObjectMeta]]](Right(Vector.empty)) { (acc, k) =>
          acc.flatMap(done => head(ObjectRef(area, k)).map(_.fold(done)(done :+ _)))
        }
      }
