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

  /** Relative path of `file` under `table.location`, or an error if the file is outside it.
    * Tolerates relative and scheme-qualified forms of either side; see [[ManifestPaths]].
    */
  def relativePath(table: LoadTable, file: String): Result[String] =
    ManifestPaths.relative(table.location, file).left.map { why =>
      StorageError.InvalidManifest(
        s"table ${table.tableName}: file $file is not under its location ${table.location} ($why)"
      )
    }

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

/** Comparison of manifest paths that may be relative or scheme-qualified.
  *
  * Normalisation of one side: `s3`, `s3a` and `s3n` are the same scheme (`s3`); `file:`, `file:/`
  * and `file:///` are the same (an absolute local path); empty segments (duplicate or trailing
  * slashes) are dropped; `.` and `..` segments are refused. Matching is by whole path segments, so
  * `run_id=ab` is not a prefix of `run_id=abc/...`.
  *
  *   - both qualified: scheme and authority (bucket) must be equal
  *   - both relative: compared as they are
  *   - one qualified, one relative: the relative path is taken as rooted at the qualified
  *     path's root (the bucket root for S3), so `s3://b/p/t` and `p/t` name the same place
  */
object ManifestPaths:
  final private case class Parsed(qualified: Option[(String, String)], segments: Vector[String])
  private val Scheme = "([A-Za-z][A-Za-z0-9+.-]*):(.*)".r

  private def parse(raw: String): Either[String, Parsed] =
    val trimmed = raw.trim
    val (qualified, path) = trimmed match
      case Scheme(sch, rest) if sch.length > 1 =>
        sch.toLowerCase match
          case "s3" | "s3a" | "s3n" => splitAuthority("s3", rest)
          case "file" => (Some(("file", "")), rest)
          case other => splitAuthority(other, rest)
      case _ => (None, trimmed)
    val segments = path.split("/", -1).toVector.filter(_.nonEmpty)
    if segments.exists(sg => sg == "." || sg == "..") then Left(s"'$raw' has a '.' or '..' segment")
    else Right(Parsed(qualified, segments))

  private def splitAuthority(scheme: String, rest: String): (Option[(String, String)], String) =
    if rest.startsWith("//") then
      val body = rest.drop(2)
      val slash = body.indexOf('/')
      if slash < 0 then (Some((scheme, body)), "")
      else (Some((scheme, body.take(slash))), body.drop(slash))
    else (Some((scheme, "")), rest)

  /** The path of `file` below `base` (no leading slash), or the reason it is not below it. */
  def relative(base: String, file: String): Either[String, String] =
    for
      b <- parse(base)
      f <- parse(file)
      _ <- (b.qualified, f.qualified) match
        case (Some(x), Some(y)) if x != y =>
          Left(s"scheme or bucket differs: ${x._1}://${x._2} vs ${y._1}://${y._2}")
        case _ => Right(())
      _ <-
        if b.segments.nonEmpty then Right(())
        else Left("the table location is empty")
      rest <-
        if f.segments.startsWith(b.segments) && f.segments.size > b.segments.size then
          Right(f.segments.drop(b.segments.size))
        else Left("the file path is not below the location")
    yield rest.mkString("/")
