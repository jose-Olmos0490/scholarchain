package scholarchain.core.model

import upickle.default.ReadWriter

/** Consolidated undirected coauthorship between two canonical researchers in one window.
  *
  * Invariants: `researcherA < researcherB`, `windowStartYear <= firstCollaborationYear <=
  * lastCollaborationYear <= windowEndYear`, `jointPublicationCount >= 1`.
  *
  * @param supportingWorkIdsRef
  *   stable pointer to the full supporting work-ID list when it is too long to inline
  */
final case class CoauthorEdge(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    windowStartYear: Int,
    windowEndYear: Int,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    supportingWorkIdsRef: Option[String],
    policyVersion: String,
    provenance: Provenance
) derives ReadWriter

/** Spark-computed collaboration measures for one researcher and one window.
  *
  * P_r publicationCount, C_r distinctCoauthorCount, T_r totalCoauthorOccurrences, A_r
  * averageCoauthorsPerPaper, M_r maximumCoauthorsOnOnePaper (with the responsible works), W_r
  * weightedDegree. Bridge measure name and value are present together or absent together.
  */
final case class ResearcherMetrics(
    schemaVersion: String,
    metricsId: String,
    researcherId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    bridgeMetric: Option[CollaborationMetric],
    bridgeScore: Option[Double],
    coreness: Option[Int],
    metricVersion: String,
    provenance: Provenance
) derives ReadWriter

/** A research-graph node. `nodeId` is the stable ID of the entity it represents. */
final case class GraphNode(
    schemaVersion: String,
    nodeId: String,
    nodeType: NodeType,
    label: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** A research-graph edge. For undirected edge types `fromId < toId`.
  *
  * @param sourceEvidenceIds
  *   stable IDs of the records that justify the edge; part of the edge ID
  */
final case class GraphEdge(
    schemaVersion: String,
    edgeId: String,
    fromId: String,
    edgeType: EdgeType,
    toId: String,
    properties: Map[String, String],
    policyVersion: String,
    sourceEvidenceIds: Vector[String],
    provenance: Provenance
) derives ReadWriter
