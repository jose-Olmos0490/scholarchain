package scholarchain.rag.ingest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.core.model.*
import scholarchain.rag.common.*
import scholarchain.rag.embed.*
import scholarchain.rag.render.PaperInput
import scholarchain.storage.common.StorageError
import scholarchain.storage.vectors.{
  IndexDefinition,
  IngestionReport,
  IngestionRun,
  RenderedDocument,
  VectorIngestor
}
import upickle.default.*

/** The three input files of an ingestion run, one JSON object per line. `semantic-facts.jsonl`
  * holds core `SemanticFact`, `paths.jsonl` core `PathEvidence`, `papers.jsonl`
  * `{"work": WorkRecord, "venueName": ..., "abstractText": ...}`. A missing file is an empty
  * input; a malformed line refuses the whole run (nothing is guessed). The entrypoint refuses a
  * directory whose files hold no record at all ([[IngestionInputs.nonEmptyFrom]]).
  */
final case class IngestionInputs(
    facts: Vector[SemanticFact],
    paths: Vector[PathEvidence],
    papers: Vector[PaperInput]
):
  /** Refuses inputs with zero records in total: a wrong directory or misnamed files must not look
    * like a successful run. One empty or absent file is fine when another has records.
    */
  def nonEmptyFrom(location: String): RResult[IngestionInputs] =
    import IngestionInputs.{FactsFile, PapersFile, PathsFile}
    Either.cond(
      facts.nonEmpty || paths.nonEmpty || papers.nonEmpty,
      this,
      RagProblem(
        "NO_INPUT_RECORDS",
        s"read 0 records from input directory '$location'; expected at least one record in " +
          s"$FactsFile, $PathsFile or $PapersFile (check the directory and the file names)"
      )
    )

object IngestionInputs:
  val FactsFile = "semantic-facts.jsonl"
  val PathsFile = "paths.jsonl"
  val PapersFile = "papers.jsonl"

  given ReadWriter[PaperInput] = macroRW

  private def lines[A: Reader](dir: Path, name: String): RResult[Vector[A]] =
    val f = dir.resolve(name)
    if !Files.isRegularFile(f) then Right(Vector.empty)
    else
      val raw = Files.readAllLines(f, StandardCharsets.UTF_8).asScala.toVector
      raw.zipWithIndex
        .filter(_._1.trim.nonEmpty)
        .foldLeft[RResult[Vector[A]]](Right(Vector.empty)) { case (acc, (l, i)) =>
          acc.flatMap { xs =>
            try Right(xs :+ read[A](l))
            catch
              case NonFatal(e) =>
                Left(RagProblem("BAD_INPUT", s"$name line ${i + 1}: ${e.getMessage.take(200)}"))
          }
        }

  def readDir(dir: Path): RResult[IngestionInputs] =
    if !Files.isDirectory(dir) then Left(RagProblem("BAD_INPUT", s"$dir is not a directory"))
    else
      for
        f <- lines[SemanticFact](dir, FactsFile)
        p <- lines[PathEvidence](dir, PathsFile)
        w <- lines[PaperInput](dir, PapersFile)
      yield IngestionInputs(f, p, w)

/** What a run produced: the text to print and whether AWS was called. */
final case class IngestionOutcome(text: String, awsCalled: Boolean)

/** render -> chunk -> (dry-run report | storage ingestor). All storage access goes through the
  * [[VectorIngestor]] built by `ingestorFor`, which is called only for a live run, with the
  * embedding function of the rag [[Embedder]] (dimension-checked per index). The by-name
  * parameters (`ingestRun`, `embedder`) are evaluated only for a live run, so a dry run builds
  * no client and calls nothing.
  */
final class IngestionRunner(
    pipeline: RenderPipeline,
    definitions: Vector[IndexDefinition],
    bindings: IndexBindings,
    sampleCount: Int
):

  /** A live run must be bounded: `--limit` is required (use a large one deliberately). */
  def run(
      o: IngestionOptions,
      inputs: IngestionInputs,
      ingestRun: => RResult[IngestionRun],
      embedder: => RResult[Embedder],
      ingestorFor: (Seq[String] => Seq[Array[Float]]) => VectorIngestor
  ): RResult[IngestionOutcome] =
    val batch = pipeline.renderAll(inputs.facts, inputs.paths, inputs.papers)
    val known = definitions.map(_.name).toSet
    o.indexName.filterNot(known) match
      case Some(n) => Left(RagProblem("UNKNOWN_INDEX", s"--index '$n' is not a configured index"))
      case None =>
        val selected = IngestionOptions.select(o, batch.chunks)
        if o.dryRun then
          Right(IngestionOutcome(DryRun.report(selected, batch.rejects, sampleCount), false))
        else if o.limit.isEmpty then
          Left(RagProblem("UNBOUNDED_RUN", "a live run requires --limit (or use --dry-run)"))
        else if selected.isEmpty then
          Left(RagProblem("NOTHING_TO_INGEST", "no documents selected after rendering"))
        else live(selected, batch, ingestRun, embedder, ingestorFor)

  private def live(
      selected: Vector[RenderedChunk],
      batch: RenderBatch,
      ingestRun: => RResult[IngestionRun],
      embedder: => RResult[Embedder],
      ingestorFor: (Seq[String] => Seq[Array[Float]]) => VectorIngestor
  ): RResult[IngestionOutcome] =
    val defs = selected.map(_.indexName).distinct.map(n => definitions.find(_.name == n))
    for
      emb <- embedder
      ds <- Either.cond(
        defs.forall(_.isDefined),
        defs.flatten,
        RagProblem("UNKNOWN_INDEX", "a rendered index is not configured")
      )
      fns <- ds.foldLeft[RResult[Vector[Seq[String] => Seq[Array[Float]]]]](Right(Vector.empty)) {
        (acc, d) =>
          for
            fs <- acc
            _ <- IndexBinder.ensure(bindings, d.name, emb.spec)
            f <- Embedder.forIndex(emb, d.name, d.dimension)
          yield fs :+ f
      }
      r <- ingestRun
      ingestor = ingestorFor(fns.head)
      staged <- ingestor.stage(r, selected.map(toDocument).iterator).left.map(storageProblem)
      report <- ingestor.ingest(r, staged.manifest).left.map(storageProblem)
    yield IngestionOutcome(summary(report, batch.rejects.size, staged.rejectedDocuments), true)

  private def toDocument(c: RenderedChunk): RenderedDocument =
    RenderedDocument(
      c.indexName,
      c.entityId,
      c.documentType,
      c.windowStartYear,
      c.windowEndYear,
      c.ordinal,
      c.text,
      c.topicIds,
      c.institutionIds,
      c.sourceDataset,
      c.communityId,
      c.provenance
    )

  private def storageProblem(e: StorageError): RagProblem =
    RagProblem("STORAGE_REFUSED", e.message)

  private def summary(r: IngestionReport, renderRejects: Int, stageRejects: Int): String =
    val sb = new StringBuilder
    sb ++= s"run ${r.runId}: render rejects=$renderRejects stage rejects=$stageRejects\n"
    r.indexes.foreach(i =>
      sb ++= s"index ${i.indexName}: items=${i.manifestItems} created=${i.created} updated=${i.updated} skipped=${i.skippedUnchanged} rejected=${i.rejected} putBatches=${i.putBatches}\n"
    )
    sb ++= s"ledgerErrors=${r.ledgerErrors.size} rejectsUri=${r.rejectsUri.getOrElse("none")}\n"
    sb.toString
