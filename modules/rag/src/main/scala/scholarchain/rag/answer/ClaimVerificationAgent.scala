package scholarchain.rag.answer

import scholarchain.core.model.{EvidenceBundle, VerificationStatus}
import scholarchain.rag.render.TextSanitizer

/** The verdict on one synthesized claim.
  *
  * @param ordinal
  *   the claim's position in the model's answer
  * @param reasons
  *   deterministic reasons, one per failed or weakened check
  * @param uncertainty
  *   the label a kept `PARTIALLY_SUPPORTED` claim carries
  * @param verified
  *   the assertions that verified, with their stored values and stored windows
  * @param display
  *   the text a person reads, written by code from `verified`; empty for a removed claim
  * @param interpretation
  *   the text of the interpretation template the claim chose, filled from `verified`; `None`
  *   when none was chosen or it did not apply
  */
final case class ClaimVerdict(
    ordinal: Int,
    claim: SynthClaim,
    status: VerificationStatus,
    reasons: Vector[String],
    uncertainty: Option[String],
    verified: Vector[VerifiedFact],
    display: String,
    interpretation: Option[String]
):
  def kept: Boolean =
    status == VerificationStatus.VERIFIED || status == VerificationStatus.PARTIALLY_SUPPORTED

final case class VerificationReport(verdicts: Vector[ClaimVerdict]):
  def kept: Vector[ClaimVerdict] = verdicts.filter(_.kept)
  def removed: Vector[ClaimVerdict] = verdicts.filterNot(_.kept)

  /** The outcome of the whole answer: `CONFLICTING` when any claim contradicted the evidence;
    * `VERIFIED` when every claim verified; `PARTIALLY_SUPPORTED` when some claim verified or was
    * partly supported; otherwise `UNSUPPORTED` (also when there was no claim to verify).
    */
  def overall: VerificationStatus =
    val statuses = verdicts.map(_.status)
    if statuses.contains(VerificationStatus.CONFLICTING) then VerificationStatus.CONFLICTING
    else if statuses.nonEmpty && statuses.forall(_ == VerificationStatus.VERIFIED) then
      VerificationStatus.VERIFIED
    else if statuses.exists(s =>
        s == VerificationStatus.VERIFIED || s == VerificationStatus.PARTIALLY_SUPPORTED
      )
    then VerificationStatus.PARTIALLY_SUPPORTED
    else VerificationStatus.UNSUPPORTED

/** Verifies the assertions of synthesized claims against an evidence bundle, and writes the text a
  * person reads from the ones that verify. Deterministic code only: it calls no model, no store
  * and no network.
  *
  * The model supplies no prose, so there is no prose to check: every sentence, number, name,
  * relation and window a person reads is rendered by [[ClaimRendering]] from a verified
  * assertion, or by [[InterpretationTemplates]] from a verified assertion.
  *
  * Rules, per claim:
  *  1. every cited evidence ID exists in the bundle, and at least one is cited;
  *  1. the claim has at least one assertion;
  *  1. every assertion matches a stored fact of the evidence the claim cites: IDs and windows
  *     exactly (an asserted window must equal the stored window; a fact without a window takes
  *     none), whole numbers exactly, real numbers within [[Tolerance.Real]];
  *  1. a path hop is an edge inside a cited `GRAPH_PATH` item, between the hop's two researchers;
  *  1. `PATH_LENGTH` is the shortest stored path of the pair (the minimum over every stored path
  *     of the pair in the bundle), and is accepted only when the path query was complete (not
  *     truncated by the depth cap, nothing dropped, every returned path present, result limit
  *     not reached); `PATH_WITHIN_HOPS` states only that a stored path of at most that many hops
  *     exists;
  *  1. semantic evidence never supports a metric, coauthor edge, path hop, community, bridge or
  *     identity assertion.
  *
  * Status: `CONFLICTING` when an assertion differs from the stored fact; `UNSUPPORTED` when a
  * citation or an assertion has no support; `PARTIALLY_SUPPORTED` when every check passes but some
  * support is weaker than a stored fact (context only, evidence the claim does not cite, or a
  * chosen interpretation template that does not fit); otherwise `VERIFIED`.
  */
final class ClaimVerificationAgent:
  private enum Outcome:
    case Supported(weak: Option[String], verified: VerifiedFact)
    case Contradicted(why: String)
    case Unsupported(why: String)

  private def clean(s: String): String = TextSanitizer.clean(s, 80)

  def verify(bundle: EvidenceBundle, answer: SynthesizedAnswer): VerificationReport =
    val facts = EvidenceFacts.ofBundle(bundle)
    val byId = facts.map(f => f.item.evidenceId -> f).toMap
    val label = ClaimRendering.labeller(ClaimRendering.namesOf(facts))
    VerificationReport(answer.claims.zipWithIndex.map { (c, i) =>
      verifyClaim(i, c, byId, facts, label)
    })

  private def verifyClaim(
      ordinal: Int,
      claim: SynthClaim,
      byId: Map[String, ItemFacts],
      all: Vector[ItemFacts],
      label: String => String
  ): ClaimVerdict =
    val missing = claim.evidenceIds.filterNot(byId.contains)
    val cited = claim.evidenceIds.distinct.flatMap(byId.get)
    val citedGraph = cited.filter(_.graph)
    val citedSemantic = cited.filterNot(_.graph)
    val otherGraph =
      all.filter(f => f.graph && !cited.exists(_.item.evidenceId == f.item.evidenceId))

    val citationProblems =
      if claim.evidenceIds.isEmpty then Vector("the claim cites no evidence")
      else if missing.nonEmpty then
        Vector(s"${missing.size} cited evidence ID(s) are not in the bundle")
      else Vector.empty
    val noAssertion =
      if claim.assertions.isEmpty then Vector("the claim has no assertion to present")
      else Vector.empty
    val outcomes = claim.assertions.zipWithIndex.map { (a, i) =>
      i -> evaluate(a, citedGraph, citedSemantic, otherGraph, all)
    }

    val conflicts = outcomes.collect { case (i, Outcome.Contradicted(w)) =>
      s"assertion $i ${claim.assertions(i).kind}/${clean(claim.assertions(i).field)}: $w"
    }
    val unsupported = outcomes.collect { case (i, Outcome.Unsupported(w)) =>
      s"assertion $i ${claim.assertions(i).kind}/${clean(claim.assertions(i).field)}: $w"
    }
    val supported = outcomes.collect { case (_, s: Outcome.Supported) => s }
    val verified = supported.map(_.verified)
    val weak = supported.flatMap(_.weak).distinct

    val hard = citationProblems ++ noAssertion ++ unsupported
    val interpretation: Option[Either[String, String]] =
      claim.interpretation.map(id => InterpretationTemplates.render(id, verified, label))
    val interpretationProblem = interpretation.collect { case Left(why) => why }.toVector
    val (status, reasons) =
      if conflicts.nonEmpty then (VerificationStatus.CONFLICTING, conflicts ++ hard)
      else if hard.nonEmpty then (VerificationStatus.UNSUPPORTED, hard)
      else if weak.nonEmpty || interpretationProblem.nonEmpty then
        (VerificationStatus.PARTIALLY_SUPPORTED, weak ++ interpretationProblem)
      else (VerificationStatus.VERIFIED, Vector.empty)
    val kept =
      status == VerificationStatus.VERIFIED || status == VerificationStatus.PARTIALLY_SUPPORTED
    ClaimVerdict(
      ordinal,
      claim,
      status,
      reasons,
      Option.when(status == VerificationStatus.PARTIALLY_SUPPORTED)(reasons.mkString("; ")),
      if kept then verified else Vector.empty,
      if kept then verified.map(ClaimRendering.sentence(_, label)).distinct.mkString(" ") else "",
      if kept then interpretation.collect { case Right(t) => t } else None
    )

  // ---- assertions ---------------------------------------------------------------------------

  private def evaluate(
      a: Assertion,
      citedGraph: Vector[ItemFacts],
      citedSemantic: Vector[ItemFacts],
      otherGraph: Vector[ItemFacts],
      all: Vector[ItemFacts]
  ): Outcome =
    if !AssertionFields.allowed(a.kind, a.field) then
      Outcome.Unsupported("this field is not verifiable for this kind")
    else if AssertionFields.needsObject(a.field) && a.objectId.forall(_.isEmpty) then
      Outcome.Unsupported("objectId is required for this field")
    else if a.kind == AssertionKind.PUBLICATION && a.field == "DESCRIBED_IN_CONTEXT" then
      context(a, citedGraph, citedSemantic)
    else if a.kind == AssertionKind.WINDOW then window(a, citedGraph, citedSemantic)
    else if citedGraph.isEmpty then
      if citedSemantic.nonEmpty then
        Outcome.Unsupported(s"semantic evidence cannot support a ${a.kind} assertion")
      else Outcome.Unsupported("no evidence is cited")
    else if a.kind == AssertionKind.PATH_HOP && AssertionFields.pathBounds.contains(a.field) then
      pathBound(a, citedGraph, all)
    else stored(a, citedGraph, otherGraph)

  private def context(
      a: Assertion,
      citedGraph: Vector[ItemFacts],
      citedSemantic: Vector[ItemFacts]
  ): Outcome =
    if (citedGraph ++ citedSemantic).exists(_.ids.contains(a.subjectId)) then
      Outcome.Supported(
        Some("described only in retrieved context, which is not a graph fact"),
        VerifiedFact(a, None, None)
      )
    else Outcome.Unsupported("the subject does not appear in the cited evidence")

  private def window(
      a: Assertion,
      citedGraph: Vector[ItemFacts],
      citedSemantic: Vector[ItemFacts]
  ): Outcome =
    val withSubject = citedGraph.filter(_.ids.contains(a.subjectId))
    val windows = withSubject.flatMap(_.facts.flatMap(_.window)).distinct
    if a.window.isEmpty then Outcome.Unsupported("a window assertion needs a window")
    else if a.window.exists(windows.contains) then
      Outcome.Supported(None, VerifiedFact(a, None, a.window))
    else if windows.nonEmpty then
      Outcome.Contradicted(s"the cited evidence is for another window than the asserted one")
    else if citedSemantic.nonEmpty && citedGraph.isEmpty then
      Outcome.Unsupported("semantic evidence cannot support a window assertion")
    else Outcome.Unsupported("no cited evidence for this subject has a window")

  /** A fact matches an assertion when kind, subject, field and (where the field has one) object
    * agree and the windows are equal: an asserted window must be the stored one, and a stored
    * fact without a window takes none.
    */
  private def candidates(a: Assertion, items: Vector[ItemFacts]): Vector[Fact] =
    val obj = Option.when(AssertionFields.needsObject(a.field))(a.objectId).flatten
    items.flatMap(_.facts).filter { f =>
      f.kind == a.kind && f.subject == a.subjectId && f.field == a.field &&
      (!AssertionFields.needsObject(a.field) || f.obj == obj) &&
      f.window == a.window
    }

  private def stored(
      a: Assertion,
      citedGraph: Vector[ItemFacts],
      otherGraph: Vector[ItemFacts]
  ): Outcome =
    val cited = candidates(a, citedGraph)
    if cited.nonEmpty then compare(a, cited)
    else
      val uncited = candidates(a, otherGraph)
      compare(a, uncited) match
        case Outcome.Supported(_, v) =>
          Outcome.Supported(
            Some("supported by evidence in the bundle that the claim does not cite"),
            v
          )
        case _ => Outcome.Unsupported("no cited evidence holds this fact for this window")

  // ---- path length --------------------------------------------------------------------------

  /** `PATH_LENGTH` and `PATH_WITHIN_HOPS`. Both read the path items of the pair and the window
    * (a path is the same pair in either direction). The claim must cite at least one of them.
    *
    * `PATH_LENGTH` is the minimum over every stored path of the pair, and it is asserted only
    * when the query's bounds make that minimum the answer: no path dropped, the depth not
    * lowered, the result limit not reached and every returned path present in the bundle.
    * Otherwise only `PATH_WITHIN_HOPS` may be asserted, and it needs a stored path of at most
    * that many hops, within the query depth.
    */
  private def pathBound(
      a: Assertion,
      citedGraph: Vector[ItemFacts],
      all: Vector[ItemFacts]
  ): Outcome =
    val pair = Set(a.subjectId) ++ a.objectId
    def scopes(items: Vector[ItemFacts]) =
      items
        .flatMap(_.pathScope)
        .filter(s => Set(s.from, s.to) == pair && a.window.contains(s.window))
    val cited = scopes(citedGraph)
    val everywhere = scopes(all.filter(_.graph))
    val asked = numeric(a.value).filter(d => d == math.rint(d) && d >= 1)
    if cited.isEmpty then Outcome.Unsupported("no cited path evidence for this pair and window")
    else if asked.isEmpty then Outcome.Unsupported("a whole number of hops is required")
    else
      val n = asked.get.toInt
      val shortest = everywhere.map(_.length).min
      val first = cited.head
      // more than one path query for the pair and window (a different query shape in the
      // bundle) means the path set is not known to be complete
      val oneQuery = everywhere
        .map(x => (x.depthLimit, x.resultLimit, x.returned, x.dropped, x.depthCapped))
        .distinct
        .size == 1
      val complete = oneQuery && first.complete && everywhere.size == first.returned
      if a.field == "PATH_LENGTH" then
        if !complete then
          Outcome.Unsupported(
            "the stored path query was not complete (paths dropped, depth lowered, result limit " +
              "reached or a path missing from the bundle): only PATH_WITHIN_HOPS can be asserted"
          )
        else if n == shortest then
          Outcome.Supported(None, VerifiedFact(a, Some(FactValue.Num(shortest.toDouble)), a.window))
        else
          Outcome.Contradicted(
            "the asserted value differs from the stored shortest path length " +
              ClaimVerificationAgent.show(shortest.toDouble)
          )
      else if n > first.depthLimit then
        Outcome.Unsupported("the number of hops is beyond the depth of the path query")
      else if n >= shortest then
        Outcome.Supported(None, VerifiedFact(a, Some(FactValue.Num(n.toDouble)), a.window))
      else if complete then
        Outcome.Contradicted(
          "no stored path has so few hops; the shortest stored path has " +
            ClaimVerificationAgent.show(shortest.toDouble)
        )
      else
        Outcome.Unsupported("no stored path has so few hops, and the path query was not complete")

  private def numeric(v: AssertedValue): Option[Double] = v match
    case AssertedValue.Num(d) => Some(d)
    case AssertedValue.Text(s) => s.trim.toDoubleOption
    case AssertedValue.Absent => None

  private def equal(a: Assertion, f: Fact): Boolean = (a.value, f.value) match
    case (_, FactValue.Gap) => false
    case (v, FactValue.Num(stored)) =>
      numeric(v).exists { x =>
        if AssertionFields.isInteger(a.field) then x == stored
        else math.abs(x - stored) <= Tolerance.Real
      }
    case (AssertedValue.Text(s), FactValue.Text(t)) => s == t
    case (AssertedValue.Absent, FactValue.Text(_)) => AssertionFields.existence.contains(a.field)
    case _ => false

  private def compare(a: Assertion, found: Vector[Fact]): Outcome =
    if found.isEmpty then Outcome.Unsupported("no stored fact")
    else
      found.find(equal(a, _)) match
        case Some(f) => Outcome.Supported(None, VerifiedFact(a, Some(f.value), f.window))
        case None =>
          if found.forall(_.value == FactValue.Gap) then
            a.value match
              case AssertedValue.Absent =>
                Outcome.Supported(None, VerifiedFact(a, Some(FactValue.Gap), found.head.window))
              case AssertedValue.Text(s) if s.trim.equalsIgnoreCase("not stored") =>
                Outcome.Supported(None, VerifiedFact(a, Some(FactValue.Gap), found.head.window))
              case _ => Outcome.Unsupported("the stored value is absent")
          else if a.value == AssertedValue.Absent && !AssertionFields.existence.contains(a.field)
          then Outcome.Unsupported("a value is required")
          else
            Outcome.Contradicted(
              "the asserted value differs from the stored value " +
                found
                  .map(_.value)
                  .collect {
                    case FactValue.Num(d) => ClaimVerificationAgent.show(d)
                    case FactValue.Text(t) => clean(t)
                  }
                  .distinct
                  .take(3)
                  .mkString(" or ")
            )

object ClaimVerificationAgent:
  /** A number without a trailing `.0`; `invalid` for a value that is not a finite number. */
  def show(d: Double): String =
    if d.isNaN || d.isInfinite then "invalid"
    else if d == math.rint(d) && math.abs(d) < 1e15 then d.toLong.toString
    else
      BigDecimal(d)
        .setScale(6, BigDecimal.RoundingMode.HALF_UP)
        .bigDecimal
        .stripTrailingZeros
        .toPlainString
