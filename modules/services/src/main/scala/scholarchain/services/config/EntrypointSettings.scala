package scholarchain.services.config

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import scholarchain.storage.queue.ConsumerSettings

/** Where an entrypoint listens. */
final case class BindSettings(host: String, port: Int)

/** Model-call retry bounds of the services. Bedrock uses all four. Anthropic uses only the two
  * delays: its attempts and per-attempt deadline are the core `llm.anthropic.*` keys.
  */
final case class LlmSettings(
    maxAttempts: Int,
    baseDelayMs: Long,
    maxDelayMs: Long,
    attemptTimeoutMillis: Long
)

/** The workflow's own tuning (the rag module takes these as inputs and defaults none).
  *
  * @param promptsDir
  *   the directory with `manifest.json` and the versioned prompts and schemas
  * @param defaultWindow
  *   the graph window a plan without one is answered over; none means such a plan is refused
  */
final case class WorkflowTuningSettings(
    promptsDir: String,
    synthesisPromptVersion: String,
    synthesisSchemaVersion: String,
    stageTimeoutMillis: Long,
    defaultWindow: Option[(Int, Int)],
    plannerMaxQueryCodePoints: Int,
    maxExcerptCodePoints: Int,
    maxSemanticQueryCodePoints: Int
)

final case class QueryApiSettings(
    bind: BindSettings,
    llm: LlmSettings,
    workflow: WorkflowTuningSettings
)

final case class GraphEvidenceApiSettings(bind: BindSettings)

/** Where the model-provider API keys are. The names have no default; which one is required
  * depends on the selected provider.
  *
  * @param refreshSeconds
  *   how long a key that was read is reused (a rotation takes effect within this time)
  * @param retrySeconds
  *   how soon a failed read is tried again
  * @param readTimeoutMillis
  *   the longest one Secrets Manager call may take
  */
final case class ProviderKeySettings(
    anthropicSecretName: Option[String],
    voyageSecretName: Option[String],
    refreshSeconds: Int,
    retrySeconds: Int,
    readTimeoutMillis: Long
)

/** The embedding worker.
  *
  * @param health
  *   the worker's own health port (`/healthz`, `/readyz`)
  * @param queueUrl
  *   the embedding work queue; `deadLetterQueueUrl` receives poison messages
  * @param consumer
  *   how messages are received and retried
  * @param maxIngestRunSeconds
  *   the longest one message may take to ingest; a longer run is abandoned and retried
  * @param idleBackoffMillis
  *   the pause after a failed receive
  * @param pollStalenessSeconds
  *   readiness fails when the loop has not made progress for this long
  * @param maxConsecutiveFailures
  *   readiness fails after this many receive failures in a row
  * @param shutdownGraceSeconds
  *   how long SIGTERM waits for the message in hand before the process exits
  */
final case class WorkerSettings(
    health: BindSettings,
    queueUrl: String,
    deadLetterQueueUrl: String,
    consumer: ConsumerSettings,
    maxIngestRunSeconds: Int,
    idleBackoffMillis: Long,
    pollStalenessSeconds: Int,
    maxConsecutiveFailures: Int,
    shutdownGraceSeconds: Int
)

/** Reads one `scholarchain.services.*` subtree, collecting every problem by key. */
final private class ConfReader(cfg: Config, root: String):
  private val problems = Vector.newBuilder[String]
  private def at(path: String) = s"$root.$path"

  def int(path: String, min: Int, max: Int = Int.MaxValue): Int =
    try
      val v = cfg.getInt(at(path))
      if v < min || v > max then problems += s"${at(path)} must be in [$min, $max]"
      v
    catch
      case _: ConfigException =>
        problems += s"${at(path)} is missing or not an integer"
        min
  def long(path: String, min: Long): Long =
    try
      val v = cfg.getLong(at(path))
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
  def optStr(path: String): Option[String] =
    if cfg.hasPath(at(path)) then Some(cfg.getString(at(path)).trim).filter(_.nonEmpty) else None
  def optInt(path: String): Option[Int] =
    if cfg.hasPath(at(path)) then Some(int(path, Int.MinValue)) else None
  def bind(path: String): BindSettings =
    BindSettings(str(s"$path.host"), int(s"$path.port", 0, 65535))
  def problem(text: String): Unit = problems += text
  def result[A](value: => A): Either[Vector[String], A] =
    val built = value
    val all = problems.result()
    if all.isEmpty then Right(built) else Left(all)

object EntrypointSettings:
  private val Root = ServiceSettings.Root

  private def resolved(extra: Config): Config =
    extra.withFallback(ConfigFactory.parseResources("scholarchain-services.conf")).resolve()

  def queryApi(extra: Config = ConfigFactory.empty()): Either[Vector[String], QueryApiSettings] =
    val r = new ConfReader(resolved(extra), Root)
    r.result {
      val llm = LlmSettings(
        r.int("llm.maxAttempts", 1),
        r.long("llm.baseDelayMs", 0),
        r.long("llm.maxDelayMs", 0),
        r.long("llm.attemptTimeoutMillis", 1)
      )
      if llm.maxDelayMs < llm.baseDelayMs then
        r.problem(s"$Root.llm.maxDelayMs must be >= baseDelayMs")
      val window = (
        r.optInt("workflow.defaultWindowStartYear"),
        r.optInt("workflow.defaultWindowEndYear")
      ) match
        case (Some(a), Some(b)) =>
          if a > b then r.problem(s"$Root.workflow.defaultWindowStartYear must be <= end year")
          Some((a, b))
        case (None, None) => None
        case _ =>
          r.problem(s"$Root.workflow.defaultWindowStartYear and EndYear are set together")
          None
      QueryApiSettings(
        r.bind("queryApi.bind"),
        llm,
        WorkflowTuningSettings(
          r.str("workflow.promptsDir"),
          r.str("workflow.synthesisPromptVersion"),
          r.str("workflow.synthesisSchemaVersion"),
          r.long("workflow.stageTimeoutMillis", 1),
          window,
          r.int("workflow.plannerMaxQueryCodePoints", 1),
          r.int("workflow.maxExcerptCodePoints", 1),
          r.int("workflow.maxSemanticQueryCodePoints", 1)
        )
      )
    }

  def providerKeys(
      extra: Config = ConfigFactory.empty()
  ): Either[Vector[String], ProviderKeySettings] =
    val r = new ConfReader(resolved(extra), Root)
    r.result(
      ProviderKeySettings(
        r.optStr("providerKeys.anthropicSecretName"),
        r.optStr("providerKeys.voyageSecretName"),
        r.int("providerKeys.refreshSeconds", 1),
        r.int("providerKeys.retrySeconds", 1),
        r.long("providerKeys.readTimeoutMillis", 1)
      )
    )

  def graphEvidenceApi(
      extra: Config = ConfigFactory.empty()
  ): Either[Vector[String], GraphEvidenceApiSettings] =
    val r = new ConfReader(resolved(extra), Root)
    r.result(GraphEvidenceApiSettings(r.bind("graphEvidenceApi.bind")))

  def embeddingWorker(
      extra: Config = ConfigFactory.empty()
  ): Either[Vector[String], WorkerSettings] =
    val r = new ConfReader(resolved(extra), Root)
    r.result {
      val p = "embeddingWorker"
      val batch = r.int(s"$p.consumer.batchSize", 1, 10)
      val visibility = r.int(s"$p.consumer.visibilityTimeoutSeconds", 1, 43200)
      val wait = r.int(s"$p.consumer.waitSeconds", 0, 20)
      val receives = r.int(s"$p.consumer.maxReceiveCount", 1)
      val perRun = r.int(s"$p.consumer.maxMessagesPerRun", 1)
      val backoff = r.int(s"$p.consumer.retryBackoffSeconds", 0, 43200)
      val ingest = r.int(s"$p.maxIngestRunSeconds", 1)
      val staleness = r.int(s"$p.pollStalenessSeconds", 1)
      // a message must stay hidden for longer than the longest run, or it is processed twice
      if visibility <= ingest then
        r.problem(s"$Root.$p.consumer.visibilityTimeoutSeconds must exceed maxIngestRunSeconds")
      // a healthy loop may be inside one long poll plus one long run
      if staleness <= wait + ingest then
        r.problem(s"$Root.$p.pollStalenessSeconds must exceed waitSeconds + maxIngestRunSeconds")
      val queue = r.str(s"$p.queueUrl")
      val dlq = r.str(s"$p.deadLetterQueueUrl")
      if queue.nonEmpty && queue == dlq then
        r.problem(s"$Root.$p.deadLetterQueueUrl must differ from queueUrl")
      WorkerSettings(
        r.bind(s"$p.health"),
        queue,
        dlq,
        ConsumerSettings(
          math.max(1, batch),
          math.max(1, visibility),
          math.max(0, wait),
          math.max(1, receives),
          math.max(1, perRun),
          math.max(0, backoff)
        ),
        ingest,
        r.long(s"$p.idleBackoffMillis", 0),
        staleness,
        r.int(s"$p.maxConsecutiveFailures", 1),
        r.int(s"$p.shutdownGraceSeconds", 1)
      )
    }
