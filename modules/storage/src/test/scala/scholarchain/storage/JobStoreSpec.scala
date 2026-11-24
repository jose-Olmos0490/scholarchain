package scholarchain.storage

import java.util.UUID
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*

/** Contract of [[JobDocumentStore]]; run against the fake and (gated) against DynamoDB. */
abstract class JobDocumentStoreContract extends PortContract:
  protected def jobs: JobDocumentStore

  protected def newJob(
      state: String = "Queued",
      terminal: Boolean = false,
      query: String = Fresh.queryId()
  ): JobDocument =
    JobDocument(
      s"job-${UUID.randomUUID}",
      query,
      "principal-1",
      terminal,
      1_700_000_000L,
      ujson.Obj("state" -> state, "trace" -> ujson.Arr(), "result" -> ujson.Null)
    )

  private def withState(j: JobDocument, state: String, terminal: Boolean): JobDocument =
    j.copy(
      terminal = terminal,
      document = ujson.Obj.from(j.document.value ++ Map("state" -> ujson.Str(state)))
    )

  contract("jobs: create once; get by id and by queryId round-trip the document") {
    val j = newJob()
    ok(jobs.create(j))
    ok(jobs.get(j.jobId)) shouldBe Some(j)
    ok(jobs.findByQueryId(j.queryId)) shouldBe Some(j)
    ok(jobs.get(s"job-${UUID.randomUUID}")) shouldBe None
    ok(jobs.findByQueryId(Fresh.queryId())) shouldBe None
  }

  contract(
    "jobs: a second job for the same job id or the same query id is refused, nothing changes"
  ) {
    val j = newJob()
    ok(jobs.create(j))
    err(jobs.create(j.copy(principalId = "other"))) shouldBe a[StorageError.AlreadyExists]
    val sameQuery = newJob(query = j.queryId)
    err(jobs.create(sameQuery)) shouldBe StorageError.AlreadyExists(j.queryId)
    ok(jobs.get(sameQuery.jobId)) shouldBe None // all or nothing
    ok(jobs.get(j.jobId)) shouldBe Some(j)
  }

  contract("jobs: update applies to a live job and never to a terminal one") {
    val j = newJob()
    ok(jobs.create(j))
    val running = ok(jobs.update(j.jobId)(withState(_, "Running", terminal = false))).get
    running.document("state").str shouldBe "Running"
    val done = ok(jobs.update(j.jobId)(withState(_, "Succeeded", terminal = true))).get
    done.terminal shouldBe true
    // a late writer (a worker that finished after the caller timed out) changes nothing
    val late = ok(jobs.update(j.jobId)(withState(_, "Failed", terminal = true))).get
    late shouldBe done
    ok(jobs.get(j.jobId)).get.document("state").str shouldBe "Succeeded"
    ok(jobs.update(s"job-${UUID.randomUUID}")(identity)) shouldBe None
  }

  contract("jobs: restart reopens a terminal job") {
    val j = newJob(state = "Failed", terminal = true)
    ok(jobs.create(j))
    ok(jobs.update(j.jobId)(withState(_, "Running", terminal = false))).get.terminal shouldBe true
    val again = ok(jobs.restart(j.jobId)(withState(_, "Queued", terminal = false))).get
    again.terminal shouldBe false
    ok(jobs.get(j.jobId)).get.document("state").str shouldBe "Queued"
  }

  contract("jobs: an update may not move a job to another query or principal key") {
    val j = newJob()
    ok(jobs.create(j))
    err(jobs.update(j.jobId)(_.copy(queryId = Fresh.queryId()))) shouldBe a[
      StorageError.InvalidRecord
    ]
    ok(jobs.get(j.jobId)) shouldBe Some(j)
  }

class JobDocumentStoreFakeSpec extends JobDocumentStoreContract:
  protected val jobs: JobDocumentStore =
    new DynamoJobDocumentStore(new InMemoryItemStore, "jobs-table", Some(3600L))

/** Design facts that hold only for the DynamoDB layout. */
class JobDocumentStoreDesignSpec extends StorageSuite:
  private val table = FakeDynamo.tables.querySession

  private def job(q: String = Fresh.queryId(), terminal: Boolean = false) =
    JobDocument(
      s"job-${UUID.randomUUID}",
      q,
      "p",
      terminal,
      1_700_000_000L,
      ujson.Obj("state" -> "Queued")
    )

  test("job and marker items live in the configured table, with TTL only when configured") {
    val items = new InMemoryItemStore
    val j = job()
    ok(new DynamoJobDocumentStore(items, table, Some(100L)).create(j))
    val rows = items.items(table)
    rows.map(_("kind").str).toSet shouldBe Set("Job", "JobQueryMarker")
    rows.map(_("pk").str).toSet shouldBe Set(s"job#${j.jobId}", s"jobq#${j.queryId}")
    rows.foreach(_("expiresAt").num.toLong shouldBe 1_700_000_100L)

    val keep = new InMemoryItemStore
    ok(new DynamoJobDocumentStore(keep, table, None).create(job()))
    keep.items(table).foreach(_.value.contains("expiresAt") shouldBe false)
  }

  test("a lost race is retried against the fresh content; a terminal winner is never overwritten") {
    val inner = new InMemoryItemStore
    val store = new DynamoJobDocumentStore(inner, table, None)
    val j = job()
    ok(store.create(j))
    var raced = false
    // a competing worker finishes the job right after our read
    val racing = new ItemStore:
      def putIfAbsent(t: String, i: ujson.Obj) = inner.putIfAbsent(t, i)
      def putAllIfAbsent(t: String, is: Vector[ujson.Obj]) = inner.putAllIfAbsent(t, is)
      def get(t: String, pk: String, sk: String) =
        val r = inner.get(t, pk, sk)
        if !raced && pk.startsWith("job#") then
          raced = true
          ok(
            store.update(j.jobId)(d =>
              d.copy(terminal = true, document = ujson.Obj("state" -> "Failed"))
            )
          )
        r
      def replaceIfHash(t: String, i: ujson.Obj, h: String) = inner.replaceIfHash(t, i, h)
      def query(t: String, pk: String, p: Option[String], asc: Boolean, l: Option[Int]) =
        inner.query(t, pk, p, asc, l)
    val loser = new DynamoJobDocumentStore(racing, table, None)
    val seen = ok(
      loser.update(j.jobId)(d =>
        d.copy(terminal = true, document = ujson.Obj("state" -> "Succeeded"))
      )
    ).get
    seen.document("state").str shouldBe "Failed"
    ok(store.get(j.jobId)).get.document("state").str shouldBe "Failed"
  }

  test("endless contention ends in a bounded transient failure") {
    val inner = new InMemoryItemStore
    val j = job()
    ok(new DynamoJobDocumentStore(inner, table, None).create(j))
    var replaces = 0
    val hostile = new ItemStore:
      def putIfAbsent(t: String, i: ujson.Obj) = inner.putIfAbsent(t, i)
      def putAllIfAbsent(t: String, is: Vector[ujson.Obj]) = inner.putAllIfAbsent(t, is)
      def get(t: String, pk: String, sk: String) = inner.get(t, pk, sk)
      def replaceIfHash(t: String, i: ujson.Obj, h: String) =
        replaces += 1
        Right(false)
      def query(t: String, pk: String, p: Option[String], asc: Boolean, l: Option[Int]) =
        inner.query(t, pk, p, asc, l)
    val e = err(
      new DynamoJobDocumentStore(hostile, table, None, maxAttempts = 3).update(j.jobId)(identity)
    )
    e shouldBe a[StorageError.Transient]
    e.retryable shouldBe true
    replaces shouldBe 3
  }

  test("an item beyond the DynamoDB item size is refused before any write") {
    val big = job().copy(document = ujson.Obj("result" -> ("x" * 410000)))
    val items = new InMemoryItemStore
    err(new DynamoJobDocumentStore(items, table, None).create(big)) shouldBe a[
      StorageError.LimitExceeded
    ]
    items.items(table) shouldBe empty
  }

  test("a blank or '#' key part is refused") {
    val s = new DynamoJobDocumentStore(new InMemoryItemStore, table, None)
    err(s.create(job().copy(jobId = "a#b"))) shouldBe a[StorageError.InvalidRecord]
    err(s.create(job(q = " "))) shouldBe a[StorageError.InvalidRecord]
  }

  test("replaceIfHash writes only on a matching hash") {
    val items = new InMemoryItemStore
    val a = ujson.Obj("pk" -> "p", "sk" -> "s", "contentHash" -> "h1", "v" -> 1)
    ok(items.putIfAbsent("t", a))
    ok(
      items.replaceIfHash("t", ujson.Obj("pk" -> "p", "sk" -> "s", "contentHash" -> "h2"), "wrong")
    ) shouldBe false
    ok(
      items.replaceIfHash("t", ujson.Obj("pk" -> "p", "sk" -> "s", "contentHash" -> "h2"), "h1")
    ) shouldBe true
    ok(
      items.replaceIfHash("t", ujson.Obj("pk" -> "p", "sk" -> "x", "contentHash" -> "h2"), "h1")
    ) shouldBe false
    ok(items.get("t", "p", "s")).get("contentHash").str shouldBe "h2"
  }
