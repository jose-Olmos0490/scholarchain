package scholarchain.adversarial.services

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.services.error.ErrorCode
import scholarchain.services.query.*
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.dynamo.{DynamoJobDocumentStore, InMemoryItemStore, ItemStore}

/** An item store with hooks to force conditional-write conflicts and outages. */
final class HookedItems(val inner: ItemStore) extends ItemStore:
  @volatile var beforeReplace: Option[(String, ujson.Obj, String) => Unit] = None
  @volatile var alwaysConflict = false
  @volatile var failAll: Option[StorageError] = None
  @volatile var failWrites: Option[StorageError] = None
  val replaces = new AtomicInteger(0)

  private def r[A](body: => Result[A]): Result[A] = failAll.toLeft(()).flatMap(_ => body)
  def putIfAbsent(t: String, i: ujson.Obj) = r(
    failWrites.toLeft(()).flatMap(_ => inner.putIfAbsent(t, i))
  )
  def putAllIfAbsent(t: String, i: Vector[ujson.Obj]) = r(
    failWrites.toLeft(()).flatMap(_ => inner.putAllIfAbsent(t, i))
  )
  def get(t: String, pk: String, sk: String) = r(inner.get(t, pk, sk))
  def replaceIfHash(t: String, i: ujson.Obj, h: String) = r {
    replaces.incrementAndGet()
    beforeReplace.foreach(_(t, i, h))
    if alwaysConflict then Right(false)
    else failWrites.toLeft(()).flatMap(_ => inner.replaceIfHash(t, i, h))
  }
  def query(t: String, pk: String, p: Option[String], asc: Boolean, l: Option[Int]) = r(
    inner.query(t, pk, p, asc, l)
  )

class JobStoreAdvSpec extends AnyFunSuite with Matchers:

  private val Table = "t-query-session"
  private val Marker = "ZZ-JS-MARKER-2"

  private def job(n: Int = 1, q: Option[String] = None) = JobRecord(
    jobId = "job-" + f"$n%032x",
    queryId = q.getOrElse(s"query:$n"),
    principalId = "alice",
    traceId = "trace-1",
    state = JobState.Queued,
    stage = "queued",
    createdAtMillis = 1_700_000_123_456L,
    startedAtMillis = None,
    finishedAtMillis = None,
    updatedAtMillis = 1_700_000_123_456L,
    attempts = 1,
    error = None,
    result = None,
    trace = Vector.empty
  )

  private def mk(items: ItemStore, maxAttempts: Int = 5) =
    new DynamoJobStore(new DynamoJobDocumentStore(items, Table, Some(86400L), maxAttempts))

  private def raises[A](f: => A): JobStoreException =
    try
      f
      fail("expected a JobStoreException")
    catch case e: JobStoreException => e

  test(
    "a competing terminal write between our read and our conditional write wins; the late writer is discarded"
  ) {
    val items = new HookedItems(new InMemoryItemStore)
    val s = mk(items)
    val competitor = mk(items.inner)
    s.create(job())
    var fired = false
    items.beforeReplace = Some { (_, _, _) =>
      if !fired then
        fired = true
        competitor.update(job().jobId)(_.copy(state = JobState.Failed, stage = "workflow"))
    }
    val out = s.update(job().jobId)(_.copy(state = JobState.Succeeded, stage = "done"))
    out.get.state shouldBe JobState.Failed
    s.get(job().jobId).get.state shouldBe JobState.Failed
    s.get(job().jobId).get.stage shouldBe "workflow"
  }

  test("a competing non-terminal write is merged by re-applying the change: no lost update") {
    val items = new HookedItems(new InMemoryItemStore)
    val s = mk(items)
    val competitor = mk(items.inner)
    s.create(job())
    var fired = false
    items.beforeReplace = Some { (_, _, _) =>
      if !fired then
        fired = true
        competitor.update(job().jobId)(_.copy(stage = "competitor"))
    }
    s.update(job().jobId)(j => j.copy(attempts = j.attempts + 10))
    val now = s.get(job().jobId).get
    now.stage shouldBe "competitor"
    now.attempts shouldBe 11
  }

  test(
    "persistent conflicts exhaust the bounded retry: retryable JobStoreException, exact attempt count"
  ) {
    val items = new HookedItems(new InMemoryItemStore)
    val s = mk(items, maxAttempts = 4)
    s.create(job())
    items.alwaysConflict = true
    val e = raises(s.update(job().jobId)(_.copy(stage = "x")))
    e.retryable shouldBe true
    items.replaces.get shouldBe 4
    e.getMessage should not include job().jobId
    s.get(job().jobId).get.stage shouldBe "queued"
  }

  test("sixteen threads incrementing one job lose no update (optimistic concurrency)") {
    val items = new InMemoryItemStore
    val s = mk(items, maxAttempts = 500)
    s.create(job())
    val pool = Executors.newFixedThreadPool(16)
    val go = new CountDownLatch(1)
    try
      val fs = (1 to 16).map(_ =>
        pool.submit(new Runnable {
          def run(): Unit = {
            go.await();
            (1 to 5).foreach(_ => s.update(job().jobId)(j => j.copy(attempts = j.attempts + 1)))
          }
        })
      )
      go.countDown()
      fs.foreach(_.get(60, TimeUnit.SECONDS))
      s.get(job().jobId).get.attempts shouldBe 1 + 16 * 5
    finally pool.shutdownNow()
  }

  test("a terminal job stays terminal under a racing herd of late writers") {
    val items = new InMemoryItemStore
    val s = mk(items, maxAttempts = 500)
    s.create(job())
    val pool = Executors.newFixedThreadPool(8)
    val go = new CountDownLatch(1)
    try
      val fs = (1 to 8).map(i =>
        pool.submit(() => {
          go.await();
          s.update(job().jobId)(
            _.copy(state = if i == 1 then JobState.Failed else JobState.Succeeded, stage = s"w$i")
          )
        })
      )
      go.countDown()
      fs.foreach(_.get(30, TimeUnit.SECONDS))
      val finalState = s.get(job().jobId).get
      finalState.state.terminal shouldBe true
      // whichever terminal write was first is final: no later writer changed the stage
      val again = s.update(job().jobId)(_.copy(state = JobState.Succeeded, stage = "late"))
      again.get.stage shouldBe finalState.stage
    finally pool.shutdownNow()
  }

  test(
    "create is atomic: sixteen racing creators of one job ID or one query ID yield exactly one winner"
  ) {
    val items = new InMemoryItemStore
    val s = mk(items)
    val pool = Executors.newFixedThreadPool(16)
    val go = new CountDownLatch(1)
    try
      val sameId = (1 to 16).map(_ => pool.submit(() => { go.await(); s.create(job(7)) }))
      val sameQuery = (1 to 16).map(i =>
        pool.submit(() => { go.await(); s.create(job(100 + i, Some("query:shared"))) })
      )
      go.countDown()
      sameId.map(_.get(30, TimeUnit.SECONDS)).count(identity) shouldBe 1
      sameQuery.map(_.get(30, TimeUnit.SECONDS)).count(identity) shouldBe 1
    finally pool.shutdownNow()
  }

  test(
    "outage classification: transient and timeout are retryable, a permanent fault is not; no text leaks"
  ) {
    val items = new HookedItems(new InMemoryItemStore)
    val s = mk(items)
    s.create(job())
    Seq[(StorageError, Boolean)](
      StorageError.Transient("dynamo", 3, s"host=$Marker") -> true,
      StorageError.Timeout(s"dynamo $Marker", 1000) -> true,
      StorageError.Permanent("dynamo", s"AccessDenied $Marker") -> false,
      StorageError.Misconfigured(Marker) -> false
    ).foreach { (err, retryable) =>
      items.failAll = Some(err)
      Seq[() => Any](
        () => s.get(job().jobId),
        () => s.findByQueryId("query:1"),
        () => s.update(job().jobId)(identity),
        () => s.restart(job().jobId)(identity),
        () => s.create(job(2))
      ).foreach { op =>
        val e = raises(op())
        withClue(err.toString)(e.retryable shouldBe retryable)
        e.getMessage should not include Marker
        Option(e.getCause) shouldBe None
        e.getStackTrace shouldBe empty // the exception is created without a stack trace
      }
    }
    items.failAll = None
    s.get(job().jobId).get.state shouldBe JobState.Queued
  }

  test("a stored record that does not decode is a non-retryable error that names no content") {
    val items = new InMemoryItemStore
    val s = mk(items)
    s.create(job())
    // corrupt the stored document through the item store: an unknown state
    val key = "job#" + job().jobId
    val cur = items.get(Table, key, "JOB").toOption.get.get
    val bad = ujson.read(ujson.write(cur)).obj
    bad("record")("document")("state") = Marker
    bad("contentHash") = cur("contentHash")
    items.replaceIfHash(Table, bad, cur("contentHash").str) shouldBe Right(true)
    val e = raises(s.get(job().jobId))
    e.retryable shouldBe false
    e.getMessage should not include Marker
    raises(s.findByQueryId("query:1")).retryable shouldBe false
    raises(s.update(job().jobId)(identity)).retryable shouldBe false
  }

  test("an update that rewrites the job ID or the query ID is refused and nothing is stored") {
    val items = new InMemoryItemStore
    val s = mk(items)
    s.create(job())
    raises(s.update(job().jobId)(_.copy(jobId = "job-" + "e" * 32))).retryable shouldBe false
    raises(s.update(job().jobId)(_.copy(queryId = "query:hijack"))).retryable shouldBe false
    s.get(job().jobId).get shouldBe job()
    s.findByQueryId("query:hijack") shouldBe None
  }

  test("an oversized job (result beyond the item limit) is a refusal, never a partial write") {
    val items = new InMemoryItemStore
    val s = mk(items)
    s.create(job())
    val huge = ujson.Obj("blob" -> ("x" * 500_000))
    val e = raises(s.update(job().jobId)(_.copy(state = JobState.Succeeded, result = Some(huge))))
    e.retryable shouldBe false
    s.get(job().jobId).get.state shouldBe JobState.Queued
  }

  test("hostile IDs: separators in a job or query ID cannot reach another partition") {
    val items = new InMemoryItemStore
    val s = mk(items)
    s.create(job())
    Seq("job-1#JOB", "../job-1", "job#x", "", " ", "a" * 4000, "job-\u0000").foreach { id =>
      try s.get(id) shouldBe None
      catch case e: JobStoreException => e.retryable shouldBe false
    }
    Seq("query:1#x", "", "a" * 4000).foreach { q =>
      try s.findByQueryId(q) shouldBe None
      catch case e: JobStoreException => e.retryable shouldBe false
    }
    try s.create(job(9, Some("query:1"))) shouldBe false // an existing query ID is a conflict
    catch case e: JobStoreException => fail("conflict must be a false, not an exception")
  }

  // ---- through the API ---------------------------------------------------------------------------

  private def apiRig(items: HookedItems) =
    new Rig(jobStore = Some(mk(items, 3)), items = new InMemoryItemStore)

  test(
    "API: a job-store outage is 503 JOB_STORE_UNAVAILABLE with Retry-After; a corrupt record is a fixed 500"
  ) {
    val items = new HookedItems(new InMemoryItemStore)
    val r = new Rig(jobStore = Some(mk(items, 3)))
    try
      val ok = r.post(Kit.coauthorsBody())
      ok.status shouldBe 200
      val id = ok.json("jobId").str
      items.failAll = Some(StorageError.Transient("dynamo", 3, Marker))
      val x = r.get(s"/v1/jobs/$id")
      x.status shouldBe 503
      x.reason shouldBe Some("JOB_STORE_UNAVAILABLE")
      x.headers should contain key "retry-after"
      x.raw should not include Marker
      r.post(Kit.coauthorsBody(""","question":"another"""")).status should (be(503) or be(502))
      items.failAll = Some(StorageError.Permanent("dynamo", Marker))
      val y = r.get(s"/v1/jobs/$id")
      y.status shouldBe 500
      y.raw should not include Marker
      items.failAll = None
      r.get(s"/v1/jobs/$id").status shouldBe 200
    finally r.close()
  }

  test("API: a conflict storm on completion never leaves the job RUNNING forever (async)") {
    val items = new HookedItems(new InMemoryItemStore)
    val store = mk(items, 3)
    val r = new Rig(jobStore = Some(store))
    try
      val real = r.engine.success
      // the engine finishes, but the job store refuses the completion write (outage mid-flight)
      r.engine.behavior = req =>
        val out = real(req)
        items.failWrites = Some(StorageError.Transient("dynamo", 3, "outage at completion"))
        out
      val a = r.post(Kit.coauthorsBody(""","mode":"async""""))
      a.status shouldBe 202
      val id = a.json("jobId").str
      Thread.sleep(500) // let the worker finish
      items.failWrites = None // the store recovers
      Thread.sleep(300)
      val st = store.get(id).get.state
      withClue("the job's completion was lost and nothing finishes it: ") {
        st should (be(JobState.Succeeded) or be(JobState.Failed))
      }
    finally r.close()
  }

  test(
    "API: the idempotent replay of a job whose store write failed returns a typed error, not a duplicate run"
  ) {
    val items = new HookedItems(new InMemoryItemStore)
    val r = new Rig(jobStore = Some(mk(items, 3)))
    try
      items.failWrites = Some(StorageError.Transient("dynamo", 3, Marker))
      val a = r.post(Kit.coauthorsBody(), key = Some("idem-key-0009"))
      a.status should (be >= 500 and be < 600)
      a.raw should not include Marker
      items.failWrites = None
      val calls = r.engine.calls.get
      val b = r.post(Kit.coauthorsBody(), key = Some("idem-key-0009"))
      b.status should (be(200) or be(409) or be(202))
      b.status should not be 500
      r.engine.calls.get should be <= calls + 1
    finally r.close()
  }
