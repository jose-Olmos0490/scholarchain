package scholarchain.core.schema

/** Frozen wire schema versions, one per record type.
  *
  * Format: `<record-type>-v<N>`. A record whose `schemaVersion` is not in the supported set for
  * its type is rejected before field decoding. Raising a version requires new frozen golden JSON
  * examples and contract tests in the same change.
  */
object SchemaVersions:
  val Provenance = "provenance-v1"
  val WorkRecord = "work-v1"
  val AuthorMention = "author-mention-v1"
  val CanonicalResearcher = "researcher-v1"
  val AuthorshipRecord = "authorship-v1"
  val PublicationTeam = "publication-team-v1"
  val CoauthorPairCandidate = "coauthor-pair-candidate-v1"
  val CoauthorEdge = "coauthor-edge-v1"
  val ResearcherMetrics = "researcher-metrics-v1"
  val Venue = "venue-v1"
  val Institution = "institution-v1"
  val Topic = "topic-v1"
  val MatchDecision = "match-decision-v1"
  val GraphNode = "graph-node-v1"
  val GraphEdge = "graph-edge-v1"
  val EvidenceItem = "evidence-v1"
  val EvidenceBundle = "evidence-bundle-v1"
  val QueryPlan = "query-plan-v1"
  val AnswerClaim = "answer-claim-v1"
  val AnswerResponse = "answer-response-v1"
  val RunManifest = "run-manifest-v1"

  /** Record type name to the set of schema versions this build accepts. */
  val supported: Map[String, Set[String]] = Map(
    "Provenance" -> Set(Provenance),
    "WorkRecord" -> Set(WorkRecord),
    "AuthorMention" -> Set(AuthorMention),
    "CanonicalResearcher" -> Set(CanonicalResearcher),
    "AuthorshipRecord" -> Set(AuthorshipRecord),
    "PublicationTeam" -> Set(PublicationTeam),
    "CoauthorPairCandidate" -> Set(CoauthorPairCandidate),
    "CoauthorEdge" -> Set(CoauthorEdge),
    "ResearcherMetrics" -> Set(ResearcherMetrics),
    "Venue" -> Set(Venue),
    "Institution" -> Set(Institution),
    "Topic" -> Set(Topic),
    "MatchDecision" -> Set(MatchDecision),
    "GraphNode" -> Set(GraphNode),
    "GraphEdge" -> Set(GraphEdge),
    "EvidenceItem" -> Set(EvidenceItem),
    "EvidenceBundle" -> Set(EvidenceBundle),
    "QueryPlan" -> Set(QueryPlan),
    "AnswerClaim" -> Set(AnswerClaim),
    "AnswerResponse" -> Set(AnswerResponse),
    "RunManifest" -> Set(RunManifest)
  )

  def supportedFor(recordType: String): Set[String] = supported.getOrElse(recordType, Set.empty)
