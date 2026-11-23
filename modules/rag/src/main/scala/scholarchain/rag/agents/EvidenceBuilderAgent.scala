package scholarchain.rag.agents

import scholarchain.core.config.RetrievalConfig
import scholarchain.core.ids.StableIds
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV
import scholarchain.core.validation.RecordContract
import scholarchain.rag.common.Hashing
import scholarchain.rag.context.SemanticContextItem
import scholarchain.rag.render.TextSanitizer

/** Everything the deterministic stages and the semantic stage produced for one query. */
final case class EvidenceInputs(
    identities: Vector[CandidateView] = Vector.empty,
    metrics: Vector[MetricsReport] = Vector.empty,
    rankings: Vector[RankingReport] = Vector.empty,
    coauthors: Vector[CoauthorPageReport] = Vector.empty,
    paths: Vector[PathReport] = Vector.empty,
    communities: Vector[CommunityReport] = Vector.empty,
    components: Vector[ComponentMembership] = Vector.empty,
    semantic: Vector[SemanticHit] = Vector.empty
)

/** What the builder did with the items it was given.
  *
  * @param droppedNoProvenance
  *   items without a valid provenance record
  * @param droppedInvalid
  *   items that could not be given a stable ID or that fail the evidence contract
  * @param droppedDuplicate
  *   items whose evidence ID was already in the bundle
  * @param droppedOverBudget
  *   items that did not fit `retrieval.evidenceMaxItems` or `retrieval.contextTokenBudget`
  * @param estimatedTokens
  *   estimated tokens of the kept items
  */
final case class EvidenceStats(
    considered: Int,
    kept: Int,
    droppedNoProvenance: Int,
    droppedInvalid: Int,
    droppedDuplicate: Int,
    droppedOverBudget: Int,
    estimatedTokens: Int
)

final case class BuiltEvidence(bundle: EvidenceBundle, stats: EvidenceStats)

/** Estimated tokens of a text: one token per four characters, rounded up. A conservative
  * stand-in for the model tokenizer, used only to keep a bundle inside its budget.
  */
object TokenEstimator:
  def estimate(text: String): Int = (text.length + 3) / 4

/** Turns stage results into a bounded [[EvidenceBundle]].
  *
  * Deterministic evidence (identity, metric, edge, path, community and component records) goes in
  * `graphEvidence`; semantic context goes in `semanticEvidence`, and the two never mix. Every
  * item has a stable evidence ID from core, a provenance record, and a text that is rendered
  * from stored fields (and sanitized source text). An item without valid provenance is dropped
  * and counted, never repaired.
  *
  * The bundle holds at most `retrieval.evidenceMaxItems` items and an estimated
  * `retrieval.contextTokenBudget` tokens. Items are considered in a fixed order: identity,
  * metric, edge, path, community and component items (deterministic section, input order), then
  * semantic items by descending score. An item that does not fit is dropped and counted; later,
  * smaller items may still fit.
  */
final class EvidenceBuilderAgent(retrieval: RetrievalConfig, snapshot: String):
  private enum Drop:
    case NoProvenance, Invalid

  final private case class Cand(
      item: Either[Drop, EvidenceItem],
      identity: Option[(String, Double)] = None,
      score: Double = 0.0
  )

  def build(queryId: String, runId: String, inputs: EvidenceInputs): SResult[BuiltEvidence] =
    val deterministic =
      inputs.identities.map(identity) ++
        inputs.metrics.map(metric) ++
        inputs.rankings.flatMap(ranking) ++
        inputs.coauthors.flatMap(coauthors) ++
        inputs.paths.flatMap(pathItems) ++
        inputs.communities.map(community) ++
        inputs.components.map(component)
    val semantic = inputs.semantic.map(semanticItem).sortBy(c => -c.score)
    val all = deterministic ++ semantic

    var tokens = 0
    val seen = scala.collection.mutable.Set.empty[String]
    val kept = Vector.newBuilder[Cand]
    var keptCount = 0
    var noProv, invalid, dup, over = 0
    all.foreach { c =>
      c.item match
        case Left(Drop.NoProvenance) => noProv += 1
        case Left(Drop.Invalid) => invalid += 1
        case Right(e) =>
          val cost =
            TokenEstimator.estimate(e.text.getOrElse("")) + TokenEstimator.estimate(e.sourceUri)
          if seen.contains(e.evidenceId) then dup += 1
          else if keptCount >= retrieval.evidenceMaxItems || tokens + cost > retrieval.contextTokenBudget
          then over += 1
          else
            seen += e.evidenceId
            tokens += cost
            keptCount += 1
            kept += c
    }
    val keptItems = kept.result()
    val items = keptItems.flatMap(_.item.toOption)
    val (graph, sem) = items.partition(_.evidenceType.isGraphFact)
    val confidence = keptItems.flatMap(_.identity).toMap
    val stats =
      EvidenceStats(all.size, items.size, noProv, invalid, dup, over, tokens)
    for
      bundleId <- StableIds
        .bundleId(queryId, items.map(_.evidenceId))
        .left
        .map(m => StageFailure.Rejected("BUNDLE_INVALID", m))
      bundle = EvidenceBundle(
        SV.EvidenceBundle,
        bundleId,
        queryId,
        graph,
        sem,
        confidence,
        Provenance(
          SV.Provenance,
          SourceType.SCHOLARCHAIN,
          queryId,
          snapshot,
          runId,
          Some(retrieval.fusionPolicyVersion),
          None,
          None,
          items.map(_.evidenceId)
        )
      )
      _ <- {
        val violations = RecordContract[EvidenceBundle].violations(bundle, "")
        if violations.isEmpty then Right(())
        else
          Left(
            StageFailure.Rejected("BUNDLE_INVALID", violations.map(_.path).distinct.mkString(", "))
          )
      }
    yield BuiltEvidence(bundle, stats)

  // ---- item construction --------------------------------------------------------------------

  private def make(
      evidenceType: EvidenceType,
      referencedIds: Vector[String],
      sourceUri: String,
      text: String,
      provenance: Option[Provenance],
      graphPath: Option[Vector[String]] = None,
      chunkId: Option[String] = None,
      score: Option[Double] = None
  ): Either[Drop, EvidenceItem] =
    provenance match
      case None => Left(Drop.NoProvenance)
      case Some(p) if p.runId == null => Left(Drop.NoProvenance)
      case Some(p) =>
        if text.trim.isEmpty then Left(Drop.Invalid)
        else
          StableIds.evidenceId(evidenceType, referencedIds, p.runId) match
            case Left(_) =>
              // a malformed run ID is a provenance defect; any other formula failure is not
              if p.runId.startsWith("run:") then Left(Drop.Invalid) else Left(Drop.NoProvenance)
            case Right(id) =>
              val item = EvidenceItem(
                SV.EvidenceItem,
                id,
                evidenceType,
                referencedIds,
                None,
                sourceUri,
                chunkId,
                graphPath,
                score,
                Some(Hashing.textHash(text)),
                Some(text),
                p
              )
              val violations = RecordContract[EvidenceItem].violations(item, "")
              if violations.isEmpty then Right(item)
              else if violations.exists(_.path.startsWith("provenance")) then
                Left(Drop.NoProvenance)
              else Left(Drop.Invalid)

  private def win(ws: Int, we: Int): String = f"$ws%04d-$we%04d"

  private def num(d: Double): String =
    if d.isNaN || d.isInfinite then d.toString
    else
      BigDecimal(d)
        .setScale(6, BigDecimal.RoundingMode.HALF_UP)
        .bigDecimal
        .stripTrailingZeros
        .toPlainString

  private def bridgeText(b: Option[BridgeValue]): String =
    b.fold("none")(x => s"${x.metric}=${num(x.value)} (${x.definition})")

  private def metricsLine(m: MetricsReport): String =
    s"P_r=${m.publicationCount} C_r=${m.distinctCoauthorCount} T_r=${m.totalCoauthorOccurrences} " +
      s"A_r=${num(m.averageCoauthorsPerPaper)} M_r=${m.maximumCoauthorsOnOnePaper} " +
      s"W_r=${num(m.weightedDegree)} component=${m.componentId} (size ${m.componentSize}) " +
      s"community=${m.communityId.getOrElse("none")} bridge=${bridgeText(m.bridge)} " +
      s"coreness=${m.coreness.fold("none")(_.toString)} metricVersion=${m.metricVersion} " +
      s"maximumTeamWorks=[${m.maximumTeamWorkIds.mkString(",")}]"

  private def metric(m: MetricsReport): Cand =
    Cand(
      make(
        EvidenceType.METRIC_RECORD,
        Vector(m.metricsId),
        s"store:researcherMetricCache/researcher#${m.researcherId}/w#${win(m.windowStartYear, m.windowEndYear)}",
        s"researcher ${m.researcherId} window ${win(m.windowStartYear, m.windowEndYear)}: ${metricsLine(m)}",
        Some(m.provenance)
      )
    )

  /** One item per ranking: the ranked metrics rows in rank order. Entries after the first one
    * without a stored row, or with another run ID, are not cited, so the cited list is always a
    * true prefix of the stored ranking.
    */
  private def ranking(r: RankingReport): Vector[Cand] =
    val firstRun = r.entries.headOption.flatMap(_.record).map(_.runId)
    val prefix = r.entries.takeWhile(e => e.record.exists(x => firstRun.contains(x.runId)))
    if prefix.isEmpty then
      if r.entries.isEmpty then Vector.empty else Vector(Cand(Left(Drop.NoProvenance)))
    else
      val rows = prefix.flatMap(e => e.record.map(e -> _))
      val lines = rows.map { (e, rec) =>
        val works =
          if r.metric == CollaborationMetric.MAXIMUM_COAUTHORS_ON_ONE_PAPER then
            s" maximumTeamWorks=[${rec.maximumTeamWorkIds.mkString(",")}]"
          else ""
        s"${e.rank}. researcher ${e.researcherId} ${TextSanitizer.quoted("name", e.displayName, 200)} value=${num(e.metricValue)}$works"
      }
      val topics = if r.topicIds.isEmpty then "" else s" topics=[${r.topicIds.mkString(",")}]"
      val text =
        s"ranking by ${r.metric} window ${win(r.windowStartYear, r.windowEndYear)} order ${r.ordering}$topics, " +
          s"top ${prefix.size} of the stored ranking: ${lines.mkString("; ")}"
      val ids = rows.map(_._2.metricsId)
      val head = rows.head._2
      val prov = Provenance(
        SV.Provenance,
        SourceType.SCHOLARCHAIN,
        head.metricsId,
        snapshot,
        head.runId,
        None,
        None,
        None,
        ids
      )
      Vector(
        Cand(
          make(
            EvidenceType.METRIC_RECORD,
            ids,
            s"store:metricReader/topK/${r.metric}/w#${win(r.windowStartYear, r.windowEndYear)}",
            text,
            Some(prov)
          )
        )
      )

  private def coauthors(p: CoauthorPageReport): Vector[Cand] =
    p.coauthors.map { c =>
      Cand(
        make(
          EvidenceType.COAUTHOR_EDGE,
          Vector(c.edgeId),
          s"store:graph/coauthor-edge/${c.edgeId}",
          s"edge ${c.edgeId}: researchers ${p.researcherId} and ${c.coauthorId} window " +
            s"${win(p.windowStartYear, p.windowEndYear)} jointPublicationCount=${c.jointPublicationCount} " +
            s"firstCollaborationYear=${c.firstCollaborationYear} lastCollaborationYear=${c.lastCollaborationYear} " +
            s"supportingWorks=[${c.supportingWorkIds.mkString(",")}]",
          Some(c.provenance)
        )
      )
    }

  private def pathItems(r: PathReport): Vector[Cand] =
    r.paths.map { p =>
      val runs = p.hops.map(_.provenance.runId).distinct
      if p.hops.isEmpty || runs.size != 1 then Cand(Left(Drop.Invalid))
      else
        val hops = p.hops.zipWithIndex.map { (h, i) =>
          s"hop ${i + 1}: ${h.fromResearcherId} to ${h.toResearcherId} edge ${h.edgeId} " +
            s"jointPublicationCount=${h.jointPublicationCount} years ${h.firstCollaborationYear}-${h.lastCollaborationYear} " +
            s"supportingWorks=[${h.supportingWorkIds.mkString(",")}]"
        }
        val prov = Provenance(
          SV.Provenance,
          SourceType.SCHOLARCHAIN,
          p.hops.head.edgeId,
          snapshot,
          runs.head,
          p.hops.head.provenance.policyVersion,
          None,
          None,
          p.hops.map(_.edgeId)
        )
        Cand(
          make(
            EvidenceType.GRAPH_PATH,
            p.hops.map(_.edgeId),
            s"store:graph/path/${r.source}/${r.target}/w#${win(r.windowStartYear, r.windowEndYear)}",
            s"path of length ${p.length} from ${r.source} to ${r.target} window " +
              s"${win(r.windowStartYear, r.windowEndYear)}: ${hops.mkString("; ")}",
            Some(prov),
            graphPath = Some(p.researcherIds)
          )
        )
    }

  private def community(c: CommunityReport): Cand =
    val m = c.membership
    val refs = StableIds.metricsId(
      c.researcherId,
      c.windowStartYear,
      c.windowEndYear,
      m.metricVersion,
      m.runId
    )
    Cand(
      refs.fold(
        _ => Left(Drop.Invalid),
        id =>
          make(
            EvidenceType.COMMUNITY_RECORD,
            Vector(id, c.communityId),
            s"store:analytics/community/${c.communityId}/w#${win(c.windowStartYear, c.windowEndYear)}",
            s"researcher ${c.researcherId} window ${win(c.windowStartYear, c.windowEndYear)}: community ${c.communityId} " +
              s"communitySize=${c.communitySize} " +
              s"storedCrossCommunityEdgeCount=${c.crossCommunityEdgeCount.fold("not stored")(_.toString)} " +
              s"storedCommunitiesConnected=${c.communitiesConnected.fold("not stored")(_.toString)} " +
              s"directDegree=${c.directDegree.fold("not stored")(_.toString)} " +
              s"bridge=${bridgeText(c.bridge)} metricVersion=${m.metricVersion}" +
              (if c.gaps.isEmpty then "" else s" gaps=[${c.gaps.mkString("; ")}]"),
            Some(m.provenance)
          )
      )
    )

  private def component(m: ComponentMembership): Cand =
    Cand(
      StableIds
        .metricsId(m.researcherId, m.windowStartYear, m.windowEndYear, m.metricVersion, m.runId)
        .fold(
          _ => Left(Drop.Invalid),
          id =>
            make(
              EvidenceType.COMPONENT_RECORD,
              Vector(id, m.componentId),
              s"store:analytics/component/${m.componentId}/w#${win(m.windowStartYear, m.windowEndYear)}",
              s"researcher ${m.researcherId} window ${win(m.windowStartYear, m.windowEndYear)}: " +
                s"component ${m.componentId} componentSize=${m.componentSize} metricVersion=${m.metricVersion}",
              Some(m.provenance)
            )
        )
    )

  private def identity(c: CandidateView): Cand =
    val prov = Provenance(
      SV.Provenance,
      SourceType.SCHOLARCHAIN,
      c.researcherId,
      snapshot,
      c.runId,
      Some(c.policyVersion),
      None,
      None,
      c.matchDecisionIds
    )
    val item = make(
      EvidenceType.MATCH_DECISION,
      c.matchDecisionIds,
      s"store:directory/researcher/${c.researcherId}",
      s"identity researcher ${c.researcherId} ${TextSanitizer.quoted("name", c.displayName, 200)} " +
        s"storedConfidence=${num(c.confidence)} policy=${c.policyVersion} sourceIds=[${c.sourceIds.mkString(",")}]" +
        (if c.affiliations.isEmpty then ""
         else s" ${TextSanitizer.quoted("affiliations", c.affiliations.mkString("; "), 400)}"),
      Some(prov)
    )
    Cand(item, identity = item.toOption.map(_ => c.researcherId -> c.confidence))

  private def semanticItem(h: SemanticHit): Cand =
    val i = h.item
    Cand(
      make(
        SemanticContextItem.evidenceType(i.documentType),
        Vector(h.chunkId),
        i.textS3Uri,
        i.excerpt,
        Some(h.provenance),
        chunkId = Some(h.chunkId),
        score = Some(i.score)
      ),
      score = i.score
    )
