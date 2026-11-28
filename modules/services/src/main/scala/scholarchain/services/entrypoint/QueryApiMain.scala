package scholarchain.services.entrypoint

import java.nio.file.Paths
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.rag.answer.SynthesisConfig
import scholarchain.rag.embed.Embedder
import scholarchain.rag.llm.{BedrockRetryPolicy, LlmClient, PromptRepository}
import scholarchain.rag.provider.{BedrockLlmSettings, EmbeddingPurpose, RetryDelays}
import scholarchain.rag.workflow.{
  SystemWorkflowClock,
  WorkflowComposition,
  WorkflowDependencies,
  WorkflowTuning
}
import scholarchain.services.config.{
  EntrypointSettings,
  LlmSettings,
  ProviderKeySettings,
  QueryApiSettings,
  RuntimeLimits,
  ServiceSettings
}
import scholarchain.services.http.{ServiceClock, SystemServiceClock}
import scholarchain.services.ops.ReadinessProbe
import scholarchain.services.query.{DynamoJobStore, JobStore, QueryEngine, RagQueryEngine}
import scholarchain.services.runtime.{QueryApiPorts, RuntimeEnvironment, ServiceRuntime}
import scholarchain.services.security.{Authenticator, SecretStringSource}
import scholarchain.storage.aws.{NeptuneGraphReader, NeptuneMetricReader}
import scholarchain.storage.common.StorageError
import scholarchain.storage.directory.{NameCandidate, ResearcherDirectory}
import scholarchain.storage.StorageSettings
import scholarchain.storage.dynamo.{
  DynamoAnalyticsReader,
  DynamoEdgeSupportReader,
  DynamoJobDocumentStore,
  DynamoResearcherDirectory,
  ItemStore
}
import scholarchain.storage.graph.*
import scholarchain.storage.{graphReaderLimits, requireGraphReadModelTable}

/** Everything the query API root needs, as values. The live `main` builds them from AWS adapters;
  * tests build them from fakes.
  */
final case class QueryApiInputs(
    settings: ServiceSettings,
    entry: QueryApiSettings,
    limits: RuntimeLimits,
    ports: QueryApiPorts,
    authenticator: Authenticator,
    env: RuntimeEnvironment = RuntimeEnvironment()
)

/** The query API composition root: `POST /v1/query`, `GET /v1/jobs/...`, `GET /v1/explain/...`,
  * `/healthz`, `/readyz`. It needs the Graph-RAG engine, the query-session, audit, evidence and job
  * stores, and the API keys. It does not need the graph API or the embedding worker.
  */
object QueryApiRoot:
  def start(in: QueryApiInputs): RunningHttp =
    RunningHttp.start(
      ServiceRuntime.queryApi(in.settings, in.limits, in.ports, in.authenticator, in.env),
      in.settings.http.maxBodyBytes,
      in.entry.bind
    )

/** Read adapters the answer workflow needs: the researcher directory (name -> researcher), the
  * edge-support reader (works behind an edge) and the analytics reader (components and
  * communities).
  */
final case class WorkflowReadPorts(
    directory: ResearcherDirectory,
    edgeSupport: EdgeSupportReader,
    analytics: AnalyticsReader
)

/** The live read adapters of the answer workflow, all over the one graph read model table. Table
  * name, run ID and limits come from configuration only. Each adapter has a readiness probe that
  * makes one bounded call through the adapter itself, so a missing table, a missing permission or
  * a store outage takes the service out of rotation by name.
  */
object ReadAdapters:
  val DirectoryProbe = "researcherDirectoryAdapter"
  val EdgeSupportProbe = "edgeSupportAdapter"
  val AnalyticsProbe = "analyticsAdapter"

  /** Name and window the probes ask for. No such record exists; a healthy store answers "none". */
  private val ProbeName = "readiness probe"
  private val ProbeKey = "readiness-probe"
  private val ProbeYear = 2000

  def open(
      items: ItemStore,
      storage: StorageSettings,
      runId: String
  ): Either[String, (WorkflowReadPorts, Vector[ReadinessProbe])] =
    storage.requireGraphReadModelTable.left.map(_.message).map { table =>
      val limits = storage.graphReaderLimits
      val directory = new DynamoResearcherDirectory(items, table, runId, limits.maxLimit)
      val support = new DynamoEdgeSupportReader(items, table, limits.maxLimit)
      val analytics = new DynamoAnalyticsReader(items, table, runId, limits)
      val probes = Vector(
        Probes.of(DirectoryProbe)(directory.candidatesByName(ProbeName, 1).isRight),
        // the support reader needs a real edge; its probe reads the same partition space directly
        Probes.of(EdgeSupportProbe)(
          items.query(table, s"esup#$runId#$ProbeKey", Some("w#"), true, Some(1)).isRight
        ),
        Probes.of(AnalyticsProbe)(
          analytics.crossCommunityEdges(ProbeKey, ProbeYear, ProbeYear, 1).isRight
        )
      )
      (WorkflowReadPorts(directory, support, analytics), probes)
    }

/** The job store over the query-session table, from configuration. */
object QueryJobs:
  def store(items: ItemStore, storage: StorageSettings): JobStore =
    new DynamoJobStore(
      new DynamoJobDocumentStore(
        items,
        storage.runtime.dynamoTables.querySession,
        Some(storage.runtime.querySession.ttlSeconds)
      )
    )

/** The model adapters of the query API and the readiness checks of their keys. */
final case class QueryModels(llm: Wired[LlmClient], embedder: Wired[Embedder]):
  def probes: Vector[ReadinessProbe] = llm.probes ++ embedder.probes

/** Builds the query API's models from the selected providers. Anthropic takes its attempts and
  * deadlines from the core `llm.anthropic.*` keys and only the retry delays from the services'
  * `llm.*`; Bedrock takes everything from the services' `llm.*`. Voyage embeds queries, with the
  * storage retry delays.
  */
object QueryModels:
  def open(
      env: Map[String, String],
      core: QueryCoreConfig,
      llmSettings: LlmSettings,
      keys: ProviderKeySettings,
      secrets: () => Either[String, SecretStringSource],
      clock: ServiceClock
  ): Either[String, QueryModels] =
    val s = core.storage
    // one Secrets Manager client serves both keys, and none is built for Bedrock
    lazy val source = secrets()
    for
      llm <- ProviderWiring.llm(
        core.llm,
        keys,
        () => source,
        clock,
        RetryDelays(llmSettings.baseDelayMs, llmSettings.maxDelayMs),
        BedrockLlmSettings(
          s.awsRegion,
          None,
          llmSettings.attemptTimeoutMillis,
          BedrockRetryPolicy(
            llmSettings.maxAttempts,
            llmSettings.baseDelayMs,
            llmSettings.maxDelayMs
          )
        )
      )
      embedder <- ProviderWiring.embedder(
        core.embedding,
        env,
        EmbeddingPurpose.Retrieval,
        keys,
        () => source,
        clock,
        RetryDelays(s.runtime.retry.baseDelayMs.toLong, s.runtime.retry.maxDelayMs.toLong),
        s.awsRegion
      )
    yield QueryModels(llm, embedder)

/** The live query API: real adapters, configuration only. */
object QueryApiMain:
  private val log = LoggerFactory.getLogger("scholarchain.services.query-api")

  def main(args: Array[String]): Unit =
    open(sys.env) match
      case Left(problems) =>
        // the problems name configuration keys only, never values
        problems.foreach(p => log.error("configuration: {}", p))
        sys.exit(2)
      case Right((in, stores)) =>
        val running = QueryApiRoot.start(in)
        log.info("query-api listening on port {}", Int.box(running.port))
        sys.addShutdownHook {
          running.stop()
          stores.close()
        }
        new java.util.concurrent.CountDownLatch(1).await()

  /** Builds the live inputs from the process environment. */
  def open(env: Map[String, String]): Either[Vector[String], (QueryApiInputs, LiveStores)] =
    for
      svc <- ServiceSettings.load()
      entry <- EntrypointSettings.queryApi()
      keys <- EntrypointSettings.providerKeys()
      core <- CoreConfigs.query(env).left.map(e => Vector(e.message))
      stores <- LiveStores.open(core.storage).left.map(Vector(_))
      built <- wire(env, svc, entry, keys, core, stores) match
        case l @ Left(_) =>
          stores.close()
          l
        case r => r
    yield (built, stores)

  private def wire(
      env: Map[String, String],
      svc: ServiceSettings,
      entry: QueryApiSettings,
      keys: ProviderKeySettings,
      core: QueryCoreConfig,
      stores: LiveStores
  ): Either[Vector[String], QueryApiInputs] =
    val s = core.storage
    for
      models <- QueryModels
        .open(
          env,
          core,
          entry.llm,
          keys,
          () => ProviderWiring.secretsManager(s.awsRegion, keys),
          SystemServiceClock
        )
        .left
        .map(Vector(_))
      prompts <- PromptRepository
        .load(Paths.get(entry.workflow.promptsDir))
        .left
        .map(f => Vector(f.message))
      auth <- LiveAuth.open(svc, s.awsRegion, SystemServiceClock).left.map(Vector(_))
      adapters <- ReadAdapters.open(stores.items, s, svc.serving.runId).left.map(Vector(_))
    yield
      val (reads, readProbes) = adapters
      val graphLimits = s.graphReaderLimits
      val neptuneGraph = new NeptuneGraphReader(stores.clients.neptune, graphLimits, stores.retrier)
      val dependencies = WorkflowDependencies(
        llm = models.llm.adapter,
        prompts = prompts,
        embedder = models.embedder.adapter,
        audit = scholarchain.rag.workflow.AuditPorts(
          stores.dynamo.querySessions,
          stores.dynamo.evidence,
          stores.dynamo.queryAudit
        ),
        directory = reads.directory,
        metrics = new NeptuneMetricReader(stores.clients.neptune, graphLimits, stores.retrier),
        metricCache = stores.dynamo.metricCache,
        graph = neptuneGraph,
        edgeSupport = reads.edgeSupport,
        pathReader = new SupportedPathReader(neptuneGraph, reads.edgeSupport),
        analytics = reads.analytics,
        vectors = stores.vectorIndex(),
        objects = stores.objects,
        layout = stores.ragLayout,
        indexNames = stores.definitions.map(_.name)
      )
      val w = entry.workflow
      val tuning = WorkflowTuning(
        rag = core.rag,
        retrieval = core.retrieval,
        synthesis = SynthesisConfig(w.synthesisPromptVersion, w.synthesisSchemaVersion),
        snapshot = svc.serving.snapshot,
        stageTimeoutMillis = w.stageTimeoutMillis,
        defaultWindow = w.defaultWindow,
        plannerMaxQueryCodePoints = w.plannerMaxQueryCodePoints,
        maxExcerptCodePoints = w.maxExcerptCodePoints,
        maxSemanticQueryCodePoints = w.maxSemanticQueryCodePoints
      )
      val engine: QueryEngine = new RagQueryEngine(
        WorkflowComposition.workflow(dependencies, tuning, SystemWorkflowClock, Slf4jTraceSink)
      )
      val jobs: JobStore = QueryJobs.store(stores.items, s)
      QueryApiInputs(
        svc,
        entry,
        core.limits,
        QueryApiPorts(
          engine,
          stores.dynamo.querySessions,
          stores.dynamo.evidence,
          stores.dynamo.queryAudit,
          jobs,
          Vector(
            stores.dynamoProbe,
            stores.neptuneProbe,
            stores.vectorProbe,
            stores.objectProbe,
            auth._2
          ) ++ readProbes ++ models.probes
        ),
        auth._1
      )
