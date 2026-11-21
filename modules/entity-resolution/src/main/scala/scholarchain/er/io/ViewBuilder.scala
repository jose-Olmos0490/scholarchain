package scholarchain.er.io

import scholarchain.core.model.*
import scholarchain.er.ids.{ErIdType, ExactIdRules}
import scholarchain.er.model.*
import scholarchain.er.policy.PairInput

/** Assembles read-only comparison views from Phase 4 records, and the pairs to decide. */
object ViewBuilder:

  private def indexed(props: Map[String, String], prefix: String): Vector[String] =
    props.toVector
      .collect {
        case (k, v) if k == prefix || k.startsWith(prefix + ".") =>
          val idx = k.stripPrefix(prefix).stripPrefix(".").takeWhile(_.isDigit)
          (if idx.isEmpty then -1 else idx.toInt, k, v)
      }
      .sortBy(x => (x._1, x._2))
      .map(_._3)
      .filter(_.trim.nonEmpty)

  private def add(
      m: Map[ErIdType, Set[String]],
      t: ErIdType,
      raw: Option[String]
  ): Map[ErIdType, Set[String]] =
    raw.flatMap(r => ExactIdRules.normalize(t, r).toOption) match
      case Some(v) => m.updated(t, m.getOrElse(t, Set.empty) + v)
      case None => m

  private def addLinks(m: Map[ErIdType, Set[String]], links: Vector[String]) =
    links.flatMap(ExactIdRules.classifyWorkLink).foldLeft(m) { case (acc, (t, v)) =>
      acc.updated(t, acc.getOrElse(t, Set.empty) + v)
    }

  /** Work identifiers: the record's DOI and arXiv ID, its dblp key (dblp records), the OpenAlex
    * work ID, and every link on the work node that classifies as an identifier (`ee.*`,
    * `ids.*`, `URL`, `landingPageUrl`). Crossref `alternativeId` is not used: it can hold the
    * proceedings-volume DOI rather than the article's.
    */
  def workIds(w: WorkRecord, node: Option[GraphNode]): Map[ErIdType, Set[String]] =
    val props = node.map(_.properties).getOrElse(Map.empty)
    var m = Map.empty[ErIdType, Set[String]]
    m = add(m, ErIdType.DOI, w.doi)
    m = add(m, ErIdType.ARXIV, w.arxivId)
    if w.source == SourceType.DBLP then m = add(m, ErIdType.DBLP_KEY, Some(w.sourceRecordId))
    if w.source == SourceType.OPENALEX then
      m = add(m, ErIdType.OPENALEX_WORK, props.get("openalexWorkId").orElse(Some(w.sourceRecordId)))
    m = add(m, ErIdType.OPENALEX_WORK, props.get("ids.openalex"))
    val links = indexed(props, "ee") ++ props.toVector.collect {
      case (k, v) if k.startsWith("ids.") => v
    }.sorted ++ props.get("URL").toVector ++ props.get("landingPageUrl").toVector
    addLinks(m, links)

  def workView(
      w: WorkRecord,
      node: Option[GraphNode],
      venuesById: Map[String, Venue]
  ): WorkView =
    val props = node.map(_.properties).getOrElse(Map.empty)
    val venueStrings =
      (w.venueId.flatMap(venuesById.get).map(_.name).toVector ++
        props.get("rawSourceName").toVector ++
        indexed(props, "booktitle") ++ indexed(props, "journal") ++
        indexed(props, "containerTitle") ++ props.get("event.acronym").toVector ++
        props.get("event.name").toVector).filter(_.trim.nonEmpty).distinct
    val refs =
      indexed(props, "referencedWork").flatMap(r =>
        ExactIdRules.normalizeOpenAlex(r, 'W').toOption.map("openalex:" + _)
      ) ++ indexed(props, "referenceDoi").flatMap(r =>
        ExactIdRules.normalizeDoi(r).toOption.map("doi:" + _)
      )
    WorkView(
      ref = w.workId,
      source = w.source,
      sourceRecordId = w.sourceRecordId,
      snapshot = w.provenance.snapshot,
      title = w.title,
      year = w.year,
      authorNames = w.authors.sortBy(_.position).map(_.rawName),
      venues = venueStrings,
      ids = workIds(w, node),
      references = refs.toSet
    )

  /** Researcher views of every author mention of `w`. */
  def mentionViews(
      w: WorkRecord,
      work: WorkView,
      mentionNodes: Map[String, GraphNode],
      institutions: Map[String, Institution]
  ): Vector[ResearcherView] =
    val authors = w.authors.sortBy(_.position)
    authors.map { a =>
      val props = mentionNodes.get(a.mentionId).map(_.properties).getOrElse(Map.empty)
      val affiliations =
        (indexed(props, "rawAffiliation") ++ indexed(props, "affiliation") ++
          indexed(props, "institutionId").flatMap(i => institutions.get(i).map(_.name))).distinct
      var ids = Map.empty[ErIdType, Set[String]]
      ids = add(ids, ErIdType.ORCID, a.orcid)
      ids = add(ids, ErIdType.ORCID, props.get("orcidRaw"))
      ids = add(ids, ErIdType.OPENALEX_AUTHOR, props.get("openalexAuthorId"))
      ids = add(ids, ErIdType.SEMANTIC_SCHOLAR_AUTHOR, props.get("semanticScholarAuthorId"))
      if w.source == SourceType.DBLP then
        ids = add(ids, ErIdType.DBLP_AUTHOR_KEY, a.sourceAuthorKey)
      ResearcherView(
        ref = a.mentionId,
        source = w.source,
        sourceRecordId = w.sourceRecordId,
        snapshot = a.provenance.snapshot,
        name = a.rawName,
        position = Some(a.position),
        teamSize = Some(authors.size),
        work = Some(work),
        coauthorNames = authors.filter(_.mentionId != a.mentionId).map(_.rawName),
        affiliations = affiliations,
        ids = ids,
        workIds = Map.empty
      )
    }

  /** Researcher view of a source researcher profile node (ORCID record, OpenAlex author). */
  def researcherNodeView(n: GraphNode): ResearcherView =
    val p = n.properties
    val name = (p.get("givenNames"), p.get("familyName")) match
      case (Some(g), Some(f)) => s"$g $f"
      case _ => n.label
    var ids = Map.empty[ErIdType, Set[String]]
    ids = add(ids, ErIdType.ORCID, p.get("orcid"))
    ids = add(ids, ErIdType.ORCID, p.get("ids.orcid"))
    ids = add(ids, ErIdType.OPENALEX_AUTHOR, p.get("openalexAuthorId"))
    ids = add(ids, ErIdType.OPENALEX_AUTHOR, p.get("ids.openalex"))
    var works = Map.empty[ErIdType, Set[String]]
    p.toVector.sortBy(_._1).foreach { (k, v) =>
      if k.startsWith("workExternalId.") && k.endsWith(".normalizedDoi") then
        works = add(works, ErIdType.DOI, Some(v))
    }
    val affiliations = p.toVector.sortBy(_._1).collect {
      case (k, v) if k.startsWith("affiliation.") && k.endsWith(".organization") => v
    } ++ indexed(p, "lastKnownInstitution")
    ResearcherView(
      ref = n.nodeId,
      source = n.provenance.sourceDataset,
      sourceRecordId = n.provenance.sourceRecordId,
      snapshot = n.provenance.snapshot,
      name = name,
      position = None,
      teamSize = None,
      work = None,
      coauthorNames = Vector.empty,
      affiliations = affiliations.distinct,
      ids = ids,
      workIds = works
    )

  /** All views by stable ref. */
  def views(d: Phase4Data): Map[String, EntityView] =
    val venues = d.venues.map(v => v.venueId -> v).toMap
    val workNodes = d.workNodes.map(n => n.nodeId -> n).toMap
    val mentionNodes = d.mentionNodes.map(n => n.nodeId -> n).toMap
    val institutions = d.institutions.map(i => i.institutionId -> i).toMap
    val works = d.works.sortBy(_.workId).flatMap { w =>
      val wv = workView(w, workNodes.get(w.workId), venues)
      (wv: EntityView) +: mentionViews(w, wv, mentionNodes, institutions)
    }
    val researchers = d.researcherNodes.map(researcherNodeView)
    (works ++ researchers).map(v => v.ref -> v).toMap

  /** Counters from pair assembly. */
  final case class PairStats(
      fromCandidates: Int,
      fromExactOutcomesOnly: Int,
      unknownRefs: Int,
      skippedSameWork: Int
  )

  /** Pairs to decide: every Phase 4 match candidate, plus every pair inside an exact-identifier
    * group that blocking did not already produce. One pair per (entity type, left, right);
    * sorted by entity type, then refs.
    */
  def pairs(d: Phase4Data, views: Map[String, EntityView]): (Vector[PairInput], PairStats) =
    var unknown = 0
    var sameWork = 0
    type Key = (MatchEntityType, String, String)
    val byKey = scala.collection.mutable.LinkedHashMap.empty[Key, PairInput]
    def ordered(x: String, y: String) = if x.compareTo(y) < 0 then (x, y) else (y, x)
    d.candidates.sortBy(_.candidateId).foreach { c =>
      (views.get(c.leftRef), views.get(c.rightRef)) match
        case (Some(l), Some(r)) =>
          byKey.update(
            (c.entityType, c.leftRef, c.rightRef),
            PairInput(
              candidateId = c.candidateId,
              kind = c.entityType,
              left = l,
              right = r,
              hadoopFeatures = c.features,
              hadoopFeatureVersion = Some(c.featureVersion),
              exactOutcomes = Vector.empty,
              snapshot = c.provenance.snapshot
            )
          )
        case _ => unknown += 1
    }
    val fromCandidates = byKey.size
    var exactOnly = 0
    d.exactOutcomes.sortBy(_.outcomeId).foreach { o =>
      val kind =
        if o.idType.identifiesResearchers then MatchEntityType.RESEARCHER else MatchEntityType.WORK
      val refs = o.recordRefs.distinct.sorted
      for
        i <- refs.indices
        j <- (i + 1) until refs.size
      do
        val (a, b) = ordered(refs(i), refs(j))
        val key = (kind, a, b)
        byKey.get(key) match
          case Some(existing) =>
            byKey.update(key, existing.copy(exactOutcomes = existing.exactOutcomes :+ o))
          case None =>
            (views.get(a), views.get(b)) match
              // Two mentions on one work are two people by construction; such a pair is kept
              // only when the identifier group is a CONFLICT (one ID asserted for two authors).
              case (Some(l: ResearcherView), Some(r: ResearcherView))
                  if l.work.isDefined && l.work.map(_.ref) == r.work.map(_.ref) &&
                    o.outcome != ExactIdOutcome.CONFLICT =>
                sameWork += 1
              case (Some(l), Some(r)) if l.kind == kind && r.kind == kind =>
                exactOnly += 1
                byKey.update(
                  key,
                  PairInput(
                    candidateId = o.outcomeId,
                    kind = kind,
                    left = l,
                    right = r,
                    hadoopFeatures = Map.empty,
                    hadoopFeatureVersion = None,
                    exactOutcomes = Vector(o),
                    snapshot = o.provenance.snapshot
                  )
                )
              case _ => unknown += 1
    }
    val sorted = byKey.values.toVector.sortBy(p => (p.kind.toString, p.left.ref, p.right.ref))
    (sorted, PairStats(fromCandidates, exactOnly, unknown, sameWork))
