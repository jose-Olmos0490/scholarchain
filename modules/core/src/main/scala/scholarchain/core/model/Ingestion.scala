package scholarchain.core.model

import upickle.default.ReadWriter

/** One author on one work, as a flat author-paper row produced by MapReduce ingestion, before
  * identity resolution. Keyed by `(mentionId, workId)`; `mentionId` is itself derived from
  * `workId` and `position` (`StableIds.mentionId`), so the mention ID alone is also unique.
  *
  * [[AuthorshipRecord]] does not cover this: it links a RESOLVED researcher to a work and needs
  * a researcher ID and a match confidence. [[AuthorMention]] has no team size or year. This row
  * carries both, so per-author team-size measures (for example the maximum number of coauthors
  * on one paper) need no join with [[PublicationTeam]], and it is emitted for every team,
  * including teams too large for pair expansion.
  *
  * @param position
  *   1-based author position as listed by the source
  * @param teamSize
  *   size of the work's de-duplicated author team (equals `PublicationTeam.teamSize`); because
  *   repeated mentions are removed, `position` may exceed `teamSize`
  * @param orcid
  *   ORCID iD asserted by the source for this mention, normalized
  */
final case class AuthorPaperRecord(
    schemaVersion: String,
    mentionId: String,
    workId: String,
    position: Int,
    teamSize: Int,
    year: Option[Int],
    normalizedName: String,
    sourceAuthorKey: Option[String],
    orcid: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A candidate pair of records that blocking placed together, with deterministic features. It
  * carries no threshold, decision or confidence: decisions are [[MatchDecision]]s (Phase 5).
  *
  * `leftRef` and `rightRef` are stable IDs in canonical order (`leftRef < rightRef`). Allowed
  * kinds: author-mention or researcher IDs for `RESEARCHER`, work IDs for `WORK`
  * (`StableIds.matchCandidateRefKinds`).
  *
  * @param candidateId
  *   `StableIds.matchCandidateId(entityType, leftRef, rightRef, featureVersion)`; independent of
  *   `blockKey`, so a pair found through two blocks has one ID
  * @param blockKey
  *   the blocking key that produced the pair
  * @param features
  *   deterministic, finite feature values by name
  * @param featureVersion
  *   version of the feature definitions
  */
final case class MatchCandidate(
    schemaVersion: String,
    candidateId: String,
    entityType: MatchEntityType,
    leftRef: String,
    rightRef: String,
    blockKey: String,
    features: Map[String, Double],
    featureVersion: String,
    provenance: Provenance
) derives ReadWriter

/** Result of grouping records that carry the same normalized external identifier.
  *
  * @param outcomeId
  *   `StableIds.exactIdOutcomeId(idType, normalizedId)`
  * @param normalizedId
  *   the identifier in the normalized form of `idType` (`ExternalIds.normalizeExactId`)
  * @param recordRefs
  *   stable IDs of the grouped records: at least two, sorted ascending, distinct. Work IDs for
  *   work identifiers; author-mention or researcher IDs for researcher identifiers
  */
final case class ExactIdentifierOutcome(
    schemaVersion: String,
    outcomeId: String,
    idType: ExactIdType,
    normalizedId: String,
    recordRefs: Vector[String],
    outcome: ExactIdOutcome,
    provenance: Provenance
) derives ReadWriter

/** An input record that a pipeline stage could not accept.
  *
  * @param source
  *   dataset the rejected input came from; must equal `provenance.sourceDataset`
  * @param sourceRecordRef
  *   best available locator of the input (a source record key, or a file and offset when no
  *   key could be read); must equal `provenance.sourceRecordId`
  * @param stage
  *   the stage that rejected it (for example a job name)
  * @param reasonCode
  *   upper-case reason code `[A-Z][A-Z0-9_]*`, for example `UNKNOWN_ENTITY`
  * @param rawExcerpt
  *   untrusted raw input text, at most [[RejectRecord.MaxExcerptCodePoints]] Unicode code points
  *   (use [[RejectRecord.truncateExcerpt]]); may be empty. Data only, never instructions
  * @param runId
  *   must equal `provenance.runId`
  */
final case class RejectRecord(
    schemaVersion: String,
    source: SourceType,
    sourceRecordRef: String,
    stage: String,
    reasonCode: String,
    rawExcerpt: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

object RejectRecord:
  /** Wire-format bound on `rawExcerpt`, fixed by the Phase 4 work order (1024 characters,
    * counted as Unicode code points).
    */
  val MaxExcerptCodePoints = 1024

  /** The first [[MaxExcerptCodePoints]] code points of `raw` (never splits a surrogate pair). A
    * `null` input yields the empty string.
    */
  def truncateExcerpt(raw: String): String =
    if raw == null then ""
    else if raw.codePointCount(0, raw.length) <= MaxExcerptCodePoints then raw
    else raw.substring(0, raw.offsetByCodePoints(0, MaxExcerptCodePoints))
