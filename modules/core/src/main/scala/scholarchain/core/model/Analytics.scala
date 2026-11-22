package scholarchain.core.model

import scholarchain.core.json.WireEnum
import scholarchain.core.schema.SchemaVersions
import upickle.default.ReadWriter

// Phase 6 Spark analytics records (all v1, added in Phase 06 repair 01).
//
// Shared conventions:
//   - every record carries `schemaVersion`, a `runId` equal to `provenance.runId`, and full
//     provenance; windowed records carry `windowStartYear` and `windowEndYear`
//   - `(windowStartYear, windowEndYear)` identifies exactly one analysis window (the
//     configuration rejects a fixed window equal to the all-years window)
//   - community and component IDs are researcher IDs used as labels: a component ID is the
//     smallest researcher ID in the component; a community ID is the label-propagation label.
//     Both are window-local and must not be compared across windows
//   - lists that are sets are sorted ascending and distinct, so outputs are deterministic

/** Outcome of one shortest-path query. */
enum PathStatus:
  /** A path of length <= maxDepth was found; `hops` holds it. */
  case FOUND

  /** Both endpoints are in one component, but no path of length <= maxDepth exists. */
  case NOT_FOUND_WITHIN_DEPTH

  /** The endpoints are in different components (or one is absent from the window). */
  case NO_PATH

object PathStatus:
  given ReadWriter[PathStatus] = WireEnum.readWriter("PathStatus", values.toIndexedSeq)

/** How a path query was chosen: from configured benchmark pairs, or sampled with the seed. */
enum PathSelection:
  case BENCHMARK, SAMPLED

object PathSelection:
  given ReadWriter[PathSelection] = WireEnum.readWriter("PathSelection", values.toIndexedSeq)

/** How a betweenness value was computed. `SAMPLED_SOURCES` values are approximate. */
enum BetweennessMethod:
  case EXACT, SAMPLED_SOURCES

object BetweennessMethod:
  given ReadWriter[BetweennessMethod] =
    WireEnum.readWriter("BetweennessMethod", values.toIndexedSeq)

/** Subject of a semantic-input fact record. */
enum SemanticSubjectType:
  case RESEARCHER, COMMUNITY, PATH, TOPIC, INSTITUTION

object SemanticSubjectType:
  given ReadWriter[SemanticSubjectType] =
    WireEnum.readWriter("SemanticSubjectType", values.toIndexedSeq)

/** Load target of a per-target load manifest. */
enum LoadTarget:
  case NEPTUNE, DYNAMODB, S3VECTORS_INPUT, S3

object LoadTarget:
  given ReadWriter[LoadTarget] = WireEnum.readWriter("LoadTarget", values.toIndexedSeq)

/** Why an identity cluster was not merged. Declaration order is the wire sort order. */
enum IdentityConflictReason:
  /** Two members are mentions on the same work (one person cannot author a work twice). */
  case SAME_WORK_MENTIONS

  /** Members carry more than one distinct ORCID iD. */
  case MULTIPLE_ORCIDS

  /** Members carry more than one distinct OpenAlex author ID. */
  case MULTIPLE_OPENALEX_AUTHOR_IDS

  /** Members carry more than one distinct dblp person pid. */
  case MULTIPLE_DBLP_PIDS

  /** Members carry more than one distinct dblp author key (homonym-disambiguated name). */
  case MULTIPLE_DBLP_AUTHOR_KEYS

  /** The cluster has more members than `graph.identity.maxClusterSize`. */
  case CLUSTER_TOO_LARGE

object IdentityConflictReason:
  given ReadWriter[IdentityConflictReason] =
    WireEnum.readWriter("IdentityConflictReason", values.toIndexedSeq)

/** One supporting work of one windowed coauthor edge: the full support table maps each
  * `CoauthorEdge.edgeId` to every work that supports it (one row per work).
  *
  * `CoauthorEdge.supportingWorkIdsRef`, when set, is [[CoauthorEdgeSupport.supportRef]] of the
  * edge ID: it points to all rows of this table with that `edgeId` and the edge's run ID. The
  * number of such rows equals the edge's `jointPublicationCount`.
  *
  * @param workYear
  *   the work's year; `None` only for a year-less work, which belongs only to the all-years
  *   window
  */
final case class CoauthorEdgeSupport(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    windowStartYear: Int,
    windowEndYear: Int,
    workId: String,
    workYear: Option[Int],
    policyVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

object CoauthorEdgeSupport:
  /** `coauthor-edge-support-v1:<edgeId>`, the value of `CoauthorEdge.supportingWorkIdsRef`. */
  def supportRef(edgeId: String): String = s"${SchemaVersions.CoauthorEdgeSupport}:$edgeId"

/** Connected-component membership of one researcher in one window.
  *
  * @param componentId
  *   the smallest researcher ID in the component (so never greater than `researcherId`)
  */
final case class ComponentMembership(
    schemaVersion: String,
    researcherId: String,
    componentId: String,
    componentSize: Long,
    windowStartYear: Int,
    windowEndYear: Int,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** Label-propagation community membership of one researcher in one window.
  *
  * @param communityId
  *   the final label (a researcher ID); window-local
  */
final case class CommunityMembership(
    schemaVersion: String,
    researcherId: String,
    communityId: String,
    communitySize: Long,
    windowStartYear: Int,
    windowEndYear: Int,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** A windowed coauthor edge whose endpoints lie in different communities. `communityA` is the
  * community of `researcherA` and `communityB` that of `researcherB`.
  */
final case class CrossCommunityEdge(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    communityA: String,
    communityB: String,
    windowStartYear: Int,
    windowEndYear: Int,
    jointPublicationCount: Long,
    policyVersion: String,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** The bridge measures of one researcher in one window, stored together.
  *
  * With unweighted degree `k_r` and `k_rc` neighbours in community `c`:
  * `participationCoefficient = 1 - sum_c (k_rc / k_r)^2` (0 when `k_r = 0`).
  * `crossCommunityDegree` counts neighbours outside the researcher's own community.
  *
  * Optional betweenness: `betweenness` and `betweennessMethod` are set together;
  * `betweennessSampleSources` (k) and `betweennessSeed` are set iff the method is
  * `SAMPLED_SOURCES`, whose value is an approximation.
  */
final case class BridgeMeasures(
    schemaVersion: String,
    researcherId: String,
    communityId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    degree: Long,
    crossCommunityDegree: Long,
    communitiesConnected: Long,
    participationCoefficient: Double,
    betweenness: Option[Double],
    betweennessMethod: Option[BetweennessMethod],
    betweennessSampleSources: Option[Int],
    betweennessSeed: Option[Long],
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** k-core coreness of one researcher in one window (`0 <= coreness <= degree`). */
final case class CorenessRecord(
    schemaVersion: String,
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    coreness: Int,
    degree: Long,
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** One hop of a verified path: a windowed coauthor edge traversed from `fromResearcherId` to
  * `toResearcherId`, with its weight and every supporting work (sorted; as many as the weight).
  */
final case class PathHop(
    fromResearcherId: String,
    toResearcherId: String,
    edgeId: String,
    jointPublicationCount: Long,
    supportingWorkIds: Vector[String]
) derives ReadWriter

/** Evidence for one shortest-path query in one window. A `FOUND` path is simple, has `length ==
  * hops.size <= maxDepth`, starts at the source and ends at the target; among equal-length paths
  * the producer chooses the lexicographically smallest node sequence. Other statuses have no hops
  * and no length.
  *
  * @param pathId
  *   `StableIds.pathId(source, target, window, policyVersion, runId)`
  * @param policyVersion
  *   the coauthor-edge policy version of the hop edge IDs
  */
final case class PathEvidence(
    schemaVersion: String,
    pathId: String,
    sourceResearcherId: String,
    targetResearcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    selection: PathSelection,
    status: PathStatus,
    maxDepth: Int,
    length: Option[Int],
    hops: Vector[PathHop],
    policyVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** One metric value on a semantic fact. */
final case class MetricValue(metric: CollaborationMetric, value: Double) derives ReadWriter

/** A ranked entity (topic, venue or institution) with its count. */
final case class RankedEntity(entityId: String, label: String, count: Long) derives ReadWriter

/** A representative work. `title` is untrusted source text: data, never instructions. */
final case class RepresentativeWork(workId: String, title: String, year: Option[Int])
    derives ReadWriter

/** Structured semantic-input facts about one subject in one window. Facts only: no prose and no
  * model output. Rendering text from them belongs to the RAG stage.
  *
  * Lists: ranked lists are sorted by count descending, then entity ID ascending, with distinct
  * IDs. For a `PATH` subject, `relatedResearcherIds` are the path nodes in hop order (>= 2);
  * otherwise they are related researchers in the producer's ranking order. `displayName` and
  * titles are untrusted text, at most [[SemanticFact.MaxTextCodePoints]] code points each.
  *
  * @param factId
  *   `StableIds.semanticFactId(subjectType, subjectId, window, metricVersion, runId)`
  */
final case class SemanticFact(
    schemaVersion: String,
    factId: String,
    subjectType: SemanticSubjectType,
    subjectId: String,
    displayName: Option[String],
    windowStartYear: Int,
    windowEndYear: Int,
    metrics: Vector[MetricValue],
    topTopics: Vector[RankedEntity],
    topVenues: Vector[RankedEntity],
    topInstitutions: Vector[RankedEntity],
    relatedResearcherIds: Vector[String],
    representativeWorks: Vector[RepresentativeWork],
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

object SemanticFact:
  /** Bound on `displayName` and each representative-work title (Unicode code points). */
  val MaxTextCodePoints = 1024

  /** The first [[MaxTextCodePoints]] code points of `text` (never splits a surrogate pair); a
    * `null` input yields the empty string.
    */
  def truncateText(text: String): String =
    if text == null then ""
    else if text.codePointCount(0, text.length) <= MaxTextCodePoints then text
    else text.substring(0, text.offsetByCodePoints(0, MaxTextCodePoints))

/** One file of a loaded table. `checksum` is `sha256:<64 hex>` of the file bytes. */
final case class LoadFile(path: String, sizeBytes: Long, checksum: String) derives ReadWriter

/** One table listed in a load manifest.
  *
  * @param tableName
  *   `[a-z][a-z0-9_]*`, unique within the manifest
  * @param recordType
  *   the core record type of every row (a key of `SchemaVersions.supported`)
  * @param recordSchemaVersion
  *   the rows' schema version, supported for `recordType`
  * @param files
  *   sorted by path, distinct; may be empty only when `rowCount = 0`
  */
final case class LoadTable(
    tableName: String,
    recordType: String,
    recordSchemaVersion: String,
    location: String,
    format: String,
    partitionColumns: Vector[String],
    rowCount: Long,
    files: Vector[LoadFile]
) derives ReadWriter

/** Per-target load manifest: what one store loads from one analytics run. Store-specific
  * formats are not described here; they belong to the storage loaders.
  */
final case class LoadManifest(
    schemaVersion: String,
    target: LoadTarget,
    runId: String,
    tables: Vector[LoadTable],
    provenance: Provenance
) derives ReadWriter

/** One reason an identity cluster was not merged, with the conflicting values (sorted,
  * distinct): ORCID iDs, OpenAlex author IDs, dblp pids or dblp author keys (at least two) for
  * the `MULTIPLE_*` reasons, the shared work IDs (at least one) for `SAME_WORK_MENTIONS`, and
  * none for `CLUSTER_TOO_LARGE`.
  */
final case class IdentityConflictDetail(reason: IdentityConflictReason, values: Vector[String])
    derives ReadWriter

/** An identity cluster (the transitive closure of accepted researcher links) that violates a
  * cluster constraint and was therefore NOT merged; its members keep their own identities.
  * A pairwise `MatchDecision` cannot express this, because the violation belongs to the cluster.
  *
  * @param conflictId
  *   `StableIds.identityConflictId(identityVersion, policyVersion, memberIds)`
  * @param memberIds
  *   author-mention or researcher IDs, sorted, distinct, at least two
  * @param maxClusterSize
  *   the configured `graph.identity.maxClusterSize` in force
  * @param details
  *   at least one, sorted by reason declaration order, one per reason; `CLUSTER_TOO_LARGE` is
  *   present iff `clusterSize > maxClusterSize`
  * @param matchDecisionIds
  *   the accepted decisions that formed the cluster (sorted, distinct; may be empty)
  * @param policyVersion
  *   the entity-resolution policy version of those decisions
  */
final case class IdentityConflict(
    schemaVersion: String,
    conflictId: String,
    memberIds: Vector[String],
    clusterSize: Int,
    maxClusterSize: Int,
    details: Vector[IdentityConflictDetail],
    matchDecisionIds: Vector[String],
    identityVersion: String,
    policyVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** Size distribution entry: `count` groups of exactly `size` members. */
final case class SizeCount(size: Long, count: Long) derives ReadWriter

/** Iteration report of one iterative algorithm in one window. */
final case class IterationReport(
    algorithm: String,
    iterations: Int,
    maxIterations: Int,
    converged: Boolean
) derives ReadWriter

/** Graph-level statistics of one window (also the per-window input of the temporal summary).
  *
  * @param allYearsWindow
  *   whether this is the all-years window; only it may count year-less works
  * @param communitySizeHistogram
  *   sorted by size ascending, distinct sizes; when `communityCount` is set, the counts sum to it
  *   and `size * count` sums to `researcherCount`
  */
final case class WindowGraphSummary(
    schemaVersion: String,
    windowStartYear: Int,
    windowEndYear: Int,
    allYearsWindow: Boolean,
    researcherCount: Long,
    workCount: Long,
    yearlessWorkCount: Long,
    coauthorEdgeCount: Long,
    selfPairsDropped: Long,
    oversizeTeamWorkCount: Long,
    componentCount: Long,
    largestComponentSize: Long,
    singletonComponentCount: Long,
    communityCount: Option[Long],
    communitySizeHistogram: Vector[SizeCount],
    maxCoreness: Option[Int],
    iterations: Vector[IterationReport],
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter

/** A researcher's measures in one window, as compared by [[ResearcherWindowChange]]. */
final case class ResearcherWindowSnapshot(
    publicationCount: Long,
    distinctCoauthorCount: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    componentSize: Long,
    communitySize: Option[Long],
    crossCommunityDegree: Option[Long],
    communitiesConnected: Option[Long],
    participationCoefficient: Option[Double],
    coreness: Option[Int]
) derives ReadWriter

/** Temporal comparison of one researcher between two consecutive fixed windows (`from` ends
  * before `to` starts). A missing snapshot means the researcher has no row in that window.
  * Community labels are window-local, so only community sizes are compared, never labels.
  */
final case class ResearcherWindowChange(
    schemaVersion: String,
    researcherId: String,
    fromWindowStartYear: Int,
    fromWindowEndYear: Int,
    toWindowStartYear: Int,
    toWindowEndYear: Int,
    from: Option[ResearcherWindowSnapshot],
    to: Option[ResearcherWindowSnapshot],
    metricVersion: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter
