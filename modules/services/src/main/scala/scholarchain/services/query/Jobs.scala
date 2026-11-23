package scholarchain.services.query

import scala.collection.mutable
import scholarchain.rag.workflow.StageTrace
import scholarchain.services.error.{ApiError, ErrorCode}

enum JobState:
  case Queued, Running, Succeeded, Failed

  def terminal: Boolean = this == Succeeded || this == Failed

/** The error a failed job keeps. It holds the typed fields only, never a message from a downstream. */
final case class JobError(
    code: ErrorCode,
    reason: Option[String],
    message: String,
    retryable: Boolean,
    retryAfterSeconds: Option[Long],
    details: Map[String, ujson.Value]
):
  def toApi: ApiError = ApiError(code, message, retryable, reason, retryAfterSeconds, details)

object JobError:
  def of(e: ApiError): JobError =
    JobError(e.code, e.reason, e.message, e.retryable, e.retryAfterSeconds, e.details)

/** One query's job. `result` is the answer document of a succeeded job; `trace` holds the stage
  * latencies the workflow reported (no model reasoning, no prompt).
  */
final case class JobRecord(
    jobId: String,
    queryId: String,
    principalId: String,
    traceId: String,
    state: JobState,
    stage: String,
    createdAtMillis: Long,
    startedAtMillis: Option[Long],
    finishedAtMillis: Option[Long],
    updatedAtMillis: Long,
    attempts: Int,
    error: Option[JobError],
    result: Option[ujson.Value],
    trace: Vector[StageTrace]
)

/** The job store port. A terminal job is never changed by a late writer, so a worker that
  * finishes after its caller timed out cannot overwrite the recorded failure.
  *
  * Note: [[InMemoryJobStore]] is per process. A deployment with more than one instance needs a
  * durable adapter (storage module).
  */
trait JobStore:
  def create(job: JobRecord): Boolean
  def get(jobId: String): Option[JobRecord]
  def findByQueryId(queryId: String): Option[JobRecord]

  /** Applies `f` unless the job is terminal (or unknown). Returns the stored record. */
  def update(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord]

  /** Applies `f` even to a terminal job: a retry of a failed, retryable job. */
  def restart(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord]

final class InMemoryJobStore extends JobStore:
  private val byId = mutable.LinkedHashMap.empty[String, JobRecord]

  def create(job: JobRecord): Boolean = synchronized {
    if byId.contains(job.jobId) || byId.values.exists(_.queryId == job.queryId) then false
    else
      byId.put(job.jobId, job)
      true
  }

  def get(jobId: String): Option[JobRecord] = synchronized(byId.get(jobId))

  /** Every job, oldest first (diagnostics and tests). */
  def all: Vector[JobRecord] = synchronized(byId.values.toVector)

  def findByQueryId(queryId: String): Option[JobRecord] = synchronized(
    byId.values.find(_.queryId == queryId)
  )

  def update(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] = synchronized {
    byId.get(jobId).map { j =>
      if j.state.terminal then j
      else
        val n = f(j)
        byId.put(jobId, n)
        n
    }
  }

  def restart(jobId: String)(f: JobRecord => JobRecord): Option[JobRecord] = synchronized {
    byId.get(jobId).map { j =>
      val n = f(j)
      byId.put(jobId, n)
      n
    }
  }
