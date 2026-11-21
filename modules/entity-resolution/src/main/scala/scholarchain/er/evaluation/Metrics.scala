package scholarchain.er.evaluation

/** A rate with its counts. `value` and `wilson` are `None` when the denominator is 0. */
final case class Ratio(num: Int, den: Int):
  def value: Option[Double] = if den == 0 then None else Some(num.toDouble / den)

  /** Wilson score interval at 95% (z = 1.959964). Honest for small n, unlike the Wald interval. */
  def wilson: Option[(Double, Double)] = Ratio.wilson(num, den, Ratio.Z95)

object Ratio:
  val Z95 = 1.959963984540054

  def wilson(k: Int, n: Int, z: Double): Option[(Double, Double)] =
    if n <= 0 then None
    else
      val p = k.toDouble / n
      val z2 = z * z
      val denom = 1.0 + z2 / n
      val centre = (p + z2 / (2.0 * n)) / denom
      val half = z * math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n)) / denom
      Some((math.max(0.0, centre - half), math.min(1.0, centre + half)))

/** Outcome names of `match-decision-v1` (core MatchOutcome), as recorded in run output. */
object Outcomes:
  val Accepted = "ACCEPTED"
  val Rejected = "REJECTED"
  val Unresolved = "UNRESOLVED"
  val Conflict = "CONFLICT"
  val All: Vector[String] = Vector(Accepted, Rejected, Unresolved, Conflict)

/** Gold label x system outcome counts over the included (non-UNSURE) pairs.
  *
  * Counting rules (stated in every summary):
  *   - Only ACCEPTED is a predicted match (a merge). REJECTED, UNRESOLVED and CONFLICT are all
  *     "not merged".
  *   - TP = SAME and ACCEPTED; FP = DIFFERENT and ACCEPTED; FN = SAME and not ACCEPTED (so a
  *     SAME pair left UNRESOLVED or CONFLICT costs recall); TN = DIFFERENT and not ACCEPTED.
  *   - CONFLICT is never merged, so it can never be a TP or an FP.
  *   - Coverage = (ACCEPTED + REJECTED) / included: the share of pairs given a decisive answer.
  *   - Unresolved rate = UNRESOLVED / included; conflict rate = CONFLICT / included.
  *   - Decided-only recall = TP / (SAME and ACCEPTED or REJECTED): recall where the system
  *     committed to an answer.
  *   - UNSURE labels are excluded from every rate and counted separately.
  */
final case class Confusion(cells: Map[(GoldLabel, String), Int]):
  def count(g: GoldLabel, o: String): Int = cells.getOrElse((g, o), 0)
  private def row(g: GoldLabel): Int = Outcomes.All.map(count(g, _)).sum
  private def col(o: String): Int = count(GoldLabel.SAME, o) + count(GoldLabel.DIFFERENT, o)

  def goldSame: Int = row(GoldLabel.SAME)
  def goldDifferent: Int = row(GoldLabel.DIFFERENT)
  def included: Int = goldSame + goldDifferent
  def tp: Int = count(GoldLabel.SAME, Outcomes.Accepted)
  def fp: Int = count(GoldLabel.DIFFERENT, Outcomes.Accepted)
  def fn: Int = goldSame - tp
  def tn: Int = goldDifferent - fp

  def precision: Ratio = Ratio(tp, tp + fp)
  def recall: Ratio = Ratio(tp, tp + fn)
  def specificity: Ratio = Ratio(tn, tn + fp)

  def f1: Option[Double] =
    for
      p <- precision.value
      r <- recall.value
      if p + r > 0
    yield 2 * p * r / (p + r)

  def coverage: Ratio = Ratio(col(Outcomes.Accepted) + col(Outcomes.Rejected), included)
  def unresolvedRate: Ratio = Ratio(col(Outcomes.Unresolved), included)
  def conflictRate: Ratio = Ratio(col(Outcomes.Conflict), included)

  def decidedRecall: Ratio =
    Ratio(tp, tp + count(GoldLabel.SAME, Outcomes.Rejected))

object Confusion:
  /** Labels of UNSURE pairs are dropped here; callers count them separately. */
  def of(pairs: Iterable[(GoldLabel, String)]): Confusion =
    Confusion(
      pairs
        .filter(_._1 != GoldLabel.UNSURE)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap
    )
