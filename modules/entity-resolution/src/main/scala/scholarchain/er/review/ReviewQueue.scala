package scholarchain.er.review

import scholarchain.core.model.{MatchEntityType, MatchOutcome}
import scholarchain.er.adjudication.Canonical
import scholarchain.er.ids.ErIdType
import scholarchain.er.model.*
import scholarchain.er.policy.{DecisionResult, PairInput, Thresholds}

/** Review-queue stratum, assigned from system-side evidence (never from human labels). */
enum Stratum:
  /** The two records share an exact identifier (or a Phase 4 exact-identifier group). */
  case SHARED_EXACT_ID

  /** Score at or above the accept threshold without a shared identifier. */
  case HIGH_SCORE

  /** Score below the reject threshold, or a deterministic rejection. */
  case LIKELY_NON_MATCH

  /** Score inside [rejectThreshold, acceptThreshold), or a CONFLICT. */
  case BORDERLINE

/** One queued pair. The system score and decision are kept OUT of [[ReviewQueue.QueueColumns]]
  * and only written to the separate key file.
  */
final case class QueueEntry(
    pairId: String,
    stratum: Stratum,
    pair: PairInput,
    result: DecisionResult
)

/** Blind review queue for human gold-set labeling. */
object ReviewQueue:
  val Version = "review-queue-v1"

  val QueueColumns: Vector[String] = Vector(
    "pair_id",
    "kind",
    "source_dataset",
    "source_record_id",
    "target_dataset",
    "target_id",
    "left_name_or_title",
    "right_name_or_title",
    "left_ids",
    "right_ids",
    "left_coauthors",
    "right_coauthors",
    "left_affiliation",
    "right_affiliation",
    "left_year",
    "right_year",
    "left_venue",
    "right_venue",
    "human_label",
    "notes"
  )

  /** Columns of the sealed key file (system output; must not be shown to labelers). */
  val KeyColumns: Vector[String] = Vector(
    "pair_id",
    "kind",
    "stratum",
    "candidate_id",
    "left_ref",
    "right_ref",
    "decision_id",
    "match_path",
    "decision",
    "score",
    "confidence",
    "reason_codes",
    "policy_version",
    "run_id"
  )

  /** `rq1-<first 16 hex of sha256(entityType, leftRef, rightRef)>`. Stable across runs. */
  def pairId(p: PairInput): String =
    "rq1-" + Canonical.hashFields(p.kind.toString, p.left.ref, p.right.ref).take(16)

  def stratum(p: PairInput, r: DecisionResult, th: Thresholds): Stratum =
    val d = r.decision
    val sharedId = d.rulesFired.exists(_.startsWith("EXACT_ID_AGREE:")) ||
      p.exactOutcomes.nonEmpty
    if sharedId then Stratum.SHARED_EXACT_ID
    else if d.decision == MatchOutcome.CONFLICT then Stratum.BORDERLINE
    else if r.score >= th.acceptThreshold ||
      (r.path == MatchPath.DETERMINISTIC_BIBLIOGRAPHIC && d.decision == MatchOutcome.ACCEPTED)
    then Stratum.HIGH_SCORE
    else if r.score < th.rejectThreshold || d.decision == MatchOutcome.REJECTED &&
      r.path != MatchPath.LLM_ADJUDICATED
    then Stratum.LIKELY_NON_MATCH
    else Stratum.BORDERLINE

  /** Quota per stratum group: likely matches (shared ID, then high score), likely non-matches,
    * borderline. Shortfalls in one group are filled from the others in the order borderline,
    * likely non-match, likely match.
    */
  final case class Quotas(likelyMatch: Int, likelyNonMatch: Int, borderline: Int):
    def total: Int = likelyMatch + likelyNonMatch + borderline

  /** Selects at most `quotas.total` pairs. Order inside a stratum is by pair ID (a hash), so the
    * choice is deterministic and does not follow the score. When fewer pairs exist than the
    * total, every pair is selected.
    */
  def select(entries: Vector[QueueEntry], quotas: Quotas): Vector[QueueEntry] =
    val byStratum = entries.sortBy(_.pairId).groupBy(_.stratum)
    def of(s: Stratum*) = s.toVector.flatMap(x => byStratum.getOrElse(x, Vector.empty))
    val likely = of(Stratum.SHARED_EXACT_ID, Stratum.HIGH_SCORE)
    val nonMatch = of(Stratum.LIKELY_NON_MATCH)
    val border = of(Stratum.BORDERLINE)
    if entries.size <= quotas.total then entries.sortBy(_.pairId)
    else
      var picked = Vector(
        likely.take(quotas.likelyMatch),
        nonMatch.take(quotas.likelyNonMatch),
        border.take(quotas.borderline)
      )
      var rest = Vector(
        likely.drop(quotas.likelyMatch),
        nonMatch.drop(quotas.likelyNonMatch),
        border.drop(quotas.borderline)
      )
      var missing = quotas.total - picked.map(_.size).sum
      for g <- Vector(2, 1, 0) do
        val extra = rest(g).take(missing)
        picked = picked.updated(g, picked(g) ++ extra)
        rest = rest.updated(g, rest(g).drop(extra.size))
        missing -= extra.size
      picked.flatten.sortBy(_.pairId)

  private def idsText(ids: Map[ErIdType, Set[String]], prefix: String = ""): Vector[String] =
    ids.toVector
      .sortBy(_._1.ordinal)
      .flatMap((t, vs) => vs.toVector.sorted.map(v => s"$prefix$t=$v"))

  private def recordId(v: EntityView): String = v match
    case r: ResearcherView =>
      r.position.fold(r.sourceRecordId)(p => s"${r.sourceRecordId}#author$p")
    case w: WorkView => w.sourceRecordId

  private def label(v: EntityView): String = v match
    case r: ResearcherView => r.name
    case w: WorkView => w.title

  private def ids(v: EntityView): String = v match
    case r: ResearcherView =>
      (idsText(r.ids) ++ idsText(r.contextWorkIds, "work.")).mkString("; ")
    case w: WorkView => idsText(w.ids).mkString("; ")

  private def coauthors(v: EntityView): String = v match
    case r: ResearcherView => r.coauthorNames.mkString("; ")
    case w: WorkView => w.authorNames.mkString("; ")

  private def affiliation(v: EntityView): String = v match
    case r: ResearcherView => r.affiliations.mkString(" | ")
    case _: WorkView => ""

  private def year(v: EntityView): String = v match
    case r: ResearcherView => r.year.fold("")(_.toString)
    case w: WorkView => w.year.fold("")(_.toString)

  private def venue(v: EntityView): String = v match
    case r: ResearcherView => r.work.map(_.venues.mkString(" | ")).getOrElse("")
    case w: WorkView => w.venues.mkString(" | ")

  def kindLabel(k: MatchEntityType): String = k.toString.toLowerCase(java.util.Locale.ROOT)

  /** Queue row. Contains source fields only: no score, decision, path, reason or stratum. */
  def queueRow(e: QueueEntry): Vector[String] =
    val l = e.pair.left
    val r = e.pair.right
    Vector(
      e.pairId,
      kindLabel(e.pair.kind),
      l.source.toString,
      recordId(l),
      r.source.toString,
      recordId(r),
      label(l),
      label(r),
      ids(l),
      ids(r),
      coauthors(l),
      coauthors(r),
      affiliation(l),
      affiliation(r),
      year(l),
      year(r),
      venue(l),
      venue(r),
      "",
      ""
    )

  def keyRow(e: QueueEntry): Vector[String] =
    val d = e.result.decision
    Vector(
      e.pairId,
      kindLabel(e.pair.kind),
      e.stratum.toString,
      e.pair.candidateId,
      e.pair.left.ref,
      e.pair.right.ref,
      d.decisionId,
      e.result.path.toString,
      d.decision.toString,
      f"${e.result.score}%.6f",
      f"${d.confidence}%.6f",
      d.reasonCodes.mkString(";"),
      d.policyVersion,
      d.runId
    )

  /** RFC 4180 field quoting: quote when the field holds a comma, quote, CR or LF. */
  def csvField(s: String): String =
    val v = if s == null then "" else s
    if v.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + v.replace("\"", "\"\"") + "\""
    else v

  def csv(header: Vector[String], rows: Vector[Vector[String]]): String =
    (header +: rows).map(_.map(csvField).mkString(",")).mkString("", "\r\n", "\r\n")

  /** Strict RFC 4180 parser (for tests and later evaluation tooling). */
  def parseCsv(text: String): Vector[Vector[String]] =
    val rows = Vector.newBuilder[Vector[String]]
    var row = Vector.newBuilder[String]
    val field = new StringBuilder
    var i = 0
    var inQuotes = false
    var rowHasContent = false
    while i < text.length do
      val c = text.charAt(i)
      if inQuotes then
        if c == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then
            field.append('"')
            i += 1
          else inQuotes = false
        else field.append(c)
      else if c == '"' then
        inQuotes = true
        rowHasContent = true
      else if c == ',' then
        row += field.toString
        field.clear()
        rowHasContent = true
      else if c == '\r' && i + 1 < text.length && text.charAt(i + 1) == '\n' then ()
      else if c == '\n' then
        row += field.toString
        field.clear()
        rows += row.result()
        row = Vector.newBuilder[String]
        rowHasContent = false
      else
        field.append(c)
        rowHasContent = true
      i += 1
    if rowHasContent || field.nonEmpty then
      row += field.toString
      rows += row.result()
    rows.result()
