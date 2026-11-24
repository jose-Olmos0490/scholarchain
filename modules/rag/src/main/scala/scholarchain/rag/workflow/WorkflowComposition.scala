package scholarchain.rag.workflow

import scholarchain.core.config.{RagConfig, RetrievalConfig}
import scholarchain.rag.agents.*
import scholarchain.rag.answer.*
import scholarchain.rag.embed.Embedder
import scholarchain.rag.llm.{LlmClient, PromptRepository, StructuredLlmCall}
import scholarchain.storage.directory.ResearcherDirectory
import scholarchain.storage.dynamo.ResearcherMetricCache
import scholarchain.storage.graph.*
import scholarchain.storage.objects.{ObjectLayout, ObjectStore}
import scholarchain.storage.vectors.VectorIndex

/** Everything [[WorkflowComposition]] needs, all injected: the store ports, the model client, the
  * embedder and the configuration. Nothing here is defaulted, so no endpoint, table, index, model
  * or snapshot is hardcoded in the module.
  */
final case class WorkflowDependencies(
    llm: LlmClient,
    prompts: PromptRepository,
    embedder: Embedder,
    audit: AuditPorts,
    directory: ResearcherDirectory,
    metrics: MetricReader,
    metricCache: Option[ResearcherMetricCache],
    graph: GraphReader,
    edgeSupport: EdgeSupportReader,
    pathReader: PathReader,
    analytics: AnalyticsReader,
    vectors: VectorIndex,
    objects: ObjectStore,
    layout: ObjectLayout,
    indexNames: Vector[String]
)

/** Limits and versions that are configuration, not code. */
final case class WorkflowTuning(
    rag: RagConfig,
    retrieval: RetrievalConfig,
    synthesis: SynthesisConfig,
    snapshot: String,
    stageTimeoutMillis: Long,
    defaultWindow: Option[(Int, Int)],
    plannerMaxQueryCodePoints: Int,
    maxExcerptCodePoints: Int,
    maxSemanticQueryCodePoints: Int
)

/** Builds the [[AnswerWorkflow]] from injected ports. A service calls this once at start-up. */
object WorkflowComposition:
  def agents(d: WorkflowDependencies, t: WorkflowTuning): WorkflowAgents =
    val structured = new StructuredLlmCall(d.llm, t.rag.llm)
    WorkflowAgents(
      new QueryPlannerAgent(
        t.rag.planner,
        t.retrieval,
        structured,
        d.prompts,
        t.plannerMaxQueryCodePoints
      ),
      new ResearcherResolverAgent(d.directory, t.rag.resolver),
      new CollaborationMetricsAgent(d.metrics, d.metricCache, t.retrieval),
      new DirectCoauthorsAgent(d.graph, d.edgeSupport, t.retrieval, t.snapshot),
      new PathFinderAgent(d.pathReader, t.retrieval, t.snapshot),
      new CommunityAnalystAgent(d.analytics, d.metricCache, t.retrieval),
      new SemanticContextAgent(
        d.vectors,
        d.objects,
        d.layout,
        d.embedder,
        d.indexNames,
        t.retrieval,
        t.maxExcerptCodePoints,
        t.maxSemanticQueryCodePoints
      ),
      new EvidenceBuilderAgent(t.retrieval, t.snapshot),
      new AnswerSynthesisAgent(t.synthesis, structured, d.prompts),
      new ClaimVerificationAgent
    )

  def workflow(
      d: WorkflowDependencies,
      t: WorkflowTuning,
      clock: WorkflowClock = SystemWorkflowClock,
      sink: TraceSink = TraceSink.Silent
  ): AnswerWorkflow =
    new AnswerWorkflow(
      agents(d, t),
      d.audit,
      WorkflowSettings(t.stageTimeoutMillis, t.defaultWindow, t.synthesis),
      clock,
      sink
    )
