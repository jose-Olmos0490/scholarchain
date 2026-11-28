package scholarchain.storage.queue

import scholarchain.storage.common.*
import scholarchain.storage.vectors.*
import upickle.default.*

/** The body of an embedding work message: a pointer to an immutable ingestion manifest in ordinary
  * S3. No text, no vectors.
  */
final case class EmbeddingWorkItem(schemaVersion: String, runId: String, snapshot: String)
    derives ReadWriter

object EmbeddingWorkItem:
  val Version = "embedding-work-item-v1"

  def of(runId: String, snapshot: String): EmbeddingWorkItem =
    EmbeddingWorkItem(Version, runId, snapshot)

  def encode(w: EmbeddingWorkItem): String = write(w)

  def decode(body: String): Either[String, EmbeddingWorkItem] =
    try
      val w = read[EmbeddingWorkItem](body)
      if w.schemaVersion != Version then Left(s"unknown schemaVersion '${w.schemaVersion}'")
      else if w.runId.trim.isEmpty || w.snapshot.trim.isEmpty then
        Left("runId and snapshot are required")
      else Right(w)
    catch case e: Exception => Left(s"not an embedding work item: ${e.getMessage.take(200)}")

/** Produces work: one message per staged ingestion run. */
final class EmbeddingWorkPublisher(queue: WorkQueue):
  def publish(item: EmbeddingWorkItem): Result[String] = queue.send(EmbeddingWorkItem.encode(item))

/** An embedding message is acknowledged (deleted) only when every manifest item is in the index
  * (written now, or already there) and the run ledger recorded the writes.
  *
  *   - Anything a later delivery may fix is a retryable error: the [[WorkConsumer]] leaves the
  *     message for redelivery and dead-letters it on its last allowed receive. This covers embedder
  *     failures (the embedder port only throws, so authentication, throttling, time-outs and 5xx all
  *     count), index and put failures, missing or unreadable documents, ledger errors and failures
  *     to read the manifest or ledger (access denied included).
  *   - A run whose only rejects are in [[EmbeddingWorker.ContentRejectCodes]] is a permanent error:
  *     repeating cannot change the content, so the message is dead-lettered at once, like other
  *     poison. The other items of the run are already written.
  *   - An absent or malformed manifest and a malformed run ID are permanent too.
  *
  * A reject code that is not in `ContentRejectCodes` is redelivered, so a new code is never dropped.
  */
object EmbeddingWorker:
  /** Reject codes decided by the manifest, document or metadata content alone. */
  val ContentRejectCodes: Set[String] = Set(
    "REJECTED_KEY_COLLISION",
    "INVALID_KEY_COMPONENT",
    "INVALID_KEY",
    "DOCUMENT_HASH_MISMATCH",
    "DOCUMENT_MISMATCH",
    "MISSING_METADATA",
    "MALFORMED_METADATA",
    "SCHEMA_VERSION_MISMATCH",
    "TOO_MANY_TOPIC_IDS",
    "TOO_MANY_INSTITUTION_IDS",
    "TOO_MANY_METADATA_KEYS",
    "FILTERABLE_VALUE_TOO_LARGE",
    "FILTERABLE_METADATA_TOO_LARGE",
    "NON_FILTERABLE_FIELD_TOO_LARGE",
    "METADATA_TOO_LARGE"
  )

  /** Handler: read the run's manifest from S3 and ingest it. The ingestion is idempotent (vectors
    * already written are skipped before the embed step), so a redelivered message repeats safely.
    */
  def handler(
      manifests: ManifestStore,
      ingestor: VectorIngestor
  ): EmbeddingWorkItem => Result[Unit] =
    item =>
      val operation = s"embedding run ${item.runId}"
      val ingested =
        for
          manifest <- manifests.read(item.runId)
          report <- ingestor.ingest(IngestionRun(item.runId, item.snapshot), manifest)
        yield report
      ingested.left.map(unreadable(operation, _)).flatMap(outcome(operation, _))

  /** A failed manifest or ledger read: permanent only when the data itself is absent or bad. */
  private def unreadable(operation: String, e: StorageError): StorageError = e match
    case _: StorageError.NotFound | _: StorageError.InvalidManifest |
        _: StorageError.InvalidRecord =>
      e
    case other if other.retryable => other
    case other => StorageError.Transient(operation, 1, other.message)

  /** The error carries counts by reason code only; the details are in the rejects output. */
  private def outcome(operation: String, report: IngestionReport): Result[Unit] =
    val codes = report.results
      .filter(r => r.status == ItemStatus.Rejected || r.status == ItemStatus.RejectedKeyCollision)
      .map(r => r.reasonCode.getOrElse(r.status.code))
    def summary =
      val counts = codes.groupBy(identity).toVector.map((c, all) => s"$c=${all.size}").sorted
      s"${codes.size} reject(s): ${counts.mkString(", ")}"
    if report.ledgerErrors.nonEmpty then
      Left(
        StorageError.Transient(
          operation,
          1,
          s"ledger errors: ${report.ledgerErrors.take(3).mkString("; ")}"
        )
      )
    else if codes.isEmpty then Right(())
    else if codes.forall(ContentRejectCodes.contains) then
      Left(StorageError.Permanent(operation, summary))
    else Left(StorageError.Transient(operation, 1, summary))

  def consumer(
      queue: WorkQueue,
      deadLetter: WorkQueue,
      manifests: ManifestStore,
      ingestor: VectorIngestor,
      settings: ConsumerSettings,
      rejects: RejectSink
  ): WorkConsumer[EmbeddingWorkItem] =
    new WorkConsumer(
      queue,
      deadLetter,
      EmbeddingWorkItem.decode,
      handler(manifests, ingestor),
      settings,
      rejects
    )
