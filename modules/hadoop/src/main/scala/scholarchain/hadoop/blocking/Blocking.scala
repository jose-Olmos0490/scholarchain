package scholarchain.hadoop.blocking

import scala.collection.immutable.TreeMap
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize
import upickle.default.ReadWriter

/** Compact view of one record inside a blocking group (J05/J05b/J06 intermediate value).
  *
  * `ref` is the stable ID being matched (an author-mention ID for RESEARCHER, a work ID for
  * WORK). Name fields are normalized. `provenance` is the provenance of the source record.
  */
final case class BlockMember(
    entityType: MatchEntityType,
    ref: String,
    source: SourceType,
    workId: String,
    year: Option[Int],
    surname: String,
    initial: String,
    givenTokens: Vector[String],
    sourceAuthorKey: Option[String],
    orcid: Option[String],
    coauthorSurnames: Vector[String],
    titleTokens: Vector[String],
    doi: Option[String],
    authorCount: Int,
    firstAuthorSurname: Option[String],
    provenance: Provenance
) derives ReadWriter

/** A blocking group of at most `maxBlockSize` members, sorted by `ref`. */
final case class Block(blockKey: String, entityType: MatchEntityType, members: Vector[BlockMember])
    derives ReadWriter

/** A member re-keyed with its fallback key (J05 side output, J05b input). */
final case class Rekeyed(fallbackKey: String, member: BlockMember) derives ReadWriter

/** Blocking keys (work order B item 9).
  *
  *   - researcher primary key: `r|<surname>|<first initial>` of the normalized name
  *   - researcher fallback key: primary + `|co:<smallest coauthor surname>`, or `|y:<year bucket>`
  *     for a mention without coauthors
  *   - work primary key: `w|<first three title tokens>|<5-year bucket>`
  *   - work fallback key: primary + `|fa:<first-author surname>`, or `|y:<exact year>` without
  *     authors
  *
  * A key that cannot be computed (no name letters, no title tokens, no year for a work) gives
  * `None` and the caller counts MISSING_KEY.
  */
object BlockingKeys:
  val TitlePrefixTokens = 3
  val YearBucketWidth = 5

  def yearBucket(year: Int): String =
    val start = Math.floorDiv(year, YearBucketWidth) * YearBucketWidth
    s"$start-${start + YearBucketWidth - 1}"

  def primary(m: BlockMember): Option[String] = m.entityType match
    case MatchEntityType.RESEARCHER =>
      if m.surname.isEmpty || m.initial.isEmpty then None
      else Some(s"r|${m.surname}|${m.initial}")
    case MatchEntityType.WORK =>
      if m.titleTokens.isEmpty then None
      else
        m.year.map(y =>
          s"w|${m.titleTokens.take(TitlePrefixTokens).mkString(" ")}|${yearBucket(y)}"
        )

  def fallback(m: BlockMember): Option[String] =
    primary(m).map { p =>
      m.entityType match
        case MatchEntityType.RESEARCHER =>
          m.coauthorSurnames.headOption match
            case Some(c) => s"$p|co:$c"
            case None => s"$p|y:${m.year.map(yearBucket).getOrElse("unknown")}"
        case MatchEntityType.WORK =>
          m.firstAuthorSurname match
            case Some(s) => s"$p|fa:$s"
            case None => s"$p|y:${m.year.map(_.toString).getOrElse("unknown")}"
    }

  /** Blocking members of one normalized work: one WORK member and one RESEARCHER member per
    * author mention.
    */
  def members(w: WorkRecord): Vector[BlockMember] =
    val names = w.authors.map(a => Normalize.surnameAndInitial(a.rawName))
    val surnames = names.map(_.map(_._1))
    val titleTokens = Normalize.titleTokens(w.title).take(64)
    val year = w.year.flatMap(Option(_))
    val workMember = BlockMember(
      entityType = MatchEntityType.WORK,
      ref = w.workId,
      source = w.source,
      workId = w.workId,
      year = year,
      surname = "",
      initial = "",
      givenTokens = Vector.empty,
      sourceAuthorKey = None,
      orcid = None,
      coauthorSurnames = surnames.flatten.distinct.sorted,
      titleTokens = titleTokens,
      doi = w.doi.flatMap(Option(_)),
      authorCount = w.authors.size,
      firstAuthorSurname = surnames.headOption.flatten,
      provenance = w.provenance
    )
    val mentionMembers = w.authors.zipWithIndex.map { (a, i) =>
      val (surname, initial) = names(i).getOrElse(("", ""))
      val tokens = Normalize.personName(a.rawName).split(' ').toVector.filter(_.nonEmpty)
      BlockMember(
        entityType = MatchEntityType.RESEARCHER,
        ref = a.mentionId,
        source = w.source,
        workId = w.workId,
        year = year,
        surname = surname,
        initial = initial,
        givenTokens = tokens.dropRight(1),
        sourceAuthorKey = a.sourceAuthorKey.flatMap(Option(_)),
        orcid = a.orcid.flatMap(Option(_)),
        coauthorSurnames =
          surnames.zipWithIndex.collect { case (Some(s), j) if j != i => s }.distinct.sorted,
        titleTokens = titleTokens,
        doi = None,
        authorCount = w.authors.size,
        firstAuthorSurname = surnames.headOption.flatten,
        provenance = w.provenance
      )
    }
    workMember +: mentionMembers

/** Deterministic match features (work order B item 10). No thresholds, no decisions. */
object MatchFeatures:
  /** Version of the feature definitions below; part of every candidate ID. v2: source author
    * keys are compared by `Normalize.identityKey` (Cf characters and invisible spacing ignored,
    * NFC) instead of raw equality.
    */
  val FeatureVersion = "hadoop-blocking-features-v2"

  private def jaccard(a: Iterable[String], b: Iterable[String]): Double =
    val sa = a.toSet
    val sb = b.toSet
    val union = (sa union sb).size
    if union == 0 then 0.0 else (sa intersect sb).size.toDouble / union

  private def b(x: Boolean): Double = if x then 1.0 else 0.0

  private def yearFeatures(x: BlockMember, y: BlockMember): Seq[(String, Double)] =
    (x.year, y.year) match
      case (Some(a), Some(c)) => Seq("yearKnown" -> 1.0, "yearDelta" -> math.abs(a - c).toDouble)
      case _ => Seq("yearKnown" -> 0.0, "yearDelta" -> 0.0)

  def features(x: BlockMember, y: BlockMember): Map[String, Double] =
    val common: Seq[(String, Double)] =
      Seq("sameSource" -> b(x.source == y.source)) ++ yearFeatures(x, y)
    val specific: Seq[(String, Double)] = x.entityType match
      case MatchEntityType.RESEARCHER =>
        val bothOrcid = x.orcid.isDefined && y.orcid.isDefined
        val xKey = x.sourceAuthorKey.map(Normalize.identityKey).filter(_.nonEmpty)
        val yKey = y.sourceAuthorKey.map(Normalize.identityKey).filter(_.nonEmpty)
        val bothKey = xKey.isDefined && yKey.isDefined
        Seq(
          "surnameEqual" -> b(x.surname == y.surname),
          "initialEqual" -> b(x.initial == y.initial),
          "givenNameTokenJaccard" -> jaccard(x.givenTokens, y.givenTokens),
          "orcidBothPresent" -> b(bothOrcid),
          "orcidEqual" -> b(bothOrcid && x.orcid == y.orcid),
          "sourceAuthorKeyBothPresent" -> b(bothKey),
          "sourceAuthorKeyEqual" -> b(bothKey && xKey == yKey),
          "sharedCoauthorSurnames" -> (x.coauthorSurnames.toSet intersect y.coauthorSurnames.toSet).size.toDouble,
          "coauthorSurnameJaccard" -> jaccard(x.coauthorSurnames, y.coauthorSurnames),
          "titleTokenJaccard" -> jaccard(x.titleTokens, y.titleTokens)
        )
      case MatchEntityType.WORK =>
        val bothDoi = x.doi.isDefined && y.doi.isDefined
        Seq(
          "titleTokenJaccard" -> jaccard(x.titleTokens, y.titleTokens),
          "titleTokensEqual" -> b(x.titleTokens == y.titleTokens),
          "doiBothPresent" -> b(bothDoi),
          "doiEqual" -> b(bothDoi && x.doi == y.doi),
          "authorCountDelta" -> math.abs(x.authorCount - y.authorCount).toDouble,
          "authorSurnameJaccard" -> jaccard(x.coauthorSurnames, y.coauthorSurnames)
        )
    TreeMap.from(common ++ specific)

  /** The candidate for a pair of members of one block, or `None` for a pair that is not a match
    * candidate (the same ref twice, or two mentions on the same work, which are different people
    * by construction).
    */
  def candidate(
      blockKey: String,
      x: BlockMember,
      y: BlockMember,
      snapshot: String,
      runId: String
  ): Option[MatchCandidate] =
    if x.ref == y.ref then None
    else if x.entityType == MatchEntityType.RESEARCHER && x.workId == y.workId then None
    else
      val (l, r) = if x.ref.compareTo(y.ref) < 0 then (x, y) else (y, x)
      StableIds.matchCandidateId(x.entityType, l.ref, r.ref, FeatureVersion).toOption.map { id =>
        MatchCandidate(
          schemaVersion = SchemaVersions.MatchCandidate,
          candidateId = id,
          entityType = x.entityType,
          leftRef = l.ref,
          rightRef = r.ref,
          blockKey = blockKey,
          features = features(l, r),
          featureVersion = FeatureVersion,
          provenance = Records.provenance(
            SourceType.SCHOLARCHAIN,
            id,
            snapshot,
            runId,
            Vector(l.ref, r.ref)
          )
        )
      }
