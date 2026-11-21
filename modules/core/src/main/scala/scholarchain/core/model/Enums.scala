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

/** Entity kind of a Phase 4 match candidate (blocking output, before any decision). */
enum MatchEntityType:
  case RESEARCHER, WORK

object MatchEntityType:
  given ReadWriter[MatchEntityType] = WireEnum.readWriter("MatchEntityType", values.toIndexedSeq)

/** Identifier type checked by exact-identifier matching. Each type names the normalizer in
  * [[scholarchain.core.ids.ExternalIds]] that defines its normalized form, and the stable-ID
  * kinds its records may have.
  */
enum ExactIdType(val identifiesResearchers: Boolean):
  /** DOI, normalized by `ExternalIds.normalizeDoi` (lower-case `10.<registrant>/<suffix>`). */
  case DOI extends ExactIdType(false)

  /** arXiv ID without version suffix (`ExternalIds.normalizeArxiv`). */
  case ARXIV extends ExactIdType(false)

  /** dblp record key (`ExternalIds.normalizeDblpKey`). */
  case DBLP_KEY extends ExactIdType(false)

  /** OpenAlex work ID `W<digits>` (`ExternalIds.normalizeOpenAlex(_, 'W')`). */
  case OPENALEX_WORK extends ExactIdType(false)

  /** ORCID iD, hyphenated with valid check character (`ExternalIds.normalizeOrcid`). */
  case ORCID extends ExactIdType(true)

  /** dblp person pid in bare form (`ExternalIds.normalizeDblpPid`). */
  case DBLP_PID extends ExactIdType(true)

  /** OpenAlex author ID `A<digits>` (`ExternalIds.normalizeOpenAlex(_, 'A')`). */
  case OPENALEX_AUTHOR extends ExactIdType(true)

object ExactIdType:
  given ReadWriter[ExactIdType] = WireEnum.readWriter("ExactIdType", values.toIndexedSeq)

/** Outcome of grouping records by one normalized identifier. The criteria that choose between
  * the outcomes belong to the producing stage; core fixes only the names and the structural rule
  * that every outcome groups at least two distinct records.
  *
  *   - `MATCH`: records from different sources carry the identifier and agree (a link)
  *   - `DUPLICATE`: records from the same source repeat the identifier (one entity listed twice)
  *   - `CONFLICT`: records carrying the identifier disagree on fields that must agree
  */
enum ExactIdOutcome:
  case MATCH, DUPLICATE, CONFLICT

object ExactIdOutcome:
  given ReadWriter[ExactIdOutcome] = WireEnum.readWriter("ExactIdOutcome", values.toIndexedSeq)
