package scholarchain.storage

import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.queue.*
import scholarchain.storage.vectors.*

/** An embedding message is deleted only when every item of its run is in the index. */
class EmbeddingRedeliverySpec extends StorageSuite:

  private val MaxReceives = 3
  private val consumerSettings = ConsumerSettings(
    batchSize = 1,
    visibilityTimeoutSeconds = 30,
    waitSeconds = 0,
    maxReceiveCount = MaxReceives,
    maxMessagesPerRun = 5,
    retryBackoffSeconds = 10
  )

  /** An embedder that records every batch it is asked for and throws while `failing` says so. The
    * port carries no typed failure: a provider error is whatever the embedder throws.
    */
  final private class Provider:
    var calls = Vector.empty[Vector[String]]
    var failing: Int => Option[String] = _ => None
    val embed: Embedder = texts =>
      calls :+= texts.toVector
      failing(calls.size).foreach(m => throw new RuntimeException(m))
      texts.map(t => Array(1f, (math.abs(t.hashCode) % 7).toFloat, 2f, 3f))

  final private class Rig(putBatch: Int = 500):
    var now = 0L
    val provider = new Provider
    val rig = new IngestRig(putBatch = putBatch)
    val queue = new InMemoryWorkQueue(() => now)
    val dlq = new InMemoryWorkQueue(() => now)
    val messages = new InMemoryRejectSink
    val body: String =
      EmbeddingWorkItem.encode(EmbeddingWorkItem.of(rig.run.runId, rig.run.snapshot))
    val manifests = new ManifestStore(rig.objects, rig.layout)

    def stage(input: RenderedDocument*): Unit =
      ok(rig.ingestor().stage(rig.run, input.iterator)): Unit

    def stageDocs(n: Int): Unit = stage((1 to n).map(i => rig.doc(i, s"text $i"))*)

    def consumer(
        index: VectorIndex = rig.index,
        ledger: RunLedger = rig.ports.runLedger
    ): WorkConsumer[EmbeddingWorkItem] =
      val ingestor = new VectorIngestor(
        index,
        rig.defs,
        new DocumentStore(rig.objects, rig.layout),
        manifests,
        rig.objects,
        rig.layout,
        ledger,
        rig.ports.artifacts,
        rig.rejects,
        provider.embed,
        rig.settings,
        new RateLimiter(rig.settings.rateLimitPerSecond, rig.time, rig.time),
        rig.time,
        MaxJitter
      )
      EmbeddingWorker.consumer(
        queue,
        dlq,
        manifests,
        ingestor,
        consumerSettings,
        messages
      )

    /** One receive, after which the retry delay passes. */
    def deliver(c: WorkConsumer[EmbeddingWorkItem]): ConsumerReport =
      val report = ok(c.runOnce())
      now += 60_000
      report

    def stored = rig.inner.stored(rig.classic.name)
    def ledgerStates = ok(rig.ports.runLedger.list(rig.run.runId)).map(_.state)

  private def assertKept(r: Rig, report: ConsumerReport): Unit =
    (report.acknowledged, report.retried, report.deadLettered) shouldBe (0, 1, 0)
    r.queue.pendingBodies shouldBe Vector(r.body)
    r.dlq.size shouldBe 0

  private val providerFailures = Vector(
    "authentication failure" -> "Voyage API: HTTP 401 after 1 attempt(s)",
    "exhausted 429" -> "Voyage API: HTTP 429 after 3 attempt(s)",
    "time-out" -> "Voyage API: the request deadline passed after 3 attempt(s)",
    "server error" -> "Voyage API: HTTP 503 after 3 attempt(s)"
  )

  providerFailures.foreach { (what, error) =>
    test(s"a provider $what does not delete the message and records no success") {
      val r = new Rig()
      r.stageDocs(2)
      r.provider.failing = _ => Some(error)
      r.queue.send(r.body)
      assertKept(r, r.deliver(r.consumer()))
      r.stored shouldBe empty
      r.ledgerStates should not contain LedgerState.SUCCEEDED
      r.rig.rejects.all.map(_.reasonCode) shouldBe Vector("EMBEDDING_FAILED", "EMBEDDING_FAILED")
      r.messages.all.map(_.reasonCode) shouldBe Vector("TRANSIENT_FAILURE")
      r.messages.all.head.detail should include("EMBEDDING_FAILED=2")
    }
  }

  test("a persistent provider failure is redelivered up to maxReceiveCount, then dead-lettered") {
    val r = new Rig()
    r.stageDocs(2)
    r.provider.failing = _ => Some("HTTP 401")
    r.queue.send(r.body)
    val c = r.consumer()
    (1 until MaxReceives).foreach(_ => assertKept(r, r.deliver(c)))
    val last = r.deliver(c)
    (last.acknowledged, last.deadLettered) shouldBe (0, 1)
    r.provider.calls.size shouldBe MaxReceives
    r.queue.size shouldBe 0
    r.dlq.pendingBodies shouldBe Vector(r.body)
    r.messages.all.last.reasonCode shouldBe "RECEIVES_EXHAUSTED"
  }

  test(
    "a successful redelivery deletes the message; written vectors are not re-embedded or duplicated"
  ) {
    val r = new Rig(putBatch = 2)
    r.stageDocs(5)
    r.provider.failing = call => Option.when(call == 2)("HTTP 429")
    r.queue.send(r.body)
    val c = r.consumer()
    assertKept(r, r.deliver(c))
    r.stored.size shouldBe 3 // put batches 1 and 3 were written
    val writtenFirst = r.stored
    val again = r.deliver(c)
    (again.acknowledged, again.retried) shouldBe (1, 0)
    r.queue.size shouldBe 0
    r.dlq.size shouldBe 0
    r.stored.size shouldBe 5
    r.stored.map(_.key).distinct.size shouldBe 5
    r.stored should contain allElementsOf writtenFirst
    // only the failed put batch is embedded a second time
    r.provider.calls.map(_.size) shouldBe Vector(2, 2, 1, 2)
    r.provider.calls.last shouldBe r.provider.calls(1)
  }

  test("a throttled put does not delete the message") {
    val r = new Rig()
    r.stageDocs(2)
    r.rig.index.putFailures = 100
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer()))
    r.messages.all.head.detail should include("PUT_FAILED_AFTER_RETRIES=2")
  }

  test("a refused put (access denied) does not delete the message") {
    val r = new Rig()
    r.stageDocs(2)
    val refusing = new VectorIndex:
      def describe(n: String) = r.rig.inner.describe(n)
      def put(n: String, v: Vector[VectorRecord]) =
        Left(StorageError.Permanent("put", "AccessDenied"))
      def get(n: String, k: Vector[String], d: Boolean) = r.rig.inner.get(n, k, d)
      def query(n: String, q: VectorQuery) = r.rig.inner.query(n, q)
      def retryStats = r.rig.inner.retryStats
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer(index = refusing)))
    r.messages.all.head.detail should include("PUT_REJECTED=2")
    r.ledgerStates shouldBe Vector(LedgerState.FAILED)
  }

  test("an unavailable index does not delete the message") {
    val r = new Rig()
    r.stageDocs(2)
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer(index = new InMemoryVectorIndex(Vector.empty))))
    r.messages.all.head.detail should include("INDEX_UNAVAILABLE=2")
  }

  test("a missing document is redelivered, not acknowledged") {
    val source = new Rig()
    source.stageDocs(2)
    // `r` holds the same manifest but none of the documents it names
    val r = new Rig()
    ok(r.manifests.write(ok(source.manifests.read(source.rig.run.runId))))
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer()))
    r.messages.all.head.detail should include("DOCUMENT_MISSING=2")
  }

  test("content rejects alone dead-letter the message at once, after writing the valid items") {
    val r = new Rig()
    r.stage(
      r.rig.doc(1, "good"),
      r.rig.doc(2, "too many", topics = Vector("t1", "t2", "t3", "t4"))
    )
    r.queue.send(r.body)
    val report = r.deliver(r.consumer())
    (report.acknowledged, report.retried, report.deadLettered) shouldBe (0, 0, 1)
    r.dlq.pendingBodies shouldBe Vector(r.body)
    r.queue.size shouldBe 0
    r.stored.size shouldBe 1
    r.messages.all.map(_.reasonCode) shouldBe Vector("PERMANENT_FAILURE")
    r.messages.all.head.detail should include("TOO_MANY_TOPIC_IDS=1")
    r.provider.calls shouldBe Vector(Vector("good")) // the refused record is never paid for
  }

  test("content rejects together with a provider failure are redelivered") {
    val r = new Rig()
    r.stage(
      r.rig.doc(1, "good"),
      r.rig.doc(2, "too many", topics = Vector("t1", "t2", "t3", "t4"))
    )
    r.provider.failing = _ => Some("HTTP 401")
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer()))
  }

  test("an access failure reading the ledger is redelivered, not dead-lettered on first receive") {
    val r = new Rig()
    r.stageDocs(2)
    val denied = new RunLedger:
      def record(e: RunLedgerEntry) = r.rig.ports.runLedger.record(e)
      def list(runId: String) = Left(StorageError.Permanent("DynamoDB Query", "AccessDenied"))
    r.queue.send(r.body)
    assertKept(r, r.deliver(r.consumer(ledger = denied)))
    r.provider.calls shouldBe empty
  }
