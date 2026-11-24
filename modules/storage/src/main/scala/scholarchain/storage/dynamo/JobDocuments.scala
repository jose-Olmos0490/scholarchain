package scholarchain.storage.dynamo

import java.nio.charset.StandardCharsets
import scholarchain.storage.common.*

/** One asynchronous query job as the storage layer sees it. Storage does not know the service's
  * job model: `document` is the service's own JSON (state, stage, result, stage trace, error). It is
  * opaque data, never interpreted, and never an instruction. Only the fields below take part in
  * keys, conditions and TTL.
  *
  * @param terminal
  *   a terminal job is never changed by [[JobDocumentStore.update]] (only by `restart`)
  * @param createdAtEpochSeconds
  *   supplied by the caller (the store reads no clock); the item expires `ttl` after it
  */
final case class JobDocument(
    jobId: String,
    queryId: String,
    principalId: String,
    terminal: Boolean,
    createdAtEpochSeconds: Long,
    document: ujson.Obj
)

/** Durable store of query jobs, with their result and stage trace inside `document`.
  *
  * Storage design (no new table: the items live in the configured `querySession` table, which
  * already has the `expiresAt` TTL attribute, like the audit items):
  *   - job `pk = job#<jobId>`, `sk = JOB`
  *   - one-job-per-query marker `pk = jobq#<queryId>`, `sk = JOBQUERY`, holding the jobId
  * Both are created in one transaction. Both carry `expiresAt` when a TTL is configured: a job
  * is a disposable operational projection. The durable record of an answer is the audit record and
  * the evidence bundle (neither has a TTL).
  */
trait JobDocumentStore:
  /** One transaction: [[StorageError.AlreadyExists]] (key = jobId or queryId) when either exists. */
  def create(job: JobDocument): Result[Unit]
  def get(jobId: String): Result[Option[JobDocument]]
  def findByQueryId(queryId: String): Result[Option[JobDocument]]

  /** Applies `f` unless the job is terminal. `Right(None)`: unknown job. A terminal job is
    * returned unchanged. The write is conditional on the content read, with a bounded retry, so a
    * late writer can never overwrite a terminal state written by a concurrent one.
    */
  def update(jobId: String)(f: JobDocument => JobDocument): Result[Option[JobDocument]]

  /** Like [[update]] but also for a terminal job (retry of a failed, retryable job). */
  def restart(jobId: String)(f: JobDocument => JobDocument): Result[Option[JobDocument]]

/** @param ttlSeconds
  *   lifetime of the job and its marker counted from `createdAtEpochSeconds`; `None` = never expire
  * @param maxAttempts
  *   bound on the optimistic-concurrency retry of an update
  */
final class DynamoJobDocumentStore(
    items: ItemStore,
    table: String,
    ttlSeconds: Option[Long],
    maxAttempts: Int = 5
) extends JobDocumentStore:
  require(maxAttempts >= 1, "maxAttempts must be >= 1")
  ttlSeconds.foreach(t => require(t > 0, "ttlSeconds must be positive"))

  private val JobSk = "JOB"
  private val MarkerSk = "JOBQUERY"

  private def jobKey(jobId: String): Result[(String, String)] =
    ItemCodec.part("jobId", jobId).map(j => (s"job#$j", JobSk))

  private def markerKey(queryId: String): Result[(String, String)] =
    ItemCodec.part("queryId", queryId).map(q => (s"jobq#$q", MarkerSk))

  private def expires(createdAt: Long): Option[Long] = ttlSeconds.map(createdAt + _)

  private def encode(j: JobDocument): Result[ujson.Obj] =
    for
      key <- jobKey(j.jobId)
      _ <- ItemCodec.key(key._1, key._2)
      _ <- ItemCodec.part("queryId", j.queryId)
      rec = ujson.Obj(
        "jobId" -> j.jobId,
        "queryId" -> j.queryId,
        "principalId" -> j.principalId,
        "terminal" -> j.terminal,
        "createdAtEpochSeconds" -> ujson.Num(j.createdAtEpochSeconds.toDouble),
        "document" -> j.document
      )
      item = ujson.Obj(
        "pk" -> key._1,
        "sk" -> key._2,
        "kind" -> "Job",
        "contentHash" -> Sha256.of(ujson.write(rec)),
        "record" -> rec
      )
      _ = expires(j.createdAtEpochSeconds).foreach(t => item("expiresAt") = ujson.Num(t.toDouble))
      bytes = ujson.write(item).getBytes(StandardCharsets.UTF_8).length.toLong
      _ <-
        if bytes > DynamoItemLimits.MaxItemBytes then
          Left(StorageError.LimitExceeded("job item bytes", DynamoItemLimits.MaxItemBytes, bytes))
        else Right(())
    yield item

  private def decode(item: ujson.Obj): Result[JobDocument] =
    try
      val r = item("record").obj
      Right(
        JobDocument(
          r("jobId").str,
          r("queryId").str,
          r("principalId").str,
          r("terminal").bool,
          r("createdAtEpochSeconds").num.toLong,
          r("document").obj
        )
      )
    catch
      case e: Exception =>
        Left(
          StorageError.InvalidRecord(
            s"stored job ${ItemCodec.keyOf(item)} does not decode: ${e.getMessage}"
          )
        )

  def create(job: JobDocument): Result[Unit] =
    for
      item <- encode(job)
      mk <- markerKey(job.queryId)
      marker = ujson.Obj(
        "pk" -> mk._1,
        "sk" -> mk._2,
        "kind" -> "JobQueryMarker",
        "contentHash" -> Sha256.of(job.jobId),
        "record" -> ujson.Obj("jobId" -> job.jobId)
      )
      _ = expires(job.createdAtEpochSeconds).foreach(t =>
        marker("expiresAt") = ujson.Num(t.toDouble)
      )
      // marker first: a conflict on the query is reported as the query
      _ <- items.putAllIfAbsent(table, Vector(marker, item)).left.map {
        case StorageError.AlreadyExists(k) if k.startsWith("jobq#") =>
          StorageError.AlreadyExists(job.queryId)
        case StorageError.AlreadyExists(_) => StorageError.AlreadyExists(job.jobId)
        case other => other
      }
    yield ()

  def get(jobId: String): Result[Option[JobDocument]] =
    jobKey(jobId).flatMap { (pk, sk) =>
      items.get(table, pk, sk).flatMap {
        case None => Right(None)
        case Some(i) => decode(i).map(Some(_))
      }
    }

  def findByQueryId(queryId: String): Result[Option[JobDocument]] =
    markerKey(queryId).flatMap { (pk, sk) =>
      items.get(table, pk, sk).flatMap {
        case None => Right(None)
        case Some(m) => get(m("record")("jobId").str)
      }
    }

  def update(jobId: String)(f: JobDocument => JobDocument): Result[Option[JobDocument]] =
    change(jobId, allowTerminal = false, f)

  def restart(jobId: String)(f: JobDocument => JobDocument): Result[Option[JobDocument]] =
    change(jobId, allowTerminal = true, f)

  private def change(
      jobId: String,
      allowTerminal: Boolean,
      f: JobDocument => JobDocument
  ): Result[Option[JobDocument]] =
    jobKey(jobId).flatMap { (pk, sk) =>
      def attempt(n: Int): Result[Option[JobDocument]] =
        items.get(table, pk, sk).flatMap {
          case None => Right(None)
          case Some(current) =>
            decode(current).flatMap { before =>
              if before.terminal && !allowTerminal then Right(Some(before))
              else
                val after = f(before)
                if after.jobId != before.jobId || after.queryId != before.queryId ||
                  after.createdAtEpochSeconds != before.createdAtEpochSeconds
                then
                  Left(
                    StorageError
                      .InvalidRecord("an update must not change jobId, queryId or createdAt")
                  )
                else
                  encode(after).flatMap { next =>
                    items.replaceIfHash(table, next, ItemCodec.hashOf(current)).flatMap {
                      case true => Right(Some(after))
                      case false if n < maxAttempts => attempt(n + 1)
                      case false =>
                        Left(
                          StorageError.Transient(
                            s"job update $jobId",
                            n,
                            "concurrent writers kept changing the job"
                          )
                        )
                    }
                  }
            }
        }
      attempt(1)
    }
