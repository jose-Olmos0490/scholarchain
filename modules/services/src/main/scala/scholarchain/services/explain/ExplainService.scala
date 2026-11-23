package scholarchain.services.explain

import scala.util.control.NonFatal
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.services.error.{ApiError, ErrorCode}
import scholarchain.services.query.{JobState, JobStore}
import scholarchain.services.resilience.Downstream
import scholarchain.services.security.Principal
import scholarchain.storage.dynamo.{EvidenceStore, QueryAuditLog}
import upickle.default.writeJs

/** `GET /v1/explain/{queryId}`: what was done for a query, from the audit record, the stored
  * evidence and the job's stage trace. It carries the plan, the stages, the prompt and model
  * versions, evidence IDs with their scores, counters and the verification status. There is no
  * field for model reasoning, a raw prompt or a raw completion: none is ever stored.
  */
final class ExplainService(
    audit: QueryAuditLog,
    evidence: EvidenceStore,
    jobs: JobStore,
    downstream: Downstream
):
  def explain(principal: Principal, queryId: String): Either[ApiError, ujson.Obj] =
    if StableId.parseAs(queryId, Set(IdKind.Query)).isLeft then
      Left(ApiError.badRequest("INVALID_ID", "the query ID is not a stable query ID"))
    else
      jobs.findByQueryId(queryId).filter(_.principalId == principal.id) match
        case None => Left(ApiError.notFound("the query was not found"))
        case Some(job) if job.state == JobState.Queued || job.state == JobState.Running =>
          Left(
            ApiError(
              ErrorCode.Conflict,
              "the query has not finished",
              retryable = true,
              reason = Some("QUERY_NOT_FINISHED"),
              retryAfterSeconds = Some(1L)
            )
          )
        case Some(job) =>
          for
            record <- downstream
              .read()(audit.get(queryId))
              .flatMap(
                _.toRight(
                  ApiError.notFound("the query has no audit record (it did not produce an answer)")
                )
              )
            items <- downstream.read()(evidence.listForQuery(queryId))
            plan <-
              try Right(ujson.read(record.planJson))
              catch case NonFatal(_) => Left(ApiError.internal)
          yield
            val claims = job.result
              .flatMap(r => scala.util.Try(r("answer")("claims").arr.size).toOption)
              .getOrElse(0)
            ujson.Obj(
              "queryId" -> queryId,
              "runId" -> record.runId,
              "plan" -> plan,
              "stagesExecuted" -> ujson.Arr.from(
                job.trace.map(t =>
                  ujson.Obj(
                    "stage" -> t.stage,
                    "latencyMillis" -> t.latencyMillis.toDouble,
                    "outcome" -> t.outcome
                  )
                )
              ),
              "promptVersions" -> ujson.Arr.from(record.promptVersions.map(ujson.Str(_))),
              "modelVersions" -> ujson.Arr.from(record.modelVersions.map(ujson.Str(_))),
              "metricVersions" -> ujson.Arr.from(record.metricVersions.map(ujson.Str(_))),
              "researcherIds" -> ujson.Arr.from(record.researcherIds.map(ujson.Str(_))),
              "evidenceIds" -> ujson.Arr.from(record.evidenceIds.map(ujson.Str(_))),
              "scoreComponents" -> ujson.Arr.from(
                items.map(i =>
                  ujson.Obj(
                    "evidenceId" -> i.evidenceId,
                    "evidenceType" -> i.evidenceType.toString,
                    "retrievalScore" -> i.score.fold[ujson.Value](ujson.Null)(ujson.Num(_)),
                    "textHash" -> i.textHash.fold[ujson.Value](ujson.Null)(ujson.Str(_))
                  )
                )
              ),
              "counters" -> ujson.Obj(
                "inputTokens" -> record.inputTokens.toDouble,
                "outputTokens" -> record.outputTokens.toDouble,
                "latencyMillis" -> record.latencyMillis.toDouble,
                "evidenceItems" -> items.size,
                "claims" -> claims
              ),
              "verification" -> ujson.Obj(
                "status" -> record.verificationResult.toString,
                "answerHash" -> record.answerHash
              ),
              "provenance" -> writeJs(record.provenance),
              "note" -> "hidden model reasoning is never stored or returned"
            )
