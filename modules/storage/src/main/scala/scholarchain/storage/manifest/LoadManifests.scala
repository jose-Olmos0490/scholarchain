package scholarchain.storage.manifest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scholarchain.core.json.WireCodec
import scholarchain.core.model.{LoadFile, LoadManifest, LoadTable, LoadTarget}
import scholarchain.core.schema.SchemaVersions
import scholarchain.storage.common.*
import scholarchain.storage.parquet.ParquetRows

/** A staged input file: a local path that can be streamed, plus its release action. */
final case class StagedFile(local: Path, release: () => Unit)

/** Turns a path written in a load manifest into a local file. */
trait FileStager:
  def stage(path: String): Result[StagedFile]

/** Local filesystem staging. A `file:` prefix is stripped; a relative path is resolved against
  * `baseDir` (the manifest's own producer-relative root).
  */
final class LocalFileStager(baseDir: Path) extends FileStager:
  def stage(path: String): Result[StagedFile] =
    val stripped =
      if path.startsWith("file://") then path.stripPrefix("file://")
      else if path.startsWith("file:") then path.stripPrefix("file:")
      else path
    if stripped.contains("://") then
      Left(StorageError.Misconfigured(s"cannot stage $path from the local filesystem"))
    else
      val p = Paths.get(stripped)
      val resolved = if p.isAbsolute then p else baseDir.resolve(p)
      if Files.isRegularFile(resolved) then Right(StagedFile(resolved, () => ()))
      else Left(StorageError.MissingFile(path))

/** The files of a manifest after every checksum, size and row count was verified. */
final case class VerifiedManifest(manifest: LoadManifest, localFiles: Map[String, Path]):
  def table(name: String): Option[LoadTable] = manifest.tables.find(_.tableName == name)

  def localPaths(table: LoadTable): Vector[Path] = table.files.map(f => localFiles(f.path))

object LoadManifests:
  def parse(json: String): Result[LoadManifest] =
    WireCodec
      .decode[LoadManifest](json)
      .left
      .map(errors => StorageError.InvalidManifest(errors.map(_.toString).mkString("; ")))

  def read(path: Path): Result[LoadManifest] =
    try parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    catch case e: java.io.IOException => Left(StorageError.MissingFile(s"$path (${e.getMessage})"))

  /** Verifies a manifest before anything is loaded from it:
    *   - the target is the expected one and every table's record schema version is supported
    *   - every file exists, has the listed size and the listed SHA-256 (streamed)
    *   - the footer row counts of a table's files add up to the table's `rowCount`
    * Any mismatch fails the whole load; nothing is read from a partially verified manifest.
    */
  def verify(
      manifest: LoadManifest,
      expectedTarget: LoadTarget,
      stager: FileStager
  ): Result[VerifiedManifest] =
    if manifest.target != expectedTarget then
      Left(
        StorageError.InvalidManifest(
          s"target is ${manifest.target}, this loader handles $expectedTarget"
        )
      )
    else
      var staged = Map.empty[String, Path]
      def checkTable(t: LoadTable): Result[Unit] =
        if !SchemaVersions.supportedFor(t.recordType).contains(t.recordSchemaVersion) then
          Left(
            StorageError.InvalidManifest(
              s"table ${t.tableName}: schema ${t.recordSchemaVersion} is not supported for ${t.recordType}"
            )
          )
        else if t.format != "parquet" then
          Left(
            StorageError.InvalidManifest(s"table ${t.tableName}: format ${t.format} is not parquet")
          )
        else
          var rows = 0L
          val it = t.files.iterator
          var failure: Option[StorageError] = None
          while failure.isEmpty && it.hasNext do
            val f = it.next()
            checkFile(f) match
              case Left(e) => failure = Some(e)
              case Right(local) =>
                staged += f.path -> local
                try rows += ParquetRows.rowCount(local)
                catch
                  case e: Exception =>
                    failure = Some(
                      StorageError.InvalidManifest(
                        s"${f.path} is not readable Parquet: ${e.getMessage}"
                      )
                    )
          failure match
            case Some(e) => Left(e)
            case None if rows != t.rowCount =>
              Left(StorageError.RowCountMismatch(t.tableName, t.rowCount, rows))
            case None => Right(())

      def checkFile(f: LoadFile): Result[Path] =
        stager.stage(f.path).flatMap { s =>
          val size = Files.size(s.local)
          if size != f.sizeBytes then Left(StorageError.SizeMismatch(f.path, f.sizeBytes, size))
          else
            val actual = Sha256.ofFile(s.local)
            if actual != f.checksum then
              Left(StorageError.ChecksumMismatch(f.path, f.checksum, actual))
            else Right(s.local)
        }

      manifest.tables
        .foldLeft[Result[Unit]](Right(()))((acc, t) => acc.flatMap(_ => checkTable(t)))
        .map(_ => VerifiedManifest(manifest, staged))
