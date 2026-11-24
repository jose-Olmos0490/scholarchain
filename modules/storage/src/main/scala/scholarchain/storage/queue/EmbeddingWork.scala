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

object EmbeddingWorker:
  /** Handler: read the run's manifest from S3 and ingest it. The ingestion is idempotent (unchanged
    * vectors are skipped), so a redelivered message repeats safely. A report with
    * `ledgerErrors` is treated as a transient failure so the run is repeated; item-level rejects
    * are already recorded by the ingestor and do not fail the message.
    */
  def handler(
      manifests: ManifestStore,
      ingestor: VectorIngestor
  ): EmbeddingWorkItem => Result[Unit] =
    item =>
      for
        manifest <- manifests.read(item.runId)
        report <- ingestor.ingest(IngestionRun(item.runId, item.snapshot), manifest)
        _ <-
          if report.ledgerErrors.isEmpty then Right(())
          else
            Left(
              StorageError.Transient(
                s"embedding run ${item.runId}",
                1,
                s"ledger errors: ${report.ledgerErrors.take(3).mkString("; ")}"
              )
            )
      yield ()

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
