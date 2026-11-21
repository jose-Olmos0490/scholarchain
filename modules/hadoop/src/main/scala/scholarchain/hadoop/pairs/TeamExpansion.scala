package scholarchain.hadoop.pairs

import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize

/** Publication-team construction and coauthor pair expansion (work order B item 8). Pure
  * functions over one work; the J07/J08 reducer calls them once per work.
  */
object TeamExpansion:

  /** A team after removing repeated mentions: the kept mentions in source order and the number of
    * mentions removed.
    */
  final case class Team(work: WorkRecord, members: Vector[AuthorMention], removedDuplicates: Int):
    def size: Int = members.size

  /** Identity of a mention inside one paper: `Normalize.identityKey` of the source author key
    * (dblp name with homonym suffix, OpenAlex author ID), else the normalized name. The first
    * occurrence is kept.
    *
    * The identity key drops Cf characters (soft hyphen, ZWSP, BOM...) and treats every run of
    * invisible spacing as one space, the same invisible-character rule as `Normalize.fold`, so
    * spellings that fold and block as one name are also one team member. It then applies NFC, so
    * canonically equivalent spellings (precomposed `é` vs `e` + combining acute) are one key.
    * Compatibility forms (NFKC), case and the homonym suffix are deliberately not folded here:
    * they can be distinct dblp persons. This key is used only for in-team de-duplication; it
    * never feeds a stable ID (mention IDs stay `work ID + position`) and the raw key is stored
    * unchanged.
    */
  def mentionKey(m: AuthorMention): String =
    m.sourceAuthorKey
      .flatMap(Option(_))
      .map(Normalize.identityKey)
      .filter(_.nonEmpty)
      .map("k:" + _)
      .getOrElse("n:" + m.normalizedName)

  def team(work: WorkRecord): Team =
    val seen = scala.collection.mutable.HashSet.empty[String]
    val kept = work.authors.filter(m => seen.add(mentionKey(m)))
    Team(work, kept, work.authors.size - kept.size)

  /** Number of pairs of a team of `n`: n(n-1)/2 (0 for n < 2). */
  def pairCount(n: Int): Long = if n < 2 then 0L else n.toLong * (n - 1) / 2

  /** How a team is routed. */
  enum Route:
    /** Expand into pairs; `large` when above the large-team threshold. */
    case Expand(large: Boolean)

    /** Above `maxPairExpansionTeamSize`: not expanded, routed to the oversize-teams output. */
    case Oversize

  def route(size: Int, largeTeamThreshold: Int, maxPairExpansionTeamSize: Int): Route =
    if size > maxPairExpansionTeamSize then Route.Oversize
    else Route.Expand(size > largeTeamThreshold)

  def provenance(work: WorkRecord, runId: String): Provenance =
    Records.provenance(
      work.provenance.sourceDataset,
      work.provenance.sourceRecordId,
      work.provenance.snapshot,
      runId,
      Vector(work.workId)
    )

  def publicationTeam(t: Team, runId: String): PublicationTeam =
    PublicationTeam(
      schemaVersion = SchemaVersions.PublicationTeam,
      workId = t.work.workId,
      year = t.work.year,
      memberMentionIds = t.members.map(_.mentionId),
      teamSize = t.size,
      provenance = provenance(t.work, runId)
    )

  def authorPapers(t: Team, runId: String): Vector[AuthorPaperRecord] =
    val prov = provenance(t.work, runId)
    t.members.map { m =>
      AuthorPaperRecord(
        schemaVersion = SchemaVersions.AuthorPaperRecord,
        mentionId = m.mentionId,
        workId = t.work.workId,
        position = m.position,
        teamSize = t.size,
        year = t.work.year,
        normalizedName = m.normalizedName,
        sourceAuthorKey = m.sourceAuthorKey,
        orcid = m.orcid,
        provenance = prov
      )
    }

  /** Lazily expands every unordered pair (i < j in source order) of a team. Each element is the
    * v2 candidate or the reason it could not be built.
    */
  def pairs(t: Team, year: Int, runId: String): Iterator[Either[String, CoauthorPairCandidate]] =
    val prov = provenance(t.work, runId)
    val ms = t.members
    for
      i <- Iterator.range(0, ms.size)
      j <- Iterator.range(i + 1, ms.size)
    yield CoauthorPairCandidate.fromPositions(
      t.work.workId,
      ms(i).position,
      ms(j).position,
      year,
      t.size,
      prov
    )
