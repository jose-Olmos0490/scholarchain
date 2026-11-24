package scholarchain.storage.loader

import java.nio.file.{Files, Path}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import scholarchain.core.config.ServiceLimits
import scholarchain.storage.{StorageSettings, requireGraphReadModelTable}
import scholarchain.storage.aws.*
import scholarchain.storage.common.*
import scholarchain.storage.dynamo.*
import scholarchain.storage.graph.GraphLoader
import scholarchain.storage.objects.*

/** Entry point that loads one Spark run into the stores.
  *
  * {{{
  *   sbt "storage/runMain scholarchain.storage.loader.LoadMain \
  *     --spark-output <dir> --run-id run:<64 hex> --snapshot <label> \
  *     [--dry-run] [--targets dynamo,s3,neptune-csv,neptune] [--work-dir <dir>]"
  * }}}
  *
  * The `neptune` target is the only one that calls the Neptune endpoint (it is never a default). It
  * starts the bulk load of `neptune-export/run_id=<hex>/csv/` and polls it to a terminal state; see
  * [[NeptuneLoadStep]]. With `--dry-run` it prints the planned request and makes no call. It runs
  * after the staging steps of the same invocation, and only when they all succeeded.
  *
  * `--dry-run` reads only local files, builds no AWS client and needs no configuration; it runs the
  * real loaders against in-memory fakes and prints counts, keys and sample items.
  *
  * A live run reads every bucket and table name from the environment (`StorageSettings`), uses the
  * default AWS credential chain, and never calls the Neptune endpoint: the Neptune step only
  * writes bulk-load CSV to S3.
  *
  * Exit status: 0 loaded, 1 failed, 2 bad arguments or configuration, 3 loaded with rejects.
  */
object LoadMain:
  /** Optional: ledger position of the load stages. */
  val LedgerSequenceVar = "SCHOLARCHAIN_STORAGE_LOAD_LEDGER_SEQUENCE"
  private val SampleSize = 5

  /** The loader a live `neptune` step uses, and what to close afterwards. */
  type NeptuneLoaderFactory = (StorageSettings, String) => (GraphLoader, AutoCloseable)

  /** Production factory: the default credential chain and the configured Neptune endpoint. */
  val awsNeptuneLoader: NeptuneLoaderFactory = (settings, roleArn) =>
    val clients = AwsClients.forRuntime(settings)
    val layout = ObjectLayout
      .fromConfig(settings.buckets, settings.runtime.s3Prefixes, ObjectArea.Rag)
      .fold(e => throw new StorageException(e), identity)
    val retrier = new Retrier(settings.runtime.retry, AwsErrors.isTransient)
    (
      new NeptuneBulkLoader(
        clients.neptune,
        layout,
        roleArn,
        settings.awsRegion,
        settings.runtime.batch.neptuneLoaderParallelism,
        retrier
      ),
      clients
    )

  def main(args: Array[String]): Unit =
    sys.exit(execute(args.toVector, sys.env, println))

  /** @param dryItems
    *   the store a dry run writes to (in-memory by default; tests inject their own)
    */
  def execute(
      args: Vector[String],
      env: Map[String, String],
      out: String => Unit,
      dryItems: () => ItemStore = () => new InMemoryItemStore,
      neptuneLoader: NeptuneLoaderFactory = awsNeptuneLoader,
      sleeper: Sleeper = Sleeper.Real,
      nowMillis: () => Long = () => System.currentTimeMillis()
  ): Int =
    LoadArgs.parse(args) match
      case Left(e) =>
        out(s"error: $e")
        out(LoadArgs.Usage)
        2
      case Right(a) if a.targets.contains(LoadStep.NeptuneLoad) =>
        // validate the Neptune step's configuration first: nothing is staged if it cannot run
        NeptuneLoadConfig.resolve(a, env) match
          case Left(e) =>
            out(s"error: neptune step: $e")
            2
          case Right(plan) =>
            val staging = a.targets.filterNot(_ == LoadStep.NeptuneLoad)
            val code =
              if staging.isEmpty then 0
              else stage(a.copy(targets = staging), env, out, dryItems)
            if code != 0 then
              out(s"neptune bulk load NOT started: the staging steps ended with status $code")
              code
            else neptune(a, plan, env, out, neptuneLoader, sleeper, nowMillis)
      case Right(a) => stage(a, env, out, dryItems)

  private def neptune(
      a: LoadArgs,
      plan: NeptuneLoadPlan,
      env: Map[String, String],
      out: String => Unit,
      factory: NeptuneLoaderFactory,
      sleeper: Sleeper,
      nowMillis: () => Long
  ): Int =
    out(s"== step ${LoadStep.NeptuneLoad.wire}")
    out(s"   POST StartLoaderJob (run ${plan.runId}); parameters:")
    plan.bulk.lines.foreach(l => out(s"     $l"))
    out(
      s"   wait at most ${plan.timeoutMillis / 1000}s, poll every ${plan.pollMillis / 1000}s; " +
        "mode=NEW re-submits the same IDs: see the idempotency note in the module report"
    )
    if a.dryRun then
      out("-- dry-run: no AWS client was built and no Neptune call was made")
      0
    else
      val built = for
        settings <- StorageSettings.load(env).left.map(_.message)
        pair <- Try(factory(settings, plan.bulk.roleArn)).toEither.left.map(e =>
          s"cannot build the Neptune loader: ${e.getMessage}"
        )
      yield pair
      built match
        case Left(e) =>
          out(s"error: $e")
          2
        case Right((loader, closer)) =>
          try
            new NeptuneLoadStep(loader, sleeper, nowMillis, out).run(plan) match
              case Right(r) =>
                out(
                  s"   NEPTUNE LOAD COMPLETED: loadId=${r.loadId} polls=${r.polls} " +
                    s"elapsed=${r.elapsedMillis / 1000}s"
                )
                0
              case Left(e) =>
                out(s"NEPTUNE LOAD FAILED: ${e.message}")
                1
          catch
            case NonFatal(e) =>
              out(s"NEPTUNE LOAD FAILED: unexpected ${e.getClass.getSimpleName}: ${e.getMessage}")
              1
          finally Try(closer.close())

  private def stage(
      a: LoadArgs,
      env: Map[String, String],
      out: String => Unit,
      dryItems: () => ItemStore
  ): Int =
    a match
      case a if !Files.isDirectory(a.sparkOutput) =>
        out(s"error: --spark-output ${a.sparkOutput} is not a directory")
        2
      case a =>
        Try {
          val work = a.workDir.getOrElse(Files.createTempDirectory("scholarchain-load-"))
          Files.createDirectories(work)
          (work, new JsonLinesRejectSink(work.resolve("rejected-load.jsonl")))
        } match
          case Failure(e) =>
            out(s"error: cannot prepare the work directory: ${e.getMessage}")
            2
          case Success((work, rejects)) =>
            out(
              s"run=${a.runId} snapshot=${a.snapshot} mode=${
                  if a.dryRun then "DRY-RUN" else "LIVE"
                }"
            )
            out(s"work dir: $work; rejects: ${work.resolve("rejected-load.jsonl")}")
            try
              if a.dryRun then dry(a, env, work, rejects, out, dryItems())
              else live(a, env, work, rejects, out)
            catch
              case NonFatal(e) =>
                out(s"LOAD FAILED: unexpected ${e.getClass.getSimpleName}: ${e.getMessage}")
                1

  private[storage] def finish(
      r: Result[LoadOutcome],
      rejects: RejectSink,
      out: String => Unit
  ): Int =
    r match
      case Left(e) =>
        out(s"LOAD FAILED: ${e.message}")
        1
      case Right(o) =>
        out(s"steps done: ${o.steps.mkString(", ")}; rejects recorded: ${o.rejected}")
        if o.rejected > 0 then
          rejects.all
            .take(SampleSize)
            .foreach(r => out(s"   reject ${r.store} ${r.reasonCode} ${r.key}"))
          3
        else 0

  private def dry(
      a: LoadArgs,
      env: Map[String, String],
      work: Path,
      rejects: RejectSink,
      out: String => Unit,
      store: ItemStore
  ): Int =
    val chunk = StorageSettings
      .load(env)
      .toOption
      .map(_.runtime.batch.dynamoBatchWrite)
      .getOrElse(ServiceLimits.DynamoBatchWriteMaxItems)
    val items = new SamplingItemStore(store, SampleSize)
    val objects = new InMemoryObjectStore
    val cache = Some(new DynamoResearcherMetricCache(items, "dry-run-metric-cache"))
    val runner = new LoadRunner(
      objects,
      items,
      "dry-run-graph-read-model",
      cache,
      None,
      chunk,
      rejects,
      work,
      0,
      () => 0L,
      out
    )
    val r = runner.run(a)
    if r.isRight then
      out("-- dry-run: DynamoDB items that would be written, by kind")
      items.kindCounts.foreach((k, n) => out(s"   $k: $n"))
      items.sampleLines.foreach(l => out(s"   sample $l"))
      val planned = objects.snapshot
      out(s"-- dry-run: ${planned.size} S3 objects that would be written")
      planned
        .take(SampleSize)
        .foreach((ref, hash) => out(s"   sample ${ref.area.wire}/${ref.key} $hash"))
      out("-- dry-run: no AWS client was built; nothing was written outside the work dir")
    finish(r, rejects, out)

  private def live(
      a: LoadArgs,
      env: Map[String, String],
      work: Path,
      rejects: RejectSink,
      out: String => Unit
  ): Int =
    val prepared = for
      settings <- StorageSettings.load(env).left.map(e => s"configuration: ${e.message}")
      table <- settings.requireGraphReadModelTable.left.map(_.message)
      layout <- ObjectLayout
        .fromConfig(settings.buckets, settings.runtime.s3Prefixes, ObjectArea.Rag)
        .left
        .map(_.message)
      seq <- env.get(LedgerSequenceVar).map(_.trim).filter(_.nonEmpty) match
        case None => Right(0)
        case Some(s) =>
          s.toIntOption
            .filter(n => n >= 0 && n <= 99999)
            .toRight(s"$LedgerSequenceVar='$s' must be an integer in 0..99999")
    yield (settings, table, layout, seq)
    prepared match
      case Left(e) =>
        out(s"error: $e")
        2
      case Right((settings, table, layout, seq)) =>
        Try(AwsClients.forRuntime(settings)) match
          case Failure(e) =>
            out(s"error: cannot build the AWS clients: ${e.getMessage}")
            2
          case Success(clients) =>
            runLive(a, rejects, out, work, settings, table, layout, seq, clients)

  private def runLive(
      a: LoadArgs,
      rejects: RejectSink,
      out: String => Unit,
      work: Path,
      settings: StorageSettings,
      table: String,
      layout: ObjectLayout,
      seq: Int,
      clients: AwsClients
  ): Int =
    try
      val retrier = new Retrier(settings.runtime.retry, AwsErrors.isTransient)
      val items = new DynamoItemStore(clients.dynamo, retrier)
      val ports = DynamoPorts(
        items,
        settings.runtime.dynamoTables,
        settings.runtime.querySession.ttlSeconds
      )
      out(
        s"buckets: graph=${settings.buckets.graphBucket} aux=${layout.bucket(ObjectArea.NeptuneExport)}; " +
          s"read model table=$table; run ledger table=${settings.runtime.dynamoTables.runLedger}"
      )
      val runner = new LoadRunner(
        new S3ObjectStore(clients.s3, layout, retrier),
        items,
        table,
        ports.metricCache,
        Some(ports.runLedger),
        settings.runtime.batch.dynamoBatchWrite,
        rejects,
        work,
        seq,
        () => System.currentTimeMillis(),
        out
      )
      finish(runner.run(a), rejects, out)
    finally clients.close()
