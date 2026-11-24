package scholarchain.services.config

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import scala.jdk.CollectionConverters.*
import scholarchain.core.config.{OperationsConfig, RetrievalConfig}

final case class HttpSettings(
    maxBodyBytes: Int,
    maxQuestionChars: Int,
    maxListItems: Int,
    idempotencyKeyMinChars: Int,
    idempotencyKeyMaxChars: Int
)
final case class RateLimitSettings(requestsPerWindow: Int, windowSeconds: Int)
final case class BreakerSettings(failureThreshold: Int, openMillis: Long)
final case class RetrySettings(readRetries: Int, backoffMillis: Long, readTimeoutMillis: Long)
final case class ServingSettings(runId: String, snapshot: String)
final case class ExecutionSettings(workerThreads: Int, queueCapacity: Int)
final case class EvidenceSettings(contentMaxChars: Int)
final case class GraphApiSettings(neighborMaxDepth: Int, edgeTypes: Vector[String])
final case class OpsSettings(metricsEnabled: Boolean, readinessTimeoutMillis: Long)

/** Limits on failed authentication attempts per source.
  *
  * @param maxFailures
  *   failures one source may make within a window before it is answered 429
  * @param windowSeconds
  *   length of the fixed window, and the `Retry-After` ceiling
  * @param maxTrackedSources
  *   bound on the sources remembered at once (memory is O(this))
  * @param trustedProxyHops
  *   how many reverse proxies in front of the service append to `X-Forwarded-For`. 0 means the
  *   header is ignored and the TCP peer is the source. N means the source is the N-th entry from
  *   the right; entries to its left are client-supplied and never used.
  */
final case class AuthFailureSettings(
    maxFailures: Int,
    windowSeconds: Int,
    maxTrackedSources: Int,
    trustedProxyHops: Int
)

/** @param keyRefreshSeconds
  *   how often the API-key digests are re-read from Secrets Manager (rotation); the previous
  *   keys stay in force when a refresh fails
  * @param maxTokenChars
  *   the longest bearer token that is looked at; a longer one is malformed
  */
final case class AuthSettings(
    keySecretName: Option[String],
    failures: AuthFailureSettings,
    keyRefreshSeconds: Int,
    maxTokenChars: Int
)

/** Settings that only the services own. Limits shared with the runtime come from the core
  * configuration ([[RuntimeLimits]]); nothing here is hardcoded in code.
  */
final case class ServiceSettings(
    http: HttpSettings,
    rateLimit: RateLimitSettings,
    breaker: BreakerSettings,
    retry: RetrySettings,
    serving: ServingSettings,
    execution: ExecutionSettings,
    evidence: EvidenceSettings,
    graph: GraphApiSettings,
    ops: OpsSettings,
    auth: AuthSettings
)

/** The bounds that come from the shared core configuration. A request above any of them is
  * refused, never clamped.
  *
  * @param synchronousDeadlineMs
  *   `operations.synchronousDeadlineMs`: how long a synchronous query may wait
  * @param maxWorkflowRetries
  *   `operations.maxWorkflowRetries`
  * @param graphLimit
  *   `retrieval.graphLimit`: the most rows a graph request may ask for
  * @param vectorLimit
  *   `retrieval.vectorLimit`
  * @param maxPathDepth
  *   `retrieval.maxPathDepth`
  * @param graphTimeoutMillis
  *   `retrieval.pathTimeoutMillis`: ceiling and default of a graph request's time limit
  * @param pageSize
  *   `retrieval.coauthorPageSize`: default page size of a neighborhood
  */
final case class RuntimeLimits(
    synchronousDeadlineMs: Long,
    maxWorkflowRetries: Int,
    graphLimit: Int,
    vectorLimit: Int,
    maxPathDepth: Int,
    graphTimeoutMillis: Long,
    pageSize: Int
)

object RuntimeLimits:
  def from(ops: OperationsConfig, retrieval: RetrievalConfig): RuntimeLimits =
    RuntimeLimits(
      ops.synchronousDeadlineMs.toLong,
      ops.maxWorkflowRetries,
      retrieval.graphLimit,
      retrieval.vectorLimit,
      retrieval.maxPathDepth,
      retrieval.pathTimeoutMillis.toLong,
      math.min(retrieval.coauthorPageSize, retrieval.graphLimit)
    )

object ServiceSettings:
  val Root = "scholarchain.services"

  /** Loads the shipped `scholarchain-services.conf` (with its environment overrides) under
    * `extra`. `requireServing = false` is for entrypoints that never answer a query (the graph
    * API and the embedding worker): they need no run ID or snapshot, and `serving` is then empty.
    */
  def load(
      extra: Config = ConfigFactory.empty(),
      requireServing: Boolean = true
  ): Either[Vector[String], ServiceSettings] =
    val cfg = extra
      .withFallback(ConfigFactory.parseResources("scholarchain-services.conf"))
      .resolve()
    read(cfg, requireServing)

  def read(cfg: Config, requireServing: Boolean = true): Either[Vector[String], ServiceSettings] =
    val problems = Vector.newBuilder[String]
    def at(path: String): String = s"$Root.$path"
    def int(path: String, min: Int): Int =
      try
        val v = cfg.getInt(at(path))
        if v < min then problems += s"${at(path)} must be >= $min"
        v
      catch
        case _: ConfigException =>
          problems += s"${at(path)} is missing or not an integer"
          min
    def str(path: String): String =
      try
        val v = cfg.getString(at(path)).trim
        if v.isEmpty then problems += s"${at(path)} must not be blank"
        v
      catch
        case _: ConfigException =>
          problems += s"${at(path)} is required"
          ""
    def bool(path: String): Boolean =
      try cfg.getBoolean(at(path))
      catch
        case _: ConfigException =>
          problems += s"${at(path)} is missing or not a boolean"
          false
    def strs(path: String): Vector[String] =
      try cfg.getStringList(at(path)).asScala.toVector
      catch
        case _: ConfigException =>
          problems += s"${at(path)} is missing or not a list"
          Vector.empty
    def opt(path: String): Option[String] =
      if cfg.hasPath(at(path)) then Some(cfg.getString(at(path)).trim).filter(_.nonEmpty)
      else None

    val s = ServiceSettings(
      HttpSettings(
        int("http.maxBodyBytes", 1),
        int("http.maxQuestionChars", 1),
        int("http.maxListItems", 1),
        int("http.idempotencyKeyMinChars", 1),
        int("http.idempotencyKeyMaxChars", 1)
      ),
      RateLimitSettings(int("rateLimit.requestsPerWindow", 1), int("rateLimit.windowSeconds", 1)),
      BreakerSettings(int("breaker.failureThreshold", 1), int("breaker.openMillis", 1).toLong),
      RetrySettings(
        int("retry.readRetries", 0),
        int("retry.backoffMillis", 0).toLong,
        int("retry.readTimeoutMillis", 1).toLong
      ),
      if requireServing then ServingSettings(str("serving.runId"), str("serving.snapshot"))
      else ServingSettings("", ""),
      ExecutionSettings(int("execution.workerThreads", 1), int("execution.queueCapacity", 1)),
      EvidenceSettings(int("evidence.contentMaxChars", 2)),
      GraphApiSettings(int("graph.neighborMaxDepth", 1), strs("graph.edgeTypes")),
      OpsSettings(bool("ops.metricsEnabled"), int("ops.readinessTimeoutMillis", 1).toLong),
      AuthSettings(
        opt("auth.keySecretName"),
        AuthFailureSettings(
          int("auth.failures.maxFailures", 1),
          int("auth.failures.windowSeconds", 1),
          int("auth.failures.maxTrackedSources", 1),
          int("auth.failures.trustedProxyHops", 0)
        ),
        int("auth.keyRefreshSeconds", 1),
        int("auth.maxTokenChars", 16)
      )
    )
    val built = problems.result()
    if built.isEmpty then Right(s) else Left(built)
