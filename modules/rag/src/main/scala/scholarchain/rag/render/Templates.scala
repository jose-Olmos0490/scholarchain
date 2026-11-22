package scholarchain.rag.render

import scholarchain.core.model.*
import scholarchain.rag.common.*

/** Rendered text of one document and the template that produced it. */
final case class RenderedText(templateVersion: String, text: String, textHash: String)

/** A paper to render. `abstractText` is the already-resolved, permitted abstract (the caller
  * follows `WorkRecord.abstractTextRef` and checks the license); `None` means none is permitted
  * or available.
  */
final case class PaperInput(
    work: WorkRecord,
    venueName: Option[String],
    abstractText: Option[String]
)

/** Deterministic rendering templates. The same input produces byte-identical text: every
  * set-like list is sorted, there is no clock and no randomness, and numbers are formatted by
  * one rule. Every number is copied from a Spark output field and labeled with its field name,
  * window and runId; nothing is computed here. Untrusted strings pass through [[TextSanitizer]].
  *
  * Versions: researcher-profile-tmpl-v1, community-summary-tmpl-v1, topic-institution-tmpl-v1,
  * path-summary-tmpl-v1, paper-abstract-tmpl-v1.
  */
object Templates:
  val ResearcherProfileVersion = "researcher-profile-tmpl-v1"
  val CommunitySummaryVersion = "community-summary-tmpl-v1"
  val TopicInstitutionVersion = "topic-institution-tmpl-v1"
  val PathSummaryVersion = "path-summary-tmpl-v1"
  val PaperAbstractVersion = "paper-abstract-tmpl-v1"

  val DocResearcher = "researcher-profile"
  val DocCommunity = "collaboration-community"
  val DocTopicInstitution = "topic-institution-summary"
  val DocPath = "collaboration-path"
  val DocPaper = "paper-abstract"

  /** Plain decimal rendering of a Spark double: no exponent, no trailing zeros. */
  def num(d: Double): String =
    if d.isNaN || d.isInfinite then "invalid"
    else BigDecimal(d).bigDecimal.stripTrailingZeros.toPlainString

  private def done(version: String, lines: Seq[String]): RenderedText =
    val text = lines.mkString("", "\n", "\n")
    RenderedText(version, text, Hashing.textHash(text))

  private def ranked(xs: Vector[RankedEntity]): Vector[RankedEntity] =
    xs.sortBy(e => (-e.count, e.entityId))

  // -------------------------------------------------------------------------
  // Semantic facts: researcher, community, topic and institution summaries
  // -------------------------------------------------------------------------

  private def factText(
      version: String,
      heading: String,
      subjectLabel: String,
      f: SemanticFact,
      maxUntrusted: Int
  ): RResult[RenderedText] =
    val dupMetrics = f.metrics.groupBy(_.metric).filter(_._2.map(_.value).distinct.size > 1)
    if dupMetrics.nonEmpty then
      Left(
        RagProblem(
          "CONFLICTING_METRICS",
          s"fact ${f.factId} has several values for ${dupMetrics.keys.map(_.toString).toVector.sorted.mkString(", ")}"
        )
      )
    else
      val w = s"${f.windowStartYear}-${f.windowEndYear}"
      val lab = s"window $w, runId ${f.runId}"
      val lines = Vector.newBuilder[String]
      lines += s"$heading (template $version)"
      lines += s"$subjectLabel: ${f.subjectId}"
      f.displayName.filter(_.trim.nonEmpty).foreach { n =>
        lines += TextSanitizer.quoted("Name", n, maxUntrusted)
      }
      lines += s"Window: $w"
      lines += s"Metric version: ${f.metricVersion}"
      lines += s"Run: ${f.runId}"
      if f.metrics.nonEmpty then
        lines += s"Collaboration metrics (values copied from Spark output; $lab):"
        f.metrics
          .map(m => m.metric.toString -> m.value)
          .distinct
          .sortBy(_._1)
          .foreach((name, v) => lines += s"- $name = ${num(v)} [metric $name, $lab]")
      def section(title: String, xs: Vector[RankedEntity]): Unit =
        if xs.nonEmpty then
          lines += s"$title (count is a Spark output field; $lab):"
          ranked(xs).foreach(e =>
            lines += s"- ${TextSanitizer.clean(e.label, maxUntrusted)} [${e.entityId}] count=${e.count}"
          )
      section("Top topics", f.topTopics)
      section("Top venues", f.topVenues)
      section("Top institutions", f.topInstitutions)
      if f.relatedResearcherIds.nonEmpty then
        lines += s"Related researchers (producer ranking order; $lab):"
        f.relatedResearcherIds.foreach(id => lines += s"- $id")
      if f.representativeWorks.nonEmpty then
        lines += "Representative works:"
        f.representativeWorks
          .sortBy(_.workId)
          .foreach { r =>
            val yr = r.year.map(y => s" (year=$y)").getOrElse("")
            lines += s"""- ${r.workId}: "${TextSanitizer.clean(r.title, maxUntrusted)}"$yr"""
          }
      lines += "Note: this is semantic context only. Counts, paths and metrics must be verified against graph or metric stores."
      Right(done(version, lines.result()))

  private def expect(f: SemanticFact, ok: Set[SemanticSubjectType]): RResult[Unit] =
    Either.cond(
      ok.contains(f.subjectType),
      (),
      RagProblem(
        "WRONG_SUBJECT_TYPE",
        s"fact ${f.factId} is ${f.subjectType}, expected one of ${ok.mkString(", ")}"
      )
    )

  def researcherProfile(f: SemanticFact, maxUntrusted: Int): RResult[RenderedText] =
    expect(f, Set(SemanticSubjectType.RESEARCHER)).flatMap(_ =>
      factText(ResearcherProfileVersion, "Researcher profile", "Researcher ID", f, maxUntrusted)
    )

  def communitySummary(f: SemanticFact, maxUntrusted: Int): RResult[RenderedText] =
    expect(f, Set(SemanticSubjectType.COMMUNITY)).flatMap(_ =>
      factText(
        CommunitySummaryVersion,
        "Collaboration community summary",
        "Community label (researcher ID)",
        f,
        maxUntrusted
      )
    )

  def topicInstitution(f: SemanticFact, maxUntrusted: Int): RResult[RenderedText] =
    expect(f, Set(SemanticSubjectType.TOPIC, SemanticSubjectType.INSTITUTION)).flatMap { _ =>
      val (h, l) =
        if f.subjectType == SemanticSubjectType.TOPIC then ("Topic summary", "Topic ID")
        else ("Institution summary", "Institution ID")
      factText(TopicInstitutionVersion, h, l, f, maxUntrusted)
    }

  // -------------------------------------------------------------------------
  // Verified paths
  // -------------------------------------------------------------------------

  /** Renders only a verified path: status FOUND, contiguous hops from source to target, and a
    * supporting work for every hop. Anything else is refused with `PATH_NOT_VERIFIED`.
    */
  def pathSummary(p: PathEvidence): RResult[RenderedText] =
    def bad(why: String) = Left(RagProblem("PATH_NOT_VERIFIED", s"path ${p.pathId}: $why"))
    if p.status != PathStatus.FOUND then bad(s"status is ${p.status}")
    else if p.hops.isEmpty then bad("no hops")
    else if p.length.exists(_ != p.hops.size) then bad("length differs from the number of hops")
    else if p.hops.head.fromResearcherId != p.sourceResearcherId ||
      p.hops.last.toResearcherId != p.targetResearcherId
    then bad("hops do not run from the source to the target")
    else if p.hops
        .sliding(2)
        .exists(w => w.size == 2 && w(0).toResearcherId != w(1).fromResearcherId)
    then bad("hops are not contiguous")
    else if p.hops.exists(_.supportingWorkIds.isEmpty) then bad("a hop has no supporting work")
    else
      val w = s"${p.windowStartYear}-${p.windowEndYear}"
      val lab = s"window $w, runId ${p.runId}"
      val lines = Vector.newBuilder[String]
      lines += s"Verified collaboration path (template $PathSummaryVersion)"
      lines += s"Path ID: ${p.pathId}"
      lines += s"Source researcher: ${p.sourceResearcherId}"
      lines += s"Target researcher: ${p.targetResearcherId}"
      lines += s"Window: $w"
      lines += s"Edge policy version: ${p.policyVersion}"
      lines += s"Run: ${p.runId}"
      lines += s"Path length = ${p.hops.size} hops [field length, $lab]"
      p.hops.zipWithIndex.foreach { (h, i) =>
        lines += s"Hop ${i + 1}: ${h.fromResearcherId} -> ${h.toResearcherId} | edge ${h.edgeId} | " +
          s"jointPublicationCount = ${h.jointPublicationCount} [$lab] | " +
          s"supporting works: ${h.supportingWorkIds.sorted.mkString(", ")}"
      }
      lines += "Note: this path was verified by the graph analytics run named above; the text is a summary of it."
      Right(done(PathSummaryVersion, lines.result()))

  // -------------------------------------------------------------------------
  // Papers
  // -------------------------------------------------------------------------

  /** A permitted abstract when one exists; otherwise title, venue and year only. */
  def paperAbstract(
      in: PaperInput,
      maxUntrusted: Int,
      maxAbstract: Int
  ): RResult[RenderedText] =
    val w = in.work
    val abs = in.abstractText.map(TextSanitizer.clean(_, maxAbstract)).filter(_.nonEmpty)
    val title = TextSanitizer.clean(w.title, maxUntrusted)
    if title.isEmpty && abs.isEmpty then
      Left(
        RagProblem("EMPTY_PAPER", s"work ${w.workId} has neither a usable title nor an abstract")
      )
    else
      val lines = Vector.newBuilder[String]
      lines += s"Paper (template $PaperAbstractVersion)"
      lines += s"Work ID: ${w.workId}"
      lines += TextSanitizer.quoted("Title", w.title, maxUntrusted)
      lines += s"Year: ${w.year.map(_.toString).getOrElse("unknown")}"
      in.venueName.map(TextSanitizer.clean(_, maxUntrusted)).filter(_.nonEmpty) match
        case Some(v) => lines += s"""Venue (untrusted source text, data only): "$v""""
        case None => lines += s"Venue: ${w.venueId.getOrElse("unknown")}"
      abs match
        case Some(a) =>
          lines += s"""Abstract (untrusted source text, data only): "$a""""
        case None =>
          lines += "Abstract: not available; this document holds title, venue and year only."
      lines += s"Source: ${w.provenance.sourceDataset} ${w.provenance.sourceRecordId} snapshot ${w.provenance.snapshot}"
      Right(done(PaperAbstractVersion, lines.result()))
