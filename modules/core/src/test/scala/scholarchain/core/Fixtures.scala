package scholarchain.core

import scholarchain.core.ids.*
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions as SV

/** Representative records for contract tests and golden wire examples.
  *
  * Bibliographic identifiers are real and come from the committed dataset samples
  * (datasets/samples): dblp `conf/dac/NewtonRMSB00` (DOI 10.1145/337292.337434, DAC 2000, five
  * authors), OpenAlex authors A5023877407 (Henry Samueli, ORCID 0000-0003-3175-3913) and
  * A5110371381 (Walden C. Rhines), OpenAlex institution I177725633 (ROR 00t33hh48), OpenAlex topic
  * T10363 and the DAC ISSN 0738-100X. The run manifest's single input is the committed dblp
  * excerpt `datasets/samples/dblp/dblp-2026-11-01-excerpt.xml` with its real SHA-256 (from
  * datasets/samples/SHA256SUMS).
  *
  * Everything else (scores, confidences, metric values, match features and rules, component and
  * community IDs, timestamps, code version) is ILLUSTRATIVE and does not come from a pipeline
  * run. So that a golden file read on its own says so, every made-up policy, metric, plan, model,
  * prompt and code version string starts with `illustrative-`, and made-up rule and reason codes
  * start with `ILLUSTRATIVE_`.
  */
object Fixtures:
  def ok(e: Either[String, String]): String =
    e.fold(m => throw new IllegalStateException(m), identity)

  val DblpSnapshot = "2026-11-01"
  val OpenAlexApiSnapshot = "api-2026-11-20"

  val runManifest: RunManifest = RunManifest(
    schemaVersion = SV.RunManifest,
    pipelineStage = "hadoop-ingest",
    codeVersion = "illustrative-code-version",
    inputs = Vector(
      RunInput(
        sourceDataset = SourceType.DBLP,
        snapshot = DblpSnapshot,
        location = "datasets/samples/dblp/dblp-2026-11-01-excerpt.xml",
        checksum = "sha256:b5d5c46dbe60284c46ddc50174ea2106fd6f55f4623611c310dc8e0b177428a1"
      )
    ),
    parameters =
      Map("datasets.dblpSubset.startYear" -> "2000", "datasets.dblpSubset.endYear" -> "2000"),
    policyVersions = Map("coauthorPolicy" -> "illustrative-coauthor-policy-v1")
  )

  val runId: String = ok(StableIds.runId(runManifest))

  def prov(
      dataset: SourceType,
      sourceRecordId: String,
      snapshot: String,
      policyVersion: Option[String] = None,
      modelVersion: Option[String] = None,
      promptVersion: Option[String] = None,
      derivedFrom: Vector[String] = Vector.empty
  ): Provenance =
    Provenance(
      SV.Provenance,
      dataset,
      sourceRecordId,
      snapshot,
      runId,
      policyVersion,
      modelVersion,
      promptVersion,
      derivedFrom
    )

  // --- Work and mentions (dblp conf/dac/NewtonRMSB00) -------------------------------------
  val dblpKey = "conf/dac/NewtonRMSB00"
  val workId: String = ok(StableIds.workId(WorkNamespace.Dblp, dblpKey))
  val venueId: String = ok(StableIds.venueId(VenueNamespace.Dblp, "conf/dac"))
  val dblpProv: Provenance = prov(SourceType.DBLP, dblpKey, DblpSnapshot)

  private val authorNames = Vector(
    ("A. Richard Newton", "a richard newton"),
    ("Walden C. Rhines", "walden c rhines"),
    ("Sünke Mehrgardt", "sunke mehrgardt"),
    ("Henry Samueli", "henry samueli"),
    ("Tudor Brown", "tudor brown")
  )

  val mentions: Vector[AuthorMention] = authorNames.zipWithIndex.map { case ((raw, norm), i) =>
    AuthorMention(
      schemaVersion = SV.AuthorMention,
      mentionId = ok(StableIds.mentionId(workId, i + 1)),
      workId = workId,
      position = i + 1,
      rawName = raw,
      normalizedName = norm,
      sourceAuthorKey = Some(raw),
      orcid = None,
      provenance = dblpProv
    )
  }

  val rhinesMention: AuthorMention = mentions(1)
  val samueliMention: AuthorMention = mentions(3)

  val work: WorkRecord = WorkRecord(
    schemaVersion = SV.WorkRecord,
    workId = workId,
    source = SourceType.DBLP,
    sourceRecordId = dblpKey,
    recordType = "inproceedings",
    title = "Embedded systems design in the new millennium (panel session).",
    normalizedTitle = "embedded systems design in the new millennium panel session",
    year = Some(2000),
    venueId = Some(venueId),
    doi = Some("10.1145/337292.337434"),
    arxivId = None,
    authors = mentions,
    abstractTextRef = None,
    provenance = dblpProv
  )

  // --- Researchers ------------------------------------------------------------------------
  val samueliId: String =
    ok(
      StableIds.researcherId(Some("0000-0003-3175-3913"), None, Some("A5023877407"), "v1", "unused")
    )
  val rhinesId: String = ok(StableIds.researcherId(None, None, Some("A5110371381"), "v1", "unused"))

  val erPolicy = "illustrative-er-policy-v1"
  val coauthorPolicy = "illustrative-coauthor-policy-v1"
  val metricVersion = "illustrative-metrics-v1"

  val researcher: CanonicalResearcher = CanonicalResearcher(
    schemaVersion = SV.CanonicalResearcher,
    researcherId = samueliId,
    displayName = "Henry Samueli",
    orcid = Some("0000-0003-3175-3913"),
    dblpPid = None,
    openAlexAuthorId = Some("A5023877407"),
    dblpAuthorKeys = Vector("Henry Samueli"),
    identityVersion = None,
    affiliationInstitutionIds = Vector.empty,
    identityConfidence = 0.97,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      samueliMention.mentionId,
      DblpSnapshot,
      policyVersion = Some(erPolicy),
      derivedFrom = Vector(samueliMention.mentionId)
    )
  )

  val matchDecision: MatchDecision = MatchDecision(
    schemaVersion = SV.MatchDecision,
    decisionId = ok(StableIds.matchDecisionId(samueliMention.mentionId, samueliId, erPolicy)),
    sourceId = samueliMention.mentionId,
    targetId = samueliId,
    features = Map("doiExact" -> 1.0, "nameSimilarity" -> 1.0, "authorPositionAgreement" -> 1.0),
    rulesFired = Vector("ILLUSTRATIVE_EXACT_DOI_WORK_LINK", "ILLUSTRATIVE_AUTHOR_POSITION_MATCH"),
    decision = MatchOutcome.ACCEPTED,
    confidence = 0.97,
    reasonCodes =
      Vector("ILLUSTRATIVE_DOI_MATCH", "ILLUSTRATIVE_NAME_MATCH", "ILLUSTRATIVE_POSITION_MATCH"),
    policyVersion = erPolicy,
    modelVersion = None,
    promptVersion = None,
    decidedAt = "2026-11-19T00:00:00Z",
    runId = runId,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      samueliMention.mentionId,
      DblpSnapshot,
      policyVersion = Some(erPolicy)
    )
  )

  val authorship: AuthorshipRecord = AuthorshipRecord(
    schemaVersion = SV.AuthorshipRecord,
    authorshipId = ok(StableIds.authorshipId(workId, samueliMention.mentionId, samueliId)),
    researcherId = samueliId,
    workId = workId,
    mentionId = samueliMention.mentionId,
    position = 4,
    year = Some(2000),
    matchConfidence = 0.97,
    matchDecisionId = Some(matchDecision.decisionId),
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      samueliMention.mentionId,
      DblpSnapshot,
      policyVersion = Some(erPolicy),
      derivedFrom = Vector(matchDecision.decisionId)
    )
  )

  val team: PublicationTeam = PublicationTeam(
    schemaVersion = SV.PublicationTeam,
    workId = workId,
    year = Some(2000),
    memberMentionIds = mentions.map(_.mentionId),
    teamSize = 5,
    provenance = dblpProv
  )

  private val candidatePair =
    CanonicalPair.of(rhinesMention.mentionId, samueliMention.mentionId).toOption.get

  val pairCandidate: CoauthorPairCandidate = CoauthorPairCandidate(
    schemaVersion = SV.CoauthorPairCandidate,
    candidateId =
      ok(StableIds.pairCandidateId(workId, rhinesMention.mentionId, samueliMention.mentionId)),
    workId = workId,
    memberA = candidatePair.a,
    memberB = candidatePair.b,
    year = 2000,
    teamSize = 5,
    provenance = dblpProv
  )

  /** The same pair as [[pairCandidate]] in `coauthor-pair-candidate-v2`, built by the producer
    * helper from the source positions (Samueli 4, Rhines 2), deliberately in non-canonical order.
    */
  val pairCandidateV2: CoauthorPairCandidate =
    CoauthorPairCandidate
      .fromPositions(workId, samueliMention.position, rhinesMention.position, 2000, 5, dblpProv)
      .fold(m => throw new IllegalStateException(m), identity)

  private val researcherPair = CanonicalPair.of(samueliId, rhinesId).toOption.get

  val coauthorEdge: CoauthorEdge = CoauthorEdge(
    schemaVersion = SV.CoauthorEdge,
    edgeId = ok(StableIds.coauthorEdgeId(samueliId, rhinesId, 2000, 2005, coauthorPolicy)),
    researcherA = researcherPair.a,
    researcherB = researcherPair.b,
    windowStartYear = 2000,
    windowEndYear = 2005,
    jointPublicationCount = 1,
    firstCollaborationYear = 2000,
    lastCollaborationYear = 2000,
    representativeWorkIds = Vector(workId),
    supportingWorkIdsRef = None,
    policyVersion = coauthorPolicy,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      pairCandidate.candidateId,
      DblpSnapshot,
      policyVersion = Some(coauthorPolicy),
      derivedFrom = Vector(pairCandidate.candidateId)
    )
  )

  val metrics: ResearcherMetrics = ResearcherMetrics(
    schemaVersion = SV.ResearcherMetrics,
    metricsId = ok(StableIds.metricsId(samueliId, 2000, 2005, metricVersion, runId)),
    researcherId = samueliId,
    windowStartYear = 2000,
    windowEndYear = 2005,
    publicationCount = 1,
    distinctCoauthorCount = 4,
    totalCoauthorOccurrences = 4,
    averageCoauthorsPerPaper = 4.0,
    maximumCoauthorsOnOnePaper = 4,
    maximumTeamWorkIds = Vector(workId),
    weightedDegree = 4.0,
    componentId = "illustrative-component-1",
    componentSize = 5,
    communityId = Some("illustrative-community-1"),
    bridgeMetric = Some(CollaborationMetric.APPROX_BETWEENNESS),
    bridgeScore = Some(0.0),
    coreness = Some(4),
    metricVersion = metricVersion,
    provenance =
      prov(SourceType.SCHOLARCHAIN, samueliId, DblpSnapshot, policyVersion = Some(coauthorPolicy))
  )

  val venue: Venue = Venue(
    schemaVersion = SV.Venue,
    venueId = venueId,
    name = "DAC",
    venueType = Some("conference"),
    sourceVenueKey = Some("conf/dac"),
    issns = Vector("0738-100X"),
    provenance = prov(SourceType.DBLP, "conf/dac/2000", DblpSnapshot)
  )

  val institution: Institution = Institution(
    schemaVersion = SV.Institution,
    institutionId = ok(StableIds.institutionId(InstitutionNamespace.OpenAlex, "I177725633")),
    name = "Chinese University of Hong Kong",
    rorId = Some("00t33hh48"),
    countryCode = Some("HK"),
    provenance = prov(SourceType.OPENALEX, "https://openalex.org/I177725633", OpenAlexApiSnapshot)
  )

  val topic: Topic = Topic(
    schemaVersion = SV.Topic,
    topicId = ok(StableIds.topicId(TopicNamespace.OpenAlex, "T10363")),
    name = "Low-power high-performance VLSI design",
    parentTopicId = None,
    provenance = prov(SourceType.OPENALEX, "https://openalex.org/T10363", OpenAlexApiSnapshot)
  )

  val graphNode: GraphNode = GraphNode(
    schemaVersion = SV.GraphNode,
    nodeId = samueliId,
    nodeType = NodeType.Researcher,
    label = "Henry Samueli",
    properties = Map("displayName" -> "Henry Samueli"),
    provenance = researcher.provenance
  )

  val graphEdge: GraphEdge = GraphEdge(
    schemaVersion = SV.GraphEdge,
    edgeId = ok(
      StableIds.graphEdgeId(
        EdgeType.COAUTHORED_WITH,
        samueliId,
        rhinesId,
        coauthorPolicy,
        Seq(coauthorEdge.edgeId)
      )
    ),
    fromId = researcherPair.a,
    edgeType = EdgeType.COAUTHORED_WITH,
    toId = researcherPair.b,
    properties = Map(
      "jointPublicationCount" -> "1",
      "firstCollaborationYear" -> "2000",
      "lastCollaborationYear" -> "2000",
      "windowStartYear" -> "2000",
      "windowEndYear" -> "2005"
    ),
    policyVersion = coauthorPolicy,
    sourceEvidenceIds = Vector(coauthorEdge.edgeId),
    provenance = coauthorEdge.provenance
  )

  // --- Runtime ----------------------------------------------------------------------------
  val queryId: String = ok(StableIds.queryId("illustrative-request-fingerprint-1"))
  val runtimeProv: Provenance = prov(SourceType.SCHOLARCHAIN, queryId, DblpSnapshot)

  val edgeEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = SV.EvidenceItem,
    evidenceId =
      ok(StableIds.evidenceId(EvidenceType.COAUTHOR_EDGE, Seq(coauthorEdge.edgeId), runId)),
    evidenceType = EvidenceType.COAUTHOR_EDGE,
    referencedIds = Vector(coauthorEdge.edgeId),
    workId = Some(workId),
    sourceUri = "s3://${GRAPH_BUCKET}/edges/type=coauthored_with/",
    chunkId = None,
    graphPath = Some(Vector(researcherPair.a, researcherPair.b)),
    score = None,
    textHash = None,
    text = None,
    provenance = coauthorEdge.provenance
  )

  val titleHash: String = ok(StableIds.textHash(work.title))
  val chunkId: String = ok(StableIds.chunkId(workId, DblpSnapshot, "paper-title", 0, titleHash))

  val chunkEvidence: EvidenceItem = EvidenceItem(
    schemaVersion = SV.EvidenceItem,
    evidenceId = ok(StableIds.evidenceId(EvidenceType.TEXT_CHUNK, Seq(chunkId), runId)),
    evidenceType = EvidenceType.TEXT_CHUNK,
    referencedIds = Vector(chunkId),
    workId = Some(workId),
    sourceUri = "s3://${RAG_BUCKET}/chunks/",
    chunkId = Some(chunkId),
    graphPath = None,
    score = Some(0.83),
    textHash = Some(titleHash),
    text = Some(work.title),
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      workId,
      DblpSnapshot,
      modelVersion = Some("illustrative-embedding-model")
    )
  )

  val bundle: EvidenceBundle = EvidenceBundle(
    schemaVersion = SV.EvidenceBundle,
    bundleId =
      ok(StableIds.bundleId(queryId, Seq(edgeEvidence.evidenceId, chunkEvidence.evidenceId))),
    queryId = queryId,
    graphEvidence = Vector(edgeEvidence),
    semanticEvidence = Vector(chunkEvidence),
    identityConfidence = Map(samueliId -> 0.97, rhinesId -> 0.95),
    provenance = runtimeProv
  )

  val queryPlan: QueryPlan = QueryPlan(
    schemaVersion = SV.QueryPlan,
    queryId = queryId,
    operations = Vector(QueryOperation.DIRECT_COAUTHORS),
    researcherNames = Vector("Henry Samueli"),
    researcherIds = Vector(samueliId),
    windowStartYear = Some(2000),
    windowEndYear = Some(2005),
    topics = Vector.empty,
    venues = Vector.empty,
    institutions = Vector.empty,
    rankingMetric = None,
    graphLimit = 100,
    vectorLimit = 30,
    maxPathDepth = 2,
    planVersion = "illustrative-query-plan-v1",
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      queryId,
      DblpSnapshot,
      modelVersion = Some("illustrative-planner-model"),
      promptVersion = Some("illustrative-query-planner-prompt-v1")
    )
  )

  val claim: AnswerClaim = AnswerClaim(
    schemaVersion = SV.AnswerClaim,
    claimId = ok(StableIds.claimId(queryId, 0)),
    text =
      "Henry Samueli and Walden C. Rhines have 1 joint publication in the 2000 to 2005 window.",
    evidenceIds = Vector(edgeEvidence.evidenceId),
    verificationStatus = VerificationStatus.VERIFIED,
    uncertainty = Some(
      "Illustrative example: identity confidences in this record are not from a pipeline run."
    ),
    provenance = runtimeProv
  )

  val answer: AnswerResponse = AnswerResponse(
    schemaVersion = SV.AnswerResponse,
    queryId = queryId,
    summary = "Henry Samueli's direct coauthors in 2000 to 2005 include Walden C. Rhines.",
    claims = Vector(claim),
    evidenceBundleId = bundle.bundleId,
    abstained = false,
    uncertainty = None,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      queryId,
      DblpSnapshot,
      modelVersion = Some("illustrative-synthesis-model"),
      promptVersion = Some("illustrative-answer-synthesis-prompt-v1")
    )
  )

  /** Audit record of the illustrative answer. `answerHash` is the SHA-256 of the answer summary. */
  val queryAudit: QueryAuditRecord = QueryAuditRecord(
    schemaVersion = SV.QueryAuditRecord,
    queryId = queryId,
    runId = runId,
    planJson = upickle.default.write(queryPlan),
    researcherIds = Vector(samueliId, rhinesId),
    evidenceIds = Vector(edgeEvidence.evidenceId),
    metricVersions = Vector("illustrative-coauthor-metrics-v1"),
    promptVersions = Vector(
      "illustrative-query-planner-prompt-v1",
      "illustrative-answer-synthesis-prompt-v1"
    ),
    modelVersions = Vector("illustrative-planner-model", "illustrative-synthesis-model"),
    inputTokens = 1200L,
    outputTokens = 240L,
    latencyMillis = 850L,
    verificationResult = VerificationStatus.VERIFIED,
    answerHash = "sha256:744bf056aeb58504d4ef99b9d86ae95943a6b65b19808748774774da5c0a074e",
    provenance = runtimeProv
  )

  // --- Phase 4 ingestion records ----------------------------------------------------------
  // The OpenAlex work W2040559498 carries the same DOI as dblp conf/dac/NewtonRMSB00
  // (datasets/samples/openalex/openalex-works-api-linked.jsonl).
  val openAlexWorkId: String = ok(StableIds.workId(WorkNamespace.OpenAlex, "W2040559498"))
  val featureVersion = "illustrative-features-v1"

  val authorPaper: AuthorPaperRecord = AuthorPaperRecord(
    schemaVersion = SV.AuthorPaperRecord,
    mentionId = samueliMention.mentionId,
    workId = workId,
    position = 4,
    teamSize = 5,
    year = Some(2000),
    normalizedName = "henry samueli",
    sourceAuthorKey = Some("Henry Samueli"),
    orcid = None,
    provenance = dblpProv
  )

  private val workCandidatePair = CanonicalPair.of(openAlexWorkId, workId).toOption.get

  val matchCandidate: MatchCandidate = MatchCandidate(
    schemaVersion = SV.MatchCandidate,
    candidateId = ok(
      StableIds.matchCandidateId(MatchEntityType.WORK, openAlexWorkId, workId, featureVersion)
    ),
    entityType = MatchEntityType.WORK,
    leftRef = workCandidatePair.a,
    rightRef = workCandidatePair.b,
    blockKey = "illustrative-block:embedded systems design|2000",
    features = Map("doiEqual" -> 1.0, "titleTokenJaccard" -> 1.0, "yearDelta" -> 0.0),
    featureVersion = featureVersion,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      workCandidatePair.a,
      DblpSnapshot,
      derivedFrom = Vector(workCandidatePair.b)
    )
  )

  val exactDoi = "10.1145/337292.337434"

  val exactIdOutcome: ExactIdentifierOutcome = ExactIdentifierOutcome(
    schemaVersion = SV.ExactIdentifierOutcome,
    outcomeId = ok(StableIds.exactIdOutcomeId(ExactIdType.DOI, exactDoi)),
    idType = ExactIdType.DOI,
    normalizedId = exactDoi,
    recordRefs = Vector(workId, openAlexWorkId).sorted,
    outcome = ExactIdOutcome.MATCH,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      workId,
      DblpSnapshot,
      derivedFrom = Vector(openAlexWorkId)
    )
  )

  val rejectRef = "illustrative/dblp-2026-11-01-excerpt.xml@offset:0"

  val reject: RejectRecord = RejectRecord(
    schemaVersion = SV.RejectRecord,
    source = SourceType.DBLP,
    sourceRecordRef = rejectRef,
    stage = "illustrative-dblp-extract",
    reasonCode = "UNKNOWN_ENTITY",
    rawExcerpt = "<title>Illustrative &unknownentity; title</title>",
    runId = runId,
    provenance = prov(SourceType.DBLP, rejectRef, DblpSnapshot)
  )

  // --- Phase 6 Spark analytics records (illustrative values) -----------------------------
  val analyticsMetricVersion = "illustrative-graph-metrics-v1"
  private val (edgeA, edgeB) = (researcherPair.a, researcherPair.b)

  def analyticsProv(sourceRecordId: String, derivedFrom: Vector[String] = Vector.empty) =
    prov(
      SourceType.SCHOLARCHAIN,
      sourceRecordId,
      DblpSnapshot,
      policyVersion = Some(coauthorPolicy),
      derivedFrom = derivedFrom
    )

  val edgeSupport: CoauthorEdgeSupport = CoauthorEdgeSupport(
    schemaVersion = SV.CoauthorEdgeSupport,
    edgeId = coauthorEdge.edgeId,
    researcherA = edgeA,
    researcherB = edgeB,
    windowStartYear = 2000,
    windowEndYear = 2005,
    workId = workId,
    workYear = Some(2000),
    policyVersion = coauthorPolicy,
    runId = runId,
    provenance = analyticsProv(coauthorEdge.edgeId, Vector(pairCandidate.candidateId))
  )

  val componentMembership: ComponentMembership = ComponentMembership(
    schemaVersion = SV.ComponentMembership,
    researcherId = samueliId,
    componentId = edgeA,
    componentSize = 2,
    windowStartYear = 2000,
    windowEndYear = 2005,
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(coauthorEdge.edgeId))
  )

  val communityMembership: CommunityMembership = CommunityMembership(
    schemaVersion = SV.CommunityMembership,
    researcherId = samueliId,
    communityId = edgeA,
    communitySize = 2,
    windowStartYear = 2000,
    windowEndYear = 2005,
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(coauthorEdge.edgeId))
  )

  val crossCommunityEdge: CrossCommunityEdge = CrossCommunityEdge(
    schemaVersion = SV.CrossCommunityEdge,
    edgeId = coauthorEdge.edgeId,
    researcherA = edgeA,
    researcherB = edgeB,
    communityA = edgeA,
    communityB = edgeB,
    windowStartYear = 2000,
    windowEndYear = 2005,
    jointPublicationCount = 1,
    policyVersion = coauthorPolicy,
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(coauthorEdge.edgeId, Vector(coauthorEdge.edgeId))
  )

  val bridgeMeasures: BridgeMeasures = BridgeMeasures(
    schemaVersion = SV.BridgeMeasures,
    researcherId = samueliId,
    communityId = edgeB,
    windowStartYear = 2000,
    windowEndYear = 2005,
    degree = 1,
    crossCommunityDegree = 1,
    communitiesConnected = 2,
    participationCoefficient = 0.0,
    betweenness = Some(0.0),
    betweennessMethod = Some(BetweennessMethod.SAMPLED_SOURCES),
    betweennessSampleSources = Some(10),
    betweennessSeed = Some(7L),
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(coauthorEdge.edgeId))
  )

  val coreness: CorenessRecord = CorenessRecord(
    schemaVersion = SV.CorenessRecord,
    researcherId = samueliId,
    windowStartYear = 2000,
    windowEndYear = 2005,
    coreness = 1,
    degree = 1,
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(coauthorEdge.edgeId))
  )

  val pathHop: PathHop = PathHop(
    fromResearcherId = samueliId,
    toResearcherId = rhinesId,
    edgeId = coauthorEdge.edgeId,
    jointPublicationCount = 1,
    supportingWorkIds = Vector(workId)
  )

  val pathEvidence: PathEvidence = PathEvidence(
    schemaVersion = SV.PathEvidence,
    pathId = ok(StableIds.pathId(samueliId, rhinesId, 2000, 2005, coauthorPolicy, runId)),
    sourceResearcherId = samueliId,
    targetResearcherId = rhinesId,
    windowStartYear = 2000,
    windowEndYear = 2005,
    selection = PathSelection.BENCHMARK,
    status = PathStatus.FOUND,
    maxDepth = 2,
    length = Some(1),
    hops = Vector(pathHop),
    policyVersion = coauthorPolicy,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(coauthorEdge.edgeId))
  )

  val semanticFact: SemanticFact = SemanticFact(
    schemaVersion = SV.SemanticFact,
    factId = ok(
      StableIds.semanticFactId(
        SemanticSubjectType.RESEARCHER,
        samueliId,
        2000,
        2005,
        analyticsMetricVersion,
        runId
      )
    ),
    subjectType = SemanticSubjectType.RESEARCHER,
    subjectId = samueliId,
    displayName = Some("Henry Samueli"),
    windowStartYear = 2000,
    windowEndYear = 2005,
    metrics = Vector(
      MetricValue(CollaborationMetric.PUBLICATION_COUNT, 1.0),
      MetricValue(CollaborationMetric.DISTINCT_COAUTHOR_COUNT, 1.0)
    ),
    topTopics = Vector(RankedEntity(topic.topicId, topic.name, 1)),
    topVenues = Vector(RankedEntity(venueId, venue.name, 1)),
    topInstitutions = Vector(RankedEntity(institution.institutionId, institution.name, 1)),
    relatedResearcherIds = Vector(rhinesId),
    representativeWorks = Vector(RepresentativeWork(workId, work.title, Some(2000))),
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(metrics.metricsId, coauthorEdge.edgeId))
  )

  val loadManifest: LoadManifest = LoadManifest(
    schemaVersion = SV.LoadManifest,
    target = LoadTarget.S3,
    runId = runId,
    tables = Vector(
      LoadTable(
        tableName = "coauthor_edges",
        recordType = "CoauthorEdge",
        recordSchemaVersion = SV.CoauthorEdge,
        location = "illustrative-output/graph/edges/type=coauthored_with/",
        format = "parquet",
        partitionColumns = Vector("windowStartYear", "windowEndYear", "runId"),
        rowCount = 1,
        files = Vector(
          LoadFile(
            path = "illustrative-output/graph/edges/type=coauthored_with/part-00000.parquet",
            sizeBytes = 2048,
            checksum = ok(StableIds.textHash("illustrative parquet bytes"))
          )
        )
      )
    ),
    provenance = analyticsProv(runId)
  )

  /** A valid ORCID iD that is not Samueli's (the ORCID documentation example). */
  val otherOrcid = "0000-0002-1825-0097"

  val identityConflict: IdentityConflict = IdentityConflict(
    schemaVersion = SV.IdentityConflict,
    conflictId = ok(
      StableIds.identityConflictId(
        "v1",
        erPolicy,
        Seq(rhinesMention.mentionId, samueliMention.mentionId)
      )
    ),
    memberIds = Vector(rhinesMention.mentionId, samueliMention.mentionId).sorted,
    clusterSize = 2,
    maxClusterSize = 4,
    details = Vector(
      IdentityConflictDetail(IdentityConflictReason.SAME_WORK_MENTIONS, Vector(workId)),
      IdentityConflictDetail(
        IdentityConflictReason.MULTIPLE_ORCIDS,
        Vector("0000-0003-3175-3913", otherOrcid).sorted
      )
    ),
    matchDecisionIds = Vector(matchDecision.decisionId),
    identityVersion = "v1",
    policyVersion = erPolicy,
    runId = runId,
    provenance = prov(
      SourceType.SCHOLARCHAIN,
      samueliMention.mentionId,
      DblpSnapshot,
      policyVersion = Some(erPolicy),
      derivedFrom = Vector(matchDecision.decisionId)
    )
  )

  val windowGraphSummary: WindowGraphSummary = WindowGraphSummary(
    schemaVersion = SV.WindowGraphSummary,
    windowStartYear = 2000,
    windowEndYear = 2005,
    allYearsWindow = false,
    researcherCount = 5,
    workCount = 1,
    yearlessWorkCount = 0,
    coauthorEdgeCount = 10,
    selfPairsDropped = 0,
    oversizeTeamWorkCount = 0,
    componentCount = 1,
    largestComponentSize = 5,
    singletonComponentCount = 0,
    communityCount = Some(1),
    communitySizeHistogram = Vector(SizeCount(5, 1)),
    maxCoreness = Some(4),
    iterations = Vector(
      IterationReport("connected-components", 2, 20, converged = true),
      IterationReport("label-propagation", 2, 20, converged = true),
      IterationReport("k-core", 1, 30, converged = true)
    ),
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(runId)
  )

  val windowSnapshot: ResearcherWindowSnapshot = ResearcherWindowSnapshot(
    publicationCount = 1,
    distinctCoauthorCount = 4,
    averageCoauthorsPerPaper = 4.0,
    maximumCoauthorsOnOnePaper = 4,
    componentSize = 5,
    communitySize = Some(5),
    crossCommunityDegree = Some(0),
    communitiesConnected = Some(1),
    participationCoefficient = Some(0.0),
    coreness = Some(4)
  )

  val researcherWindowChange: ResearcherWindowChange = ResearcherWindowChange(
    schemaVersion = SV.ResearcherWindowChange,
    researcherId = samueliId,
    fromWindowStartYear = 2000,
    fromWindowEndYear = 2005,
    toWindowStartYear = 2006,
    toWindowEndYear = 2010,
    from = Some(windowSnapshot),
    to = None,
    metricVersion = analyticsMetricVersion,
    runId = runId,
    provenance = analyticsProv(samueliId, Vector(metrics.metricsId))
  )
