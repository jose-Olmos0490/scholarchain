package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scholarchain.rag.workflow.StageTrace
import scholarchain.services.error.ErrorCode
import scholarchain.services.query.*
import scholarchain.storage.common.{Result, StorageError}
import scholarchain.storage.dynamo.{DynamoJobDocumentStore, InMemoryItemStore, ItemStore}

/** An item store that can be told to fail, with a message that must never be repeated. */
final class FailingItems(inner: ItemStore) extends ItemStore:
  @volatile var failure: Option[StorageError] = None
  val writes = new AtomicInteger(0)
  private def gate[A](body: => Result[A]): Result[A] = failure.toLeft(()).flatMap(_ => body)
  def putIfAbsent(t: String, i: ujson.Obj) = gate(inner.putIfAbsent(t, i))
  def putAllIfAbsent(t: String, i: Vector[ujson.Obj]) = gate(inner.putAllIfAbsent(t, i))
  def get(t: String, pk: String, sk: String) = gate(inner.get(t, pk, sk))
  def replaceIfHash(t: String, i: ujson.Obj, h: String) =
    writes.incrementAndGet()
    gate(inner.replaceIfHash(t, i, h))
  def query(t: String, pk: String, p: Option[String], asc: Boolean, l: Option[Int]) =
    gate(inner.query(t, pk, p, asc, l))

class DynamoJobStoreSpec extends ApiSuite:
  private val table = "t-query-session"

  private def job(id: String = "job-" + "a" * 32, q: String = "query:1") = JobRecord(
    jobId = id,
    queryId = q,
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

  private def store(items: ItemStore = new InMemoryItemStore) =
    new DynamoJobStore(new DynamoJobDocumentStore(items, table, Some(86400L)))

  test("a job round-trips with every field, including error, result and stage trace") {
    val s = store()
    val failed = job().copy(
      state = JobState.Failed,
      stage = "workflow",
      startedAtMillis = Some(1_700_000_124_000L),
      finishedAtMillis = Some(1_700_000_125_000L),
      updatedAtMillis = 1_700_000_125_000L,
      attempts = 2,
      error = Some(
        JobError(
          ErrorCode.GatewayTimeout,
          Some("STAGE_TIMEOUT"),
          "a workflow stage timed out",
          retryable = true,
          Some(3L),
          Map("stage" -> ujson.Str("synthesis"))
        )
      ),
      result = Some(ujson.Obj("answer" -> ujson.Obj("summary" -> "x"))),
      trace =
        Vector(StageTrace("trace-1", "plan", 12L, "ok"), StageTrace("trace-1", "x", 4L, "TIMEOUT"))
    )
    s.create(failed) shouldBe true
    s.get(failed.jobId) shouldBe Some(failed)
    s.findByQueryId("query:1") shouldBe Some(failed)
    s.get("job-" + "b" * 32) shouldBe None
    s.findByQueryId("query:none") shouldBe None
  }

  test("a second job for the same job ID or query ID is a conflict, not an exception") {
    val s = store()
    s.create(job()) shouldBe true
    s.create(job()) shouldBe false
    s.create(job(id = "job-" + "c" * 32)) shouldBe false // same query
    s.create(job(id = "job-" + "d" * 32, q = "query:2")) shouldBe true
  }

  test("update changes a running job, and a terminal job is never changed by a late writer") {
    val s = store()
    s.create(job())
    val running = s.update(job().jobId)(_.copy(state = JobState.Running, stage = "workflow")).get
    running.state shouldBe JobState.Running
    val done = s.update(job().jobId)(_.copy(state = JobState.Failed, stage = "workflow")).get
    done.state shouldBe JobState.Failed
    // the late success must not overwrite the recorded failure
    val late = s.update(job().jobId)(_.copy(state = JobState.Succeeded, stage = "done")).get
    late.state shouldBe JobState.Failed
    s.get(job().jobId).get.state shouldBe JobState.Failed
    s.update("job-" + "e" * 32)(identity) shouldBe None
  }

  test("restart changes a failed job back to queued") {
    val s = store()
    s.create(job())
    s.update(job().jobId)(_.copy(state = JobState.Failed))
    val again = s.restart(job().jobId)(_.copy(state = JobState.Queued, attempts = 2)).get
    again.state shouldBe JobState.Queued
    again.attempts shouldBe 2
    // and it is a live job again, so an update applies
    s.update(job().jobId)(_.copy(state = JobState.Running)).get.state shouldBe JobState.Running
  }

  test("two stores over one table see each other's jobs (several service instances)") {
    val items = new InMemoryItemStore
    val a = store(items)
    val b = store(items)
    a.create(job()) shouldBe true
    b.get(job().jobId) shouldBe Some(job())
    b.create(job()) shouldBe false
    b.update(job().jobId)(_.copy(state = JobState.Succeeded, result = Some(ujson.Obj("k" -> 1))))
    a.get(job().jobId).get.state shouldBe JobState.Succeeded
  }

  test("a store outage is a retryable JobStoreException with no downstream text") {
    val items = new FailingItems(new InMemoryItemStore)
    val s = store(items)
    s.create(job())
    items.failure =
      Some(StorageError.Transient("DynamoDB GetItem secret-table", 3, "password=hunter2"))
    val e = intercept[JobStoreException](s.get(job().jobId))
    e.retryable shouldBe true
    e.getMessage should not include "hunter2"
    e.getMessage should not include "secret-table"
    intercept[JobStoreException](
      s.create(job(id = "job-" + "f" * 32, q = "query:9"))
    ).retryable shouldBe true
    intercept[JobStoreException](s.update(job().jobId)(identity)).retryable shouldBe true
  }

  test("a stored record this version cannot read is a non-retryable failure") {
    val items = new InMemoryItemStore
    val docs = new DynamoJobDocumentStore(items, table, None)
    docs.create(
      scholarchain.storage.dynamo.JobDocument(
        "job-" + "a" * 32,
        "query:1",
        "alice",
        terminal = false,
        1_700_000_123L,
        ujson.Obj("unexpected" -> "shape")
      )
    ) shouldBe Right(())
    val e = intercept[JobStoreException](new DynamoJobStore(docs).get("job-" + "a" * 32))
    e.retryable shouldBe false
  }

  test("through the API: a job survives a new service instance over the same table") {
    val items = new InMemoryItemStore
    val first = new Harness(jobStoreOverride = Some(store(items)))
    val queryId =
      try
        val r = first.post(Bodies.coauthors())
        r.status shouldBe 200
        r.json("queryId").str
      finally first.close()
    // a second instance (fresh engine, fresh executor) answers the job and the explain call
    val second = new Harness(jobStoreOverride = Some(store(items)))
    try
      val jobId = store(items).findByQueryId(queryId).get.jobId
      val j = second.get(s"/v1/jobs/$jobId")
      j.status shouldBe 200
      j.json("state").str shouldBe "SUCCEEDED"
      second.get(s"/v1/jobs/$jobId/result").json("queryId").str shouldBe queryId
    finally second.close()
  }

  test("through the API: a job-store outage is 503 and retryable, never 500, and leaks nothing") {
    val items = new FailingItems(new InMemoryItemStore)
    withHarness(new Harness(jobStoreOverride = Some(store(items)))) { h =>
      items.failure = Some(StorageError.Transient("DynamoDB GetItem", 3, "password=hunter2"))
      val r = h.get("/v1/jobs/job-" + "a" * 32)
      r.status shouldBe 503
      r.err("reason").str shouldBe "JOB_STORE_UNAVAILABLE"
      r.err("retryable").bool shouldBe true
      r.headers("retry-after") shouldBe "1"
      r.raw should not include "hunter2"
      r.traceId should not be empty
    }
  }
