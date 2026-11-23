package scholarchain.rag.workflow

import java.util.concurrent.{
  ArrayBlockingQueue,
  Callable,
  ExecutionException,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import scala.collection.mutable
import scholarchain.core.config.RetrievalConfig
import scholarchain.core.model.*
import scholarchain.rag.agents.*
import scholarchain.rag.answer.SynthesisConfig
import scholarchain.rag.common.Hashing
import scholarchain.rag.llm.LlmUsage
import scholarchain.storage.dynamo.{EvidenceStore, QueryAuditLog, QuerySessions}

/** Settings of the workflow that the shared configuration does not hold yet; they are supplied
  * where the runtime is wired.
  *
  * @param stageTimeoutMillis
  *   the longest one stage may run before it fails as retryable
  * @param defaultWindow
  *   the window used when a plan has none (the loaded graph window); none means a plan without a
  *   window is refused
  * @param synthesis
  *   the synthesis prompt and schema versions
  */
final case class WorkflowSettings(
    stageTimeoutMillis: Long,
    defaultWindow: Option[(Int, Int)],
    synthesis: SynthesisConfig
):
  require(stageTimeoutMillis > 0, "stageTimeoutMillis must be positive")

/** Time, injectable so latencies are testable. */
trait WorkflowClock:
  def nanos: Long
  def epochSeconds: Long

object SystemWorkflowClock extends WorkflowClock:
  def nanos: Long = System.nanoTime()
  def epochSeconds: Long = System.currentTimeMillis() / 1000L

/** The stores the audit stage writes. */
final case class AuditPorts(sessions: QuerySessions, evidence: EvidenceStore, audit: QueryAuditLog)

final case class AnswerRequest(
    traceId: String,
    queryId: String,
    runId: String,
    snapshot: String,
    question: Option[String],
    fields: StructuredFields,
    idempotencyKey: Option[String],
    requestFingerprint: String
)

/** What one stage did. `outcome` is `ok` or the failure code. */
final case class StageTrace(traceId: String, stage: String, latencyMillis: Long, outcome: String)

/** Receives every stage trace as it happens (for logging). It must not throw. */
trait TraceSink:
  def record(t: StageTrace): Unit

object TraceSink:
  val Silent: TraceSink = _ => ()

/** A workflow failure: no answer was produced. `retryable` is the failing stage's own flag, so a
  * time-out or a transient store failure is surfaced as retryable.
  */
final case class WorkflowFailure(
    traceId: String,
    stage: String,
    failure: StageFailure,
    trace: Vector[StageTrace],
    usage: LlmUsage
):
  def retryable: Boolean = failure.retryable
  def message: String = s"$stage: ${failure.message}"

/** Why the workflow did not answer, with what the person needs to fix the request.
  *
  * @param code
  *   a planner refusal code, `AMBIGUOUS_RESEARCHER`, `RESEARCHER_NOT_FOUND`, `WINDOW_REQUIRED`,
  *   `NO_DETERMINISTIC_EVIDENCE`, `NO_EVIDENCE` or `NO_VERIFIED_CLAIMS`
  */
final case class Abstention(
    code: String,
    detail: String,
    candidates: Vector[CandidateView],
    gaps: Vector[String]
)

/** The result of the stages up to the evidence bundle. */
final case class Gathered(
    traceId: String,
    plan: Option[QueryPlan],
    resolution: Option[ResolutionSet],
    window: Option[(Int, Int)],
    built: BuiltEvidence,
    gaps: Vector[String],
    metricVersions: Vector[String],
    planUsage: LlmUsage,
    abstention: Option[Abstention],
    trace: Vector[StageTrace]
)

final case class RemovedClaim(
    ordinal: Int,
    text: String,
    status: VerificationStatus,
    reasons: Vector[String]
)

/** A claim of the model that has no assertion and states no relation: its reading of the evidence.
  * It is not a fact and is never evidence. The response carries it, labeled, in its uncertainty
  * text, apart from the claims; a service shows this list as a separate block.
  */
final case class Interpretation(ordinal: Int, text: String, evidenceIds: Vector[String])

final case class WorkflowResult(
    traceId: String,
    response: AnswerResponse,
    bundle: EvidenceBundle,
    removedClaims: Vector[RemovedClaim],
    verification: VerificationStatus,
    abstention: Option[Abstention],
    audit: QueryAuditRecord,
    usage: LlmUsage,
    trace: Vector[StageTrace],
    interpretations: Vector[Interpretation] = Vector.empty
)

/** The text a user reads, hashed for the audit record. */
object AnswerHash:
  def text(r: AnswerResponse): String =
    (Vector(r.summary) ++
      r.claims.map(c => c.text + c.uncertainty.fold("")(u => s" [$u]")) ++
      r.uncertainty.toVector).mkString("\n")

  def of(r: AnswerResponse): String = Hashing.textHash(text(r))

/** Runs a stage body on a pooled daemon thread with a deadline. A stage that overruns fails as
  * [[StageFailure.Retryable]] and its thread is interrupted. A stage that throws fails as
  * [[StageFailure.Failed]] and the exception text is not repeated.
  *
  * The pool is bounded: at most `maxThreads` stages run at once, and a bounded queue holds the
  * rest. A store call that ignores interruption holds its thread until it returns, but it can no
  * longer make the thread count grow; when the pool and its queue are full a stage fails as
  * retryable at once.
  */
final class StageRunner(timeoutMillis: Long, maxThreads: Int = StageRunner.MaxThreads):
  require(maxThreads >= 1, "maxThreads must be positive")

  private val pool =
    new ThreadPoolExecutor(
      maxThreads,
      maxThreads,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](maxThreads * StageRunner.QueuePerThread),
      new ThreadFactory:
        def newThread(r: Runnable): Thread =
          val t = new Thread(r, "rag-stage")
          t.setDaemon(true)
          t
    )

  def run[A](stage: String)(body: => SResult[A]): SResult[A] =
    try
      val future = pool.submit(new Callable[SResult[A]]:
        def call(): SResult[A] = body)
      try future.get(timeoutMillis, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          future.cancel(true)
          pool.remove(future.asInstanceOf[Runnable]) // a stage that timed out while queued leaves
          Left(StageFailure.Retryable(stage, s"the stage did not finish within $timeoutMillis ms"))
        case _: ExecutionException =>
          Left(StageFailure.Failed(stage, "the stage failed unexpectedly"))
        case _: InterruptedException =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          Left(StageFailure.Failed(stage, "the stage was interrupted"))
    catch
      case _: RejectedExecutionException =>
        Left(StageFailure.Retryable(stage, "too many stages are running; try again"))

object StageRunner:
  /** The most stages that run at once in one runner. A named constant until the shared
    * configuration has a key for it (review finding 7 is routed to the domain owner); it is far
    * above the stages of one request (one at a time) and bounds the threads that stuck store
    * calls can hold.
    */
  val MaxThreads: Int = 16

  /** Queued stages per thread before a stage is refused as retryable. */
  val QueuePerThread: Int = 4

/** One request's trace: runs stages, times them and records them. */
final private[workflow] class RunTrace(
    val traceId: String,
    runner: StageRunner,
    clock: WorkflowClock,
    sink: TraceSink
):
  private val buffer = mutable.ArrayBuffer.empty[StageTrace]
  val startNanos: Long = clock.nanos

  def stage[A](name: String)(body: => SResult[A]): SResult[A] =
    val t0 = clock.nanos
    val r = runner.run(name)(body)
    val t = StageTrace(
      traceId,
      name,
      (clock.nanos - t0) / 1_000_000L,
      r.fold(_.code, _ => "ok")
    )
    buffer += t
    try sink.record(t)
    catch case scala.util.control.NonFatal(_) => ()
    r

  def trace: Vector[StageTrace] = buffer.toVector
  def elapsedMillis: Long = (clock.nanos - startNanos) / 1_000_000L

/** Which evidence satisfies which operation: the abstention rule "no deterministic evidence for the
  * requested metric".
  */
private[workflow] object EvidenceCoverage:
  private def isRanking(i: EvidenceItem) =
    i.evidenceType == EvidenceType.METRIC_RECORD && i.text.exists(_.startsWith("ranking by "))
  private def isProfile(i: EvidenceItem) =
    i.evidenceType == EvidenceType.METRIC_RECORD && !isRanking(i)

  def satisfied(
      op: QueryOperation,
      metric: Option[CollaborationMetric],
      graph: Vector[EvidenceItem]
  ): Boolean = op match
    case QueryOperation.RESEARCHER_RANKING | QueryOperation.BRIDGE_RANKING =>
      graph.exists(i =>
        isRanking(i) && metric.forall(m => i.text.exists(_.startsWith(s"ranking by $m ")))
      )
    case QueryOperation.MAXIMUM_TEAM_SIZE =>
      graph.exists(i =>
        isRanking(i) &&
          i.text.exists(_.startsWith("ranking by MAXIMUM_COAUTHORS_ON_ONE_PAPER "))
      )
    case QueryOperation.TEMPORAL_COMPARISON => graph.exists(isProfile)
    case QueryOperation.DIRECT_COAUTHORS =>
      graph.exists(i => i.evidenceType == EvidenceType.COAUTHOR_EDGE || isProfile(i))
    case QueryOperation.SHORTEST_PATH => graph.exists(_.evidenceType == EvidenceType.GRAPH_PATH)
    case QueryOperation.COMPONENT_LOOKUP =>
      graph.exists(_.evidenceType == EvidenceType.COMPONENT_RECORD)
    case QueryOperation.COMMUNITY_COMPARISON =>
      graph.exists(_.evidenceType == EvidenceType.COMMUNITY_RECORD)
    case _ => true
