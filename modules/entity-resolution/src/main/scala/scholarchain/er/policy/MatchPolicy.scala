package scholarchain.er.policy

import scala.collection.immutable.TreeMap
import scala.collection.mutable.ArrayBuffer
import scholarchain.core.ids.StableIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.RecordContract.given
import scholarchain.er.adjudication.*
import scholarchain.er.features.Features
import scholarchain.er.ids.{ErIdType, ExactIdRules, IdComparison}
import scholarchain.er.model.*

/** One pair to decide.
  *
  * @param candidateId
  *   the Phase 4 match-candidate ID, or the exact-identifier outcome ID when the pair came only
  *   from an exact-identifier group
  * @param left
  *   the record whose ref sorts first (`left.ref < right.ref`)
  * @param hadoopFeatures
  *   the Phase 4 blocking features of the candidate (empty for exact-ID-only pairs); recorded on
  *   the decision with the prefix `hadoop.`, never used for scoring
  * @param exactOutcomes
  *   Phase 4 exact-identifier outcomes whose group contains both refs
  */
final case class PairInput(
    candidateId: String,
    kind: MatchEntityType,
    left: EntityView,
    right: EntityView,
    hadoopFeatures: Map[String, Double],
    hadoopFeatureVersion: Option[String],
    exactOutcomes: Vector[ExactIdentifierOutcome],
    snapshot: String
)

final case class DecisionContext(
    policy: Policy,
    runId: String,
    decidedAt: String,
    adjudication: Option[AdjudicationService]
)

final case class DecisionResult(
    decision: MatchDecision,
    path: MatchPath,
    score: Double,
    adjudication: Option[AdjudicationResult]
)

/** Scoring policy er-v1, decision-logic revision er-v1-r2: the evidence hierarchy applied to
  * one pair. Changes from revision r1 are listed in `modules/entity-resolution/POLICY-CHANGELOG.md`.
  *
  * Order of evaluation (the first that applies decides):
  *   1. Exact identifiers (level 1).
  *      - a Phase 4 exact-identifier outcome CONFLICT for the pair: CONFLICT
  *      - identifiers agree on one type and disagree on another, or on the same type
  *        (partially overlapping sets, see [[ExactIdRules.compare]]): CONFLICT
  *   2. Distinct author slots of one work (researcher; finding F3). Two mentions attached to the
  *      same work (same record, agreeing work identifiers, or bibliographically identical
  *      records) at different author positions:
  *      - identifiers agree: CONFLICT (one identifier asserted for two author slots)
  *      - identifiers disagree: REJECTED
  *      - names incompatible: REJECTED (two different authors of one paper)
  *      - the same work record: REJECTED (one person occupies one slot of an author list)
  *      - otherwise (two sources of one paper, positions differ): UNRESOLVED, for human review
  *      Never ACCEPTED, and never sent to the LLM.
  *   3. Exact identifiers, continued.
  *      - identifiers agree but the names (researcher) or titles (work) are incompatible:
  *        CONFLICT
  *      - identifiers agree: ACCEPTED
  *      - identifiers disagree while the bibliographic evidence would accept: CONFLICT
  *      - identifiers disagree: REJECTED
  *   4. Generic work titles (finding F4). A work pair where either title is generic
  *      ([[GenericTitles]]) and no identifier agrees is never ACCEPTED: REJECTED when the score
  *      is below the reject threshold, otherwise UNRESOLVED. Never sent to the LLM.
  *   5. Exact normalized bibliographic fields (level 2).
  *      - work: equal normalized non-generic title, equal year, fully compatible author
  *        sequence: ACCEPTED
  *      - researcher: mentions on the same publication at the same author position with
  *        compatible names: ACCEPTED; on the same publication with incompatible names: REJECTED
  *   6. Researcher name gate: incompatible surnames or initials: REJECTED.
  *   7. Weighted score (level 3), thresholds from configuration:
  *      - `score >= acceptThreshold`: ACCEPTED
  *      - `score < rejectThreshold`: REJECTED
  *      - `llmBandMin <= score < llmBandMax`: LLM adjudication (level 4) when an adjudicator
  *        is configured; ACCEPT or REJECT with confidence >= `llmMinConfidence` decides,
  *        anything else (ABSTAIN, low confidence, invalid output, invented ID, transport error)
  *        is UNRESOLVED
  *      - otherwise UNRESOLVED (left for human review, level 5)
  *
  * The cutoffs `minSurnameSimilarity`, `minSharedIdTitleSimilarity` and `llmMinConfidence` come
  * from [[Policy.cutoffs]], read only from the er policy properties file ([[DecisionCutoffs]]).
  *
  * Confidence is the confidence in the recorded outcome: the score for a score-based ACCEPTED,
  * `1 - score` for a score-based REJECTED, the model's confidence for an LLM decision, 1 for
  * exact-identifier and exact-bibliographic decisions and for CONFLICT (a deterministic fact),
  * and 0 for UNRESOLVED (no outcome is asserted).
  *
  * The decision record depends only on the pair, the policy and the context: whether an
  * adjudication was answered from the cache or by a model call is not recorded on it (finding
  * F8), so a warm and a cold cache give byte-identical decisions.
  */
object MatchPolicy:

  final private case class Outcome(
      decision: MatchOutcome,
      path: MatchPath,
      confidence: Double,
      reasons: Vector[String]
  )

  /** True when two researcher names are compatible enough to denote one person. */
  def namesCompatible(features: Map[String, Double], c: DecisionCutoffs): Boolean =
    features.getOrElse("surnameSimilarity", 0.0) >= c.minSurnameSimilarity &&
      features.getOrElse("initialsCompatibility", 0.5) > 0.0

  private def idRules(c: IdComparison): Vector[String] =
    c.agree.map(t => s"EXACT_ID_AGREE:$t") ++ c.disagree.map(t => s"EXACT_ID_DISAGREE:$t")

  def decide(in: PairInput, ctx: DecisionContext): Either[String, DecisionResult] =
    val p = ctx.policy.parameters
    (in.left, in.right) match
      case (a: ResearcherView, b: ResearcherView) if in.kind == MatchEntityType.RESEARCHER =>
        decideWith(in, ctx, Features.researcher(a, b, p))
      case (a: WorkView, b: WorkView) if in.kind == MatchEntityType.WORK =>
        decideWith(in, ctx, Features.work(a, b, p))
      case _ =>
        Left(s"pair ${in.candidateId}: views do not match entity type ${in.kind}")

  private def decideWith(
      in: PairInput,
      ctx: DecisionContext,
      features: TreeMap[String, Double]
  ): Either[String, DecisionResult] =
    val p = ctx.policy.parameters
    val th = ctx.policy.thresholds
    val cut = ctx.policy.cutoffs
    val weights =
      if in.kind == MatchEntityType.RESEARCHER then p.researcherWeights else p.workWeights
    val score = Features.score(features, weights, p.missingFeatureValue)
    val ids = ExactIdRules.compare(in.left.ids, in.right.ids)
    val rules = ArrayBuffer.empty[String]
    rules += s"POLICY_REVISION:${p.policyRevision}"
    rules += s"FEATURE_VERSION:${p.featureVersion}"
    in.hadoopFeatureVersion.foreach(v => rules += s"HADOOP_FEATURE_VERSION:$v")
    rules ++= idRules(ids)
    var adjudication: Option[AdjudicationResult] = None

    val isResearcher = in.kind == MatchEntityType.RESEARCHER
    val nameOk = !isResearcher || namesCompatible(features, cut)
    // Titles of two works sharing an identifier are compatible when similar enough, or when one
    // is a token prefix of the other (a title stored without its subtitle).
    val titleOk = isResearcher || features.getOrElse("titlePrefix", 0.0) == 1.0 ||
      features.get("titleSimilarity").forall(_ >= cut.minSharedIdTitleSimilarity)
    val biblioAccept: Option[String] =
      if isResearcher then
        if features.getOrElse("sameWorkContext", 0.0) == 1.0 && nameOk &&
          features.get("authorOrder").contains(1.0)
        then Some("SAME_WORK_SAME_POSITION")
        else None
      else if features.getOrElse("exactBibliographic", 0.0) == 1.0 then
        Some("EXACT_BIBLIOGRAPHIC_MATCH")
      else None
    val hadoopConflicts = in.exactOutcomes.filter(_.outcome == ExactIdOutcome.CONFLICT)
    // F3: two different author positions on one work are two author slots.
    val (sameRecord, distinctSlots) = (in.left, in.right) match
      case (a: ResearcherView, b: ResearcherView) =>
        val sameRec = a.work.isDefined && a.work.map(_.ref) == b.work.map(_.ref)
        val sameWork = sameRec || features.getOrElse("sameWorkContext", 0.0) == 1.0 ||
          features.getOrElse("sameWorkBibliographic", 0.0) == 1.0
        val slotsDiffer = (a.position, b.position) match
          case (Some(x), Some(y)) => x != y
          case _ => false
        (sameRec, sameWork && slotsDiffer)
      case _ => (false, false)
    // F4: a generic title carries no identity.
    val genericTitle = !isResearcher && features.getOrElse("genericTitle", 0.0) == 1.0

    val outcome: Outcome =
      if hadoopConflicts.nonEmpty then
        hadoopConflicts.foreach(o => rules += s"HADOOP_EXACT_ID_CONFLICT:${o.idType}")
        Outcome(MatchOutcome.CONFLICT, MatchPath.EXACT_ID, 1.0, Vector("EXACT_ID_CONFLICT"))
      else if ids.isConflict then
        Outcome(MatchOutcome.CONFLICT, MatchPath.EXACT_ID, 1.0, Vector("EXACT_ID_CONFLICT"))
      else if distinctSlots then
        rules += "SAME_WORK_DISTINCT_POSITIONS"
        if ids.hasAgreement then
          Outcome(
            MatchOutcome.CONFLICT,
            MatchPath.EXACT_ID,
            1.0,
            Vector("EXACT_ID_SAME_WORK_DISTINCT_POSITIONS")
          )
        else if ids.hasDisagreement then
          Outcome(MatchOutcome.REJECTED, MatchPath.EXACT_ID, 1.0, Vector("EXACT_ID_DISAGREE"))
        else if !nameOk then
          rules += "NAME_INCOMPATIBLE"
          Outcome(
            MatchOutcome.REJECTED,
            MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
            1.0,
            Vector("SAME_WORK_DIFFERENT_AUTHOR")
          )
        else if sameRecord then
          Outcome(
            MatchOutcome.REJECTED,
            MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
            1.0,
            Vector("SAME_WORK_DISTINCT_AUTHOR_SLOTS")
          )
        else
          Outcome(
            MatchOutcome.UNRESOLVED,
            MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
            0.0,
            Vector("SAME_WORK_DIFFERENT_POSITION")
          )
      else if ids.hasAgreement then
        if !nameOk then
          rules += "NAME_INCOMPATIBLE"
          Outcome(
            MatchOutcome.CONFLICT,
            MatchPath.EXACT_ID,
            1.0,
            Vector("EXACT_ID_NAME_INCOMPATIBLE")
          )
        else if !titleOk then
          rules += "TITLE_INCOMPATIBLE"
          Outcome(
            MatchOutcome.CONFLICT,
            MatchPath.EXACT_ID,
            1.0,
            Vector("EXACT_ID_TITLE_INCOMPATIBLE")
          )
        else Outcome(MatchOutcome.ACCEPTED, MatchPath.EXACT_ID, 1.0, Vector("EXACT_ID_MATCH"))
      else if ids.hasDisagreement then
        // With a generic title a high score is title-driven, not evidence against the IDs.
        if biblioAccept.isDefined || (!genericTitle && score >= th.acceptThreshold) then
          biblioAccept.foreach(r => rules += r)
          if score >= th.acceptThreshold then rules += "SCORE_AT_OR_ABOVE_ACCEPT"
          Outcome(
            MatchOutcome.CONFLICT,
            MatchPath.EXACT_ID,
            1.0,
            Vector("EXACT_ID_DISAGREES_WITH_EVIDENCE")
          )
        else Outcome(MatchOutcome.REJECTED, MatchPath.EXACT_ID, 1.0, Vector("EXACT_ID_DISAGREE"))
      else if genericTitle then
        rules += "GENERIC_TITLE"
        if score < th.rejectThreshold then
          rules += "SCORE_BELOW_REJECT"
          Outcome(
            MatchOutcome.REJECTED,
            MatchPath.PROBABILISTIC,
            1.0 - score,
            Vector("SCORE_REJECT")
          )
        else
          Outcome(
            MatchOutcome.UNRESOLVED,
            MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
            0.0,
            Vector("GENERIC_TITLE_NEEDS_IDENTIFIER")
          )
      else if biblioAccept.isDefined then
        rules += biblioAccept.get
        Outcome(
          MatchOutcome.ACCEPTED,
          MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
          1.0,
          Vector(biblioAccept.get)
        )
      else if isResearcher && features.getOrElse("sameWorkContext", 0.0) == 1.0 && !nameOk then
        rules += "NAME_INCOMPATIBLE"
        Outcome(
          MatchOutcome.REJECTED,
          MatchPath.DETERMINISTIC_BIBLIOGRAPHIC,
          1.0,
          Vector("SAME_WORK_DIFFERENT_AUTHOR")
        )
      else if !nameOk then
        rules += "NAME_INCOMPATIBLE"
        Outcome(
          MatchOutcome.REJECTED,
          MatchPath.PROBABILISTIC,
          1.0 - score,
          Vector("NAME_INCOMPATIBLE")
        )
      else if score >= th.acceptThreshold then
        rules += "SCORE_AT_OR_ABOVE_ACCEPT"
        Outcome(MatchOutcome.ACCEPTED, MatchPath.PROBABILISTIC, score, Vector("SCORE_ACCEPT"))
      else if score < th.rejectThreshold then
        rules += "SCORE_BELOW_REJECT"
        Outcome(MatchOutcome.REJECTED, MatchPath.PROBABILISTIC, 1.0 - score, Vector("SCORE_REJECT"))
      else if th.inLlmBand(score) then
        rules += "SCORE_IN_LLM_BAND"
        ctx.adjudication match
          case None =>
            Outcome(
              MatchOutcome.UNRESOLVED,
              MatchPath.PROBABILISTIC,
              0.0,
              Vector("LLM_BAND_NO_ADJUDICATOR")
            )
          case Some(service) =>
            val request = AdjudicationRequests.build(in, features, score)
            val result = service.adjudicate(request)
            adjudication = Some(result)
            // Same rule for a cache hit and a model call: decisions are cache-state independent.
            rules += "LLM_ADJUDICATION"
            result.parsed match
              case Left(errors) =>
                errors.foreach(e => rules += s"LLM_OUTPUT_ERROR:${e.takeWhile(_ != ':')}")
                val reason =
                  if errors.exists(_.startsWith("TRANSPORT_ERROR")) then "LLM_UNAVAILABLE"
                  else if errors.exists(_.startsWith(AdjudicationSchema.FabricatedIdError)) then
                    "LLM_FABRICATED_ID"
                  else "LLM_OUTPUT_INVALID"
                Outcome(MatchOutcome.UNRESOLVED, MatchPath.LLM_ADJUDICATED, 0.0, Vector(reason))
              case Right(r) =>
                r.citedFields.foreach(f => rules += s"LLM_CITED:$f")
                val llmReasons = r.reasonCodes.map(c => s"LLM_$c")
                r.outcome match
                  case AdjudicationOutcome.ABSTAIN =>
                    Outcome(
                      MatchOutcome.UNRESOLVED,
                      MatchPath.LLM_ADJUDICATED,
                      0.0,
                      "LLM_ABSTAINED" +: llmReasons
                    )
                  case _ if r.confidence < cut.llmMinConfidence =>
                    Outcome(
                      MatchOutcome.UNRESOLVED,
                      MatchPath.LLM_ADJUDICATED,
                      0.0,
                      "LLM_LOW_CONFIDENCE" +: llmReasons
                    )
                  case AdjudicationOutcome.ACCEPT =>
                    Outcome(
                      MatchOutcome.ACCEPTED,
                      MatchPath.LLM_ADJUDICATED,
                      r.confidence,
                      "LLM_ACCEPT" +: llmReasons
                    )
                  case AdjudicationOutcome.REJECT =>
                    Outcome(
                      MatchOutcome.REJECTED,
                      MatchPath.LLM_ADJUDICATED,
                      r.confidence,
                      "LLM_REJECT" +: llmReasons
                    )
      else
        rules += "SCORE_IN_UNRESOLVED_BAND"
        Outcome(MatchOutcome.UNRESOLVED, MatchPath.PROBABILISTIC, 0.0, Vector("SCORE_UNRESOLVED"))

    rules += s"PATH:${outcome.path}"
    val recorded: Map[String, Double] =
      TreeMap.from(
        features.toSeq ++ Seq("score" -> score) ++
          in.hadoopFeatures.toSeq.map((k, v) => s"hadoop.$k" -> v)
      )
    val modelVersion = adjudication.map(_.modelVersion)
    val promptVersion = adjudication.map(_.promptVersion)
    val derived =
      (Vector(in.left.ref, in.right.ref) ++ in.exactOutcomes.map(_.outcomeId)).distinct.sorted
    for
      decisionId <- StableIds.matchDecisionId(in.left.ref, in.right.ref, ctx.policy.policyVersion)
      decision = MatchDecision(
        schemaVersion = SchemaVersions.MatchDecision,
        decisionId = decisionId,
        sourceId = in.left.ref,
        targetId = in.right.ref,
        features = recorded,
        rulesFired = rules.toVector,
        decision = outcome.decision,
        confidence = math.min(1.0, math.max(0.0, outcome.confidence)),
        reasonCodes = outcome.reasons,
        policyVersion = ctx.policy.policyVersion,
        modelVersion = modelVersion,
        promptVersion = promptVersion,
        decidedAt = ctx.decidedAt,
        runId = ctx.runId,
        provenance = Provenance(
          schemaVersion = SchemaVersions.Provenance,
          sourceDataset = SourceType.SCHOLARCHAIN,
          sourceRecordId = in.candidateId,
          snapshot = in.snapshot,
          runId = ctx.runId,
          policyVersion = Some(ctx.policy.policyVersion),
          modelVersion = modelVersion,
          promptVersion = promptVersion,
          derivedFrom = derived
        )
      )
      valid <- WireCodec
        .validate(decision)
        .left
        .map(errs => s"decision for ${in.candidateId} violates its contract: $errs")
    yield DecisionResult(valid, outcome.path, score, adjudication)

/** Builds the adjudication request: candidate IDs plus a copy of the compared fields. */
object AdjudicationRequests:
  private def idsJson(ids: Map[ErIdType, Set[String]]): ujson.Obj =
    ujson.Obj.from(
      ids.toSeq
        .filter(_._2.nonEmpty)
        .sortBy(_._1.toString)
        .map((t, v) => t.toString -> ujson.Arr.from(v.toVector.sorted.map(ujson.Str(_))))
    )

  private def strs(v: Vector[String]): ujson.Arr = ujson.Arr.from(v.map(ujson.Str(_)))

  private def opt(v: Option[Int]): ujson.Value = v.map(x => ujson.Num(x)).getOrElse(ujson.Null)

  def fields(v: EntityView): ujson.Obj = v match
    case w: WorkView =>
      ujson.Obj(
        "ref" -> w.ref,
        "source" -> w.source.toString,
        "sourceRecordId" -> w.sourceRecordId,
        "title" -> w.title,
        "year" -> opt(w.year),
        "authors" -> strs(w.authorNames),
        "venues" -> strs(w.venues),
        "ids" -> idsJson(w.ids)
      )
    case r: ResearcherView =>
      ujson.Obj(
        "ref" -> r.ref,
        "source" -> r.source.toString,
        "sourceRecordId" -> r.sourceRecordId,
        "name" -> r.name,
        "position" -> opt(r.position),
        "teamSize" -> opt(r.teamSize),
        "workTitle" -> r.work.map(w => ujson.Str(w.title)).getOrElse(ujson.Null),
        "year" -> opt(r.year),
        "venues" -> strs(r.work.map(_.venues).getOrElse(Vector.empty)),
        "coauthors" -> strs(r.coauthorNames),
        "affiliations" -> strs(r.affiliations),
        "ids" -> idsJson(r.ids)
      )

  def build(in: PairInput, features: Map[String, Double], score: Double): AdjudicationRequest =
    AdjudicationRequest(
      candidateId = in.candidateId,
      entityType = in.kind.toString,
      left = fields(in.left),
      right = fields(in.right),
      features = features,
      score = score
    )
