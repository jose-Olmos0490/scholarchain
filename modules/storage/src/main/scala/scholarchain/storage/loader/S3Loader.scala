package scholarchain.storage.loader

import java.nio.charset.StandardCharsets
import scholarchain.core.model.{LoadTable, LoadTarget, Provenance}
import scholarchain.storage.common.*
import scholarchain.storage.manifest.VerifiedManifest
import scholarchain.storage.objects.*
import upickle.default.*

object StorageSchemas:
  val S3RunManifest = "s3-run-manifest-v1"
  val NeptuneCsvExport = "neptune-csv-export-v1"

/** One object written (or found identical) by an S3 load. */
final case class S3ObjectEntry(
    area: String,
    key: String,
    sha256: String,
    sizeBytes: Long,
    schemaVersion: String,
    tableName: String
) derives ReadWriter

/** JSON run manifest of one S3 load. Deterministic: objects are sorted by (area, key) and there are
  * no time fields, so reloading the same run writes byte-identical content (a skip).
  *
  * @param schemaVersion
  *   `s3-run-manifest-v1`
  * @param sourceRunId
  *   the Spark run the objects came from; each object's own `schemaVersion` is the core record
  *   schema version of its table
  */
final case class S3RunManifest(
    schemaVersion: String,
    sourceRunId: String,
    objects: Vector[S3ObjectEntry],
    provenance: Provenance
) derives ReadWriter

final case class S3LoadReport(
    runId: String,
    created: Int,
    skipped: Int,
    manifestKey: String,
    manifestOutcome: PutOutcome
)

/** Copies the files of a verified `S3` load manifest into ordinary S3.
  *
  * Layout: `<area>/run_id=<hex>/<tableName>/<path relative to the table location>`. A table goes to
  * `Graph`, except tables named in `ragTables`, which go to `Rag` (semantic context). The
  * manifest is written to `Reports` under `run_id=<hex>/storage/s3-run-manifest.json`.
  *
  * Idempotent: same key and hash is a skip, same key and different hash fails the load and records
  * a reject. Nothing is ever overwritten or deleted.
  */
final class S3Loader(
    store: ObjectStore,
    rejects: RejectSink,
    ragTables: Set[String] = Set("semantic_fact")
):
  def areaOf(table: LoadTable): ObjectArea =
    if ragTables.contains(table.tableName) then ObjectArea.Rag else ObjectArea.Graph

  /** Relative path of `file` under `location`, or an error if the file is outside it. */
  def relativePath(table: LoadTable, file: String): Result[String] =
    val loc = table.location.stripSuffix("/") + "/"
    if file.startsWith(loc) then Right(file.stripPrefix(loc))
    else
      Left(
        StorageError.InvalidManifest(
          s"table ${table.tableName}: file $file is not under its location ${table.location}"
        )
      )

  def load(verified: VerifiedManifest): Result[S3LoadReport] =
    val manifest = verified.manifest
    if manifest.target != LoadTarget.S3 then
      Left(StorageError.InvalidManifest(s"S3 loader got a ${manifest.target} manifest"))
    else
      ObjectKeys.runHex(manifest.runId).flatMap { hex =>
        var created = 0
        var skipped = 0
        val entries = Vector.newBuilder[S3ObjectEntry]
        var failure: Option[StorageError] = None
        val tables = manifest.tables.iterator
        while failure.isEmpty && tables.hasNext do
          val t = tables.next()
          val files = t.files.iterator
          while failure.isEmpty && files.hasNext do
            val f = files.next()
            val step = for
              rel <- relativePath(t, f.path)
              ref = ObjectRef(areaOf(t), ObjectKeys.tableFile(hex, t.tableName, rel))
              outcome <- store.putFile(ref, verified.localFiles(f.path), f.checksum, f.sizeBytes)
            yield (ref, outcome)
            step match
              case Left(e) =>
                e match
                  case c: StorageError.ConflictingContent =>
                    rejects.record(
                      StoreReject("s3", c.key, "KEY_CONFLICT", c.message, manifest.runId)
                    )
                  case _ => ()
                failure = Some(e)
              case Right((ref, outcome)) =>
                outcome match
                  case PutOutcome.Created => created += 1
                  case PutOutcome.Skipped => skipped += 1
                entries += S3ObjectEntry(
                  ref.area.wire,
                  ref.key,
                  f.checksum,
                  f.sizeBytes,
                  t.recordSchemaVersion,
                  t.tableName
                )
        failure match
          case Some(e) => Left(e)
          case None =>
            val run = S3RunManifest(
              StorageSchemas.S3RunManifest,
              manifest.runId,
              entries.result().sortBy(e => (e.area, e.key)),
              manifest.provenance
            )
            val ref =
              ObjectRef(ObjectArea.Reports, ObjectKeys.runReport(hex, "s3-run-manifest.json"))
            val bytes = write(run).getBytes(StandardCharsets.UTF_8)
            store.putBytes(ref, bytes, "application/json").map { o =>
              S3LoadReport(manifest.runId, created, skipped, ref.key, o)
            }
      }
