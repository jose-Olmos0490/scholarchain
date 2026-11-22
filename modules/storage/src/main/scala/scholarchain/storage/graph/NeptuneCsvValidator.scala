package scholarchain.storage.graph

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scholarchain.core.ids.CanonicalPair
import scholarchain.storage.common.*
import upickle.default.*

final case class ValidationSummary(
    nodeIds: Long,
    relationships: Long,
    files: Int
)

/** Pre-load gate over an exported `csv/` directory and its export manifest. A load must not start
  * unless this passes.
  *
  * Checks (every problem is reported, up to `maxProblemsPerKind` samples per kind):
  *   - each file in the manifest exists, and its SHA-256 and row count equal the manifest entry
  *   - every row has as many fields as the header; node files have `:ID` and `:LABEL`;
  *     relationship files have `:ID`, `:START_ID`, `:END_ID` and `:TYPE`
  *   - `:ID` is unique within every file, across all `node` files, and across all relationship files
  *   - every `node-overlay` row names a node that exists in a `node` file
  *   - every relationship endpoint is the `:ID` of a node
  *   - every COAUTHORED_WITH row has a non-empty evidence pointer, canonical endpoint order
  *     (`:START_ID` before `:END_ID`) and the `COAUTHORED_WITH` type
  *
  * Memory: the set of node IDs is held in memory (IDs only, no rows); suitable for the bounded
  * loads of this project, to be replaced by an external index for the full corpus.
  */
object NeptuneCsvValidator:
  val DefaultMaxProblemsPerKind = 25

  def readManifest(outDir: Path): Result[CsvExportManifest] =
    val p = outDir.resolve(NeptuneCsvExporter.ManifestFile)
    if !Files.isRegularFile(p) then Left(StorageError.MissingFile(p.toString))
    else
      try Right(read[CsvExportManifest](new String(Files.readAllBytes(p), StandardCharsets.UTF_8)))
      catch
        case e: Exception =>
          Left(StorageError.InvalidRecord(s"export manifest is not valid: ${e.getMessage}"))

  def validate(
      outDir: Path,
      maxProblemsPerKind: Int = DefaultMaxProblemsPerKind
  ): Result[ValidationSummary] =
    readManifest(outDir).flatMap(m => validate(outDir, m, maxProblemsPerKind))

  def validate(
      outDir: Path,
      manifest: CsvExportManifest,
      maxProblemsPerKind: Int
  ): Result[ValidationSummary] =
    val csvDir = outDir.resolve(NeptuneCsvExporter.CsvDir)
    val counts = mutable.LinkedHashMap.empty[String, Int]
    val samples = mutable.ArrayBuffer.empty[String]
    def problem(kind: String, detail: String): Unit =
      val n = counts.getOrElse(kind, 0)
      counts(kind) = n + 1
      if n < maxProblemsPerKind then samples += s"$kind: $detail"

    val nodeIds = mutable.HashSet.empty[String]
    val overlayRefs = mutable.ArrayBuffer.empty[(String, String)]
    val relationshipEnds = mutable.ArrayBuffer.empty[(String, Long, String, String)]
    var nodeCount = 0L
    var relCount = 0L

    // Pass 1: node files (base first so overlays and endpoints can be checked against them).
    val ordered = manifest.files.sortBy(f => if f.role == "node" then 0 else 1)
    val relIds = mutable.HashSet.empty[String]

    ordered.foreach { f =>
      val path = csvDir.resolve(f.file)
      if !Files.isRegularFile(path) then problem("MISSING_FILE", f.file)
      else
        val actualHash = Sha256.ofFile(path)
        if actualHash != f.sha256 then
          problem("FILE_CHECKSUM", s"${f.file}: manifest ${f.sha256}, file $actualHash")
        val reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)
        try
          val rows = Csv.parse(reader)
          if !rows.hasNext then problem("EMPTY_FILE", s"${f.file} has no header")
          else
            val header = rows.next()
            val col = header.zipWithIndex.toMap
            val isNode = f.role == "node" || f.role == "node-overlay"
            val required =
              if isNode then Vector(NeptuneSchema.Id, NeptuneSchema.Label)
              else
                Vector(
                  NeptuneSchema.Id,
                  NeptuneSchema.StartId,
                  NeptuneSchema.EndId,
                  NeptuneSchema.Type
                )
            required
              .filterNot(col.contains)
              .foreach(c => problem("MISSING_HEADER", s"${f.file}: $c"))
            if required.forall(col.contains) then
              val idIdx = col(NeptuneSchema.Id)
              val seenInFile = mutable.HashSet.empty[String]
              var n = 0L
              rows.foreach { row =>
                n += 1
                if row.size != header.size then
                  problem(
                    "FIELD_COUNT",
                    s"${f.file} row $n has ${row.size} fields, header ${header.size}"
                  )
                else
                  val id = row(idIdx)
                  if id.isEmpty then problem("EMPTY_ID", s"${f.file} row $n")
                  val repeated = id.nonEmpty && !seenInFile.add(id)
                  if repeated then problem("DUPLICATE_ID", s"${f.file} row $n repeats $id")
                  if id.nonEmpty && !repeated then
                    if f.role == "node" then
                      if !nodeIds.add(id) then
                        problem(
                          "DUPLICATE_ID",
                          s"${f.file} row $n: $id is also in another node file"
                        )
                      else nodeCount += 1
                    else if f.role == "node-overlay" then overlayRefs += ((f.file, id))
                    else if !relIds.add(id) then
                      problem(
                        "DUPLICATE_ID",
                        s"${f.file} row $n: relationship $id is in another file"
                      )
                  if f.role == "relationship" then
                    relCount += 1
                    val start = row(col(NeptuneSchema.StartId))
                    val end = row(col(NeptuneSchema.EndId))
                    relationshipEnds += ((f.file, n, start, end))
                    if row(col(NeptuneSchema.Type)) == "COAUTHORED_WITH" then
                      col.get(NeptuneSchema.EvidencePointerHeader) match
                        case None =>
                          problem(
                            "MISSING_EVIDENCE_POINTER",
                            s"${f.file}: no evidencePointer column"
                          )
                        case Some(ei) =>
                          if row(ei).trim.isEmpty then
                            problem("MISSING_EVIDENCE_POINTER", s"${f.file} row $n edge $id")
                      if !CanonicalPair.isCanonical(start, end) then
                        problem("NOT_CANONICAL_PAIR", s"${f.file} row $n edge $id: $start / $end")
                    else if f.label == "COAUTHORED_WITH" then
                      problem("WRONG_TYPE", s"${f.file} row $n edge $id is not COAUTHORED_WITH")
              }
              if n != f.rowCount then
                problem("ROW_COUNT", s"${f.file}: manifest ${f.rowCount}, file $n")
        finally reader.close()
    }

    overlayRefs.foreach { (file, id) =>
      if !nodeIds.contains(id) then problem("OVERLAY_WITHOUT_NODE", s"$file: $id has no node row")
    }
    relationshipEnds.foreach { (file, n, start, end) =>
      if !nodeIds.contains(start) then
        problem("DANGLING_ENDPOINT", s"$file row $n: :START_ID $start")
      if !nodeIds.contains(end) then problem("DANGLING_ENDPOINT", s"$file row $n: :END_ID $end")
    }

    if counts.isEmpty then Right(ValidationSummary(nodeCount, relCount, manifest.files.size))
    else
      val summary = counts.map((k, v) => s"$k x$v").mkString(", ")
      Left(StorageError.ValidationFailed(s"Neptune CSV validation: $summary" +: samples.toVector))
