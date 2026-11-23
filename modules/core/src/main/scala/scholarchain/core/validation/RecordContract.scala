package scholarchain.core.validation

import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.Checks.*
import scholarchain.core.validation.ValidationError.*

/** Wire and validation contract of one record type.
  *
  * @tparam A
  *   the record type
  */
trait RecordContract[A]:
  /** Record type name; the key into [[scholarchain.core.schema.SchemaVersions.supported]]. */
  def recordType: String

  /** Whether the record must carry a top-level `provenance` object. */
  def requiresProvenance: Boolean = true

  /** JSON array fields whose elements are themselves provenance-bearing records. */
  def nestedRecordArrays: Vector[String] = Vector.empty

  /** All violations of the record, with paths prefixed by `path` (empty for a top-level value).
    */
  def violations(a: A, path: String): Vector[ValidationError]

object RecordContract extends AnalyticsContracts:
  def apply[A](using c: RecordContract[A]): RecordContract[A] = c

  private def at(path: String, field: String): String =
    if path.isEmpty then field else s"$path.$field"

  /** The namespace segment of a well-formed stable ID of one of `kinds`, else `None` (the
    * malformed ID is reported by the `id` check).
    */
  private def namespaceOf(value: String, kinds: IdKind*): Option[String] =
    StableId.parseAs(value, kinds.toSet).toOption.flatMap(_.namespace)

  /** For an entity ID in the `openalex` namespace whose record came straight from OpenAlex, the
    * provenance `sourceRecordId` is the OpenAlex ID the stable ID was built from.
    */
  private def fromOpenAlexProvenance(
      path: String,
      found: String,
      p: Provenance,
      formula: String => Either[String, String]
  ): Vector[ValidationError] =
    if p != null && p.sourceDataset == SourceType.OPENALEX && p.sourceRecordId != null then
      matchesFormulaStrict(path, found, formula(p.sourceRecordId))
    else ok

  private def common(path: String, recordType: String, schema: String, p: Provenance) =
    schemaVersion(at(path, "schemaVersion"), recordType, schema) ++
      provenance(at(path, "provenance"), p)

  given RecordContract[Provenance] with
    val recordType = "Provenance"
    override val requiresProvenance = false
    def violations(p: Provenance, path: String) =
      provenance(if path.isEmpty then "provenance" else path, p)

  given authorMention: RecordContract[AuthorMention] with
    val recordType = "AuthorMention"
    def violations(m: AuthorMention, path: String) =
      common(path, recordType, m.schemaVersion, m.provenance) ++
        id(at(path, "mentionId"), m.mentionId, IdKind.AuthorMention) ++
        id(at(path, "workId"), m.workId, IdKind.Work) ++
        check(
          m.position >= 1,
          InvalidValue(at(path, "position"), s"must be >= 1, got ${m.position}")
        ) ++
        nonBlank(at(path, "rawName"), m.rawName) ++
        nonBlank(at(path, "normalizedName"), m.normalizedName) ++
        optNonBlank(at(path, "sourceAuthorKey"), m.sourceAuthorKey) ++
        (if m.orcid == null then Vector(InvalidValue(at(path, "orcid"), "null option"))
         else
           m.orcid.toVector.flatMap(o =>
             ExternalIds
               .normalizeOrcid(o)
               .fold(
                 r => Vector(InvalidValue(at(path, "orcid"), r)),
                 n =>
                   check(n == o, InvalidValue(at(path, "orcid"), s"'$o' is not normalized ('$n')"))
               )
           )) ++
        (if m.workId != null && m.mentionId != null then
           matchesFormula(
             at(path, "mentionId"),
             m.mentionId,
             StableIds.mentionId(m.workId, m.position)
           )
         else ok)

  given RecordContract[WorkRecord] with
    val recordType = "WorkRecord"
    override val nestedRecordArrays = Vector("authors")
    def violations(w: WorkRecord, path: String) =
      common(path, recordType, w.schemaVersion, w.provenance) ++
        id(at(path, "workId"), w.workId, IdKind.Work) ++
        notNull(at(path, "source"), w.source) ++
        nonBlank(at(path, "sourceRecordId"), w.sourceRecordId) ++
        nonBlank(at(path, "recordType"), w.recordType) ++
        nonBlank(at(path, "title"), w.title) ++
        nonBlank(at(path, "normalizedTitle"), w.normalizedTitle) ++
        optId(at(path, "venueId"), w.venueId, IdKind.Venue) ++
        (if w.doi == null then Vector(InvalidValue(at(path, "doi"), "null option"))
         else
           w.doi.toVector.flatMap(d =>
             ExternalIds
               .normalizeDoi(d)
               .fold(
                 r => Vector(InvalidValue(at(path, "doi"), r)),
                 n => check(n == d, InvalidValue(at(path, "doi"), s"'$d' is not normalized ('$n')"))
               )
           )) ++
        optNonBlank(at(path, "arxivId"), w.arxivId) ++
        optNonBlank(at(path, "abstractTextRef"), w.abstractTextRef) ++
        workIdFormula(w, path) ++
        (if w.authors == null then Vector(InvalidValue(at(path, "authors"), "must not be null"))
         else
           w.authors.zipWithIndex.flatMap { (m, i) =>
             val p = at(path, s"authors[$i]")
             authorMention.violations(m, p) ++
               check(
                 m == null || m.workId == w.workId,
                 InvalidValue(s"$p.workId", "mention belongs to a different work")
               ) ++
               check(
                 m == null || m.position == i + 1,
                 InvalidValue(s"$p.position", s"expected position ${i + 1}")
               )
           })

  /** Recomputes `workId` from the field its namespace names: `dblp` and `openalex` from
    * `sourceRecordId` (and `source` must be that dataset), `doi` from `doi`, `arxiv` from
    * `arxivId`.
    */
  private def workIdFormula(w: WorkRecord, path: String): Vector[ValidationError] =
    val p = at(path, "workId")
    def fromSource(expected: SourceType, ns: WorkNamespace) =
      if w.source != expected then
        Vector(
          InvalidValue(
            p,
            s"a ${ns.segment} work ID requires source $expected, got ${String.valueOf(w.source)}"
          )
        )
      else if w.sourceRecordId == null then ok
      else matchesFormulaStrict(p, w.workId, StableIds.workId(ns, w.sourceRecordId))
    def fromField(name: String, value: Option[String], ns: WorkNamespace) =
      value match
        case Some(v) if v != null => matchesFormulaStrict(p, w.workId, StableIds.workId(ns, v))
        case _ =>
          Vector(InvalidValue(at(path, name), s"required for a ${ns.segment} work ID"))
    namespaceOf(w.workId, IdKind.Work) match
      case Some("dblp") => fromSource(SourceType.DBLP, WorkNamespace.Dblp)
      case Some("openalex") => fromSource(SourceType.OPENALEX, WorkNamespace.OpenAlex)
      case Some("doi") => fromField("doi", w.doi, WorkNamespace.Doi)
      case Some("arxiv") => fromField("arxivId", w.arxivId, WorkNamespace.Arxiv)
      case _ => ok

  given RecordContract[CanonicalResearcher] with
    val recordType = "CanonicalResearcher"
    def violations(r: CanonicalResearcher, path: String) =
      val idErrors = id(at(path, "researcherId"), r.researcherId, IdKind.Researcher)
      val nullOptions = r.orcid == null || r.dblpPid == null || r.openAlexAuthorId == null
      val formula: Vector[ValidationError] =
        if idErrors.nonEmpty || nullOptions then ok
        else
          val parsed = StableId.parse(r.researcherId).toOption.get
          val expectedBasis = StableIds.researcherIdBasis(r.orcid, r.dblpPid, r.openAlexAuthorId)
          def external(
              field: String,
              value: Option[String],
              ns: ResearcherNamespace
          ): Vector[ValidationError] =
            value match
              case Some(v) =>
                matchesFormula(
                  at(path, "researcherId"),
                  r.researcherId,
                  StableIds.researcherIdFromExternal(ns, v)
                )
              case None =>
                Vector(InvalidValue(at(path, field), s"required for a ${ns.segment} researcher ID"))
          val (foundBasis, byFormula) = parsed.namespace match
            case Some("orcid") =>
              (ResearcherIdBasis.Orcid, external("orcid", r.orcid, ResearcherNamespace.Orcid))
            case Some("dblp") =>
              (
                ResearcherIdBasis.DblpPid,
                external("dblpPid", r.dblpPid, ResearcherNamespace.DblpPid)
              )
            case Some("openalex") =>
              (
                ResearcherIdBasis.OpenAlex,
                external("openAlexAuthorId", r.openAlexAuthorId, ResearcherNamespace.OpenAlex)
              )
            case other =>
              val ns = other.getOrElse("")
              val version = ns.stripPrefix(IdKind.Researcher.versionedNamespacePrefix.get)
              (
                ResearcherIdBasis.ScholarChain,
                check(
                  r.identityVersion == Some(version),
                  InvalidValue(
                    at(path, "identityVersion"),
                    s"must be Some($version) for namespace $ns"
                  )
                )
              )
          // Precedence: only report a missing input when the namespace itself is the right one.
          if foundBasis != expectedBasis then
            Vector(
              InvalidValue(
                at(path, "researcherId"),
                s"precedence ORCID > dblp pid > OpenAlex > sc-researcher: the identifiers " +
                  s"present require namespace '${expectedBasis.namespaceLabel}', found " +
                  s"'${foundBasis.namespaceLabel}'"
              )
            )
          else byFormula
      common(path, recordType, r.schemaVersion, r.provenance) ++ idErrors ++ formula ++
        nonBlank(at(path, "displayName"), r.displayName) ++
        (if r.orcid == null then Vector(InvalidValue(at(path, "orcid"), "null option"))
         else
           r.orcid.toVector.flatMap(o =>
             ExternalIds
               .normalizeOrcid(o)
               .left
               .toOption
               .map(InvalidValue(at(path, "orcid"), _))
               .toVector
           )) ++
        (if r.dblpPid == null then Vector(InvalidValue(at(path, "dblpPid"), "null option"))
         else
           r.dblpPid.toVector.flatMap(p =>
             ExternalIds.normalizeDblpPid(p) match
               case Left(reason) => Vector(InvalidValue(at(path, "dblpPid"), reason))
               case Right(n) =>
                 check(
                   n == p,
                   InvalidValue(at(path, "dblpPid"), s"must be stored in bare form '$n'")
                 )
           )) ++
        (if r.openAlexAuthorId == null then
           Vector(InvalidValue(at(path, "openAlexAuthorId"), "null option"))
         else
           r.openAlexAuthorId.toVector.flatMap(a =>
             ExternalIds
               .normalizeOpenAlex(a, 'A')
               .left
               .toOption
               .map(InvalidValue(at(path, "openAlexAuthorId"), _))
               .toVector
           )) ++
        (if r.dblpAuthorKeys == null then
           Vector(InvalidValue(at(path, "dblpAuthorKeys"), "must not be null"))
         else
           r.dblpAuthorKeys.zipWithIndex.flatMap((k, i) =>
             nonBlank(at(path, s"dblpAuthorKeys[$i]"), k)
           )) ++
        optNonBlank(at(path, "identityVersion"), r.identityVersion) ++
        ids(
          at(path, "affiliationInstitutionIds"),
          r.affiliationInstitutionIds,
          IdKind.Institution
        ) ++
        confidence(at(path, "identityConfidence"), r.identityConfidence)

  given RecordContract[AuthorshipRecord] with
    val recordType = "AuthorshipRecord"
    def violations(a: AuthorshipRecord, path: String) =
      common(path, recordType, a.schemaVersion, a.provenance) ++
        id(at(path, "authorshipId"), a.authorshipId, IdKind.Authorship) ++
        id(at(path, "researcherId"), a.researcherId, IdKind.Researcher) ++
        id(at(path, "workId"), a.workId, IdKind.Work) ++
        id(at(path, "mentionId"), a.mentionId, IdKind.AuthorMention) ++
        check(
          a.position >= 1,
          InvalidValue(at(path, "position"), s"must be >= 1, got ${a.position}")
        ) ++
        confidence(at(path, "matchConfidence"), a.matchConfidence) ++
        optId(at(path, "matchDecisionId"), a.matchDecisionId, IdKind.MatchDecision) ++
        (if a.authorshipId != null then
           matchesFormula(
             at(path, "authorshipId"),
             a.authorshipId,
             StableIds.authorshipId(a.workId, a.mentionId, a.researcherId)
           )
         else ok)

  given RecordContract[PublicationTeam] with
    val recordType = "PublicationTeam"
    def violations(t: PublicationTeam, path: String) =
      common(path, recordType, t.schemaVersion, t.provenance) ++
        id(at(path, "workId"), t.workId, IdKind.Work) ++
        ids(at(path, "memberMentionIds"), t.memberMentionIds, IdKind.AuthorMention) ++
        distinct(at(path, "memberMentionIds"), t.memberMentionIds) ++
        check(
          t.memberMentionIds == null || t.teamSize == t.memberMentionIds.size,
          InvalidValue(at(path, "teamSize"), s"teamSize ${t.teamSize} != member count")
        ) ++
        check(t.teamSize >= 1, InvalidValue(at(path, "teamSize"), "must be >= 1"))

  given RecordContract[CoauthorPairCandidate] with
    val recordType = "CoauthorPairCandidate"
    def violations(c: CoauthorPairCandidate, path: String) =
      common(path, recordType, c.schemaVersion, c.provenance) ++
        id(at(path, "candidateId"), c.candidateId, IdKind.PairCandidate) ++
        id(at(path, "workId"), c.workId, IdKind.Work) ++
        id(at(path, "memberA"), c.memberA, IdKind.AuthorMention) ++
        id(at(path, "memberB"), c.memberB, IdKind.AuthorMention) ++
        pair(at(path, "memberA,memberB"), c.memberA, c.memberB) ++
        check(c.teamSize >= 2, InvalidValue(at(path, "teamSize"), "a pair needs teamSize >= 2")) ++
        pairPositions(c, path) ++
        (if c.candidateId != null && c.memberA != c.memberB then
           matchesFormula(
             at(path, "candidateId"),
             c.candidateId,
             StableIds.pairCandidateId(c.workId, c.memberA, c.memberB)
           )
         else ok)

  /** Version-dependent position rules of [[CoauthorPairCandidate]]: v1 carries no positions; v2
    * carries both, each >= 1, distinct, and each consistent with its member's mention ID.
    */
  private def pairPositions(c: CoauthorPairCandidate, path: String): Vector[ValidationError] =
    val pa = at(path, "positionA")
    val pb = at(path, "positionB")
    if c.positionA == null || c.positionB == null then
      (if c.positionA == null then Vector(InvalidValue(pa, "null option")) else ok) ++
        (if c.positionB == null then Vector(InvalidValue(pb, "null option")) else ok)
    else if c.schemaVersion == SchemaVersions.CoauthorPairCandidate then
      check(
        c.positionA.isEmpty,
        InvalidValue(pa, s"not part of ${SchemaVersions.CoauthorPairCandidate}")
      ) ++
        check(
          c.positionB.isEmpty,
          InvalidValue(pb, s"not part of ${SchemaVersions.CoauthorPairCandidate}")
        )
    else if c.schemaVersion == SchemaVersions.CoauthorPairCandidateV2 then
      def one(
          p: String,
          memberPath: String,
          member: String,
          position: Option[Int]
      ): Vector[ValidationError] =
        position match
          case None =>
            Vector(InvalidValue(p, s"required in ${SchemaVersions.CoauthorPairCandidateV2}"))
          case Some(n) if n < 1 => Vector(InvalidValue(p, s"must be >= 1, got $n"))
          case Some(n) =>
            if member != null && c.workId != null then
              matchesFormula(
                memberPath,
                member,
                StableIds.mentionId(c.workId, n)
              )
            else ok
      one(pa, at(path, "memberA"), c.memberA, c.positionA) ++
        one(pb, at(path, "memberB"), c.memberB, c.positionB) ++
        check(
          c.positionA.isEmpty || c.positionA != c.positionB,
          InvalidValue(at(path, "positionA,positionB"), "positions must be distinct")
        )
    else ok // an unsupported version is reported by the schemaVersion check

  given RecordContract[CoauthorEdge] with
    val recordType = "CoauthorEdge"
    def violations(e: CoauthorEdge, path: String) =
      val windowErrors =
        window(at(path, "windowStartYear,windowEndYear"), e.windowStartYear, e.windowEndYear)
      common(path, recordType, e.schemaVersion, e.provenance) ++
        id(at(path, "edgeId"), e.edgeId, IdKind.CoauthorEdge) ++
        id(at(path, "researcherA"), e.researcherA, IdKind.Researcher) ++
        id(at(path, "researcherB"), e.researcherB, IdKind.Researcher) ++
        pair(at(path, "researcherA,researcherB"), e.researcherA, e.researcherB) ++
        windowErrors ++
        check(
          e.jointPublicationCount >= 1,
          InvalidValue(at(path, "jointPublicationCount"), "must be >= 1")
        ) ++
        check(
          e.firstCollaborationYear <= e.lastCollaborationYear,
          InvalidValue(at(path, "firstCollaborationYear"), "must be <= lastCollaborationYear")
        ) ++
        check(
          windowErrors.nonEmpty ||
            (e.firstCollaborationYear >= e.windowStartYear && e.lastCollaborationYear <= e.windowEndYear),
          InvalidValue(
            at(path, "firstCollaborationYear"),
            "collaboration years must lie in the window"
          )
        ) ++
        ids(at(path, "representativeWorkIds"), e.representativeWorkIds, IdKind.Work) ++
        distinct(at(path, "representativeWorkIds"), e.representativeWorkIds) ++
        check(
          e.representativeWorkIds == null || e.representativeWorkIds.nonEmpty,
          InvalidValue(at(path, "representativeWorkIds"), "must not be empty")
        ) ++
        check(
          e.representativeWorkIds == null || e.representativeWorkIds.size <= e.jointPublicationCount,
          InvalidValue(at(path, "representativeWorkIds"), "more works than jointPublicationCount")
        ) ++
        (if e.supportingWorkIdsRef == null then
           Vector(InvalidValue(at(path, "supportingWorkIdsRef"), "null option"))
         else
           e.supportingWorkIdsRef.toVector.flatMap(r =>
             check(
               r == CoauthorEdgeSupport.supportRef(e.edgeId),
               InvalidValue(
                 at(path, "supportingWorkIdsRef"),
                 s"must be '${CoauthorEdgeSupport.supportRef(e.edgeId)}' (the edge's rows in " +
                   "the coauthor-edge-support table)"
               )
             )
           )) ++
        nonBlank(at(path, "policyVersion"), e.policyVersion) ++
        (if e.edgeId != null && CanonicalPair.isCanonical(
             String.valueOf(e.researcherA),
             String.valueOf(e.researcherB)
           )
         then
           matchesFormula(
             at(path, "edgeId"),
             e.edgeId,
             StableIds.coauthorEdgeId(
               e.researcherA,
               e.researcherB,
               e.windowStartYear,
               e.windowEndYear,
               e.policyVersion
             )
           )
         else ok)

  given RecordContract[ResearcherMetrics] with
    val recordType = "ResearcherMetrics"
    def violations(m: ResearcherMetrics, path: String) =
      common(path, recordType, m.schemaVersion, m.provenance) ++
        id(at(path, "metricsId"), m.metricsId, IdKind.Metrics) ++
        id(at(path, "researcherId"), m.researcherId, IdKind.Researcher) ++
        window(at(path, "windowStartYear,windowEndYear"), m.windowStartYear, m.windowEndYear) ++
        nonNegative(at(path, "publicationCount"), m.publicationCount) ++
        nonNegative(at(path, "distinctCoauthorCount"), m.distinctCoauthorCount) ++
        nonNegative(at(path, "totalCoauthorOccurrences"), m.totalCoauthorOccurrences) ++
        finiteNonNegative(at(path, "averageCoauthorsPerPaper"), m.averageCoauthorsPerPaper) ++
        nonNegative(at(path, "maximumCoauthorsOnOnePaper"), m.maximumCoauthorsOnOnePaper) ++
        ids(at(path, "maximumTeamWorkIds"), m.maximumTeamWorkIds, IdKind.Work) ++
        finiteNonNegative(at(path, "weightedDegree"), m.weightedDegree) ++
        nonBlank(at(path, "componentId"), m.componentId) ++
        check(m.componentSize >= 1, InvalidValue(at(path, "componentSize"), "must be >= 1")) ++
        optNonBlank(at(path, "communityId"), m.communityId) ++
        check(
          m.bridgeMetric != null && m.bridgeScore != null &&
            m.bridgeMetric.isDefined == m.bridgeScore.isDefined,
          InvalidValue(
            at(path, "bridgeMetric"),
            "bridgeMetric and bridgeScore must be set together"
          )
        ) ++
        check(
          m.distinctCoauthorCount <= m.totalCoauthorOccurrences,
          InvalidValue(at(path, "distinctCoauthorCount"), "exceeds totalCoauthorOccurrences")
        ) ++
        nonBlank(at(path, "metricVersion"), m.metricVersion) ++
        (if m.metricsId != null && m.provenance != null then
           matchesFormula(
             at(path, "metricsId"),
             m.metricsId,
             StableIds.metricsId(
               m.researcherId,
               m.windowStartYear,
               m.windowEndYear,
               m.metricVersion,
               m.provenance.runId
             )
           )
         else ok)

  given RecordContract[Venue] with
    val recordType = "Venue"
    def violations(v: Venue, path: String) =
      common(path, recordType, v.schemaVersion, v.provenance) ++
        id(at(path, "venueId"), v.venueId, IdKind.Venue) ++
        nonBlank(at(path, "name"), v.name) ++
        optNonBlank(at(path, "venueType"), v.venueType) ++
        optNonBlank(at(path, "sourceVenueKey"), v.sourceVenueKey) ++
        (if v.issns == null then Vector(InvalidValue(at(path, "issns"), "must not be null"))
         else
           v.issns.zipWithIndex.flatMap((s, i) =>
             ExternalIds
               .normalizeIssn(s)
               .left
               .toOption
               .map(InvalidValue(at(path, s"issns[$i]"), _))
               .toVector
           )) ++
        venueIdFormula(v, path)

  /** Recomputes `venueId`: `dblp` and `openalex` from `sourceVenueKey`; `issn` from one of
    * `issns`.
    */
  private def venueIdFormula(v: Venue, path: String): Vector[ValidationError] =
    val p = at(path, "venueId")
    def fromKey(ns: VenueNamespace) =
      v.sourceVenueKey match
        case Some(k) if k != null => matchesFormulaStrict(p, v.venueId, StableIds.venueId(ns, k))
        case _ =>
          Vector(
            InvalidValue(at(path, "sourceVenueKey"), s"required for a ${ns.segment} venue ID")
          )
    namespaceOf(v.venueId, IdKind.Venue) match
      case Some("dblp") => fromKey(VenueNamespace.Dblp)
      case Some("openalex") => fromKey(VenueNamespace.OpenAlex)
      case Some("issn") =>
        val issns = Option(v.issns).getOrElse(Vector.empty)
        check(
          issns.exists(i => StableIds.venueId(VenueNamespace.Issn, i) == Right(v.venueId)),
          InvalidValue(p, "an issn venue ID must be derived from one of issns")
        )
      case _ => ok

  given RecordContract[Institution] with
    val recordType = "Institution"
    def violations(i: Institution, path: String) =
      common(path, recordType, i.schemaVersion, i.provenance) ++
        id(at(path, "institutionId"), i.institutionId, IdKind.Institution) ++
        nonBlank(at(path, "name"), i.name) ++
        (if i.rorId == null then Vector(InvalidValue(at(path, "rorId"), "null option"))
         else
           i.rorId.toVector.flatMap(r =>
             ExternalIds
               .normalizeRor(r)
               .left
               .toOption
               .map(InvalidValue(at(path, "rorId"), _))
               .toVector
           )) ++
        (if i.countryCode == null then Vector(InvalidValue(at(path, "countryCode"), "null option"))
         else
           i.countryCode.toVector.flatMap(c =>
             check(
               "[A-Z]{2}".r.matches(c),
               InvalidValue(at(path, "countryCode"), "expected ISO 3166-1 alpha-2")
             )
           )) ++
        (namespaceOf(i.institutionId, IdKind.Institution) match
          case Some("ror") =>
            i.rorId match
              case Some(r) if r != null =>
                matchesFormulaStrict(
                  at(path, "institutionId"),
                  i.institutionId,
                  StableIds.institutionId(InstitutionNamespace.Ror, r)
                )
              case _ => Vector(InvalidValue(at(path, "rorId"), "required for a ror institution ID"))
          case Some("openalex") =>
            fromOpenAlexProvenance(
              at(path, "institutionId"),
              i.institutionId,
              i.provenance,
              StableIds.institutionId(InstitutionNamespace.OpenAlex, _)
            )
          case _ => ok)

  given RecordContract[Topic] with
    val recordType = "Topic"
    def violations(t: Topic, path: String) =
      common(path, recordType, t.schemaVersion, t.provenance) ++
        id(at(path, "topicId"), t.topicId, IdKind.Topic) ++
        nonBlank(at(path, "name"), t.name) ++
        optId(at(path, "parentTopicId"), t.parentTopicId, IdKind.Topic) ++
        (namespaceOf(t.topicId, IdKind.Topic) match
          case Some("openalex") =>
            fromOpenAlexProvenance(
              at(path, "topicId"),
              t.topicId,
              t.provenance,
              StableIds.topicId(TopicNamespace.OpenAlex, _)
            )
          case _ => ok)

  given RecordContract[MatchDecision] with
    val recordType = "MatchDecision"
    def violations(d: MatchDecision, path: String) =
      common(path, recordType, d.schemaVersion, d.provenance) ++
        id(at(path, "decisionId"), d.decisionId, IdKind.MatchDecision) ++
        id(at(path, "sourceId"), d.sourceId) ++
        id(at(path, "targetId"), d.targetId) ++
        (if d.features == null then Vector(InvalidValue(at(path, "features"), "must not be null"))
         else
           d.features.toVector.sortBy(_._1).flatMap { (k, v) =>
             nonBlank(at(path, "features.<key>"), k) ++
               check(
                 !v.isNaN && !v.isInfinite,
                 InvalidValue(at(path, s"features.$k"), "must be finite")
               )
           }) ++
        notNull(at(path, "rulesFired"), d.rulesFired) ++
        notNull(at(path, "decision"), d.decision) ++
        confidence(at(path, "confidence"), d.confidence) ++
        notNull(at(path, "reasonCodes"), d.reasonCodes) ++
        check(
          d.reasonCodes == null || d.reasonCodes.nonEmpty,
          InvalidValue(at(path, "reasonCodes"), "at least one reason code is required")
        ) ++
        nonBlank(at(path, "policyVersion"), d.policyVersion) ++
        optNonBlank(at(path, "modelVersion"), d.modelVersion) ++
        optNonBlank(at(path, "promptVersion"), d.promptVersion) ++
        instant(at(path, "decidedAt"), d.decidedAt) ++
        id(at(path, "runId"), d.runId, IdKind.Run) ++
        check(
          d.provenance == null || d.runId == d.provenance.runId,
          InvalidValue(at(path, "runId"), "must equal provenance.runId")
        ) ++
        (if d.decisionId != null then
           matchesFormula(
             at(path, "decisionId"),
             d.decisionId,
             StableIds.matchDecisionId(d.sourceId, d.targetId, d.policyVersion)
           )
         else ok)

  private def nodeKinds(t: NodeType): Seq[IdKind] = t match
    case NodeType.Researcher => Seq(IdKind.Researcher)
    case NodeType.AuthorMention => Seq(IdKind.AuthorMention)
    case NodeType.Work => Seq(IdKind.Work)
    case NodeType.Venue => Seq(IdKind.Venue)
    case NodeType.Institution => Seq(IdKind.Institution)
    case NodeType.Topic => Seq(IdKind.Topic)
    case NodeType.TextChunk => Seq(IdKind.Chunk, IdKind.Profile)
    case NodeType.DatasetSnapshot => Seq(IdKind.Snapshot)

  given RecordContract[GraphNode] with
    val recordType = "GraphNode"
    def violations(n: GraphNode, path: String) =
      common(path, recordType, n.schemaVersion, n.provenance) ++
        (if n.nodeType == null then Vector(InvalidValue(at(path, "nodeType"), "must not be null"))
         else id(at(path, "nodeId"), n.nodeId, nodeKinds(n.nodeType)*)) ++
        nonBlank(at(path, "label"), n.label) ++
        notNull(at(path, "properties"), n.properties)

  /** Properties a `COAUTHORED_WITH` edge must carry (spec "Research graph model"). */
  val CoauthoredWithRequiredProperties: Vector[String] =
    Vector("jointPublicationCount", "firstCollaborationYear", "lastCollaborationYear")

  given RecordContract[GraphEdge] with
    val recordType = "GraphEdge"
    def violations(e: GraphEdge, path: String) =
      val undirected = e.edgeType != null && e.edgeType.undirected
      common(path, recordType, e.schemaVersion, e.provenance) ++
        id(at(path, "edgeId"), e.edgeId, IdKind.GraphEdge) ++
        id(at(path, "fromId"), e.fromId) ++
        id(at(path, "toId"), e.toId) ++
        notNull(at(path, "edgeType"), e.edgeType) ++
        (if undirected then pair(at(path, "fromId,toId"), e.fromId, e.toId) else ok) ++
        (if e.edgeType == EdgeType.COAUTHORED_WITH then
           id(at(path, "fromId"), e.fromId, IdKind.Researcher) ++
             id(at(path, "toId"), e.toId, IdKind.Researcher) ++
             CoauthoredWithRequiredProperties.flatMap(k =>
               check(
                 e.properties != null && e.properties.contains(k),
                 InvalidValue(at(path, s"properties.$k"), "required for COAUTHORED_WITH")
               )
             )
         else ok) ++
        notNull(at(path, "properties"), e.properties) ++
        nonBlank(at(path, "policyVersion"), e.policyVersion) ++
        ids(at(path, "sourceEvidenceIds"), e.sourceEvidenceIds) ++
        check(
          e.sourceEvidenceIds == null || e.sourceEvidenceIds.nonEmpty,
          InvalidValue(at(path, "sourceEvidenceIds"), "must not be empty")
        ) ++
        (if e.edgeId != null && e.edgeType != null && e.sourceEvidenceIds != null &&
           (!undirected || CanonicalPair.isCanonical(
             String.valueOf(e.fromId),
             String.valueOf(e.toId)
           ))
         then
           matchesFormula(
             at(path, "edgeId"),
             e.edgeId,
             StableIds
               .graphEdgeId(e.edgeType, e.fromId, e.toId, e.policyVersion, e.sourceEvidenceIds)
           )
         else ok)

  given evidenceItem: RecordContract[EvidenceItem] with
    val recordType = "EvidenceItem"
    def violations(e: EvidenceItem, path: String) =
      common(path, recordType, e.schemaVersion, e.provenance) ++
        id(at(path, "evidenceId"), e.evidenceId, IdKind.Evidence) ++
        notNull(at(path, "evidenceType"), e.evidenceType) ++
        ids(at(path, "referencedIds"), e.referencedIds) ++
        check(
          e.referencedIds == null || e.referencedIds.nonEmpty,
          InvalidValue(at(path, "referencedIds"), "must not be empty")
        ) ++
        optId(at(path, "workId"), e.workId, IdKind.Work) ++
        nonBlank(at(path, "sourceUri"), e.sourceUri) ++
        optId(at(path, "chunkId"), e.chunkId, IdKind.Chunk, IdKind.Profile) ++
        (if e.graphPath == null then Vector(InvalidValue(at(path, "graphPath"), "null option"))
         else
           e.graphPath.toVector.flatMap(p => ids(at(path, "graphPath"), p, IdKind.Researcher))) ++
        check(
          e.evidenceType != EvidenceType.GRAPH_PATH ||
            (e.graphPath != null && e.graphPath.exists(_.size >= 2)),
          InvalidValue(at(path, "graphPath"), "GRAPH_PATH evidence needs at least two researchers")
        ) ++
        check(
          e.evidenceType != EvidenceType.TEXT_CHUNK || (e.chunkId != null && e.chunkId.isDefined),
          InvalidValue(at(path, "chunkId"), "TEXT_CHUNK evidence needs a chunkId")
        ) ++
        (if e.score == null then Vector(InvalidValue(at(path, "score"), "null option"))
         else
           e.score.toVector.flatMap(s =>
             check(!s.isNaN && !s.isInfinite, InvalidValue(at(path, "score"), "must be finite"))
           )) ++
        (if e.textHash == null then Vector(InvalidValue(at(path, "textHash"), "null option"))
         else e.textHash.toVector.flatMap(textHash(at(path, "textHash"), _))) ++
        (if e.text == null then Vector(InvalidValue(at(path, "text"), "null option"))
         else
           e.text.toVector.flatMap { t =>
             e.textHash match
               case Some(h) if h != null =>
                 check(
                   StableIds.textHash(t) == Right(h),
                   InvalidValue(at(path, "textHash"), "does not match text")
                 )
               case _ => Vector(InvalidValue(at(path, "textHash"), "required when text is present"))
           }) ++
        (if e.evidenceId != null && e.evidenceType != null && e.referencedIds != null &&
           e.provenance != null
         then
           matchesFormula(
             at(path, "evidenceId"),
             e.evidenceId,
             StableIds.evidenceId(e.evidenceType, e.referencedIds, e.provenance.runId)
           )
         else ok)

  given RecordContract[EvidenceBundle] with
    val recordType = "EvidenceBundle"
    override val nestedRecordArrays = Vector("graphEvidence", "semanticEvidence")
    def violations(b: EvidenceBundle, path: String) =
      def section(name: String, items: Vector[EvidenceItem], graph: Boolean) =
        if items == null then Vector(InvalidValue(at(path, name), "must not be null"))
        else
          items.zipWithIndex.flatMap { (e, i) =>
            val p = at(path, s"$name[$i]")
            evidenceItem.violations(e, p) ++
              check(
                e == null || e.evidenceType == null || e.evidenceType.isGraphFact == graph,
                InvalidValue(
                  s"$p.evidenceType",
                  if graph then "semantic evidence in the graph section"
                  else "graph-fact evidence in the semantic section"
                )
              )
          }
      val all = Option(b.graphEvidence).getOrElse(Vector.empty) ++
        Option(b.semanticEvidence).getOrElse(Vector.empty)
      val evidenceIds = all.filter(_ != null).map(_.evidenceId)
      common(path, recordType, b.schemaVersion, b.provenance) ++
        id(at(path, "bundleId"), b.bundleId, IdKind.Bundle) ++
        id(at(path, "queryId"), b.queryId, IdKind.Query) ++
        section("graphEvidence", b.graphEvidence, graph = true) ++
        section("semanticEvidence", b.semanticEvidence, graph = false) ++
        distinct(at(path, "graphEvidence+semanticEvidence"), evidenceIds) ++
        (if b.identityConfidence == null then
           Vector(InvalidValue(at(path, "identityConfidence"), "must not be null"))
         else
           b.identityConfidence.toVector.sortBy(_._1).flatMap { (k, v) =>
             id(at(path, s"identityConfidence.$k"), k, IdKind.Researcher) ++
               confidence(at(path, s"identityConfidence.$k"), v)
           }) ++
        (if b.bundleId != null then
           matchesFormula(
             at(path, "bundleId"),
             b.bundleId,
             StableIds.bundleId(b.queryId, evidenceIds)
           )
         else ok)

  given RecordContract[QueryPlan] with
    val recordType = "QueryPlan"
    def violations(q: QueryPlan, path: String) =
      common(path, recordType, q.schemaVersion, q.provenance) ++
        id(at(path, "queryId"), q.queryId, IdKind.Query) ++
        check(
          q.operations != null && q.operations.nonEmpty,
          InvalidValue(at(path, "operations"), "at least one operation is required")
        ) ++
        notNull(at(path, "researcherNames"), q.researcherNames) ++
        ids(at(path, "researcherIds"), q.researcherIds, IdKind.Researcher) ++
        ((q.windowStartYear, q.windowEndYear) match
          case (Some(s), Some(e)) => window(at(path, "windowStartYear,windowEndYear"), s, e)
          case (None, None) => ok
          case _ =>
            Vector(
              InvalidValue(at(path, "windowStartYear,windowEndYear"), "both bounds or neither")
            )) ++
        notNull(at(path, "topics"), q.topics) ++
        notNull(at(path, "venues"), q.venues) ++
        notNull(at(path, "institutions"), q.institutions) ++
        check(q.graphLimit >= 1, InvalidValue(at(path, "graphLimit"), "must be >= 1")) ++
        check(q.vectorLimit >= 0, InvalidValue(at(path, "vectorLimit"), "must be >= 0")) ++
        check(q.maxPathDepth >= 1, InvalidValue(at(path, "maxPathDepth"), "must be >= 1")) ++
        nonBlank(at(path, "planVersion"), q.planVersion)

  given answerClaim: RecordContract[AnswerClaim] with
    val recordType = "AnswerClaim"
    def violations(c: AnswerClaim, path: String) =
      common(path, recordType, c.schemaVersion, c.provenance) ++
        id(at(path, "claimId"), c.claimId, IdKind.Claim) ++
        nonBlank(at(path, "text"), c.text) ++
        ids(at(path, "evidenceIds"), c.evidenceIds, IdKind.Evidence) ++
        notNull(at(path, "verificationStatus"), c.verificationStatus) ++
        check(
          c.evidenceIds == null || c.evidenceIds.nonEmpty,
          InvalidValue(
            at(path, "evidenceIds"),
            s"a ${String.valueOf(c.verificationStatus)} claim must cite at least one evidence ID"
          )
        ) ++
        distinct(at(path, "evidenceIds"), c.evidenceIds) ++
        optNonBlank(at(path, "uncertainty"), c.uncertainty)

  given RecordContract[AnswerResponse] with
    val recordType = "AnswerResponse"
    override val nestedRecordArrays = Vector("claims")
    def violations(r: AnswerResponse, path: String) =
      common(path, recordType, r.schemaVersion, r.provenance) ++
        id(at(path, "queryId"), r.queryId, IdKind.Query) ++
        nonBlank(at(path, "summary"), r.summary) ++
        (if r.claims == null then Vector(InvalidValue(at(path, "claims"), "must not be null"))
         else
           r.claims.zipWithIndex.flatMap((c, i) =>
             answerClaim.violations(c, at(path, s"claims[$i]")) ++
               (if c != null && c.claimId != null && r.queryId != null then
                  // claimId = claim:sha256(queryId, ordinal); the ordinal is the claim's index.
                  matchesFormula(
                    at(path, s"claims[$i].claimId"),
                    c.claimId,
                    StableIds.claimId(r.queryId, i)
                  )
                else ok)
           )) ++
        id(at(path, "evidenceBundleId"), r.evidenceBundleId, IdKind.Bundle) ++
        optNonBlank(at(path, "uncertainty"), r.uncertainty) ++
        check(
          !r.abstained || (r.uncertainty != null && r.uncertainty.isDefined),
          InvalidValue(at(path, "uncertainty"), "an abstaining answer must state its uncertainty")
        )

  given RecordContract[QueryAuditRecord] with
    val recordType = "QueryAuditRecord"
    def violations(r: QueryAuditRecord, path: String) =
      def versions(field: String, values: Vector[String]) =
        if values == null then Vector(InvalidValue(at(path, field), "must not be null"))
        else
          values.zipWithIndex.flatMap((v, i) => nonBlank(at(path, s"$field[$i]"), v)) ++
            distinct(at(path, field), values)
      common(path, recordType, r.schemaVersion, r.provenance) ++
        id(at(path, "queryId"), r.queryId, IdKind.Query) ++
        id(at(path, "runId"), r.runId, IdKind.Run) ++
        nonBlank(at(path, "planJson"), r.planJson) ++
        ids(at(path, "researcherIds"), r.researcherIds, IdKind.Researcher) ++
        distinct(at(path, "researcherIds"), r.researcherIds) ++
        ids(at(path, "evidenceIds"), r.evidenceIds, IdKind.Evidence) ++
        distinct(at(path, "evidenceIds"), r.evidenceIds) ++
        versions("metricVersions", r.metricVersions) ++
        versions("promptVersions", r.promptVersions) ++
        versions("modelVersions", r.modelVersions) ++
        nonNegative(at(path, "inputTokens"), r.inputTokens) ++
        nonNegative(at(path, "outputTokens"), r.outputTokens) ++
        nonNegative(at(path, "latencyMillis"), r.latencyMillis) ++
        notNull(at(path, "verificationResult"), r.verificationResult) ++
        textHash(at(path, "answerHash"), r.answerHash)

  given RecordContract[RunManifest] with
    val recordType = "RunManifest"
    override val requiresProvenance = false
    def violations(m: RunManifest, path: String) =
      schemaVersion(at(path, "schemaVersion"), recordType, m.schemaVersion) ++
        nonBlank(at(path, "pipelineStage"), m.pipelineStage) ++
        nonBlank(at(path, "codeVersion"), m.codeVersion) ++
        (if m.inputs == null then Vector(InvalidValue(at(path, "inputs"), "must not be null"))
         else
           check(m.inputs.nonEmpty, InvalidValue(at(path, "inputs"), "at least one input")) ++
             m.inputs.zipWithIndex.flatMap { (in, i) =>
               val p = at(path, s"inputs[$i]")
               if in == null then Vector(InvalidValue(p, "must not be null"))
               else
                 notNull(s"$p.sourceDataset", in.sourceDataset) ++
                   nonBlank(s"$p.snapshot", in.snapshot) ++
                   nonBlank(s"$p.location", in.location) ++
                   check(
                     StableIds.isTextHash(in.checksum),
                     InvalidValue(
                       s"$p.checksum",
                       s"'${in.checksum}' is not sha256:<64 lower-case hex>; every input needs one"
                     )
                   )
             } ++
             distinct(
               at(path, "inputs"),
               m.inputs.filter(_ != null).map(in => s"${in.sourceDataset}|${in.location}")
             )) ++
        notNull(at(path, "parameters"), m.parameters) ++
        notNull(at(path, "policyVersions"), m.policyVersions)

  // ---------------------------------------------------------------------------
  // Phase 4 ingestion records
  // ---------------------------------------------------------------------------

  private def normalizedOrcid(path: String, value: Option[String]): Vector[ValidationError] =
    if value == null then Vector(InvalidValue(path, "null option"))
    else
      value.toVector.flatMap(o =>
        ExternalIds
          .normalizeOrcid(o)
          .fold(
            r => Vector(InvalidValue(path, r)),
            n => check(n == o, InvalidValue(path, s"'$o' is not normalized ('$n')"))
          )
      )

  given RecordContract[AuthorPaperRecord] with
    val recordType = "AuthorPaperRecord"
    def violations(r: AuthorPaperRecord, path: String) =
      common(path, recordType, r.schemaVersion, r.provenance) ++
        id(at(path, "mentionId"), r.mentionId, IdKind.AuthorMention) ++
        id(at(path, "workId"), r.workId, IdKind.Work) ++
        check(
          r.position >= 1,
          InvalidValue(at(path, "position"), s"must be >= 1, got ${r.position}")
        ) ++
        check(
          r.teamSize >= 1,
          InvalidValue(at(path, "teamSize"), s"must be >= 1, got ${r.teamSize}")
        ) ++
        check(r.year != null, InvalidValue(at(path, "year"), "null option")) ++
        nonBlank(at(path, "normalizedName"), r.normalizedName) ++
        optNonBlank(at(path, "sourceAuthorKey"), r.sourceAuthorKey) ++
        normalizedOrcid(at(path, "orcid"), r.orcid) ++
        (if r.workId != null && r.mentionId != null then
           matchesFormula(
             at(path, "mentionId"),
             r.mentionId,
             StableIds.mentionId(r.workId, r.position)
           )
         else ok)

  given RecordContract[MatchCandidate] with
    val recordType = "MatchCandidate"
    def violations(c: MatchCandidate, path: String) =
      val refKinds =
        if c.entityType == null then Seq.empty
        else StableIds.matchCandidateRefKinds(c.entityType).toSeq.sortBy(_.prefix)
      val refErrors =
        if c.entityType == null then
          Vector(InvalidValue(at(path, "entityType"), "must not be null"))
        else
          id(at(path, "leftRef"), c.leftRef, refKinds*) ++
            id(at(path, "rightRef"), c.rightRef, refKinds*)
      common(path, recordType, c.schemaVersion, c.provenance) ++
        id(at(path, "candidateId"), c.candidateId, IdKind.MatchCandidate) ++
        refErrors ++
        pair(at(path, "leftRef,rightRef"), c.leftRef, c.rightRef) ++
        nonBlank(at(path, "blockKey"), c.blockKey) ++
        (if c.features == null then Vector(InvalidValue(at(path, "features"), "must not be null"))
         else
           c.features.toVector.sortBy(_._1).flatMap { (k, v) =>
             nonBlank(at(path, "features.<key>"), k) ++
               check(
                 !v.isNaN && !v.isInfinite,
                 InvalidValue(at(path, s"features.$k"), "must be finite")
               )
           }) ++
        nonBlank(at(path, "featureVersion"), c.featureVersion) ++
        (if c.candidateId != null && refErrors.isEmpty && CanonicalPair.isCanonical(
             c.leftRef,
             c.rightRef
           )
         then
           matchesFormula(
             at(path, "candidateId"),
             c.candidateId,
             StableIds.matchCandidateId(c.entityType, c.leftRef, c.rightRef, c.featureVersion)
           )
         else ok)

  /** Stable-ID kinds the records grouped under one exact identifier may have. */
  def exactIdRecordKinds(idType: ExactIdType): Seq[IdKind] =
    if idType.identifiesResearchers then Seq(IdKind.AuthorMention, IdKind.Researcher)
    else Seq(IdKind.Work)

  given RecordContract[ExactIdentifierOutcome] with
    val recordType = "ExactIdentifierOutcome"
    def violations(o: ExactIdentifierOutcome, path: String) =
      val normalizedErrors =
        if o.idType == null then Vector(InvalidValue(at(path, "idType"), "must not be null"))
        else if isBlank(o.normalizedId) then Vector(BlankField(at(path, "normalizedId")))
        else
          ExternalIds
            .normalizeExactId(o.idType, o.normalizedId)
            .fold(
              r => Vector(InvalidValue(at(path, "normalizedId"), r)),
              n =>
                check(
                  n == o.normalizedId,
                  InvalidValue(
                    at(path, "normalizedId"),
                    s"'${o.normalizedId}' is not normalized ('$n')"
                  )
                )
            )
      common(path, recordType, o.schemaVersion, o.provenance) ++
        id(at(path, "outcomeId"), o.outcomeId, IdKind.ExactIdOutcome) ++
        normalizedErrors ++
        (if o.recordRefs == null then
           Vector(InvalidValue(at(path, "recordRefs"), "must not be null"))
         else
           ids(
             at(path, "recordRefs"),
             o.recordRefs,
             (if o.idType == null then Seq.empty else exactIdRecordKinds(o.idType))*
           ) ++
             check(
               o.recordRefs.size >= 2,
               InvalidValue(at(path, "recordRefs"), "an outcome groups at least two records")
             ) ++
             distinct(at(path, "recordRefs"), o.recordRefs) ++
             check(
               o.recordRefs.contains(null) || o.recordRefs == o.recordRefs.sorted,
               InvalidValue(at(path, "recordRefs"), "must be sorted ascending")
             )) ++
        notNull(at(path, "outcome"), o.outcome) ++
        (if o.outcomeId != null && normalizedErrors.isEmpty then
           matchesFormula(
             at(path, "outcomeId"),
             o.outcomeId,
             StableIds.exactIdOutcomeId(o.idType, o.normalizedId)
           )
         else ok)

  private val ReasonCodePattern = "[A-Z][A-Z0-9_]*".r

  given RecordContract[RejectRecord] with
    val recordType = "RejectRecord"
    def violations(r: RejectRecord, path: String) =
      val p = r.provenance
      common(path, recordType, r.schemaVersion, p) ++
        notNull(at(path, "source"), r.source) ++
        nonBlank(at(path, "sourceRecordRef"), r.sourceRecordRef) ++
        nonBlank(at(path, "stage"), r.stage) ++
        check(
          r.reasonCode != null && ReasonCodePattern.matches(r.reasonCode),
          InvalidValue(
            at(path, "reasonCode"),
            s"'${r.reasonCode}' must match [A-Z][A-Z0-9_]*"
          )
        ) ++
        (if r.rawExcerpt == null then
           Vector(InvalidValue(at(path, "rawExcerpt"), "must not be null"))
         else
           val n = r.rawExcerpt.codePointCount(0, r.rawExcerpt.length)
           check(
             n <= RejectRecord.MaxExcerptCodePoints,
             InvalidValue(
               at(path, "rawExcerpt"),
               s"$n code points exceed the limit of ${RejectRecord.MaxExcerptCodePoints}"
             )
           )) ++
        id(at(path, "runId"), r.runId, IdKind.Run) ++
        check(
          p == null || r.runId == p.runId,
          InvalidValue(at(path, "runId"), "must equal provenance.runId")
        ) ++
        check(
          p == null || r.source == p.sourceDataset,
          InvalidValue(at(path, "source"), "must equal provenance.sourceDataset")
        ) ++
        check(
          p == null || r.sourceRecordRef == p.sourceRecordId,
          InvalidValue(at(path, "sourceRecordRef"), "must equal provenance.sourceRecordId")
        )
