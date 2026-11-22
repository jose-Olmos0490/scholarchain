package scholarchain.storage.dynamo

import scala.collection.mutable
import scholarchain.storage.common.*

/** Local fake of the conditional item store with DynamoDB's key semantics: items are keyed by
  * `(table, pk, sk)`, a conditional put never overwrites, a partition is ordered by `sk`, and a
  * transactional put is all-or-nothing. TTL is not simulated (expired items are not removed);
  * `expiresAt` is stored so tests can inspect it.
  */
final class InMemoryItemStore extends ItemStore:
  private val data = mutable.Map.empty[(String, String), mutable.TreeMap[String, ujson.Obj]]

  private def partition(table: String, pk: String) =
    data.getOrElseUpdate((table, pk), mutable.TreeMap.empty[String, ujson.Obj])

  private def copy(o: ujson.Obj): ujson.Obj = ujson.read(ujson.write(o)).obj

  def putIfAbsent(table: String, item: ujson.Obj): Result[Unit] = synchronized {
    val part = partition(table, item("pk").str)
    val sk = item("sk").str
    if part.contains(sk) then Left(StorageError.AlreadyExists(ItemCodec.keyOf(item)))
    else
      part(sk) = copy(item)
      Right(())
  }

  def putAllIfAbsent(table: String, items: Vector[ujson.Obj]): Result[Unit] = synchronized {
    if items.size > 100 then Left(StorageError.LimitExceeded("transaction items", 100, items.size))
    else
      val keys = items.map(i => (i("pk").str, i("sk").str))
      if keys.distinct.size != keys.size then
        Left(StorageError.InvalidRecord("transaction contains two items with the same key"))
      else
        items.find(i => partition(table, i("pk").str).contains(i("sk").str)) match
          case Some(conflict) => Left(StorageError.AlreadyExists(ItemCodec.keyOf(conflict)))
          case None =>
            items.foreach(i => partition(table, i("pk").str)(i("sk").str) = copy(i))
            Right(())
  }

  def get(table: String, pk: String, sk: String): Result[Option[ujson.Obj]] = synchronized {
    Right(data.get((table, pk)).flatMap(_.get(sk)).map(copy))
  }

  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      ascending: Boolean,
      limit: Option[Int]
  ): Result[Vector[ujson.Obj]] = synchronized {
    val rows = data
      .get((table, pk))
      .map(_.iterator.filter((sk, _) => skPrefix.forall(sk.startsWith)).map(_._2).toVector)
      .getOrElse(Vector.empty)
    val ordered = if ascending then rows else rows.reverse
    Right(limit.fold(ordered)(ordered.take).map(copy))
  }

  /** Test inspection: every item of a table, sorted by key. */
  def items(table: String): Vector[ujson.Obj] = synchronized {
    data.toVector
      .filter(_._1._1 == table)
      .sortBy(_._1._2)
      .flatMap(_._2.values.map(copy))
  }
