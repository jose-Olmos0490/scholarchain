package scholarchain.er.evaluation

/** One gold pair joined to the run's decision for that pair. */
final case class EvalRow(gold: GoldRow, key: KeyRow, decision: RunDecision):
  def included: Boolean = gold.label != GoldLabel.UNSURE
  def merged: Boolean = decision.decision == Outcomes.Accepted

  /** TP / FP / FN / TN under the rules of [[Confusion]]; EXCLUDED for UNSURE. */
  def outcomeClass: String = gold.label match
    case GoldLabel.UNSURE => "EXCLUDED_UNSURE"
    case GoldLabel.SAME => if merged then "TP" else "FN"
    case GoldLabel.DIFFERENT => if merged then "FP" else "TN"

/** Precision/recall of one counterfactual threshold pair (reporting only; never fed back). */
final case class SensitivityRow(
    variant: String,
    kind: String,
    accept: Double,
    reject: Double,
    configured: Boolean,
    rethresholded: Int,
    confusion: Confusion
)

object Evaluation:
  val Kinds: Vector[String] = Vector("researcher", "work")
  val PathOrder: Vector[String] =
    Vector("EXACT_ID", "DETERMINISTIC_BIBLIOGRAPHIC", "PROBABILISTIC", "LLM_ADJUDICATED")

  /** Policy variant: only decisions the configured thresholds actually governed are re-scored. */
  val PolicyVariant = "policy-score-path"

  /** Score-only variant: every non-CONFLICT pair decided by its weighted score alone. */
  val ScoreOnlyVariant = "score-only"

  private val ScoreRules =
    Set(
      "SCORE_AT_OR_ABOVE_ACCEPT",
      "SCORE_BELOW_REJECT",
      "SCORE_IN_LLM_BAND",
      "SCORE_IN_UNRESOLVED_BAND"
    )

  /** Joins gold rows to decisions: gold `pair_id` -> key row -> run decision by `candidate_id`.
    * Every gold pair must join, kinds must agree across gold, key and run, and the run's refs must
    * be the key's refs (either order). Any mismatch is an error, never a silent drop.
    */
  def join(
      gold: Vector[GoldRow],
      key: Vector[KeyRow],
      run: RunData
  ): Either[String, Vector[EvalRow]] =
    val keyById = key.map(k => k.pairId -> k).toMap
    val joined = gold.map { g =>
      keyById.get(g.pairId) match
        case None => Left(s"gold pair ${g.pairId} is not in the review key")
        case Some(k) if k.kind != g.kind =>
          Left(s"gold pair ${g.pairId}: kind ${g.kind} in gold set, ${k.kind} in review key")
        case Some(k) =>
          run.byCandidate.get(k.candidateId) match
            case None =>
              Left(
                s"gold pair ${g.pairId}: candidate ${k.candidateId} has no decision in ${run.runId}"
              )
            case Some(d) if d.kind != k.kind =>
              Left(s"gold pair ${g.pairId}: kind ${k.kind} in review key, ${d.kind} in run")
            case Some(d) if Set(d.leftRef, d.rightRef) != Set(k.leftRef, k.rightRef) =>
              Left(s"gold pair ${g.pairId}: run refs differ from review-key refs")
            case Some(d) => Right(EvalRow(g, k, d))
    }
    joined.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None => Right(joined.collect { case Right(r) => r })

  def confusion(rows: Iterable[EvalRow]): Confusion =
    Confusion.of(rows.map(r => r.gold.label -> r.decision.decision))

  def byKind(rows: Vector[EvalRow]): Vector[(String, Confusion)] =
    Kinds.map(k => k -> confusion(rows.filter(_.gold.kind == k)))

  def byKindAndPath(rows: Vector[EvalRow]): Vector[(String, String, Confusion)] =
    for
      k <- Kinds
      p <- PathOrder
    yield (k, p, confusion(rows.filter(r => r.gold.kind == k && r.decision.path == p)))

  def unsureByKind(rows: Vector[EvalRow]): Vector[(String, Int)] =
    Kinds.map(k => k -> rows.count(r => r.gold.kind == k && r.gold.label == GoldLabel.UNSURE))

  /** True when the configured accept/reject thresholds decided this outcome (weighted-score path,
    * no generic-title or name-gate override).
    */
  def scoreGoverned(d: RunDecision): Boolean =
    (d.path == "PROBABILISTIC" || d.path == "LLM_ADJUDICATED") &&
      !d.rulesFired.contains("GENERIC_TITLE") &&
      !d.rulesFired.contains("NAME_INCOMPATIBLE") &&
      d.rulesFired.exists(ScoreRules.contains)

  /** Counterfactual outcome of a score. The band between the thresholds is UNRESOLVED: no
    * adjudicator is re-run in a counterfactual.
    */
  def byScore(score: Double, accept: Double, reject: Double): String =
    if score >= accept then Outcomes.Accepted
    else if score < reject then Outcomes.Rejected
    else Outcomes.Unresolved

  def counterfactual(variant: String, d: RunDecision, accept: Double, reject: Double): String =
    variant match
      case PolicyVariant =>
        if scoreGoverned(d) then byScore(d.score, accept, reject) else d.decision
      case _ =>
        if d.decision == Outcomes.Conflict then Outcomes.Conflict
        else byScore(d.score, accept, reject)

  def sensitivity(
      rows: Vector[EvalRow],
      accepts: Vector[Double],
      rejects: Vector[Double],
      run: RunData
  ): Vector[SensitivityRow] =
    for
      v <- Vector(PolicyVariant, ScoreOnlyVariant)
      k <- Kinds
      a <- accepts.sorted
      r <- rejects.sorted
      if r <= a
    yield
      val ks = rows.filter(_.gold.kind == k)
      val affected =
        if v == PolicyVariant then ks.count(x => scoreGoverned(x.decision))
        else ks.count(_.decision.decision != Outcomes.Conflict)
      SensitivityRow(
        v,
        k,
        a,
        r,
        a == run.acceptThreshold && r == run.rejectThreshold,
        affected,
        Confusion.of(ks.map(x => x.gold.label -> counterfactual(v, x.decision, a, r)))
      )

  def falsePositives(rows: Vector[EvalRow]): Vector[EvalRow] =
    rows.filter(_.outcomeClass == "FP").sortBy(_.gold.pairId)

  def falseNegatives(rows: Vector[EvalRow]): Vector[EvalRow] =
    rows.filter(_.outcomeClass == "FN").sortBy(_.gold.pairId)
