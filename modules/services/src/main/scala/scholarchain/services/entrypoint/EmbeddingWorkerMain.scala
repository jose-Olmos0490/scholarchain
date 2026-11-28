package scholarchain.services.entrypoint

import java.util.concurrent.{
  Callable,
  CountDownLatch,
  ExecutionException,
  Executors,
  ThreadFactory,
  TimeUnit,
  TimeoutException
}
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.core.config.EmbeddingProviderConfig
import scholarchain.rag.embed.Embedder
import scholarchain.rag.provider.{EmbeddingPurpose, RetryDelays}
import scholarchain.services.config.{
  EntrypointSettings,
  ProviderKeySettings,
  ServiceSettings,
  WorkerSettings
}
import scholarchain.services.http.{ServiceClock, SystemServiceClock}
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.runtime.{RuntimeEnvironment, ServiceRuntime}
import scholarchain.storage.StorageSettings
import scholarchain.storage.aws.SqsWorkQueue
import scholarchain.storage.common.{Jitter, Result, Sleeper, StorageError}
import scholarchain.storage.queue.{ConsumerReport, EmbeddingWorkItem, EmbeddingWorker, WorkConsumer}
import scholarchain.storage.vectors.*

/** What the consumer loop reports about itself, for `/readyz`. Time is injected.
  *
  * The worker is ready when its loop runs, has not been told to stop, has made progress (a receive
  * or a message handled) within `stalenessMillis`, and has not failed `maxConsecutiveFailures`
  * receives in a row. `stalenessMillis` is validated to exceed one long poll plus one longest
  * ingest run, so a worker busy with a long message is not reported dead.
  */
final class ConsumerHealth(
    clock: ServiceClock,
    stalenessMillis: Long,
    maxConsecutiveFailures: Int
):
  @volatile private var lastProgressAt = clock.nowMillis
  @volatile private var failures = 0
  @volatile private var running = false
  @volatile private var stopping = false

  def loopStarted(): Unit =
    lastProgressAt = clock.nowMillis
    running = true
  def loopEnded(): Unit = running = false
  def markStopping(): Unit = stopping = true
  def progress(): Unit = lastProgressAt = clock.nowMillis
  def pollSucceeded(): Unit = failures = 0
  def pollFailed(): Unit = failures += 1

  def ready: Boolean =
    running && !stopping && failures < maxConsecutiveFailures &&
      clock.nowMillis - lastProgressAt <= stalenessMillis

/** The consumer loop: receive, handle, repeat, until asked to stop.
  *
  * Bounds: one receive takes at most `batchSize` messages and the stop request is checked between
  * receives, so a stop waits only for the batch in hand (and one long poll). A failed receive
  * pauses `idleBackoffMillis`; an empty receive pauses the same only when the poll itself does not
  * wait (`waitSeconds = 0`), so the loop never spins on the queue. Nothing here reads a message
  * body: the report holds counts and message IDs.
  */
final class WorkerLoop(
    poll: () => Result[ConsumerReport],
    health: ConsumerHealth,
    idleBackoffMillis: Long,
    pauseWhenEmptyMillis: Long
):
  private val log = LoggerFactory.getLogger("scholarchain.services.embedding-worker")
  private val stop = new CountDownLatch(1)
  private val done = new CountDownLatch(1)

  def requestStop(): Unit =
    health.markStopping()
    stop.countDown()

  def stopRequested: Boolean = stop.getCount == 0
  def awaitStopped(millis: Long): Boolean = done.await(millis, TimeUnit.MILLISECONDS)

  def run(): Unit =
    health.loopStarted()
    try
      while !stopRequested do
        health.progress()
        val outcome: Result[ConsumerReport] =
          try poll()
          catch case NonFatal(_) => Left(StorageError.Transient("embedding poll", 1, "poll threw"))
        health.progress()
        outcome match
          case Right(report) =>
            health.pollSucceeded()
            if report.received > 0 then
              log.info(
                "batch received={} acknowledged={} retried={} deadLettered={}",
                Int.box(report.received),
                Int.box(report.acknowledged),
                Int.box(report.retried),
                Int.box(report.deadLettered)
              )
            else if pauseWhenEmptyMillis > 0 then
              stop.await(pauseWhenEmptyMillis, TimeUnit.MILLISECONDS)
          case Left(e) =>
            health.pollFailed()
            log.warn("receive failed kind={}", e.getClass.getSimpleName)
            stop.await(idleBackoffMillis, TimeUnit.MILLISECONDS)
    finally
      health.loopEnded()
      done.countDown()

/** Bounds one message's run. A run past `maxSeconds` is interrupted and reported as a time-out, so
  * the message goes back to the queue (ingestion is idempotent) instead of holding the worker.
  */
object IngestDeadline:
  def apply(
      handler: EmbeddingWorkItem => Result[Unit],
      maxSeconds: Int
  ): EmbeddingWorkItem => Result[Unit] =
    val pool = Executors.newCachedThreadPool(new ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, "embedding-ingest")
        t.setDaemon(true)
        t)
    item =>
      val future = pool.submit(new Callable[Result[Unit]]:
        def call(): Result[Unit] = handler(item))
      try future.get(maxSeconds.toLong, TimeUnit.SECONDS)
      catch
        case _: TimeoutException =>
          future.cancel(true)
          Left(StorageError.Timeout("embedding ingest", maxSeconds * 1000L))
        case _: ExecutionException =>
          Left(StorageError.Transient("embedding ingest", 1, "the ingest threw"))
        case _: InterruptedException =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          Left(StorageError.Transient("embedding ingest", 1, "interrupted"))

/** Everything the worker root needs, as values. `poll` is one receive-and-handle step of the
  * consumer ([[WorkConsumer.runOnce]] in production).
  */
final case class EmbeddingWorkerInputs(
    settings: ServiceSettings,
    entry: WorkerSettings,
    poll: () => Result[ConsumerReport],
    probes: Vector[ReadinessProbe],
    env: RuntimeEnvironment = RuntimeEnvironment()
)

/** A started worker: the loop thread and its own health listener. */
final class RunningWorker(
    http: RunningHttp,
    val loop: WorkerLoop,
    thread: Thread,
    graceMillis: Long
):
  /** The port of `/healthz` and `/readyz`. */
  def healthPort: Int = http.port

  /** Asks the loop to stop after the batch in hand, waits at most the grace period for it, then
    * closes the health listener. True when the loop ended within the grace period.
    */
  def shutdown(): Boolean =
    loop.requestStop()
    val clean = loop.awaitStopped(graceMillis)
    http.stop()
    clean

  /** Blocks until the loop thread ends. */
  def awaitExit(): Unit = thread.join()

/** The embedding worker composition root: a long-running consumer of the embedding queue, with
  * `/healthz` and `/readyz` on its own port. It serves no API route and needs neither the graph,
  * the query API nor the graph API. Scale it on the queue's depth (Terraform); every instance
  * competes for messages and ingestion is idempotent.
  */
object EmbeddingWorkerRoot:
  def start(in: EmbeddingWorkerInputs): RunningWorker =
    val stalenessMillis = in.entry.pollStalenessSeconds * 1000L
    val health = new ConsumerHealth(in.env.clock, stalenessMillis, in.entry.maxConsecutiveFailures)
    val probes = ReadinessProbe("consumer", () => health.ready) +: in.probes
    val http = RunningHttp.start(
      ServiceRuntime.probesOnly(in.settings, probes, in.env),
      in.settings.http.maxBodyBytes,
      in.entry.health
    )
    val pauseWhenEmpty =
      if in.entry.consumer.waitSeconds == 0 then in.entry.idleBackoffMillis else 0L
    val loop = new WorkerLoop(in.poll, health, in.entry.idleBackoffMillis, pauseWhenEmpty)
    val thread = new Thread(() => loop.run(), "embedding-worker-loop")
    thread.setDaemon(false)
    thread.start()
    new RunningWorker(http, loop, thread, in.entry.shutdownGraceSeconds * 1000L)

/** The live embedding worker. SIGTERM stops the loop after the batch in hand (bounded by
  * `shutdownGraceSeconds`), then the process exits.
  */
object EmbeddingWorkerMain:
  private val log = LoggerFactory.getLogger("scholarchain.services.embedding-worker")

  def main(args: Array[String]): Unit =
    open(sys.env) match
      case Left(problems) =>
        problems.foreach(p => log.error("configuration: {}", p))
        sys.exit(2)
      case Right((in, resources)) =>
        val running = EmbeddingWorkerRoot.start(in)
        log.info("embedding-worker health on port {}", Int.box(running.healthPort))
        sys.addShutdownHook {
          log.info("stop requested")
          if !running.shutdown() then
            log.warn("the consumer loop did not stop within the grace period")
          resources.close()
        }
        running.awaitExit()
        // the loop ended without a stop request: let the orchestrator restart the task
        if !running.loop.stopRequested then
          log.error("the consumer loop ended unexpectedly")
          sys.exit(1)

  /** Closes what [[open]] built. */
  final class Resources(stores: LiveStores, queues: Seq[AutoCloseable]):
    def close(): Unit =
      queues.foreach(q =>
        try q.close()
        catch case NonFatal(_) => ()
      )
      stores.close()

  def open(env: Map[String, String]): Either[Vector[String], (EmbeddingWorkerInputs, Resources)] =
    for
      svc <- ServiceSettings.load(requireServing = false)
      entry <- EntrypointSettings.embeddingWorker()
      keys <- EntrypointSettings.providerKeys()
      storage <- StorageSettings.load(env).left.map(e => Vector(e.message))
      embedding <- CoreConfigs.embedding(env).left.map(e => Vector(e.message))
      ingestion <- IngestionSettings.load(storage, env).left.map(e => Vector(e.message))
      stores <- LiveStores.open(storage).left.map(Vector(_))
      built <- wire(env, svc, entry, keys, storage, embedding, ingestion, stores) match
        case l @ Left(_) =>
          stores.close()
          l
        case r => r
    yield built

  private def wire(
      env: Map[String, String],
      svc: ServiceSettings,
      entry: WorkerSettings,
      keys: ProviderKeySettings,
      storage: StorageSettings,
      embedding: EmbeddingProviderConfig,
      ingestion: IngestionSettings,
      stores: LiveStores
  ): Either[Vector[String], (EmbeddingWorkerInputs, Resources)] =
    val defs = stores.definitions
    for
      _ <- Either.cond(
        defs.map(_.dimension).distinct.size == 1,
        (),
        Vector("all vector indexes must share one dimension: one embedder serves them")
      )
      model <- ProviderWiring
        .embedder(
          embedding,
          env,
          EmbeddingPurpose.Ingest,
          keys,
          () => ProviderWiring.secretsManager(storage.awsRegion, keys),
          SystemServiceClock,
          RetryDelays(
            storage.runtime.retry.baseDelayMs.toLong,
            storage.runtime.retry.maxDelayMs.toLong
          ),
          storage.awsRegion
        )
        .left
        .map(Vector(_))
      embed <- Embedder
        .forIndex(model.adapter, defs.head.name, defs.head.dimension)
        .left
        .map(p => Vector(p.message))
    yield
      val sqs = SqsWorkQueue.client(storage)
      val queue = new SqsWorkQueue(sqs, entry.queueUrl, stores.retrier)
      val deadLetter = new SqsWorkQueue(sqs, entry.deadLetterQueueUrl, stores.retrier)
      val objects = stores.objects
      val manifests = new ManifestStore(objects, stores.ragLayout)
      val time = new MonotonicClock with Sleeper:
        def nanos: Long = java.lang.System.nanoTime()
        def sleep(ms: Long): Unit = Sleeper.Real.sleep(ms)
      val ingestor = new VectorIngestor(
        stores.vectorIndex(singleAttempt = true),
        defs,
        new DocumentStore(objects, stores.ragLayout),
        manifests,
        objects,
        stores.ragLayout,
        stores.dynamo.runLedger,
        stores.dynamo.artifacts,
        new LoggingRejectSink,
        embed,
        ingestion,
        new RateLimiter(ingestion.rateLimitPerSecond, time, time),
        time,
        Jitter.random()
      )
      val consumer = new WorkConsumer(
        queue,
        deadLetter,
        EmbeddingWorkItem.decode,
        IngestDeadline(EmbeddingWorker.handler(manifests, ingestor), entry.maxIngestRunSeconds),
        entry.consumer,
        new LoggingRejectSink
      )
      (
        EmbeddingWorkerInputs(
          svc,
          entry,
          ProviderWiring.gated(() => consumer.runOnce(), model.key),
          stores.vectorProbe +: model.probes,
          RuntimeEnvironment(clock = SystemServiceClock)
        ),
        new Resources(stores, Seq(sqs))
      )
