package scholarchain.storage.loader

import scholarchain.core.model.{LoadTarget, ResearcherMetrics}
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.SkippedTable
import scholarchain.storage.manifest.*

final case class DynamoLoadReport(
    runId: String,
    created: Long,
    unchanged: Long,
    rejected: Long,
    chunks: Int,
    skippedTables: Vector[SkippedTable]
)

/** Loads the DynamoDB-target Parquet tables.
  *
  * Only `researcher_collaboration` (ResearcherMetrics) is loaded here: the
  * `researcherMetricCache` table serves "metrics by researcherId and window". The tables of the
  * graph read model (`researcher`, `coauthored_with_support`, `components`, `community_membership`,
  * `cross_community_edge`) belong to `GraphReadModelLoader`. Every other table in the DynamoDB
  * manifest has no table and no access pattern defined. All of them are reported as skipped with
  * their reason, never dropped silently.
  *
  * Items are written with conditional puts, one item per request. `BatchWriteItem` cannot carry a
  * condition expression on a put (DynamoDB API reference), so it would break "writes are
  * conditional" and could overwrite. The configured batch size (`batch.dynamoBatchWrite`, at most
  * the BatchWriteItem maximum of 25) is the chunk the loader works in: rejects are recorded and
  * progress is counted per chunk. Transient failures are retried inside the item store adapter.
  *
  * Idempotent: an existing item with identical content is `unchanged`; different content is a
  * recorded reject (`CONTENT_CONFLICT`) and the existing item stays; an undecodable row is a
  * recorded reject (`ROW_DECODE`).
  */
final class DynamoLoader(
    cache: Option[ResearcherMetricCache],
    chunkSize: Int,
    rejects: RejectSink
):
  private val MetricsTable = "researcher_collaboration"

  def load(
      manifest: scholarchain.core.model.LoadManifest,
      stager: FileStager
  ): Result[DynamoLoadReport] =
    for
      store <- cache.toRight(
        StorageError.Misconfigured("dynamodb.tables.researcherMetricCache is not configured")
      )
      verified <- LoadManifests.verify(manifest, LoadTarget.DYNAMODB, stager)
      report <- loadMetrics(store, verified)
    yield report

  private def loadMetrics(
      store: ResearcherMetricCache,
      verified: VerifiedManifest
  ): Result[DynamoLoadReport] =
    val runId = verified.manifest.runId
    var created = 0L
    var unchanged = 0L
    var rejected = 0L
    var chunks = 0
    var failure: Option[StorageError] = None
    def reject(key: String, code: String, detail: String): Unit =
      rejected += 1
      rejects.record(StoreReject("dynamodb", key, code, detail, runId))

    val rows = TableRows.stream[ResearcherMetrics](verified, MetricsTable).grouped(chunkSize)
    while failure.isEmpty && rows.hasNext do
      chunks += 1
      val chunk = rows.next().iterator
      while failure.isEmpty && chunk.hasNext do
        val sr = chunk.next()
        sr.value match
          case Left(f) => reject(s"${f.file}#${f.rowIndex}", "ROW_DECODE", f.reason)
          case Right(m) =>
            store.put(m) match
              case Right(CreateOutcome.Created) => created += 1
              case Right(CreateOutcome.Unchanged) => unchanged += 1
              case Left(c: StorageError.ConflictingContent) =>
                reject(m.metricsId, "CONTENT_CONFLICT", c.message)
              case Left(i: StorageError.InvalidRecord) =>
                reject(m.metricsId, "INVALID_RECORD", i.message)
              case Left(other) => failure = Some(other)

    failure match
      case Some(e) => Left(e)
      case None =>
        val readModel = Set(
          "researcher",
          "coauthored_with_support",
          "components",
          "community_membership",
          "cross_community_edge"
        )
        val skipped = verified.manifest.tables
          .filterNot(_.tableName == MetricsTable)
          .map(t =>
            SkippedTable(
              t.tableName,
              if readModel.contains(t.tableName) then
                "loaded into the graph read model table by GraphReadModelLoader"
              else "no DynamoDB table or access pattern is defined for this table"
            )
          )
        Right(DynamoLoadReport(runId, created, unchanged, rejected, chunks, skipped))
