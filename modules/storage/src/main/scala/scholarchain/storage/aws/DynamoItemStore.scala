package scholarchain.storage.aws

import scala.jdk.CollectionConverters.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

/** AWS SDK v2 adapter for the conditional [[ItemStore]] seam.
  *
  * Every create is a `PutItem` (or `TransactWriteItems` of puts) with
  * `attribute_not_exists(pk)`: nothing is overwritten. `BatchWriteItem` is not used because it
  * cannot carry a condition on a put
  * (https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html).
  * Reads are strongly consistent. Throttling and transient failures are retried by `retrier`.
  */
final class DynamoItemStore(client: DynamoDbClient, retrier: Retrier) extends ItemStore:

  private val NotExists = "attribute_not_exists(pk)"

  private def toAttr(v: ujson.Value): AttributeValue = v match
    case ujson.Str(s) => AttributeValue.fromS(s)
    case ujson.Num(n) =>
      val bd = java.math.BigDecimal.valueOf(n).stripTrailingZeros
      AttributeValue.fromN(bd.toPlainString)
    case ujson.Bool(b) => AttributeValue.fromBool(b)
    case ujson.Null => AttributeValue.builder().nul(true).build()
    case ujson.Arr(xs) => AttributeValue.fromL(xs.map(toAttr).asJava)
    case ujson.Obj(m) => AttributeValue.fromM(m.map((k, x) => k -> toAttr(x)).asJava)

  private def fromAttr(a: AttributeValue): ujson.Value =
    if a.s() != null then ujson.Str(a.s())
    else if a.n() != null then ujson.Num(a.n().toDouble)
    else if a.bool() != null then ujson.Bool(a.bool())
    else if a.nul() != null && a.nul() then ujson.Null
    else if a.hasL then ujson.Arr.from(a.l().asScala.map(fromAttr))
    else if a.hasM then ujson.Obj.from(a.m().asScala.map((k, x) => k -> fromAttr(x)))
    else ujson.Null

  private def attrs(item: ujson.Obj): java.util.Map[String, AttributeValue] =
    item.value.map((k, v) => k -> toAttr(v)).asJava

  private def toItem(m: java.util.Map[String, AttributeValue]): ujson.Obj =
    ujson.Obj.from(m.asScala.map((k, v) => k -> fromAttr(v)))

  def putIfAbsent(table: String, item: ujson.Obj): Result[Unit] =
    retrier.run(s"DynamoDB put $table") {
      try
        client.putItem(
          PutItemRequest
            .builder()
            .tableName(table)
            .item(attrs(item))
            .conditionExpression(NotExists)
            .build()
        )
        ()
      catch
        case _: ConditionalCheckFailedException =>
          throw new StorageException(StorageError.AlreadyExists(ItemCodec.keyOf(item)))
    }

  def putAllIfAbsent(table: String, items: Vector[ujson.Obj]): Result[Unit] =
    if items.size > 100 then Left(StorageError.LimitExceeded("transaction items", 100, items.size))
    else
      retrier.run(s"DynamoDB transaction $table") {
        try
          client.transactWriteItems(
            TransactWriteItemsRequest
              .builder()
              .transactItems(
                items.map { i =>
                  TransactWriteItem
                    .builder()
                    .put(
                      Put
                        .builder()
                        .tableName(table)
                        .item(attrs(i))
                        .conditionExpression(NotExists)
                        .build()
                    )
                    .build()
                }.asJava
              )
              .build()
          )
          ()
        catch
          case e: TransactionCanceledException =>
            val reasons = e.cancellationReasons().asScala.toVector
            reasons.zipWithIndex.find(_._1.code() == "ConditionalCheckFailed") match
              case Some((_, idx)) =>
                throw new StorageException(StorageError.AlreadyExists(ItemCodec.keyOf(items(idx))))
              case None => throw e // other reasons (conflict, throttling) are classified as usual
      }

  def get(table: String, pk: String, sk: String): Result[Option[ujson.Obj]] =
    retrier.run(s"DynamoDB get $table") {
      val r = client.getItem(
        GetItemRequest
          .builder()
          .tableName(table)
          .consistentRead(true)
          .key(java.util.Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk)))
          .build()
      )
      if r.hasItem && !r.item().isEmpty then Some(toItem(r.item())) else None
    }

  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      ascending: Boolean,
      limit: Option[Int]
  ): Result[Vector[ujson.Obj]] =
    retrier.run(s"DynamoDB query $table") {
      val values = new java.util.HashMap[String, AttributeValue]()
      values.put(":pk", AttributeValue.fromS(pk))
      skPrefix.foreach(p => values.put(":p", AttributeValue.fromS(p)))
      val condition = "pk = :pk" + skPrefix.fold("")(_ => " AND begins_with(sk, :p)")
      val out = Vector.newBuilder[ujson.Obj]
      var count = 0
      var start: java.util.Map[String, AttributeValue] = null
      var more = true
      while more do
        val b = QueryRequest
          .builder()
          .tableName(table)
          .consistentRead(true)
          .scanIndexForward(ascending)
          .keyConditionExpression(condition)
          .expressionAttributeValues(values)
        limit.foreach(l => b.limit(l - count))
        if start != null then b.exclusiveStartKey(start)
        val r = client.query(b.build())
        r.items().asScala.foreach { i =>
          out += toItem(i)
          count += 1
        }
        start = if r.hasLastEvaluatedKey && !r.lastEvaluatedKey().isEmpty then r.lastEvaluatedKey()
        else null
        more = start != null && limit.forall(count < _)
      out.result()
    }
