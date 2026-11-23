package scholarchain.rag.agents

import java.text.Normalizer
import java.util.Locale
import scholarchain.core.config.ResolverConfig
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.rag.render.TextSanitizer
import scholarchain.storage.directory.{NameCandidate, ResearcherDirectory}

/** The name form the directory is keyed by. It mirrors the fold applied to author names when the
  * records were normalized: NFKD, combining marks and invisible format characters dropped, letters
  * and digits lower-cased, every other run of characters one space.
  */
object NameNormalizer:
  def fold(raw: String): String =
    if raw == null then ""
    else
      val d = Normalizer.normalize(raw, Normalizer.Form.NFKD)
      val sb = new StringBuilder
      var pendingSpace = false
      var i = 0
      while i < d.length do
        val cp = d.codePointAt(i)
        val t = Character.getType(cp)
        val mark =
          t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK ||
            t == Character.ENCLOSING_MARK
        if mark || t == Character.FORMAT then ()
        else if Character.isLetterOrDigit(cp) then
          if pendingSpace && sb.nonEmpty then sb.append(' ')
          pendingSpace = false
          sb.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT))
        else pendingSpace = true
        i += Character.charCount(cp)
      sb.toString

/** A candidate as shown to a person who must choose. Text fields are sanitized; nothing is
  * recomputed: `confidence` is the stored match confidence.
  */
final case class CandidateView(
    researcherId: String,
    displayName: String,
    confidence: Double,
    sourceIds: Vector[String],
    affiliations: Vector[String],
    coauthorSample: Vector[String],
    matchDecisionIds: Vector[String],
    policyVersion: String,
    runId: String
)

enum AmbiguityReason:
  /** The best candidate is below `rag.resolver.minConfidence`. */
  case LowConfidence

  /** The best two candidates are closer than `rag.resolver.minMargin`. */
  case SmallMargin

sealed trait Resolution:
  def name: String

object Resolution:
  /** The one candidate that meets both thresholds. */
  final case class Resolved(
      name: String,
      candidate: CandidateView,
      runnerUpConfidence: Option[Double]
  ) extends Resolution

  /** More than one plausible identity, or one that is not confident enough. The caller must ask
    * the person to choose; this agent never picks.
    */
  final case class Ambiguous(
      name: String,
      candidates: Vector[CandidateView],
      reason: AmbiguityReason,
      totalFound: Int
  ) extends Resolution

  final case class NotFound(name: String) extends Resolution

/** The researchers a plan refers to, split by outcome. Explicit researcher IDs are taken as given
  * (they carry no match confidence).
  */
final case class ResolutionSet(
    resolved: Vector[Resolution.Resolved],
    explicitIds: Vector[String],
    ambiguous: Vector[Resolution.Ambiguous],
    notFound: Vector[Resolution.NotFound]
):
  /** Every researcher ID that is certain, in request order: explicit IDs, then resolved names. */
  def researcherIds: Vector[String] =
    (explicitIds ++ resolved.map(_.candidate.researcherId)).distinct

  def complete: Boolean = ambiguous.isEmpty && notFound.isEmpty

/** Resolves researcher names against the stored identity directory.
  *
  * A name is `Resolved` only when the best candidate's stored confidence is at least
  * `rag.resolver.minConfidence` AND it leads the runner-up by at least `rag.resolver.minMargin`.
  * A single candidate must meet the confidence threshold. Otherwise the name is `Ambiguous` with
  * up to `rag.resolver.maxCandidates` candidates, or `NotFound`. There is no silent choice, no
  * fuzzy matching and no recomputed confidence.
  */
final class ResearcherResolverAgent(directory: ResearcherDirectory, cfg: ResolverConfig):
  private val Epsilon = 1e-9
  private val DisplayCodePoints = 200

  def resolve(rawName: String): SResult[Resolution] =
    val name = NameNormalizer.fold(rawName)
    if name.isEmpty then
      Left(StageFailure.Rejected("NAME_INVALID", "the researcher name has no letters or digits"))
    else
      directory
        .candidatesByName(name, cfg.maxCandidates + 1)
        .left
        .map(StageFailure.fromStorage("researcherDirectory.candidatesByName", _))
        .map(found => decide(name, found))

  private def decide(name: String, found: Vector[NameCandidate]): Resolution =
    val ranked = found
      .filter(c => !c.confidence.isNaN && c.confidence >= 0.0 && c.confidence <= 1.0)
      .sortBy(c => (-c.confidence, c.researcherId))
    ranked.headOption match
      case None => Resolution.NotFound(name)
      case Some(top) =>
        val rest = ranked.tail
        val runnerUp = rest.headOption.map(_.confidence)
        val confident = top.confidence + Epsilon >= cfg.minConfidence
        val clear = runnerUp.forall(r => top.confidence - r + Epsilon >= cfg.minMargin)
        if confident && clear then Resolution.Resolved(name, view(top), runnerUp)
        else
          Resolution.Ambiguous(
            name,
            ranked.take(cfg.maxCandidates).map(view),
            if !confident then AmbiguityReason.LowConfidence else AmbiguityReason.SmallMargin,
            ranked.size
          )

  private def view(c: NameCandidate): CandidateView =
    def t(s: String) = TextSanitizer.clean(s, DisplayCodePoints)
    CandidateView(
      c.researcherId,
      t(c.displayName),
      c.confidence,
      c.sourceIds,
      c.affiliations.map(t),
      c.coauthorSample.map(t),
      c.matchDecisionIds,
      c.policyVersion,
      c.runId
    )

  /** Resolves every name of a plan. A directory failure (not a miss) fails the whole call. */
  def resolveAll(names: Vector[String], explicitIds: Vector[String]): SResult[ResolutionSet] =
    for
      _ <- explicitIds
        .find(id => StableId.parseAs(id, Set(IdKind.Researcher)).isLeft)
        .fold[SResult[Unit]](Right(()))(_ =>
          Left(StageFailure.Rejected("RESEARCHER_ID_INVALID", "a researcher ID is malformed"))
        )
      outcomes <- names.foldLeft[SResult[Vector[Resolution]]](Right(Vector.empty)) { (acc, n) =>
        acc.flatMap(done => resolve(n).map(done :+ _))
      }
    yield ResolutionSet(
      outcomes.collect { case r: Resolution.Resolved => r },
      explicitIds.distinct,
      outcomes.collect { case a: Resolution.Ambiguous => a },
      outcomes.collect { case n: Resolution.NotFound => n }
    )
