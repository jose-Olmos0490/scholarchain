package scholarchain.storage.loader

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scholarchain.core.model.{LoadManifest, LoadTarget}
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.*
import scholarchain.storage.manifest.*
import scholarchain.storage.objects.*

/** Where the Spark load manifests sit under the Spark output directory. Spark writes
  * `graph/load-manifests/target=<t>/run_id=<hex>/load-manifest.json`; `--spark-output` may name the
  * directory that holds `graph/` or the `graph/` directory itself.
  */
object SparkOutputLayout:
  def targetDir(target: LoadTarget): String = target match
    case LoadTarget.NEPTUNE => "neptune"
    case LoadTarget.DYNAMODB => "dynamodb"
    case LoadTarget.S3VECTORS_INPUT => "s3vectors-input"
    case LoadTarget.S3 => "s3"

  /** The directory that holds `load-manifests/`, or the given root when none is found. */
  def graphRoot(root: Path): Path =
    if Files.isDirectory(root.resolve("load-manifests")) then root
    else if Files.isDirectory(root.resolve("graph").resolve("load-manifests")) then
      root.resolve("graph")
    else root

  def manifestPath(root: Path, target: LoadTarget, runHex: String): Path =
    graphRoot(root)
      .resolve("load-manifests")
      .resolve(s"target=${targetDir(target)}")
      .resolve(s"run_id=$runHex")
      .resolve("load-manifest.json")

/** Stages the files of a Spark load manifest from a local copy of the Spark output. Spark wrote the
  * paths on its own file system, so a path that does not exist as written is looked up by its
  * trailing segments under the output directory (`.../graph/nodes/type=x/f.parquet` resolves to
  * `<root>/nodes/type=x/f.parquet`). Remote schemes and `..` segments are refused.
  */
final class SparkOutputStager(root: Path) extends FileStager:
  private val base = SparkOutputLayout.graphRoot(root)

  def stage(path: String): Result[StagedFile] =
    val stripped =
      if path.startsWith("file://") then path.stripPrefix("file://")
      else if path.startsWith("file:") then path.stripPrefix("file:")
      else path
    if stripped.contains("://") then
      Left(
        StorageError.Misconfigured(
          s"cannot stage $path: only a local copy of the Spark output is read; copy it down first"
        )
      )
    else
      val p = Paths.get(stripped)
      val segs = p.iterator().asScala.map(_.toString).toVector
      if segs.contains("..") then
        Left(StorageError.InvalidManifest(s"path $path has a '..' segment"))
      else
        try
          val rootReal = root.toRealPath()
          // a candidate counts only when it is a regular file, not a symlink, inside the root
          def inside(f: Path): Boolean =
            Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
              f.toRealPath().startsWith(rootReal)
          val direct = if p.isAbsolute then Some(p) else None
          val minSegs = math.min(2, segs.size)
          val found = direct.filter(inside).orElse {
            (0 to segs.size - minSegs).iterator
              .map(i => base.resolve(segs.drop(i).mkString("/")))
              .find(inside)
          }
          found match
            case Some(f) => Right(StagedFile(f, () => ()))
            case None => Left(StorageError.MissingFile(path))
        catch
          case e: java.io.IOException => Left(StorageError.MissingFile(s"$path (${e.getMessage})"))
          case e: java.nio.file.InvalidPathException =>
            Left(StorageError.InvalidManifest(s"path $path: ${e.getMessage}"))

/** Used for the `neptune-csv` step: the CSV is exported, validated and uploaded, and the Neptune
  * endpoint is never called. The job is a marker that nothing was started.
  */
object StagingOnlyGraphLoader extends GraphLoader:
  val JobId = "not-started"
  def startLoad(request: GraphLoadRequest): Result[GraphLoadJob] =
    Right(GraphLoadJob(JobId, request.runId))
  def status(job: GraphLoadJob): Result[GraphLoadState] =
    Left(StorageError.Unsupported("no Neptune bulk load was started by the CSV staging step"))

/** An item store that records what a dry run would write. */
final class SamplingItemStore(inner: ItemStore, sampleSize: Int) extends ItemStore:
  private val kinds = scala.collection.mutable.TreeMap.empty[String, Long]
  private val samples = Vector.newBuilder[String]
  private var sampled = 0

  def kindCounts: Map[String, Long] = synchronized(kinds.toMap)
  def sampleLines: Vector[String] = synchronized(samples.result())

  def putIfAbsent(table: String, item: ujson.Obj): Result[Unit] =
    val r = inner.putIfAbsent(table, item)
    if r.isRight then count(item)
    r

  private def count(item: ujson.Obj): Unit =
    synchronized {
      val kind = item.obj.get("kind").map(_.str).getOrElse("?")
      kinds(kind) = kinds.getOrElse(kind, 0L) + 1
      if sampled < sampleSize then
        sampled += 1
        samples += s"$kind  pk=${item("pk").str}  sk=${item("sk").str}"
    }

  def putAllIfAbsent(table: String, items: Vector[ujson.Obj]): Result[Unit] =
    val r = inner.putAllIfAbsent(table, items)
    if r.isRight then items.foreach(count)
    r
  def get(table: String, pk: String, sk: String): Result[Option[ujson.Obj]] =
    inner.get(table, pk, sk)
  def replaceIfHash(table: String, item: ujson.Obj, expectedHash: String): Result[Boolean] =
    inner.replaceIfHash(table, item, expectedHash)
  def query(
      table: String,
      pk: String,
      skPrefix: Option[String],
      ascending: Boolean,
      limit: Option[Int]
  ): Result[Vector[ujson.Obj]] = inner.query(table, pk, skPrefix, ascending, limit)

final case class LoadOutcome(steps: Vector[String], rejected: Long)

/** Runs the selected steps against the given ports. The same code runs a dry run (in-memory fakes,
  * no ledger) and a live run (AWS adapters); only the ports differ.
  *
  * Ledger: one entry per step, stage `load-<step>`, sequence `ledgerSequence`. The detail is
  * derived from the manifests (target, snapshot, rows per table), so reloading the same run finds
  * an identical SUCCEEDED entry and writes nothing; any other state gets the next attempt number.
  */
final class LoadRunner(
    objects: ObjectStore,
    items: ItemStore,
    readModelTable: String,
    metricCache: Option[ResearcherMetricCache],
    ledger: Option[RunLedger],
    chunkSize: Int,
    rejects: RejectSink,
    workDir: Path,
    ledgerSequence: Int,
    nowMillis: () => Long,
    out: String => Unit
):
  def run(args: LoadArgs): Result[LoadOutcome] =
    ObjectKeys.runHex(args.runId).flatMap { hex =>
      val stager = new SparkOutputStager(args.sparkOutput)
      var done = Vector.empty[String]
      var failure: Option[StorageError] = None
      val it = args.targets.iterator
      while failure.isEmpty && it.hasNext do
        val step = it.next()
        out(s"== step ${step.wire}")
        runStep(step, args, hex, stager) match
          case Right(_) => done :+= step.wire
          case Left(e) =>
            out(s"step ${step.wire} FAILED: ${e.message}")
            failure = Some(e)
      failure match
        case Some(e) => Left(e)
        case None => Right(LoadOutcome(done, rejects.all.size.toLong))
    }

  // ---- manifests ----

  private def manifest(
      args: LoadArgs,
      hex: String,
      target: LoadTarget,
      required: Boolean
  ): Result[Option[LoadManifest]] =
    val path = SparkOutputLayout.manifestPath(args.sparkOutput, target, hex)
    if !Files.isRegularFile(path) then
      if required then Left(StorageError.MissingFile(path.toString)) else Right(None)
    else
      LoadManifests.read(path).flatMap { m =>
        if m.runId != args.runId then
          Left(StorageError.InvalidManifest(s"$path is for ${m.runId}, not ${args.runId}"))
        else if m.provenance.snapshot != args.snapshot then
          Left(
            StorageError.InvalidManifest(
              s"$path is for snapshot ${m.provenance.snapshot}, not ${args.snapshot}"
            )
          )
        else Right(Some(m))
      }

  private def tableLines(m: LoadManifest): Unit =
    m.tables.foreach(t =>
      out(f"   ${t.tableName}%-28s rows=${t.rowCount}%8d files=${t.files.size}")
    )

  // ---- steps ----

  private def runStep(
      step: LoadStep,
      args: LoadArgs,
      hex: String,
      stager: FileStager
  ): Result[Unit] =
    val target = step match
      case LoadStep.Dynamo => LoadTarget.DYNAMODB
      case LoadStep.S3 => LoadTarget.S3
      case LoadStep.NeptuneCsv => LoadTarget.NEPTUNE
      case LoadStep.NeptuneLoad =>
        return Left(
          StorageError.Unsupported("the neptune bulk-load step is not run by LoadRunner")
        )
    val rejectsBefore = rejects.all.size
    val result = manifest(args, hex, target, required = true).flatMap { m0 =>
      val m = m0.get
      tableLines(m)
      val body = step match
        case LoadStep.Dynamo => dynamo(args, hex, m, stager)
        case LoadStep.S3 => s3(m, stager)
        case LoadStep.NeptuneCsv => neptuneCsv(m, hex, stager)
        case LoadStep.NeptuneLoad => Left(StorageError.Unsupported("neptune bulk load"))
      body.map(_ => m)
    }
    result match
      case Right(m) =>
        val newRejects = rejects.all.size - rejectsBefore
        if newRejects > 0 then
          out(s"   step ${step.wire} recorded $newRejects reject(s); not recorded as SUCCEEDED")
          record(
            step,
            args,
            m,
            LedgerState.COMPLETED_WITH_REJECTS,
            ledgerDetail(step, m, Some(newRejects))
          )
        else record(step, args, m, LedgerState.SUCCEEDED, ledgerDetail(step, m, None))
      case Left(e) =>
        // best effort: a FAILED entry for the attempt; the original failure is what is returned
        manifest(args, hex, target, required = false).toOption.flatten.foreach { m =>
          record(step, args, m, LedgerState.FAILED, ujson.Obj("error" -> e.message).render())
        }
        Left(e)

  private def dynamo(
      args: LoadArgs,
      hex: String,
      dynamoManifest: LoadManifest,
      stager: FileStager
  ): Result[Unit] =
    def readModel(m: LoadManifest, target: LoadTarget, label: String): Result[Unit] =
      new GraphReadModelLoader(items, readModelTable, chunkSize, rejects, target)
        .load(m, stager)
        .map { r =>
          out(s"   read model from $label manifest: chunks=${r.chunks}")
          r.tables.foreach(t =>
            out(
              s"   ${t.table} rows=${t.rows} created=${t.created} unchanged=${t.unchanged} rejected=${t.rejected}"
            )
          )
          r.skippedTables.foreach(s => out(s"   skipped ${s.table}: ${s.reason}"))
          r.connectivityNote.foreach(n => out(s"   connectivity: $n"))
          out(s"   connectivityStored=${r.connectivityStored}")
        }
    for
      _ <- readModel(dynamoManifest, LoadTarget.DYNAMODB, "DYNAMODB")
      // cross_community_edge is listed only by the NEPTUNE and S3 manifests; the same table, the same
      // conditional writes, so every item already stored is unchanged and the markers are added.
      neptune <- manifest(args, hex, LoadTarget.NEPTUNE, required = false)
      _ <- neptune match
        case Some(n) if n.tables.exists(_.tableName == "cross_community_edge") =>
          readModel(n, LoadTarget.NEPTUNE, "NEPTUNE")
        case _ =>
          out(
            "   no NEPTUNE manifest with cross_community_edge: cross-community counts stay unread"
          )
          Right(())
      _ <- metricCache match
        case None =>
          out("   metric cache not configured: researcher_collaboration not loaded")
          Right(())
        case Some(_) =>
          new DynamoLoader(metricCache, chunkSize, rejects)
            .load(dynamoManifest, stager)
            .map { r =>
              out(
                s"   researcher_collaboration: created=${r.created} unchanged=${r.unchanged} rejected=${r.rejected}"
              )
            }
    yield ()

  private def s3(m: LoadManifest, stager: FileStager): Result[Unit] =
    for
      v <- LoadManifests.verify(m, LoadTarget.S3, stager)
      r <- new S3Loader(objects, rejects).load(v)
    yield out(
      s"   objects created=${r.created} skipped=${r.skipped} manifest=${r.manifestKey} (${r.manifestOutcome})"
    )

  private def neptuneCsv(m: LoadManifest, hex: String, stager: FileStager): Result[Unit] =
    val wf = new GraphLoadWorkflow(stager, objects, StagingOnlyGraphLoader, rejects, workDir)
    wf.run(m).map { r =>
      r.exported.files.foreach(f => out(f"   ${f.file}%-40s ${f.role}%-13s rows=${f.rowCount}%8d"))
      out(
        s"   validated: nodeIds=${r.validation.nodeIds} relationships=${r.validation.relationships} " +
          s"files=${r.validation.files} rejectedRows=${r.exported.rejectedRows}"
      )
      out(s"   uploaded=${r.uploaded} skipped=${r.skipped} (no Neptune load started)")
    }

  // ---- ledger ----

  private def ledgerDetail(step: LoadStep, m: LoadManifest, newRejects: Option[Int]): String =
    val o = ujson.Obj(
      "step" -> step.wire,
      "target" -> m.target.toString,
      "snapshot" -> m.provenance.snapshot,
      "tables" -> ujson.Obj.from(
        m.tables.sortBy(_.tableName).map(t => t.tableName -> ujson.Num(t.rowCount.toDouble))
      )
    )
    newRejects.foreach(n => o("rejects") = ujson.Num(n.toDouble))
    o.render()

  private def record(
      step: LoadStep,
      args: LoadArgs,
      m: LoadManifest,
      state: LedgerState,
      detail: String
  ): Result[Unit] = ledger match
    case None => Right(())
    case Some(l) =>
      val stage = s"load-${step.wire}"
      l.list(args.runId).flatMap { all =>
        val mine = all.filter(_.stage == stage)
        if state == LedgerState.SUCCEEDED &&
          mine.exists(e => e.state == LedgerState.SUCCEEDED && e.detail == detail)
        then
          out(s"   ledger: $stage already recorded for this run")
          Right(())
        else
          val attempt = mine.map(_.attempt).maxOption.getOrElse(0) + 1
          l.record(
            RunLedgerEntry(
              DynamoSchemas.RunLedgerEntry,
              args.runId,
              ledgerSequence,
              stage,
              attempt,
              state,
              nowMillis(),
              detail,
              m.provenance
            )
          ).map(_ => out(s"   ledger: recorded $stage attempt $attempt $state"))
      }
