package scholarchain.services.query

import scholarchain.rag.workflow.StageTrace
import scholarchain.services.error.ErrorCode
import scholarchain.storage.common.StorageError
import scholarchain.storage.dynamo.{JobDocument, JobDocumentStore}

/** The job store failed (or held a record that does not decode). It carries no downstream text:
  * a store message can hold keys or request data. `retryable` is true for an outage and false for
  * a stored record this version cannot read.
  */
final class JobStoreException(val retryable: Boolean)
    extends RuntimeException("the job store failed", null, false, false)

/** The durable [[JobStore]]: the services' job model on top of storage's [[JobDocumentStore]].
  * Storage keeps the job as an opaque document next to the key fields this class derives
  * (`terminal` from the state, `createdAtEpochSeconds` from the creation time).
  *
  * The port has no error channel, so a store failure surfaces as a [[JobStoreException]] that
  * the HTTP layer answers as 503 (outage) or 500. A conflict on `create` is the port's `false`.
  * Several service instances can share one table: updates are conditional on the stored content,
  * and a terminal job is never changed by a late writer.
  */
final class DynamoJobStore(documents: JobDocumentStore) extends JobStore:
  def create(job: JobRecord): Boolean =
    documents.create(JobDocuments.encode(job)) match
      case Right(_) => true
      case Left(_: StorageError.AlreadyExists) => false
      case Left(e) => throw failure(e)

  def get(jobId: String): Option[JobRecord] =
    documents.get(jobId).fold(e => throw failure(e), _.map(JobDocuments.decode))

  def findByQueryId(queryId: String): Option[JobRecord] =
    documents.findByQueryId(queryId).fold(e => throw failure(e), _.map(JobDocuments.decode))

  def update(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] =
    documents
      .update(jobId)(d => JobDocuments.encode(f(JobDocuments.decode(d))))
      .fold(e => throw failure(e), _.map(JobDocuments.decode))

  def restart(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] =
    documents
      .restart(jobId)(d => JobDocuments.encode(f(JobDocuments.decode(d))))
      .fold(e => throw failure(e), _.map(JobDocuments.decode))

  private def failure(e: StorageError): JobStoreException =
    new JobStoreException(retryable = e.retryable)

/** [[JobRecord]] <-> storage [[JobDocument]]. The document is JSON the services own. */
object JobDocuments:
  def encode(j: JobRecord): JobDocument =
    JobDocument(
      jobId = j.jobId,
      queryId = j.queryId,
      principalId = j.principalId,
      terminal = j.state.terminal,
      createdAtEpochSeconds = j.createdAtMillis / 1000L,
      document = toJson(j)
    )

  /** A document that does not decode is a stored record this version cannot read (not an outage). */
  def decode(d: JobDocument): JobRecord =
    try fromJson(d.document)
    catch case scala.util.control.NonFatal(_) => throw new JobStoreException(retryable = false)

  private def opt[A](v: Option[A])(f: A => ujson.Value): ujson.Value = v.fold(ujson.Null)(f)

  private def toJson(j: JobRecord): ujson.Obj = ujson.Obj(
    "jobId" -> j.jobId,
    "queryId" -> j.queryId,
    "principalId" -> j.principalId,
    "traceId" -> j.traceId,
    "state" -> j.state.toString,
    "stage" -> j.stage,
    "createdAtMillis" -> ujson.Num(j.createdAtMillis.toDouble),
    "startedAtMillis" -> opt(j.startedAtMillis)(m => ujson.Num(m.toDouble)),
    "finishedAtMillis" -> opt(j.finishedAtMillis)(m => ujson.Num(m.toDouble)),
    "updatedAtMillis" -> ujson.Num(j.updatedAtMillis.toDouble),
    "attempts" -> j.attempts,
    "error" -> opt(j.error)(errorJson),
    "result" -> j.result.getOrElse(ujson.Null),
    "trace" -> ujson.Arr.from(
      j.trace.map(t =>
        ujson.Obj(
          "traceId" -> t.traceId,
          "stage" -> t.stage,
          "latencyMillis" -> ujson.Num(t.latencyMillis.toDouble),
          "outcome" -> t.outcome
        )
      )
    )
  )

  private def errorJson(e: JobError): ujson.Value = ujson.Obj(
    "code" -> e.code.wire,
    "reason" -> opt(e.reason)(ujson.Str(_)),
    "message" -> e.message,
    "retryable" -> e.retryable,
    "retryAfterSeconds" -> opt(e.retryAfterSeconds)(s => ujson.Num(s.toDouble)),
    "details" -> ujson.Obj.from(e.details)
  )

  private def optional(v: ujson.Value): Option[ujson.Value] = v match
    case ujson.Null => None
    case other => Some(other)

  private def fromJson(o: ujson.Obj): JobRecord =
    val state = JobState.valueOf(o("state").str)
    JobRecord(
      jobId = o("jobId").str,
      queryId = o("queryId").str,
      principalId = o("principalId").str,
      traceId = o("traceId").str,
      state = state,
      stage = o("stage").str,
      createdAtMillis = o("createdAtMillis").num.toLong,
      startedAtMillis = optional(o("startedAtMillis")).map(_.num.toLong),
      finishedAtMillis = optional(o("finishedAtMillis")).map(_.num.toLong),
      updatedAtMillis = o("updatedAtMillis").num.toLong,
      attempts = o("attempts").num.toInt,
      error = optional(o("error")).map(e => errorFrom(e.obj)),
      result = optional(o("result")),
      trace = o("trace").arr.toVector.map(t =>
        StageTrace(
          t("traceId").str,
          t("stage").str,
          t("latencyMillis").num.toLong,
          t("outcome").str
        )
      )
    )

  private def errorFrom(e: ujson.Obj): JobError =
    JobError(
      ErrorCode.fromWire(e("code").str).getOrElse(throw new NoSuchElementException("code")),
      optional(e("reason")).map(_.str),
      e("message").str,
      e("retryable").bool,
      optional(e("retryAfterSeconds")).map(_.num.toLong),
      e("details").obj.toMap
    )
