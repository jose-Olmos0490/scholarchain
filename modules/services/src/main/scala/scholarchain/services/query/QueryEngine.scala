package scholarchain.services.query

import scholarchain.rag.agents.StageFailure
import scholarchain.rag.workflow.{AnswerRequest, AnswerWorkflow, WorkflowFailure, WorkflowResult}
import scholarchain.services.error.{ApiError, ErrorCode}
import upickle.default.writeJs

/** The only door from the services to the Graph-RAG runtime. Routes never reach the agents: the
  * production engine is the rag module's [[AnswerWorkflow]], and tests substitute a fake.
  */
trait QueryEngine:
  def answer(request: AnswerRequest): Either[WorkflowFailure, WorkflowResult]

final class RagQueryEngine(workflow: AnswerWorkflow) extends QueryEngine:
  def answer(request: AnswerRequest): Either[WorkflowFailure, WorkflowResult] =
    workflow.answer(request)

/** Maps a workflow failure to the typed error model. Messages are fixed text.
  *
  * Time-outs and saturation are read from the failure's type: [[StageFailure.Timeout]] is 504 and
  * [[StageFailure.Overloaded]] is 503, both retryable. No text of a failure decides the status.
  * One legacy path remains: the stage runner and storage time-outs still report a plain
  * [[StageFailure.Retryable]] whose `detail` says what happened. Until the rag module types them,
  * [[LegacyRetryable]] reads two fixed markers of that detail; a plain `Retryable` without a
  * marker is a store that was unavailable (502).
  */
object FailureMapper:
  /** The only place that reads a failure's text. Remove it when the rag stage runner emits
    * [[StageFailure.Timeout]] and [[StageFailure.Overloaded]] itself.
    */
  private object LegacyRetryable:
    private val TimeoutMarker = "did not finish within"
    private val BusyMarker = "too many stages"

    def timeout(r: StageFailure.Retryable): Boolean = r.detail.contains(TimeoutMarker)
    def busy(r: StageFailure.Retryable): Boolean = r.detail.contains(BusyMarker)

  private val timedOut: ApiError =
    ApiError.of(ErrorCode.GatewayTimeout, "STAGE_TIMEOUT", "a workflow stage timed out")
  private val overloaded: ApiError =
    ApiError.of(ErrorCode.Unavailable, "OVERLOADED", "the workflow is busy; try again")

  def toApi(f: WorkflowFailure): ApiError =
    val stage = Map("stage" -> ujson.Str(f.stage))
    val base: ApiError = f.failure match
      case StageFailure.Rejected("IDEMPOTENCY_KEY_REUSED", _) =>
        ApiError.of(
          ErrorCode.Conflict,
          "IDEMPOTENCY_KEY_REUSED",
          "the Idempotency-Key belongs to another query"
        )
      case StageFailure.Rejected(code, _)
          if Set("TRACE_ID_INVALID", "QUERY_ID_INVALID", "RUN_ID_INVALID", "ANSWER_INVALID")
            .contains(code) =>
        // a fault of this service or its configuration, not of the caller
        ApiError.internal
      case StageFailure.Rejected("INVALID_REQUEST", _) =>
        ApiError.badRequest("INVALID_REQUEST", "the request was refused by the query planner")
      case StageFailure.Rejected(code, _) =>
        ApiError.unprocessable(safeCode(code), "the request was refused by the runtime")
      case _: StageFailure.Unsupported =>
        ApiError.unprocessable("UNSUPPORTED", "the stores cannot serve this request")
      case _: StageFailure.IdempotencyConflict =>
        ApiError.of(
          ErrorCode.Conflict,
          "IDEMPOTENCY_CONFLICT",
          "the query already has an audit record for a different answer"
        )
      case _: StageFailure.NotFound =>
        ApiError.unprocessable("REFERENCED_RECORD_NOT_FOUND", "a referenced record was not found")
      case _: StageFailure.Timeout => timedOut
      case _: StageFailure.Overloaded => overloaded
      case r: StageFailure.Retryable if LegacyRetryable.timeout(r) => timedOut
      case r: StageFailure.Retryable if LegacyRetryable.busy(r) => overloaded
      case _: StageFailure.Retryable =>
        ApiError.of(ErrorCode.BadGateway, "DOWNSTREAM_UNAVAILABLE", "a store was unavailable")
      case _: StageFailure.Failed =>
        ApiError(
          ErrorCode.BadGateway,
          "a workflow stage failed",
          retryable = false,
          reason = Some("STAGE_FAILED")
        )
    if base.code == ErrorCode.Internal then base else base.copy(details = stage)

  /** A rag reason code is an upper-case token; anything else is replaced. */
  private def safeCode(code: String): String =
    if code.matches("[A-Z0-9_]{1,64}") then code else "REFUSED"

/** The JSON a finished query returns. Claims come from the verified answer only; the model's own
  * text is never shown, and a removed claim is reported as a count.
  */
object ResultView:
  private def link(id: String) = s"/v1/evidence/$id"

  def of(r: WorkflowResult): ujson.Obj =
    val answer = writeJs(r.response)
    val evidenceIds = r.response.claims.flatMap(_.evidenceIds).distinct
    val out = ujson.Obj(
      "queryId" -> r.response.queryId,
      "traceId" -> r.traceId,
      "answer" -> answer,
      "verification" -> r.verification.toString,
      "evidenceIds" -> ujson.Arr.from(evidenceIds.map(ujson.Str(_))),
      "removedClaimCount" -> r.removedClaims.size,
      "interpretations" -> ujson.Arr.from(
        r.interpretations.map(i =>
          ujson.Obj(
            "ordinal" -> i.ordinal,
            "text" -> i.text,
            "evidenceIds" -> ujson.Arr.from(i.evidenceIds.map(ujson.Str(_))),
            "note" -> "interpretation, not a verified fact"
          )
        )
      ),
      "links" -> ujson.Obj(
        "evidence" -> ujson.Arr.from(evidenceIds.map(id => ujson.Str(link(id)))),
        "explain" -> s"/v1/explain/${r.response.queryId}"
      )
    )
    r.abstention.foreach { a =>
      out("abstention") = ujson.Obj(
        "code" -> a.code,
        "detail" -> a.detail,
        "candidates" -> ujson.Arr.from(
          a.candidates.map(c =>
            ujson.Obj(
              "researcherId" -> c.researcherId,
              "displayName" -> c.displayName,
              "confidence" -> c.confidence
            )
          )
        ),
        "gaps" -> ujson.Arr.from(a.gaps.map(ujson.Str(_)))
      )
    }
    out
