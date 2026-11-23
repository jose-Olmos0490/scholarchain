package scholarchain.services.runtime

import scholarchain.services.api.ApiService
import scholarchain.services.config.{ExecutionSettings, RuntimeLimits, ServiceSettings}
import scholarchain.services.evidence.EvidenceService
import scholarchain.services.explain.ExplainService
import scholarchain.services.graph.GraphService
import scholarchain.services.http.{IdGenerator, RandomIdGenerator, ServiceClock, SystemServiceClock}
import scholarchain.services.ops.{AccessLog, Metrics, OpsService, ReadinessProbe, Slf4jAccessLog}
import scholarchain.services.query.{InMemoryJobStore, JobStore, QueryEngine, QueryService}
import scholarchain.services.resilience.{
  BoundedExecutor,
  CircuitBreaker,
  Downstream,
  Retrier,
  Sleeper,
  ThreadSleeper
}
import scholarchain.services.security.{Authenticator, RateLimiter}
import scholarchain.storage.dynamo.{EvidenceStore, QueryAuditLog, QuerySessions}
import scholarchain.storage.graph.GraphReader

/** Everything the services need from the other modules, as ports only. Nothing here is a
  * concrete adapter: the caller passes the Graph-RAG engine and the storage ports.
  *
  * @param engine
  *   the Graph-RAG runtime (production: [[scholarchain.services.query.RagQueryEngine]])
  * @param probes
  *   readiness checks, each a cheap call through one of the ports
  */
final case class RuntimePorts(
    engine: QueryEngine,
    sessions: QuerySessions,
    evidence: EvidenceStore,
    audit: QueryAuditLog,
    graph: GraphReader,
    jobs: JobStore,
    probes: Vector[ReadinessProbe]
)

/** The composition root. Dependency graph:
  *
  * {{{
  * ApiService
  *   |- Authenticator, RateLimiter
  *   |- QueryService -- QueryEngine (rag), QuerySessions (storage), JobStore,
  *   |                  BoundedExecutor, CircuitBreaker "workflow", Retrier
  *   |- EvidenceService -- EvidenceStore (storage) via Downstream "evidence"
  *   |- GraphService    -- GraphReader (storage) via Downstream "graph"
  *   |- ExplainService  -- QueryAuditLog, EvidenceStore (storage), JobStore via Downstream "evidence"
  *   |- OpsService      -- ReadinessProbes, Metrics
  * }}}
  */
final class ServiceRuntime private (val api: ApiService, executors: Seq[BoundedExecutor]):
  def close(): Unit = executors.foreach(_.shutdown())

object ServiceRuntime:
  def build(
      settings: ServiceSettings,
      limits: RuntimeLimits,
      ports: RuntimePorts,
      authenticator: Authenticator,
      clock: ServiceClock = SystemServiceClock,
      ids: IdGenerator = RandomIdGenerator,
      sleeper: Sleeper = ThreadSleeper,
      accessLog: AccessLog = Slf4jAccessLog
  ): ServiceRuntime =
    val executor = new BoundedExecutor(settings.execution)
    val probeSize = math.max(1, ports.probes.size)
    val probeExecutor = new BoundedExecutor(ExecutionSettings(probeSize, probeSize))
    val retrier = new Retrier(settings.retry, sleeper)
    val workflowBreaker = new CircuitBreaker("workflow", settings.breaker, clock)
    val graphBreaker = new CircuitBreaker("graph", settings.breaker, clock)
    val evidenceBreaker = new CircuitBreaker("evidence", settings.breaker, clock)
    val graphDown = new Downstream(graphBreaker, retrier, executor, settings.retry)
    val evidenceDown = new Downstream(evidenceBreaker, retrier, executor, settings.retry)
    val metrics = new Metrics
    val queries = new QueryService(
      ports.engine,
      ports.sessions,
      ports.jobs,
      executor,
      workflowBreaker,
      retrier,
      settings,
      limits,
      clock,
      ids
    )
    val ops = new OpsService(
      ports.probes,
      probeExecutor,
      settings.ops,
      metrics,
      Seq(workflowBreaker, graphBreaker, evidenceBreaker)
    )
    val api = new ApiService(
      settings,
      authenticator,
      new RateLimiter(settings.rateLimit, clock),
      queries,
      ports.jobs,
      new EvidenceService(ports.evidence, evidenceDown, settings.evidence),
      new GraphService(ports.graph, graphDown, limits, settings.graph),
      new ExplainService(ports.audit, ports.evidence, ports.jobs, evidenceDown),
      ops,
      metrics,
      accessLog,
      ids
    )
    new ServiceRuntime(api, Seq(executor, probeExecutor))

  def newJobStore(): JobStore = new InMemoryJobStore
