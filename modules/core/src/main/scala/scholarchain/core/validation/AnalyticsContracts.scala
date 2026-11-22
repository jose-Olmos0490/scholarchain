package scholarchain.core.validation

import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.core.validation.Checks.*
import scholarchain.core.validation.ValidationError.*

/** Contracts of the Phase 6 Spark analytics records. Mixed into the [[RecordContract]] companion,
  * so these givens are in implicit scope exactly like the others.
  */
trait AnalyticsContracts:

  private def at(path: String, field: String): String =
    if path.isEmpty then field else s"$path.$field"

  private def common(
      path: String,
      recordType: String,
      schema: String,
      runId: String,
      p: Provenance
  ): Errors =
    schemaVersion(at(path, "schemaVersion"), recordType, schema) ++
      provenance(at(path, "provenance"), p) ++
      id(at(path, "runId"), runId, IdKind.Run) ++
      check(
        p == null || runId == p.runId,
        InvalidValue(at(path, "runId"), "must equal provenance.runId")
      )

  private def win(path: String, start: Int, end: Int): Errors =
    window(at(path, "windowStartYear,windowEndYear"), start, end)

  private def sortedDistinct(path: String, values: Vector[String]): Errors =
    if values == null then Vector(InvalidValue(path, "must not be null"))
    else
      distinct(path, values) ++
        check(
          values.contains(null) || values == values.sorted,
          InvalidValue(path, "must be sorted ascending")
        )

  private def maxCodePoints(path: String, text: String, max: Int): Errors =
    if text == null then Vector(InvalidValue(path, "must not be null"))
    else
      val n = text.codePointCount(0, text.length)
      check(n <= max, InvalidValue(path, s"$n code points exceed the limit of $max"))

  private def noneOrSome[A](path: String, o: Option[A]): Errors =
    if o == null then Vector(InvalidValue(path, "null option")) else ok

  /** Recomputes a coauthor edge ID when the endpoints are canonical. */
  private def edgeFormula(
      path: String,
      edgeId: String,
      a: String,
      b: String,
      start: Int,
      end: Int,
      policy: String
  ): Errors =
    if edgeId != null && CanonicalPair.isCanonical(String.valueOf(a), String.valueOf(b)) then
      matchesFormula(path, edgeId, StableIds.coauthorEdgeId(a, b, start, end, policy))
    else ok

  given RecordContract[CoauthorEdgeSupport] with
    val recordType = "CoauthorEdgeSupport"
    def violations(s: CoauthorEdgeSupport, path: String) =
      val windowErrors = win(path, s.windowStartYear, s.windowEndYear)
      common(path, recordType, s.schemaVersion, s.runId, s.provenance) ++
        id(at(path, "edgeId"), s.edgeId, IdKind.CoauthorEdge) ++
        id(at(path, "researcherA"), s.researcherA, IdKind.Researcher) ++
        id(at(path, "researcherB"), s.researcherB, IdKind.Researcher) ++
        pair(at(path, "researcherA,researcherB"), s.researcherA, s.researcherB) ++
        windowErrors ++
        id(at(path, "workId"), s.workId, IdKind.Work) ++
        noneOrSome(at(path, "workYear"), s.workYear) ++
        (if s.workYear == null || windowErrors.nonEmpty then ok
         else
           s.workYear.toVector.flatMap(y =>
             check(
               y >= s.windowStartYear && y <= s.windowEndYear,
               InvalidValue(at(path, "workYear"), s"$y lies outside the window")
             )
           )) ++
        nonBlank(at(path, "policyVersion"), s.policyVersion) ++
        edgeFormula(
          at(path, "edgeId"),
          s.edgeId,
          s.researcherA,
          s.researcherB,
          s.windowStartYear,
          s.windowEndYear,
          s.policyVersion
        )

  given RecordContract[ComponentMembership] with
    val recordType = "ComponentMembership"
    def violations(m: ComponentMembership, path: String) =
      val idErrors = id(at(path, "researcherId"), m.researcherId, IdKind.Researcher) ++
        id(at(path, "componentId"), m.componentId, IdKind.Researcher)
      common(path, recordType, m.schemaVersion, m.runId, m.provenance) ++
        idErrors ++
        win(path, m.windowStartYear, m.windowEndYear) ++
        check(m.componentSize >= 1, InvalidValue(at(path, "componentSize"), "must be >= 1")) ++
        (if idErrors.nonEmpty then ok
         else
           check(
             m.componentId.compareTo(m.researcherId) <= 0,
             InvalidValue(
               at(path, "componentId"),
               "must be the smallest researcher ID in the component (<= researcherId)"
             )
           ) ++
             check(
               m.componentSize != 1 || m.componentId == m.researcherId,
               InvalidValue(at(path, "componentId"), "a singleton component is its own member")
             )) ++
        nonBlank(at(path, "metricVersion"), m.metricVersion)

  given RecordContract[CommunityMembership] with
    val recordType = "CommunityMembership"
    def violations(m: CommunityMembership, path: String) =
      common(path, recordType, m.schemaVersion, m.runId, m.provenance) ++
        id(at(path, "researcherId"), m.researcherId, IdKind.Researcher) ++
        id(at(path, "communityId"), m.communityId, IdKind.Researcher) ++
        win(path, m.windowStartYear, m.windowEndYear) ++
        check(m.communitySize >= 1, InvalidValue(at(path, "communitySize"), "must be >= 1")) ++
        nonBlank(at(path, "metricVersion"), m.metricVersion)

  given RecordContract[CrossCommunityEdge] with
    val recordType = "CrossCommunityEdge"
    def violations(e: CrossCommunityEdge, path: String) =
      common(path, recordType, e.schemaVersion, e.runId, e.provenance) ++
        id(at(path, "edgeId"), e.edgeId, IdKind.CoauthorEdge) ++
        id(at(path, "researcherA"), e.researcherA, IdKind.Researcher) ++
        id(at(path, "researcherB"), e.researcherB, IdKind.Researcher) ++
        pair(at(path, "researcherA,researcherB"), e.researcherA, e.researcherB) ++
        id(at(path, "communityA"), e.communityA, IdKind.Researcher) ++
        id(at(path, "communityB"), e.communityB, IdKind.Researcher) ++
        check(
          e.communityA == null || e.communityA != e.communityB,
          InvalidValue(at(path, "communityA,communityB"), "a cross-community edge joins two")
        ) ++
        win(path, e.windowStartYear, e.windowEndYear) ++
        check(
          e.jointPublicationCount >= 1,
          InvalidValue(at(path, "jointPublicationCount"), "must be >= 1")
        ) ++
        nonBlank(at(path, "policyVersion"), e.policyVersion) ++
        nonBlank(at(path, "metricVersion"), e.metricVersion) ++
        edgeFormula(
          at(path, "edgeId"),
          e.edgeId,
          e.researcherA,
          e.researcherB,
          e.windowStartYear,
          e.windowEndYear,
          e.policyVersion
        )

  given RecordContract[BridgeMeasures] with
    val recordType = "BridgeMeasures"
    def violations(b: BridgeMeasures, path: String) =
      val pc = b.participationCoefficient
      val pcValid = !pc.isNaN && pc >= 0.0 && pc <= 1.0
      val optionals = noneOrSome(at(path, "betweenness"), b.betweenness) ++
        noneOrSome(at(path, "betweennessMethod"), b.betweennessMethod) ++
        noneOrSome(at(path, "betweennessSampleSources"), b.betweennessSampleSources) ++
        noneOrSome(at(path, "betweennessSeed"), b.betweennessSeed)
      common(path, recordType, b.schemaVersion, b.runId, b.provenance) ++
        id(at(path, "researcherId"), b.researcherId, IdKind.Researcher) ++
        id(at(path, "communityId"), b.communityId, IdKind.Researcher) ++
        win(path, b.windowStartYear, b.windowEndYear) ++
        nonNegative(at(path, "degree"), b.degree) ++
        check(
          b.crossCommunityDegree >= 0 && b.crossCommunityDegree <= b.degree,
          InvalidValue(at(path, "crossCommunityDegree"), "must lie in [0, degree]")
        ) ++
        check(
          b.communitiesConnected >= 0 && b.communitiesConnected <= b.degree + 1,
          InvalidValue(at(path, "communitiesConnected"), "must lie in [0, degree + 1]")
        ) ++
        check(
          pcValid,
          InvalidValue(at(path, "participationCoefficient"), s"$pc is outside [0, 1]")
        ) ++
        check(
          !pcValid || b.crossCommunityDegree != 0 || pc == 0.0,
          InvalidValue(
            at(path, "participationCoefficient"),
            "must be 0 when crossCommunityDegree is 0 (all neighbours in the own community)"
          )
        ) ++
        optionals ++
        (if optionals.nonEmpty then ok
         else
           check(
             b.betweenness.isDefined == b.betweennessMethod.isDefined,
             InvalidValue(
               at(path, "betweennessMethod"),
               "betweenness and betweennessMethod must be set together"
             )
           ) ++
             b.betweenness.toVector.flatMap(finiteNonNegative(at(path, "betweenness"), _)) ++
             (b.betweennessMethod match
               case Some(BetweennessMethod.SAMPLED_SOURCES) =>
                 check(
                   b.betweennessSampleSources.exists(_ >= 1) && b.betweennessSeed.isDefined,
                   InvalidValue(
                     at(path, "betweennessSampleSources"),
                     "sampled betweenness needs sampleSources >= 1 and a seed"
                   )
                 )
               case _ =>
                 check(
                   b.betweennessSampleSources.isEmpty && b.betweennessSeed.isEmpty,
                   InvalidValue(
                     at(path, "betweennessSampleSources"),
                     "sample size and seed apply only to SAMPLED_SOURCES betweenness"
                   )
                 ))) ++
        nonBlank(at(path, "metricVersion"), b.metricVersion)

  given RecordContract[CorenessRecord] with
    val recordType = "CorenessRecord"
    def violations(r: CorenessRecord, path: String) =
      common(path, recordType, r.schemaVersion, r.runId, r.provenance) ++
        id(at(path, "researcherId"), r.researcherId, IdKind.Researcher) ++
        win(path, r.windowStartYear, r.windowEndYear) ++
        nonNegative(at(path, "degree"), r.degree) ++
        check(
          r.coreness >= 0 && r.coreness <= r.degree,
          InvalidValue(at(path, "coreness"), "must lie in [0, degree]")
        ) ++
        nonBlank(at(path, "metricVersion"), r.metricVersion)

  given RecordContract[PathEvidence] with
    val recordType = "PathEvidence"
    def violations(p: PathEvidence, path: String) =
      val endpointErrors =
        id(at(path, "sourceResearcherId"), p.sourceResearcherId, IdKind.Researcher) ++
          id(at(path, "targetResearcherId"), p.targetResearcherId, IdKind.Researcher)
      val hops = Option(p.hops).getOrElse(Vector.empty)
      val shape = p.status match
        case null => Vector(InvalidValue(at(path, "status"), "must not be null"))
        case PathStatus.FOUND =>
          check(hops.nonEmpty, InvalidValue(at(path, "hops"), "a FOUND path has hops")) ++
            check(
              p.length == Some(hops.size),
              InvalidValue(at(path, "length"), "must equal the number of hops")
            ) ++
            check(
              hops.size <= p.maxDepth,
              InvalidValue(at(path, "hops"), s"${hops.size} hops exceed maxDepth ${p.maxDepth}")
            ) ++
            (if hops.contains(null) || hops.isEmpty then ok
             else
               val nodes = hops.head.fromResearcherId +: hops.map(_.toResearcherId)
               check(
                 hops.head.fromResearcherId == p.sourceResearcherId,
                 InvalidValue(at(path, "hops[0].fromResearcherId"), "must be the source")
               ) ++
                 check(
                   hops.last.toResearcherId == p.targetResearcherId,
                   InvalidValue(at(path, "hops"), "the last hop must end at the target")
                 ) ++
                 hops
                   .zip(hops.drop(1))
                   .zipWithIndex
                   .flatMap { case ((a, b), i) =>
                     check(
                       a.toResearcherId == b.fromResearcherId,
                       InvalidValue(
                         at(path, s"hops[${i + 1}].fromResearcherId"),
                         "breaks the chain"
                       )
                     )
                   } ++
                 check(
                   nodes.distinct.size == nodes.size,
                   InvalidValue(at(path, "hops"), "a path visits each researcher once")
                 ))
        case _ =>
          check(hops.isEmpty, InvalidValue(at(path, "hops"), s"a ${p.status} path has no hops")) ++
            check(p.length == None, InvalidValue(at(path, "length"), "must be null"))
      common(path, recordType, p.schemaVersion, p.runId, p.provenance) ++
        id(at(path, "pathId"), p.pathId, IdKind.Path) ++
        endpointErrors ++
        check(
          p.sourceResearcherId == null || p.sourceResearcherId != p.targetResearcherId,
          InvalidValue(at(path, "targetResearcherId"), "must differ from the source")
        ) ++
        win(path, p.windowStartYear, p.windowEndYear) ++
        notNull(at(path, "selection"), p.selection) ++
        check(p.maxDepth >= 1, InvalidValue(at(path, "maxDepth"), "must be >= 1")) ++
        noneOrSome(at(path, "length"), p.length) ++
        notNull(at(path, "hops"), p.hops) ++
        shape ++
        hops.zipWithIndex.flatMap { (h, i) =>
          val hp = at(path, s"hops[$i]")
          if h == null then Vector(InvalidValue(hp, "must not be null"))
          else
            val ends = id(s"$hp.fromResearcherId", h.fromResearcherId, IdKind.Researcher) ++
              id(s"$hp.toResearcherId", h.toResearcherId, IdKind.Researcher)
            ends ++
              id(s"$hp.edgeId", h.edgeId, IdKind.CoauthorEdge) ++
              check(
                h.jointPublicationCount >= 1,
                InvalidValue(s"$hp.jointPublicationCount", "must be >= 1")
              ) ++
              ids(s"$hp.supportingWorkIds", h.supportingWorkIds, IdKind.Work) ++
              sortedDistinct(s"$hp.supportingWorkIds", h.supportingWorkIds) ++
              check(
                h.supportingWorkIds == null ||
                  h.supportingWorkIds.size.toLong == h.jointPublicationCount,
                InvalidValue(
                  s"$hp.supportingWorkIds",
                  "must list every supporting work (size == jointPublicationCount)"
                )
              ) ++
              (if ends.nonEmpty || h.edgeId == null || h.fromResearcherId == h.toResearcherId
               then
                 check(
                   h.fromResearcherId != h.toResearcherId,
                   SelfPair(hp, String.valueOf(h.fromResearcherId))
                 )
               else
                 val cp = CanonicalPair.of(h.fromResearcherId, h.toResearcherId).toOption.get
                 matchesFormula(
                   s"$hp.edgeId",
                   h.edgeId,
                   StableIds.coauthorEdgeId(
                     cp.a,
                     cp.b,
                     p.windowStartYear,
                     p.windowEndYear,
                     p.policyVersion
                   )
                 ))
        } ++
        nonBlank(at(path, "policyVersion"), p.policyVersion) ++
        (if p.pathId != null && p.provenance != null && endpointErrors.isEmpty then
           matchesFormula(
             at(path, "pathId"),
             p.pathId,
             StableIds.pathId(
               p.sourceResearcherId,
               p.targetResearcherId,
               p.windowStartYear,
               p.windowEndYear,
               p.policyVersion,
               p.runId
             )
           )
         else ok)

  private def ranked(path: String, values: Vector[RankedEntity], kind: IdKind): Errors =
    if values == null then Vector(InvalidValue(path, "must not be null"))
    else
      val each = values.zipWithIndex.flatMap { (r, i) =>
        val rp = s"$path[$i]"
        if r == null then Vector(InvalidValue(rp, "must not be null"))
        else
          id(s"$rp.entityId", r.entityId, kind) ++
            nonBlank(s"$rp.label", r.label) ++
            (if r.label == null then ok
             else maxCodePoints(s"$rp.label", r.label, SemanticFact.MaxTextCodePoints)) ++
            check(r.count >= 1, InvalidValue(s"$rp.count", "must be >= 1"))
      }
      val clean = values.filter(r => r != null && r.entityId != null)
      each ++
        distinct(path, clean.map(_.entityId)) ++
        check(
          clean == clean.sortBy(r => (-r.count, r.entityId)),
          InvalidValue(path, "must be sorted by count descending, then entity ID ascending")
        )

  given RecordContract[SemanticFact] with
    val recordType = "SemanticFact"
    def violations(f: SemanticFact, path: String) =
      val windowErrors = win(path, f.windowStartYear, f.windowEndYear)
      val subjectErrors =
        if f.subjectType == null then
          Vector(InvalidValue(at(path, "subjectType"), "must not be null"))
        else
          id(
            at(path, "subjectId"),
            f.subjectId,
            StableIds.semanticSubjectKinds(f.subjectType).toSeq.sortBy(_.prefix)*
          )
      common(path, recordType, f.schemaVersion, f.runId, f.provenance) ++
        id(at(path, "factId"), f.factId, IdKind.SemanticFact) ++
        subjectErrors ++
        noneOrSome(at(path, "displayName"), f.displayName) ++
        (if f.displayName == null then ok
         else
           f.displayName.toVector.flatMap(d =>
             nonBlank(at(path, "displayName"), d) ++
               maxCodePoints(at(path, "displayName"), d, SemanticFact.MaxTextCodePoints)
           )) ++
        windowErrors ++
        (if f.metrics == null then Vector(InvalidValue(at(path, "metrics"), "must not be null"))
         else
           f.metrics.zipWithIndex.flatMap { (m, i) =>
             val mp = at(path, s"metrics[$i]")
             if m == null then Vector(InvalidValue(mp, "must not be null"))
             else
               notNull(s"$mp.metric", m.metric) ++
                 check(
                   !m.value.isNaN && !m.value.isInfinite,
                   InvalidValue(s"$mp.value", "must be finite")
                 )
           } ++ distinct(
             at(path, "metrics"),
             f.metrics.filter(_ != null).map(m => String.valueOf(m.metric))
           )) ++
        ranked(at(path, "topTopics"), f.topTopics, IdKind.Topic) ++
        ranked(at(path, "topVenues"), f.topVenues, IdKind.Venue) ++
        ranked(at(path, "topInstitutions"), f.topInstitutions, IdKind.Institution) ++
        ids(at(path, "relatedResearcherIds"), f.relatedResearcherIds, IdKind.Researcher) ++
        distinct(at(path, "relatedResearcherIds"), f.relatedResearcherIds) ++
        check(
          f.subjectType != SemanticSubjectType.PATH || f.relatedResearcherIds == null ||
            f.relatedResearcherIds.size >= 2,
          InvalidValue(
            at(path, "relatedResearcherIds"),
            "a PATH fact lists its nodes in hop order (>= 2)"
          )
        ) ++
        (if f.representativeWorks == null then
           Vector(InvalidValue(at(path, "representativeWorks"), "must not be null"))
         else
           f.representativeWorks.zipWithIndex.flatMap { (w, i) =>
             val wp = at(path, s"representativeWorks[$i]")
             if w == null then Vector(InvalidValue(wp, "must not be null"))
             else
               id(s"$wp.workId", w.workId, IdKind.Work) ++
                 nonBlank(s"$wp.title", w.title) ++
                 (if w.title == null then ok
                  else maxCodePoints(s"$wp.title", w.title, SemanticFact.MaxTextCodePoints)) ++
                 noneOrSome(s"$wp.year", w.year) ++
                 (if w.year == null || windowErrors.nonEmpty then ok
                  else
                    w.year.toVector.flatMap(y =>
                      check(
                        y >= f.windowStartYear && y <= f.windowEndYear,
                        InvalidValue(s"$wp.year", s"$y lies outside the window")
                      )
                    ))
           } ++ distinct(
             at(path, "representativeWorks"),
             f.representativeWorks.filter(_ != null).map(w => String.valueOf(w.workId))
           )) ++
        nonBlank(at(path, "metricVersion"), f.metricVersion) ++
        (if f.factId != null && subjectErrors.isEmpty && f.provenance != null then
           matchesFormula(
             at(path, "factId"),
             f.factId,
             StableIds.semanticFactId(
               f.subjectType,
               f.subjectId,
               f.windowStartYear,
               f.windowEndYear,
               f.metricVersion,
               f.runId
             )
           )
         else ok)

  private val TableName = "[a-z][a-z0-9_]*".r

  given RecordContract[LoadManifest] with
    val recordType = "LoadManifest"
    def violations(m: LoadManifest, path: String) =
      common(path, recordType, m.schemaVersion, m.runId, m.provenance) ++
        notNull(at(path, "target"), m.target) ++
        (if m.tables == null then Vector(InvalidValue(at(path, "tables"), "must not be null"))
         else
           check(m.tables.nonEmpty, InvalidValue(at(path, "tables"), "at least one table")) ++
             distinct(
               at(path, "tables"),
               m.tables.filter(_ != null).map(t => String.valueOf(t.tableName))
             ) ++
             m.tables.zipWithIndex.flatMap { (t, i) =>
               val tp = at(path, s"tables[$i]")
               if t == null then Vector(InvalidValue(tp, "must not be null"))
               else
                 val supported = SchemaVersions.supportedFor(t.recordType)
                 check(
                   t.tableName != null && TableName.matches(t.tableName),
                   InvalidValue(s"$tp.tableName", s"'${t.tableName}' must match [a-z][a-z0-9_]*")
                 ) ++
                   check(
                     supported.nonEmpty,
                     InvalidValue(s"$tp.recordType", s"'${t.recordType}' is not a core record type")
                   ) ++
                   (if supported.isEmpty then ok
                    else
                      schemaVersion(
                        s"$tp.recordSchemaVersion",
                        t.recordType,
                        t.recordSchemaVersion
                      )) ++
                   nonBlank(s"$tp.location", t.location) ++
                   nonBlank(s"$tp.format", t.format) ++
                   (if t.partitionColumns == null then
                      Vector(InvalidValue(s"$tp.partitionColumns", "must not be null"))
                    else
                      t.partitionColumns.zipWithIndex.flatMap((c, j) =>
                        nonBlank(s"$tp.partitionColumns[$j]", c)
                      ) ++ distinct(s"$tp.partitionColumns", t.partitionColumns)) ++
                   nonNegative(s"$tp.rowCount", t.rowCount) ++
                   (if t.files == null then Vector(InvalidValue(s"$tp.files", "must not be null"))
                    else
                      check(
                        t.files.nonEmpty || t.rowCount == 0,
                        InvalidValue(s"$tp.files", "a table with rows lists its files")
                      ) ++
                        t.files.zipWithIndex.flatMap { (f, j) =>
                          val fp = s"$tp.files[$j]"
                          if f == null then Vector(InvalidValue(fp, "must not be null"))
                          else
                            nonBlank(s"$fp.path", f.path) ++
                              nonNegative(s"$fp.sizeBytes", f.sizeBytes) ++
                              textHash(s"$fp.checksum", f.checksum)
                        } ++
                        sortedDistinct(
                          s"$tp.files",
                          t.files.filter(_ != null).map(f => String.valueOf(f.path))
                        ))
             })

  private def conflictValues(path: String, d: IdentityConflictDetail): Errors =
    val vs = d.values
    def atLeast(n: Int) =
      check(vs.size >= n, InvalidValue(path, s"${d.reason} needs at least $n values"))
    def normalized(normalize: String => Either[String, String]) =
      vs.zipWithIndex.flatMap((v, i) =>
        normalize(v).fold(
          r => Vector(InvalidValue(s"$path[$i]", r)),
          n => check(n == v, InvalidValue(s"$path[$i]", s"'$v' is not normalized ('$n')"))
        )
      )
    sortedDistinct(path, vs) ++ (d.reason match
      case IdentityConflictReason.SAME_WORK_MENTIONS => atLeast(1) ++ ids(path, vs, IdKind.Work)
      case IdentityConflictReason.MULTIPLE_ORCIDS =>
        atLeast(2) ++ normalized(ExternalIds.normalizeOrcid)
      case IdentityConflictReason.MULTIPLE_OPENALEX_AUTHOR_IDS =>
        atLeast(2) ++ normalized(ExternalIds.normalizeOpenAlex(_, 'A'))
      case IdentityConflictReason.MULTIPLE_DBLP_PIDS =>
        atLeast(2) ++ normalized(ExternalIds.normalizeDblpPid)
      case IdentityConflictReason.MULTIPLE_DBLP_AUTHOR_KEYS =>
        atLeast(2) ++ vs.zipWithIndex.flatMap((v, i) => nonBlank(s"$path[$i]", v))
      case IdentityConflictReason.CLUSTER_TOO_LARGE =>
        check(vs.isEmpty, InvalidValue(path, "CLUSTER_TOO_LARGE carries no values")))

  given RecordContract[IdentityConflict] with
    val recordType = "IdentityConflict"
    def violations(c: IdentityConflict, path: String) =
      val members = c.memberIds
      val memberErrors =
        ids(at(path, "memberIds"), members, IdKind.AuthorMention, IdKind.Researcher)
      val details = Option(c.details).getOrElse(Vector.empty)
      val reasons = details.filter(d => d != null && d.reason != null).map(_.reason)
      common(path, recordType, c.schemaVersion, c.runId, c.provenance) ++
        id(at(path, "conflictId"), c.conflictId, IdKind.IdentityConflict) ++
        memberErrors ++
        (if members == null then ok
         else
           sortedDistinct(at(path, "memberIds"), members) ++
             check(
               members.size >= 2,
               InvalidValue(at(path, "memberIds"), "at least two members")
             )) ++
        check(
          members == null || c.clusterSize == members.size,
          InvalidValue(at(path, "clusterSize"), "must equal the number of members")
        ) ++
        check(c.maxClusterSize >= 1, InvalidValue(at(path, "maxClusterSize"), "must be >= 1")) ++
        notNull(at(path, "details"), c.details) ++
        check(
          c.details == null || details.nonEmpty,
          InvalidValue(at(path, "details"), "at least one reason")
        ) ++
        details.zipWithIndex.flatMap { (d, i) =>
          val dp = at(path, s"details[$i]")
          if d == null then Vector(InvalidValue(dp, "must not be null"))
          else if d.reason == null then Vector(InvalidValue(s"$dp.reason", "must not be null"))
          else if d.values == null then Vector(InvalidValue(s"$dp.values", "must not be null"))
          else conflictValues(s"$dp.values", d)
        } ++
        check(
          reasons.distinct.size == reasons.size && reasons == reasons.sortBy(_.ordinal),
          InvalidValue(at(path, "details"), "one detail per reason, in reason declaration order")
        ) ++
        check(
          reasons.contains(IdentityConflictReason.CLUSTER_TOO_LARGE) ==
            (c.clusterSize > c.maxClusterSize),
          InvalidValue(
            at(path, "details"),
            "CLUSTER_TOO_LARGE must be present exactly when clusterSize > maxClusterSize"
          )
        ) ++
        ids(at(path, "matchDecisionIds"), c.matchDecisionIds, IdKind.MatchDecision) ++
        (if c.matchDecisionIds == null then ok
         else sortedDistinct(at(path, "matchDecisionIds"), c.matchDecisionIds)) ++
        check(
          c.identityVersion != null && IdKind.VersionTag.matches(c.identityVersion),
          InvalidValue(at(path, "identityVersion"), s"'${c.identityVersion}' must match v<N>")
        ) ++
        nonBlank(at(path, "policyVersion"), c.policyVersion) ++
        (if c.conflictId != null && members != null && memberErrors.isEmpty then
           matchesFormula(
             at(path, "conflictId"),
             c.conflictId,
             StableIds.identityConflictId(c.identityVersion, c.policyVersion, members)
           )
         else ok)

  given RecordContract[WindowGraphSummary] with
    val recordType = "WindowGraphSummary"
    def violations(s: WindowGraphSummary, path: String) =
      val hist = Option(s.communitySizeHistogram).getOrElse(Vector.empty).filter(_ != null)
      common(path, recordType, s.schemaVersion, s.runId, s.provenance) ++
        win(path, s.windowStartYear, s.windowEndYear) ++
        Vector(
          "researcherCount" -> s.researcherCount,
          "workCount" -> s.workCount,
          "yearlessWorkCount" -> s.yearlessWorkCount,
          "coauthorEdgeCount" -> s.coauthorEdgeCount,
          "selfPairsDropped" -> s.selfPairsDropped,
          "oversizeTeamWorkCount" -> s.oversizeTeamWorkCount,
          "componentCount" -> s.componentCount,
          "largestComponentSize" -> s.largestComponentSize,
          "singletonComponentCount" -> s.singletonComponentCount
        ).flatMap((f, v) => nonNegative(at(path, f), v)) ++
        check(
          s.yearlessWorkCount <= s.workCount,
          InvalidValue(at(path, "yearlessWorkCount"), "exceeds workCount")
        ) ++
        check(
          s.allYearsWindow || s.yearlessWorkCount == 0,
          InvalidValue(
            at(path, "yearlessWorkCount"),
            "only the all-years window contains works with no year"
          )
        ) ++
        check(
          s.componentCount <= s.researcherCount &&
            s.singletonComponentCount <= s.componentCount &&
            s.largestComponentSize <= s.researcherCount,
          InvalidValue(
            at(path, "componentCount"),
            "need singletons <= components <= researchers and largest <= researchers"
          )
        ) ++
        check(
          (s.researcherCount == 0) == (s.componentCount == 0) &&
            (s.researcherCount == 0) == (s.largestComponentSize == 0),
          InvalidValue(
            at(path, "componentCount"),
            "a window with researchers has >= 1 component of size >= 1, an empty one has none"
          )
        ) ++
        noneOrSome(at(path, "communityCount"), s.communityCount) ++
        notNull(at(path, "communitySizeHistogram"), s.communitySizeHistogram) ++
        hist.zipWithIndex.flatMap { (h, i) =>
          check(
            h.size >= 1 && h.count >= 1,
            InvalidValue(at(path, s"communitySizeHistogram[$i]"), "size and count must be >= 1")
          )
        } ++
        check(
          hist.map(_.size).distinct.size == hist.size && hist == hist.sortBy(_.size),
          InvalidValue(at(path, "communitySizeHistogram"), "sizes must be distinct and ascending")
        ) ++
        (if s.communityCount == null then ok
         else
           s.communityCount match
             case None =>
               check(
                 hist.isEmpty,
                 InvalidValue(at(path, "communitySizeHistogram"), "requires communityCount")
               )
             case Some(n) =>
               check(
                 hist.map(_.count).sum == n &&
                   hist.map(h => h.size * h.count).sum == s.researcherCount,
                 InvalidValue(
                   at(path, "communitySizeHistogram"),
                   "counts must sum to communityCount and sizes to researcherCount"
                 )
               )) ++
        noneOrSome(at(path, "maxCoreness"), s.maxCoreness) ++
        (if s.maxCoreness == null then ok
         else
           s.maxCoreness.toVector.flatMap(k =>
             check(k >= 0, InvalidValue(at(path, "maxCoreness"), "must be >= 0"))
           )) ++
        (if s.iterations == null then
           Vector(InvalidValue(at(path, "iterations"), "must not be null"))
         else
           s.iterations.zipWithIndex.flatMap { (r, i) =>
             val rp = at(path, s"iterations[$i]")
             if r == null then Vector(InvalidValue(rp, "must not be null"))
             else
               nonBlank(s"$rp.algorithm", r.algorithm) ++
                 check(r.maxIterations >= 1, InvalidValue(s"$rp.maxIterations", "must be >= 1")) ++
                 check(
                   r.iterations >= 0 && r.iterations <= r.maxIterations,
                   InvalidValue(s"$rp.iterations", "must lie in [0, maxIterations]")
                 )
           } ++ distinct(
             at(path, "iterations"),
             s.iterations.filter(_ != null).map(r => String.valueOf(r.algorithm))
           )) ++
        nonBlank(at(path, "metricVersion"), s.metricVersion)

  private def snapshot(path: String, o: Option[ResearcherWindowSnapshot]): Errors =
    if o == null then Vector(InvalidValue(path, "null option"))
    else
      o.toVector.flatMap { s =>
        if s == null then Vector(InvalidValue(path, "must not be null"))
        else
          val opts = Vector(
            "communitySize" -> s.communitySize,
            "crossCommunityDegree" -> s.crossCommunityDegree,
            "communitiesConnected" -> s.communitiesConnected,
            "participationCoefficient" -> s.participationCoefficient,
            "coreness" -> s.coreness
          ).flatMap((f, v) => noneOrSome(s"$path.$f", v))
          nonNegative(s"$path.publicationCount", s.publicationCount) ++
            nonNegative(s"$path.distinctCoauthorCount", s.distinctCoauthorCount) ++
            finiteNonNegative(s"$path.averageCoauthorsPerPaper", s.averageCoauthorsPerPaper) ++
            nonNegative(s"$path.maximumCoauthorsOnOnePaper", s.maximumCoauthorsOnOnePaper) ++
            check(s.componentSize >= 1, InvalidValue(s"$path.componentSize", "must be >= 1")) ++
            opts ++
            (if opts.nonEmpty then ok
             else
               s.communitySize.toVector.flatMap(v =>
                 check(v >= 1, InvalidValue(s"$path.communitySize", "must be >= 1"))
               ) ++
                 s.crossCommunityDegree.toVector.flatMap(
                   nonNegative(s"$path.crossCommunityDegree", _)
                 ) ++
                 s.communitiesConnected.toVector.flatMap(
                   nonNegative(s"$path.communitiesConnected", _)
                 ) ++
                 s.participationCoefficient.toVector.flatMap(v =>
                   check(
                     !v.isNaN && v >= 0.0 && v <= 1.0,
                     InvalidValue(s"$path.participationCoefficient", s"$v is outside [0, 1]")
                   )
                 ) ++
                 s.coreness.toVector.flatMap(v =>
                   check(v >= 0, InvalidValue(s"$path.coreness", "must be >= 0"))
                 ))
      }

  given RecordContract[ResearcherWindowChange] with
    val recordType = "ResearcherWindowChange"
    def violations(c: ResearcherWindowChange, path: String) =
      val fromErrors =
        window(
          at(path, "fromWindowStartYear,fromWindowEndYear"),
          c.fromWindowStartYear,
          c.fromWindowEndYear
        )
      val toErrors =
        window(
          at(path, "toWindowStartYear,toWindowEndYear"),
          c.toWindowStartYear,
          c.toWindowEndYear
        )
      common(path, recordType, c.schemaVersion, c.runId, c.provenance) ++
        id(at(path, "researcherId"), c.researcherId, IdKind.Researcher) ++
        fromErrors ++
        toErrors ++
        check(
          fromErrors.nonEmpty || toErrors.nonEmpty || c.fromWindowEndYear < c.toWindowStartYear,
          InvalidValue(
            at(path, "toWindowStartYear"),
            "the from window must end before the to window"
          )
        ) ++
        snapshot(at(path, "from"), c.from) ++
        snapshot(at(path, "to"), c.to) ++
        check(
          c.from == null || c.to == null || c.from.isDefined || c.to.isDefined,
          InvalidValue(at(path, "from,to"), "at least one window snapshot is required")
        ) ++
        nonBlank(at(path, "metricVersion"), c.metricVersion)
