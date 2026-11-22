package scholarchain.storage.vectors

import scala.collection.mutable
import scholarchain.core.config.DistanceMetric
import scholarchain.storage.common.*

/** Local fake of the S3 Vectors port: the same client-side limits, upsert semantics (PutVectors
  * overwrites), exact nearest-neighbour search and metadata filter evaluation. Indexes must be
  * declared up front (a fake never creates infrastructure on its own).
  */
final class InMemoryVectorIndex(definitions: Vector[IndexDefinition]) extends VectorIndex:
  private val defs = definitions.map(d => d.name -> d).toMap
  private val data = mutable.Map.empty[(String, String), VectorRecord]
  private val puts = mutable.ArrayBuffer.empty[(String, Int)]

  def describe(indexName: String): Result[Option[IndexDefinition]] = Right(defs.get(indexName))

  private def index(name: String): Result[IndexDefinition] =
    defs.get(name).toRight(StorageError.NotFound(s"vector index $name"))

  def put(indexName: String, vectors: Vector[VectorRecord]): Result[Unit] = synchronized {
    for
      d <- index(indexName)
      _ <- VectorGuards.checkPut(d, vectors)
    yield
      vectors.foreach(v => data((indexName, v.key)) = v)
      puts += ((indexName, vectors.size))
  }

  def get(
      indexName: String,
      keys: Vector[String],
      returnData: Boolean
  ): Result[Vector[StoredVector]] =
    synchronized {
      for
        _ <- index(indexName)
        _ <- VectorGuards.checkGet(keys)
      yield keys.flatMap(k =>
        data
          .get((indexName, k))
          .map(r => StoredVector(k, r.metadata, if returnData then Some(r.embedding) else None))
      )
    }

  def query(indexName: String, q: VectorQuery): Result[Vector[VectorMatch]] = synchronized {
    for
      d <- index(indexName)
      _ <- VectorGuards.checkQuery(d, q)
    yield data.iterator
      .collect { case ((i, _), r) if i == indexName => r }
      .filter(r => q.filter.forall(matches(_, r.metadata.toJson)))
      .map(r => (r, distance(d.distanceMetric, q.embedding, r.embedding)))
      .toVector
      .sortBy((r, dist) => (dist, r.key))
      .take(q.topK)
      .map((r, dist) =>
        VectorMatch(r.key, dist, if q.returnMetadata then Some(r.metadata) else None)
      )
  }

  def retryStats: RetrySnapshot = RetrySnapshot(0, 0, 0, 0)

  /** Test inspection: sizes of the put calls that reached the index, in order. */
  def putSizes: Vector[Int] = synchronized(puts.map(_._2).toVector)

  def stored(indexName: String): Vector[VectorRecord] = synchronized {
    data.collect { case ((i, _), r) if i == indexName => r }.toVector.sortBy(_.key)
  }

  private def distance(m: DistanceMetric, a: Vector[Float], b: Vector[Float]): Double = m match
    case DistanceMetric.Euclidean =>
      math.sqrt(a.zip(b).map((x, y) => math.pow((x - y).toDouble, 2)).sum)
    case DistanceMetric.Cosine =>
      val dot = a.zip(b).map((x, y) => x.toDouble * y).sum
      val na = math.sqrt(a.map(x => x.toDouble * x).sum)
      val nb = math.sqrt(b.map(x => x.toDouble * x).sum)
      1.0 - dot / (na * nb)

  private def matches(f: VectorFilter, meta: ujson.Obj): Boolean = f match
    case VectorFilter.Eq(k, v) => meta.value.get(k).contains(v)
    case VectorFilter.In(k, vs) => meta.value.get(k).exists(vs.contains)
    case VectorFilter.Gte(k, v) =>
      meta.value.get(k).exists { case ujson.Num(n) => n >= v; case _ => false }
    case VectorFilter.Lte(k, v) =>
      meta.value.get(k).exists { case ujson.Num(n) => n <= v; case _ => false }
    case VectorFilter.And(ps) => ps.forall(matches(_, meta))
