package scholarchain.core.model

import scholarchain.core.json.WireEnum
import upickle.default.ReadWriter

/** Dataset (or pipeline) that a record or provenance entry originates from. */
enum SourceType:
  case DBLP, OPENALEX, ORCID, CROSSREF, SEMANTIC_SCHOLAR

  /** A record derived by a ScholarChain pipeline stage from other records. */
  case SCHOLARCHAIN

object SourceType:
  given ReadWriter[SourceType] = WireEnum.readWriter("SourceType", values.toIndexedSeq)

/** Entity-resolution decision outcome (spec "Entity linkage policy"). */
enum MatchOutcome:
  case ACCEPTED, REJECTED, UNRESOLVED, CONFLICT

object MatchOutcome:
  given ReadWriter[MatchOutcome] = WireEnum.readWriter("MatchOutcome", values.toIndexedSeq)

/** Kind of evidence item. Graph facts and semantic context are kept apart: an evidence bundle
  * may only place `isGraphFact` items in its graph section and the rest in its semantic section.
  */
enum EvidenceType(val isGraphFact: Boolean):
  case WORK_RECORD extends EvidenceType(true)
  case AUTHORSHIP extends EvidenceType(true)
  case COAUTHOR_EDGE extends EvidenceType(true)
  case GRAPH_PATH extends EvidenceType(true)
  case METRIC_RECORD extends EvidenceType(true)
  case COMPONENT_RECORD extends EvidenceType(true)
  case COMMUNITY_RECORD extends EvidenceType(true)
  case MATCH_DECISION extends EvidenceType(true)
  case TEXT_CHUNK extends EvidenceType(false)
  case RESEARCHER_PROFILE extends EvidenceType(false)
  case COMMUNITY_SUMMARY extends EvidenceType(false)
  case PATH_SUMMARY extends EvidenceType(false)
  case TOPIC_INSTITUTION_SUMMARY extends EvidenceType(false)

object EvidenceType:
  given ReadWriter[EvidenceType] = WireEnum.readWriter("EvidenceType", values.toIndexedSeq)

/** Collaboration measures (spec "What must be measured"). Names only; definitions and
  * algorithms belong to the Spark module.
  */
enum CollaborationMetric:
  case PUBLICATION_COUNT
  case DISTINCT_COAUTHOR_COUNT
  case TOTAL_COAUTHOR_OCCURRENCES
  case AVERAGE_COAUTHORS_PER_PAPER
  case MAXIMUM_COAUTHORS_ON_ONE_PAPER
  case WEIGHTED_DEGREE
  case COMPONENT_SIZE
  case SHORTEST_PATH_LENGTH
  case COMMUNITY_SIZE
  case APPROX_BETWEENNESS
  case CROSS_COMMUNITY_DEGREE
  case PARTICIPATION_COEFFICIENT
  case COMMUNITIES_CONNECTED
  case CORENESS

object CollaborationMetric:
  given ReadWriter[CollaborationMetric] =
    WireEnum.readWriter("CollaborationMetric", values.toIndexedSeq)

/** Allowlisted query-plan operations (spec Phase 9, QueryPlannerAgent). */
enum QueryOperation:
  case RESEARCHER_RANKING
  case MAXIMUM_TEAM_SIZE
  case DIRECT_COAUTHORS
  case SHORTEST_PATH
  case COMPONENT_LOOKUP
  case COMMUNITY_COMPARISON
  case BRIDGE_RANKING
  case TEMPORAL_COMPARISON
  case TOPIC_FILTER
  case VENUE_FILTER
  case INSTITUTION_FILTER
  case SEMANTIC_EXPLANATION

object QueryOperation:
  given ReadWriter[QueryOperation] = WireEnum.readWriter("QueryOperation", values.toIndexedSeq)

/** Claim verification outcome (spec Phase 9, ClaimVerificationAgent). */
enum VerificationStatus:
  case VERIFIED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONFLICTING

object VerificationStatus:
  given ReadWriter[VerificationStatus] =
    WireEnum.readWriter("VerificationStatus", values.toIndexedSeq)

/** Research-graph node classes (spec "Research graph model"). */
enum NodeType:
  case Researcher, AuthorMention, Work, Venue, Institution, Topic, TextChunk, DatasetSnapshot

object NodeType:
  given ReadWriter[NodeType] = WireEnum.readWriter("NodeType", values.toIndexedSeq)

/** Research-graph edge classes. `COAUTHORED_WITH` is undirected: its endpoints are stored in
  * canonical (lexicographic) order.
  */
enum EdgeType(val undirected: Boolean):
  case AUTHORED extends EdgeType(false)
  case RESOLVES_TO extends EdgeType(false)
  case COAUTHORED_WITH extends EdgeType(true)
  case PUBLISHED_IN extends EdgeType(false)
  case AFFILIATED_WITH extends EdgeType(false)
  case HAS_TOPIC extends EdgeType(false)
  case DERIVED_FROM extends EdgeType(false)
  case SUPPORTED_BY extends EdgeType(false)

object EdgeType:
  given ReadWriter[EdgeType] = WireEnum.readWriter("EdgeType", values.toIndexedSeq)
