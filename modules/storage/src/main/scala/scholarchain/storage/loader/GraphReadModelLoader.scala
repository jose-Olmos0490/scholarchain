package scholarchain.storage.loader

import scholarchain.core.model.*
import scholarchain.core.validation.RecordContract
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.SkippedTable
import scholarchain.storage.manifest.*
import upickle.default.ReadWriter

/** What one table of the manifest contributed. `rows` counts the Parquet rows read; `created` and
  * `unchanged` count items (a researcher row yields one item per indexed name, a cross-community
  * edge four). `rejected` counts rows.
  */
final case class ReadModelTableReport(
    table: String,
    rows: Long,
    created: Long,
    unchanged: Long,
    rejected: Long
)

final case class ReadModelLoadReport(
    runId: String,
    tables: Vector[ReadModelTableReport],
    chunks: Int,
    skippedTables: Vector[SkippedTable],
    /** Whether connectivity markers were written, which is what makes the stored cross-community
      * counts readable. `false` carries the reason.
      */
    connectivityStored: Boolean,
    connectivityNote: Option[String]
):
  def rejected: Long = tables.map(_.rejected).sum

/** Loads the graph read model table (see `GraphReadModel.scala` for the key design) from the
  * Parquet tables of a verified Spark load manifest:
  *
  * {{{
  *   researcher               -> researcher directory
  *   coauthored_with_support  -> edge support
  *   components               -> component membership
  *   community_membership     -> community membership (+ connectivity markers)
  *   cross_community_edge     -> cross-community edges and community links
  * }}}
  *
  * Any other table of the manifest is reported as skipped, never dropped silently. A table that the
  * manifest does not list is simply absent from the report. The Spark DynamoDB manifest lists the
  * first four; `cross_community_edge` is in the Neptune and S3 manifests, so connectivity needs a
  * manifest of `expectedTarget` that lists it (see the report's `connectivityNote`).
  *
  * Writes are conditional puts, one item per request: `BatchWriteItem` cannot carry a condition
  * (https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html). The
  * chunk size (`batch.dynamoBatchWrite`, at most 25) is the unit of progress counting. Transient
  * failures are retried by the item store adapter and surface here only when they survived.
  *
  * Idempotent: an item that exists with identical content is `unchanged`; different content is a
  * recorded `CONTENT_CONFLICT` reject and the stored item stays; an undecodable or invalid row is a
  * recorded reject. Cross-community edges are stored before connectivity markers, and markers are
  * withheld if any cross-community edge was rejected, so a count is never readable over an
  * incomplete edge set.
  */
final class GraphReadModelLoader(
    items: ItemStore,
    table: String,
    chunkSize: Int,
    rejects: RejectSink,
    expectedTarget: LoadTarget = LoadTarget.DYNAMODB
):
  private val Handled = Vector(
    "researcher",
    "coauthored_with_support",
    "components",
    "cross_community_edge",
    "community_membership"
  )

  def load(manifest: LoadManifest, stager: FileStager): Result[ReadModelLoadReport] =
    if chunkSize < 1 then
      Left(StorageError.Misconfigured(s"chunk size must be >= 1, got $chunkSize"))
    else
      LoadManifests.verify(manifest, expectedTarget, stager).flatMap { verified =>
        val run = verified.manifest.runId
        val base = verified.manifest.provenance
        var chunks = 0
        def has(name: String) = verified.table(name).isDefined

        def stream[A: ReadWriter: RecordContract](name: String)(
            build: A => Result[Vector[ujson.Obj]]
        )(keyOf: A => String): Result[Option[ReadModelTableReport]] =
          if !has(name) then Right(None)
          else
            var rows = 0L
            var created = 0L
            var unchanged = 0L
            var rejected = 0L
            var failure: Option[StorageError] = None
            def reject(key: String, code: String, detail: String): Unit =
              rejected += 1
              rejects.record(StoreReject("dynamodb", key, code, detail, run))
            val groups = TableRows.stream[A](verified, name).grouped(chunkSize)
            while failure.isEmpty && groups.hasNext do
              chunks += 1
              val chunk = groups.next().iterator
              while failure.isEmpty && chunk.hasNext do
                val sr = chunk.next()
                rows += 1
                sr.value match
                  case Left(f) => reject(s"${f.file}#${f.rowIndex}", "ROW_DECODE", f.reason)
                  case Right(record) =>
                    build(record) match
                      case Left(
                            e @ (_: StorageError.InvalidRecord | _: StorageError.LimitExceeded)
                          ) =>
                        reject(keyOf(record), "INVALID_RECORD", e.message)
                      case Left(other) => failure = Some(other)
                      case Right(toWrite) =>
                        var rowRejected = false
                        val it = toWrite.iterator
                        while failure.isEmpty && !rowRejected && it.hasNext do
                          val item = it.next()
                          ItemCodec.createOrSame(items, table, item) match
                            case Right(CreateOutcome.Created) => created += 1
                            case Right(CreateOutcome.Unchanged) => unchanged += 1
                            case Left(c: StorageError.ConflictingContent) =>
                              rowRejected = true
                              reject(keyOf(record), "CONTENT_CONFLICT", c.message)
                            case Left(other) => failure = Some(other)
            failure.toLeft(Some(ReadModelTableReport(name, rows, created, unchanged, rejected)))

        for
          researchers <- stream[CanonicalResearcher]("researcher")(r =>
            ReadModelItems.directory(r, run)
          )(_.researcherId)
          support <- stream[CoauthorEdgeSupport]("coauthored_with_support")(s =>
            ReadModelItems.support(s, run).map(Vector(_))
          )(s => s"${s.edgeId}/${s.workId}")
          components <- stream[ComponentMembership]("components")(m =>
            ReadModelItems.component(m, run).map(Vector(_))
          )(_.researcherId)
          cross <- stream[CrossCommunityEdge]("cross_community_edge")(e =>
            ReadModelItems.crossEdge(e, run, base)
          )(_.edgeId)
          withMarkers = cross.exists(_.rejected == 0)
          communities <- stream[CommunityMembership]("community_membership") { m =>
            ReadModelItems.community(m, run).flatMap { c =>
              if withMarkers then ReadModelItems.marker(m, run, base).map(mk => Vector(c, mk))
              else Right(Vector(c))
            }
          }(_.researcherId)
        yield
          val reports = (researchers ++ support ++ components ++ cross ++ communities).toVector
          val note =
            if withMarkers && communities.isDefined then None
            else if cross.isEmpty then
              Some(
                s"the $expectedTarget manifest lists no cross_community_edge table; " +
                  "cross-community counts stay NotFound"
              )
            else if cross.exists(_.rejected > 0) then
              Some("cross-community edges were rejected; connectivity markers withheld")
            else Some("the manifest lists no community_membership table; no markers written")
          ReadModelLoadReport(
            run,
            reports,
            chunks,
            verified.manifest.tables
              .filterNot(t => Handled.contains(t.tableName))
              .map(t =>
                SkippedTable(t.tableName, "not a graph read model table; another loader owns it")
              ),
            connectivityStored = note.isEmpty,
            connectivityNote = note
          )
      }
