package scholarchain.services.runtime

import scholarchain.services.api.{ApiService, QueryRoutes, ResourceRoutes, Route}
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
import scholarchain.services.security.{AuthFailureLimiter, Authenticator, RateLimiter}
import scholarchain.storage.dynamo.{EvidenceStore, QueryAuditLog, QuerySessions}
import scholarchain.storage.graph.GraphReader

/** Everything the services need from the other modules, as ports only. Nothing here is a
  * concrete adapter: the caller passes the Graph-RAG engine and the storage ports. This is the
  * all-in-one set (every route in one process: local runs and API tests). The deployed
  * entrypoints use the smaller [[QueryApiPorts]] and [[ResourceApiPorts]].
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

/** What the query API uses: the engine, sessions, audit, evidence (explain) and jobs. No graph. */
final case class QueryApiPorts(
    engine: QueryEngine,
    sessions: QuerySessions,
    evidence: EvidenceStore,
    audit: QueryAuditLog,
    jobs: JobStore,
    probes: Vector[ReadinessProbe]
)

/** What the graph and evidence API uses: the graph reader and the evidence store. */
final case class ResourceApiPorts(
    evidence: EvidenceStore,
    graph: GraphReader,
    probes: Vector[ReadinessProbe]
)

/** The clock, ID source, sleeper and access log of a runtime; tests replace them. */
final case class RuntimeEnvironment(
    clock: ServiceClock = SystemServiceClock,
    ids: IdGenerator = RandomIdGenerator,
    sleeper: Sleeper = ThreadSleeper,
    accessLog: AccessLog = Slf4jAccessLog
)

/** The composition root of the HTTP services. Dependency graph (each entrypoint builds only its
  * own branch):
  *
  * {{{
  * query API          ApiService
  *                      |- Authenticator, RateLimiter, AuthFailureLimiter
  *                      |- QueryRoutes -- QueryService -- QueryEngine (rag), QuerySessions
  *                      |                  (storage), JobStore, BoundedExecutor,
  *                      |                  CircuitBreaker "workflow", Retrier
  *                      |             `- ExplainService -- QueryAuditLog, EvidenceStore, JobStore
  *                      |                                  via Downstream "evidence"
  *                      `- OpsService -- ReadinessProbes, Metrics
  * graph/evidence API ApiService
  *                      |- Authenticator, RateLimiter, AuthFailureLimiter
  *                      |- ResourceRoutes -- EvidenceService (EvidenceStore, Downstream "evidence")
  *                      |                 `- GraphService (GraphReader, Downstream "graph")
  *                      `- OpsService -- ReadinessProbes, Metrics
  * embedding worker   ApiService with no routes but /healthz, /readyz (its own port)
  * }}}
  */
final class ServiceRuntime private (val api: ApiService, executors: Seq[BoundedExecutor]):
  def close(): Unit = executors.foreach(_.shutdown())

object ServiceRuntime:
  /** The all-in-one runtime: query, jobs, explain, evidence and graph in one process. */
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
    new Assembly(settings, authenticator, RuntimeEnvironment(clock, ids, sleeper, accessLog))
      .full(limits, ports)

  /** The query API: `POST /v1/query`, jobs and explain. */
  def queryApi(
      settings: ServiceSettings,
      limits: RuntimeLimits,
      ports: QueryApiPorts,
      authenticator: Authenticator,
      env: RuntimeEnvironment = RuntimeEnvironment()
  ): ServiceRuntime =
    new Assembly(settings, authenticator, env).queryApi(limits, ports)

  /** The graph and evidence read API. */
  def resourceApi(
      settings: ServiceSettings,
      limits: RuntimeLimits,
      ports: ResourceApiPorts,
      authenticator: Authenticator,
      env: RuntimeEnvironment = RuntimeEnvironment()
  ): ServiceRuntime =
    new Assembly(settings, authenticator, env).resourceApi(limits, ports)

  /** Only `/healthz` and `/readyz`: the worker's health port. No key can open any other route. */
  def probesOnly(
      settings: ServiceSettings,
      probes: Vector[ReadinessProbe],
      env: RuntimeEnvironment = RuntimeEnvironment()
  ): ServiceRuntime =
    new Assembly(settings, DenyAllAuthenticator, env).probesOnly(probes)

  def newJobStore(): JobStore = new InMemoryJobStore

  /** Rejects every request: an entrypoint with no protected route has nothing to authenticate. */
  private object DenyAllAuthenticator extends Authenticator:
    def authenticate(h: Option[String]) = Left(scholarchain.services.security.AuthFailure.Unknown)

  final private class Assembly(
      settings: ServiceSettings,
      authenticator: Authenticator,
      env: RuntimeEnvironment
  ):
    private val executor = new BoundedExecutor(settings.execution)
    private val metrics = new Metrics
    private val retrier = new Retrier(settings.retry, env.sleeper)

    private def breaker(name: String) = new CircuitBreaker(name, settings.breaker, env.clock)
    private def downstream(b: CircuitBreaker) = new Downstream(b, retrier, executor, settings.retry)

    private def finish(
        mounted: Vector[Route],
        probes: Vector[ReadinessProbe],
        breakers: Seq[CircuitBreaker]
    ): ServiceRuntime =
      val probeSize = math.max(1, probes.size)
      val probeExecutor = new BoundedExecutor(ExecutionSettings(probeSize, probeSize))
      val ops = new OpsService(probes, probeExecutor, settings.ops, metrics, breakers)
      val api = new ApiService(
        settings,
        authenticator,
        new RateLimiter(settings.rateLimit, env.clock),
        new AuthFailureLimiter(settings.auth.failures, env.clock),
        mounted,
        ops,
        metrics,
        env.accessLog,
        env.ids
      )
      new ServiceRuntime(api, Seq(executor, probeExecutor))

    private def queryRoutes(
        limits: RuntimeLimits,
        p: QueryApiPorts,
        workflow: CircuitBreaker,
        evidence: Downstream
    ): QueryRoutes =
      val queries = new QueryService(
        p.engine,
        p.sessions,
        p.jobs,
        executor,
        workflow,
        retrier,
        settings,
        limits,
        env.clock,
        env.ids
      )
      new QueryRoutes(queries, p.jobs, new ExplainService(p.audit, p.evidence, p.jobs, evidence))

    def queryApi(limits: RuntimeLimits, p: QueryApiPorts): ServiceRuntime =
      val workflowBreaker = breaker("workflow")
      val evidenceBreaker = breaker("evidence")
      val routes = queryRoutes(limits, p, workflowBreaker, downstream(evidenceBreaker))
      finish(routes.routes, p.probes, Seq(workflowBreaker, evidenceBreaker))

    def resourceApi(limits: RuntimeLimits, p: ResourceApiPorts): ServiceRuntime =
      val graphBreaker = breaker("graph")
      val evidenceBreaker = breaker("evidence")
      val routes = new ResourceRoutes(
        new EvidenceService(p.evidence, downstream(evidenceBreaker), settings.evidence),
        new GraphService(p.graph, downstream(graphBreaker), limits, settings.graph)
      )
      finish(routes.routes, p.probes, Seq(graphBreaker, evidenceBreaker))

    def full(limits: RuntimeLimits, p: RuntimePorts): ServiceRuntime =
      val workflowBreaker = breaker("workflow")
      val graphBreaker = breaker("graph")
      val evidenceBreaker = breaker("evidence")
      val evidenceDown = downstream(evidenceBreaker)
      val q = queryRoutes(
        limits,
        QueryApiPorts(p.engine, p.sessions, p.evidence, p.audit, p.jobs, p.probes),
        workflowBreaker,
        evidenceDown
      )
      val r = new ResourceRoutes(
        new EvidenceService(p.evidence, evidenceDown, settings.evidence),
        new GraphService(p.graph, downstream(graphBreaker), limits, settings.graph)
      )
      finish(
        q.routes ++ r.routes,
        p.probes,
        Seq(workflowBreaker, graphBreaker, evidenceBreaker)
      )

    def probesOnly(probes: Vector[ReadinessProbe]): ServiceRuntime =
      finish(Vector.empty, probes, Nil)
