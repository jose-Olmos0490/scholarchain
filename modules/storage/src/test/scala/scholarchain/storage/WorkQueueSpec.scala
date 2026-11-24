package scholarchain.storage

import scholarchain.storage.aws.{AwsErrors, SqsWorkQueue}
import scholarchain.storage.common.*
import scholarchain.storage.queue.*
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*

class WorkConsumerSpec extends StorageSuite:
  private var clock = 0L
  private def newQueue = new InMemoryWorkQueue(() => clock)

  private val settings = ConsumerSettings(
    batchSize = 2,
    visibilityTimeoutSeconds = 30,
    waitSeconds = 0,
    maxReceiveCount = 3,
    maxMessagesPerRun = 5,
    retryBackoffSeconds = 10
  )

  private class Rig(
      handler: String => Result[Unit],
      decode: String => Either[String, String] = Right(_)
  ):
    clock = 0L
    val queue = newQueue
    val dlq = newQueue
    val rejects = new InMemoryRejectSink
    val consumer = new WorkConsumer[String](queue, dlq, decode, handler, settings, rejects)

  test("a handled message is acknowledged and the queue is empty") {
    val seen = Vector.newBuilder[String]
    val rig = new Rig(b => { seen += b; Right(()) })
    rig.queue.send("a"); rig.queue.send("b")
    val r = ok(rig.consumer.runOnce())
    (r.received, r.acknowledged) shouldBe (2, 2)
    seen.result() shouldBe Vector("a", "b")
    rig.queue.size shouldBe 0
    rig.dlq.size shouldBe 0
  }

  test(
    "a received message is invisible until its visibility timeout, then redelivered with a higher count"
  ) {
    val rig = new Rig(_ => Left(StorageError.Transient("x", 1, "down")))
    rig.queue.send("a")
    ok(rig.consumer.runOnce()).retried shouldBe 1
    // backoff 10 s: not visible now
    ok(rig.consumer.runOnce()).received shouldBe 0
    clock += 11_000
    val again = ok(rig.queue.receive(1, 30, 0))
    again.map(_.receiveCount) shouldBe Vector(2)
  }

  test("a message that keeps failing transiently is dead-lettered on its last allowed receive") {
    val calls = new java.util.concurrent.atomic.AtomicInteger
    val rig = new Rig(_ => {
      calls.incrementAndGet(); Left(StorageError.Transient("x", 1, "down"))
    })
    rig.queue.send("poison")
    (1 to 3).foreach { _ =>
      ok(rig.consumer.runOnce())
      clock += 60_000
    }
    calls.get shouldBe 3
    rig.queue.size shouldBe 0
    rig.dlq.pendingBodies shouldBe Vector("poison")
    rig.rejects.all.map(_.reasonCode).last shouldBe "RECEIVES_EXHAUSTED"
  }

  test(
    "a permanent failure and an undecodable body go to the dead-letter queue at once, with a reject"
  ) {
    val rig = new Rig(
      b => if b == "bad" then Left(StorageError.NotFound("manifest")) else Right(()),
      b => if b == "junk" then Left("not json") else Right(b)
    )
    rig.queue.send("bad"); rig.queue.send("junk")
    val r = ok(rig.consumer.runOnce())
    r.deadLettered shouldBe 2
    rig.dlq.pendingBodies.toSet shouldBe Set("bad", "junk")
    rig.queue.size shouldBe 0
    rig.rejects.all.map(_.reasonCode).toSet shouldBe Set("PERMANENT_FAILURE", "UNDECODABLE_MESSAGE")
  }

  test("if the dead-letter send fails the message stays on the source queue") {
    val rig = new Rig(_ => Left(StorageError.Permanent("x", "no")))
    val failing = new WorkQueue:
      def send(b: String) = Left(StorageError.Transient("send", 1, "down"))
      def receive(m: Int, v: Int, w: Int) = Right(Vector.empty)
      def delete(h: String) = Right(())
      def changeVisibility(h: String, s: Int) = Right(())
    val c = new WorkConsumer[String](
      rig.queue,
      failing,
      Right(_),
      _ => Left(StorageError.Permanent("x", "no")),
      settings,
      rig.rejects
    )
    rig.queue.send("a")
    ok(c.runOnce()).outcomes.head._2 shouldBe a[MessageOutcome.QueueFault]
    rig.queue.size shouldBe 1
  }

  test("a throwing handler is a transient failure, not a crash; redelivery repeats idempotently") {
    var first = true
    val done = scala.collection.mutable.Set.empty[String]
    val rig = new Rig(b =>
      if first then { first = false; throw new RuntimeException("boom") }
      else { done += b; Right(()) }
    )
    rig.queue.send("a")
    ok(rig.consumer.runOnce()).retried shouldBe 1
    clock += 11_000
    ok(rig.consumer.runOnce()).acknowledged shouldBe 1
    done shouldBe Set("a")
  }

  test("drain is bounded by maxMessagesPerRun and stops on an empty queue") {
    val rig = new Rig(_ => Right(()))
    (1 to 8).foreach(i => rig.queue.send(s"m$i"))
    val r = ok(rig.consumer.drain())
    r.received shouldBe 5
    rig.queue.size shouldBe 3
    ok(rig.consumer.drain()).received shouldBe 3
    ok(rig.consumer.drain()).received shouldBe 0
  }

  test("a stale receipt handle cannot acknowledge") {
    val q = newQueue
    q.send("a")
    val m1 = ok(q.receive(1, 5, 0)).head
    clock += 6_000
    val m2 = ok(q.receive(1, 5, 0)).head
    err(q.delete(m1.receiptHandle)) shouldBe a[StorageError.NotFound]
    ok(q.delete(m2.receiptHandle))
  }

  test("embedding work items round-trip and unknown versions are refused") {
    val w = EmbeddingWorkItem.of("run-1", "snap-1")
    EmbeddingWorkItem.decode(EmbeddingWorkItem.encode(w)) shouldBe Right(w)
    EmbeddingWorkItem
      .decode("""{"schemaVersion":"v0","runId":"r","snapshot":"s"}""")
      .isLeft shouldBe true
    EmbeddingWorkItem.decode("{}").isLeft shouldBe true
    EmbeddingWorkItem
      .decode("""{"schemaVersion":"embedding-work-item-v1","runId":" ","snapshot":"s"}""")
      .isLeft shouldBe true
  }

  test("the embedding worker dead-letters a run whose manifest does not exist, without ingesting") {
    val rig = new IngestRig
    val q = newQueue
    val dlq = newQueue
    val rejects = new InMemoryRejectSink
    val publisher = new EmbeddingWorkPublisher(q)
    ok(publisher.publish(EmbeddingWorkItem.of(rig.run.runId, rig.run.snapshot)))
    val c = EmbeddingWorker.consumer(
      q,
      dlq,
      new scholarchain.storage.vectors.ManifestStore(rig.objects, rig.layout),
      rig.ingestor(),
      settings,
      rejects
    )
    ok(c.runOnce()).deadLettered shouldBe 1
    rejects.all.head.reasonCode shouldBe "PERMANENT_FAILURE"
  }

  test("the embedding worker ingests a staged run once; a redelivered message is a no-op repeat") {
    val rig = new IngestRig
    ok(rig.ingestor().stage(rig.run, Iterator(rig.doc(1, "text one"))))
    val q = newQueue
    val dlq = newQueue
    val c = EmbeddingWorker.consumer(
      q,
      dlq,
      new scholarchain.storage.vectors.ManifestStore(rig.objects, rig.layout),
      rig.ingestor(),
      settings,
      new InMemoryRejectSink
    )
    val body = EmbeddingWorkItem.encode(EmbeddingWorkItem.of(rig.run.runId, rig.run.snapshot))
    q.send(body)
    ok(c.runOnce()).acknowledged shouldBe 1
    val after = rig.inner.stored(rig.classic.name)
    after.size shouldBe 1
    q.send(body) // redelivery
    ok(c.runOnce()).acknowledged shouldBe 1
    dlq.size shouldBe 0
    rig.inner.stored(rig.classic.name) shouldBe after
  }

class SqsWorkQueueSpec extends StorageSuite:
  private class Stub extends SqsClient:
    var received = Vector.empty[ReceiveMessageRequest]
    var deleted = Vector.empty[DeleteMessageRequest]
    var sent = Vector.empty[SendMessageRequest]
    def serviceName(): String = "sqs"
    def close(): Unit = ()
    override def receiveMessage(r: ReceiveMessageRequest): ReceiveMessageResponse =
      received :+= r
      ReceiveMessageResponse
        .builder()
        .messages(
          Message
            .builder()
            .messageId("m1")
            .receiptHandle("h1")
            .body("b")
            .attributes(
              java.util.Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "4")
            )
            .build()
        )
        .build()
    override def deleteMessage(r: DeleteMessageRequest): DeleteMessageResponse =
      deleted :+= r
      DeleteMessageResponse.builder().build()
    override def sendMessage(r: SendMessageRequest): SendMessageResponse =
      sent :+= r
      SendMessageResponse.builder().messageId("m9").build()

  private def queue(stub: SqsClient) = new SqsWorkQueue(
    stub,
    "https://sqs.example/queue",
    new Retrier(
      TestEnv.settings.runtime.retry,
      AwsErrors.isTransient,
      new RecordingSleeper,
      MaxJitter
    )
  )

  test("receive asks for the receive count and the visibility timeout, and maps the message") {
    val stub = new Stub
    val ms = ok(queue(stub).receive(5, 60, 10))
    ms shouldBe Vector(QueueMessage("m1", "h1", "b", 4))
    val r = stub.received.head
    (
      r.maxNumberOfMessages.intValue,
      r.visibilityTimeout.intValue,
      r.waitTimeSeconds.intValue
    ) shouldBe (5, 60, 10)
    r.messageSystemAttributeNames should contain(
      MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT
    )
  }

  test("documented limits are checked before any call") {
    val stub = new Stub
    val q = queue(stub)
    err(q.receive(11, 30, 0)) shouldBe a[StorageError.LimitExceeded]
    err(q.receive(1, 43201, 0)) shouldBe a[StorageError.LimitExceeded]
    err(q.send("x" * 1048577)) shouldBe a[StorageError.LimitExceeded]
    err(q.changeVisibility("h", -1)) shouldBe a[StorageError.LimitExceeded]
    stub.received shouldBe empty
    stub.sent shouldBe empty
  }

  test("send and delete use the configured queue URL") {
    val stub = new Stub
    val q = queue(stub)
    ok(q.send("hello")) shouldBe "m9"
    ok(q.delete("h1"))
    stub.sent.head.queueUrl shouldBe "https://sqs.example/queue"
    stub.deleted.head.receiptHandle shouldBe "h1"
  }
