package scholarchain.storage.loader

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scholarchain.core.model.{LoadManifest, LoadTarget}
import scholarchain.storage.common.*
import scholarchain.storage.graph.*
import scholarchain.storage.manifest.*
import scholarchain.storage.objects.*
import upickle.default.*

final case class GraphLoadReport(
    runId: String,
    exported: CsvExportManifest,
    validation: ValidationSummary,
    uploaded: Int,
    skipped: Int,
    job: GraphLoadJob
)

/** The Neptune load, end to end, in a fixed order that never starts a load on unverified or invalid
  * data:
  *   1. verify the `NEPTUNE` load manifest (SHA-256, size and row count of every Parquet file)
  *   2. export Parquet to openCypher CSV in `workDir/run_id=<hex>/`
  *   3. validate the CSV (dangling endpoints, duplicate IDs, missing evidence pointers, ...)
  *   4. upload the CSV and its export manifest to the `NeptuneExport` area (same key and hash is a
  *      skip; same key and different hash fails)
  *   5. start the bulk load through the [[GraphLoader]] port
  * Re-running the same run repeats steps 1-4 as no-ops and starts a load whose node and
  * relationship IDs already exist, which Neptune merges without change.
  */
final class GraphLoadWorkflow(
    stager: FileStager,
    objects: ObjectStore,
    loader: GraphLoader,
    rejects: RejectSink,
    workDir: Path,
    options: ExportOptions = ExportOptions()
):
  private val exporter = new NeptuneCsvExporter(rejects)

  def run(manifest: LoadManifest): Result[GraphLoadReport] =
    for
      verified <- LoadManifests.verify(manifest, LoadTarget.NEPTUNE, stager)
      hex <- ObjectKeys.runHex(manifest.runId)
      outDir = workDir.resolve(ObjectKeys.runDir(hex))
      exported <- exporter.exportRun(verified, outDir, options)
      summary <- NeptuneCsvValidator.validate(
        outDir,
        exported,
        NeptuneCsvValidator.DefaultMaxProblemsPerKind
      )
      counts <- upload(hex, outDir, exported)
      csvPrefix = ObjectRef(
        ObjectArea.NeptuneExport,
        ObjectKeys.runDir(hex) + NeptuneCsvExporter.CsvDir + "/"
      )
      job <- loader.startLoad(GraphLoadRequest(manifest.runId, csvPrefix))
    yield GraphLoadReport(manifest.runId, exported, summary, counts._1, counts._2, job)

  private def upload(hex: String, outDir: Path, exported: CsvExportManifest): Result[(Int, Int)] =
    var created = 0
    var skipped = 0
    def count(o: PutOutcome): Unit = o match
      case PutOutcome.Created => created += 1
      case PutOutcome.Skipped => skipped += 1
    val it = exported.files.iterator
    var failure: Option[StorageError] = None
    while failure.isEmpty && it.hasNext do
      val f = it.next()
      val path = outDir.resolve(NeptuneCsvExporter.CsvDir).resolve(f.file)
      val ref = ObjectRef(
        ObjectArea.NeptuneExport,
        ObjectKeys.runDir(hex) + NeptuneCsvExporter.CsvDir + "/" + f.file
      )
      objects.putFile(ref, path, f.sha256, Files.size(path)) match
        case Left(e) => failure = Some(e)
        case Right(o) => count(o)
    failure match
      case Some(e) => Left(e)
      case None =>
        val manifestRef =
          ObjectRef(
            ObjectArea.NeptuneExport,
            ObjectKeys.runDir(hex) + NeptuneCsvExporter.ManifestFile
          )
        val bytes = Files.readAllBytes(outDir.resolve(NeptuneCsvExporter.ManifestFile))
        objects.putBytes(manifestRef, bytes, "application/json").map { o =>
          count(o)
          (created, skipped)
        }
