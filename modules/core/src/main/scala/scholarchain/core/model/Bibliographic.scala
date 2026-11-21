package scholarchain.core.model

import scholarchain.core.ids.{CanonicalPair, StableIds}
import scholarchain.core.schema.SchemaVersions
import upickle.default.ReadWriter

/** One author position on one work, before identity resolution.
  *
  * @param position
  *   1-based author position as listed by the source
  * @param sourceAuthorKey
  *   source-side author key when present (dblp name with homonym suffix, OpenAlex author ID)
  * @param orcid
  *   ORCID iD asserted by the source for this mention, normalized
  */
final case class AuthorMention(
    schemaVersion: String,
    mentionId: String,
    workId: String,
    position: Int,
    rawName: String,
    normalizedName: String,
    sourceAuthorKey: Option[String],
    orcid: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A publication as read from one source.
  *
  * @param recordType
  *   source record type, for example dblp `inproceedings` or `article`
  * @param doi
  *   normalized (lower-case) DOI
  * @param abstractTextRef
  *   pointer to permitted abstract text in ordinary storage; abstracts are not inlined
  */
final case class WorkRecord(
    schemaVersion: String,
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    recordType: String,
    title: String,
    normalizedTitle: String,
    year: Option[Int],
    venueId: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    authors: Vector[AuthorMention],
    abstractTextRef: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A canonical researcher identity.
  *
  * Only public identity fields are modelled (ORCID policy: iD, name, affiliations, work
  * identifiers). No email, biography or address fields exist by design.
  *
  * The external identifiers `orcid`, `dblpPid` and `openAlexAuthorId` are VALIDATED identifiers
  * (set by entity resolution only after validation). `researcherId` is built from the first
  * present one by the frozen precedence ORCID > dblp pid > OpenAlex > ScholarChain identity
  * (`StableIds.researcherId`), and validation enforces that.
  *
  * @param dblpPid
  *   validated dblp person pid in bare form (for example `71/4882`)
  * @param identityVersion
  *   ScholarChain identity scheme version (`v1`, ...) when the ID is a ScholarChain identity ID
  * @param identityConfidence
  *   confidence in [0, 1] that the merged mentions denote one person
  */
final case class CanonicalResearcher(
    schemaVersion: String,
    researcherId: String,
    displayName: String,
    orcid: Option[String],
    dblpPid: Option[String],
    openAlexAuthorId: Option[String],
    dblpAuthorKeys: Vector[String],
    identityVersion: Option[String],
    affiliationInstitutionIds: Vector[String],
    identityConfidence: Double,
    provenance: Provenance
) derives ReadWriter

/** Resolved link from a canonical researcher to a work through one author mention. */
final case class AuthorshipRecord(
    schemaVersion: String,
    authorshipId: String,
    researcherId: String,
    workId: String,
    mentionId: String,
    position: Int,
    year: Option[Int],
    matchConfidence: Double,
    matchDecisionId: Option[String],
    provenance: Provenance
) derives ReadWriter

/** The ordered author team of one work, as produced by MapReduce. */
final case class PublicationTeam(
    schemaVersion: String,
    workId: String,
    year: Option[Int],
    memberMentionIds: Vector[String],
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

/** One undirected coauthor pair induced by one work, before identity resolution.
  *
  * `memberA` and `memberB` are author-mention IDs in canonical order (`memberA < memberB`).
  *
  * Two wire versions share this type:
  *
  *   - `coauthor-pair-candidate-v1` (frozen, Phase 3): no author positions. `positionA` and
  *     `positionB` are `None` and are omitted from the wire object, so v1 bytes are unchanged.
  *     Positions cannot be recovered from a v1 record because mention IDs are hashes.
  *   - `coauthor-pair-candidate-v2` (Phase 4): `positionA` and `positionB` are required. They are
  *     the 1-based source author positions of `memberA` and `memberB`, distinct, with
  *     `memberA == StableIds.mentionId(workId, positionA)` and likewise for B. They are written
  *     after `memberB`.
  *
  * Producers emit v2 through [[CoauthorPairCandidate.fromPositions]], which orders members
  * canonically and keeps each position aligned with its member.
  *
  * `candidateId` is `StableIds.pairCandidateId(workId, memberA, memberB)` in both versions; the
  * positions are not an input, so one pair has the same ID in v1 and v2.
  *
  * @param positionA
  *   1-based source author position of `memberA` (v2 only)
  * @param positionB
  *   1-based source author position of `memberB` (v2 only)
  */
final case class CoauthorPairCandidate(
    schemaVersion: String,
    candidateId: String,
    workId: String,
    memberA: String,
    memberB: String,
    positionA: Option[Int] = None,
    positionB: Option[Int] = None,
    year: Int,
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

object CoauthorPairCandidate:

  /** Builds a `coauthor-pair-candidate-v2` record from the two source author positions.
    *
    * Each mention ID is derived as `StableIds.mentionId(workId, position)`. The two mentions are
    * ordered canonically (`memberA < memberB`) and each position moves with its mention, so the
    * input order of `positionX` and `positionY` does not matter. `candidateId` is
    * `StableIds.pairCandidateId(workId, memberA, memberB)`.
    *
    * Rejects a malformed `workId`, a position below 1, equal positions and a null provenance.
    * Other record rules (provenance completeness, `teamSize >= 2`) are checked by the record
    * contract, for example through `WireCodec.encodeValidated`.
    */
  def fromPositions(
      workId: String,
      positionX: Int,
      positionY: Int,
      year: Int,
      teamSize: Int,
      provenance: Provenance
  ): Either[String, CoauthorPairCandidate] =
    for
      _ <-
        if positionX != positionY then Right(())
        else Left(s"positions must be distinct, got $positionX twice")
      _ <- if provenance != null then Right(()) else Left("provenance must not be null")
      mx <- StableIds.mentionId(workId, positionX)
      my <- StableIds.mentionId(workId, positionY)
      pair <- CanonicalPair.of(mx, my)
      candidateId <- StableIds.pairCandidateId(workId, pair.a, pair.b)
    yield
      val (posA, posB) = if pair.a == mx then (positionX, positionY) else (positionY, positionX)
      CoauthorPairCandidate(
        schemaVersion = SchemaVersions.CoauthorPairCandidateV2,
        candidateId = candidateId,
        workId = workId,
        memberA = pair.a,
        memberB = pair.b,
        positionA = Some(posA),
        positionB = Some(posB),
        year = year,
        teamSize = teamSize,
        provenance = provenance
      )

final case class Venue(
    schemaVersion: String,
    venueId: String,
    name: String,
    venueType: Option[String],
    sourceVenueKey: Option[String],
    issns: Vector[String],
    provenance: Provenance
) derives ReadWriter

final case class Institution(
    schemaVersion: String,
    institutionId: String,
    name: String,
    rorId: Option[String],
    countryCode: Option[String],
    provenance: Provenance
) derives ReadWriter

final case class Topic(
    schemaVersion: String,
    topicId: String,
    name: String,
    parentTopicId: Option[String],
    provenance: Provenance
) derives ReadWriter

/** An entity-resolution decision (spec "Entity linkage policy").
  *
  * @param decidedAt
  *   ISO-8601 instant. It is a record field only; no stable ID depends on it.
  * @param runId
  *   must equal `provenance.runId`
  */
final case class MatchDecision(
    schemaVersion: String,
    decisionId: String,
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    rulesFired: Vector[String],
    decision: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String],
    policyVersion: String,
    modelVersion: Option[String],
    promptVersion: Option[String],
    decidedAt: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter
