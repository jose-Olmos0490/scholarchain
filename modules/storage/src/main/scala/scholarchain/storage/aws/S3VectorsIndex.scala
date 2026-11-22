package scholarchain.storage.aws

import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{DistanceMetric, VectorIndexType}
import scholarchain.storage.common.*
import scholarchain.storage.vectors.*
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.*

/** AWS SDK v2 adapter for [[VectorIndex]] over one vector bucket.
  *
  * It creates nothing: [[describe]] reads the deployed index (GetIndex), including its index mode,
  * so [[VectorWriter]] can refuse to write when the deployed index differs from configuration.
  * Limits are checked by [[VectorGuards]] before any request is built. Throttling (429), timeouts
  * (408) and transient server errors (500, 503) are retried by `retrier`; validation, quota and
  * access errors are not. Index limits: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html
  */
final class S3VectorsIndex(
    client: S3VectorsClient,
    vectorBucket: String,
    retrier: Retrier,
    known: Vector[IndexDefinition]
) extends VectorIndex:

  private val byName = known.map(d => d.name -> d).toMap

  private def definition(name: String): Result[IndexDefinition] =
    byName
      .get(name)
      .toRight(StorageError.Misconfigured(s"vector index $name is not a configured index"))

  def describe(indexName: String): Result[Option[IndexDefinition]] =
    retrier.run(s"S3 Vectors GetIndex $indexName") {
      try
        val i = client
          .getIndex(
            GetIndexRequest.builder().vectorBucketName(vectorBucket).indexName(indexName).build()
          )
          .index()
        val metric = DistanceMetric
          .parse(i.distanceMetricAsString())
          .getOrElse(
            throw new StorageException(
              StorageError.InvalidRecord(
                s"index $indexName has unknown distance metric ${i.distanceMetricAsString()}"
              )
            )
          )
        val mode = VectorIndexType
          .parse(i.indexModeAsString())
          .getOrElse(
            throw new StorageException(
              StorageError
                .InvalidRecord(s"index $indexName has unknown index mode ${i.indexModeAsString()}")
            )
          )
        val nonFilterable =
          Option(i.metadataConfiguration())
            .map(_.nonFilterableMetadataKeys().asScala.toVector)
            .getOrElse(Vector.empty)
        Some(IndexDefinition(indexName, i.dimension(), metric, mode, nonFilterable))
      catch case _: NotFoundException => None
    }

  def put(indexName: String, vectors: Vector[VectorRecord]): Result[Unit] =
    for
      d <- definition(indexName)
      _ <- VectorGuards.checkPut(d, vectors)
      _ <- retrier.run(s"S3 Vectors PutVectors $indexName") {
        client.putVectors(
          PutVectorsRequest
            .builder()
            .vectorBucketName(vectorBucket)
            .indexName(indexName)
            .vectors(
              vectors.map { v =>
                PutInputVector
                  .builder()
                  .key(v.key)
                  .data(VectorData.fromFloat32(v.embedding.map(Float.box).asJava))
                  .metadata(Docs.toDocument(v.metadata.toJson))
                  .build()
              }.asJava
            )
            .build()
        )
        ()
      }
    yield ()

  def get(
      indexName: String,
      keys: Vector[String],
      returnData: Boolean
  ): Result[Vector[StoredVector]] =
    for
      _ <- definition(indexName)
      _ <- VectorGuards.checkGet(keys)
      found <- retrier.run(s"S3 Vectors GetVectors $indexName") {
        client
          .getVectors(
            GetVectorsRequest
              .builder()
              .vectorBucketName(vectorBucket)
              .indexName(indexName)
              .keys(keys.asJava)
              .returnData(returnData)
              .returnMetadata(true)
              .build()
          )
          .vectors()
          .asScala
          .toVector
      }
      decoded <- found.foldLeft[Result[Vector[StoredVector]]](Right(Vector.empty)) { (acc, v) =>
        acc.flatMap { done =>
          Docs.toJson(v.metadata()) match
            case o: ujson.Obj =>
              VectorMetadata.fromJson(o).map { m =>
                val emb =
                  if returnData && v.data() != null then
                    Some(v.data().float32().asScala.toVector.map(_.floatValue))
                  else None
                done :+ StoredVector(v.key(), m, emb)
              }
            case _ => Left(StorageError.InvalidRecord(s"vector ${v.key()} has no metadata object"))
        }
      }
    yield decoded

  def query(indexName: String, q: VectorQuery): Result[Vector[VectorMatch]] =
    for
      d <- definition(indexName)
      _ <- VectorGuards.checkQuery(d, q)
      found <- retrier.run(s"S3 Vectors QueryVectors $indexName") {
        val b = QueryVectorsRequest
          .builder()
          .vectorBucketName(vectorBucket)
          .indexName(indexName)
          .topK(q.topK)
          .queryVector(VectorData.fromFloat32(q.embedding.map(Float.box).asJava))
          .returnMetadata(q.returnMetadata)
          .returnDistance(true)
        q.filter.foreach(f => b.filter(Docs.toDocument(f.toJson)))
        client.queryVectors(b.build()).vectors().asScala.toVector
      }
      decoded <- found.foldLeft[Result[Vector[VectorMatch]]](Right(Vector.empty)) { (acc, v) =>
        acc.flatMap { done =>
          val dist = Option(v.distance()).map(_.toDouble).getOrElse(Double.NaN)
          if !q.returnMetadata then Right(done :+ VectorMatch(v.key(), dist, None))
          else
            Docs.toJson(v.metadata()) match
              case o: ujson.Obj =>
                VectorMetadata.fromJson(o).map(m => done :+ VectorMatch(v.key(), dist, Some(m)))
              case _ => Left(StorageError.InvalidRecord(s"match ${v.key()} has no metadata object"))
        }
      }
    yield decoded

  def retryStats: RetrySnapshot = retrier.stats.snapshot
