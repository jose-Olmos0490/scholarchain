STATUS: BLOCKED-ON
OWNER: rag-engineer
WORK ORDER: Phase 9 work order A of 2 (deterministic half of the runtime collaboration-analysis workflow). STEP 0 port inventory found missing operations, so I stopped as instructed. I wrote no code and changed no files.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: read-only file listing, grep and sed over modules/core, modules/storage, modules/rag. No sbt command was run, because nothing changed.
TEST RESULTS: not run (no change). I report no test counts.
VALIDATION RESULTS: STEP 0 port inventory below.

PORT MAPPING TABLE (storage and core as of this tree)

Required operation: Researcher metrics by researcherId + window
Existing port or type: ResearcherMetricCache.get(researcherId,
  windowStartYear, windowEndYear): Result[Option[ResearcherMetrics]].
  GraphReader.getResearcher also returns ResearcherNodeMetrics, but for
  the node window only.
Status: COVERED. ResearcherMetricCache is an optional table, so the
  wiring owner must provide it.
────────────────────────────────────────
Required operation: Top-K researchers by a CollaborationMetric + window,
  optional topic/venue/institution filters
Existing port or type: none
Status: MISSING
────────────────────────────────────────
Required operation: Direct coauthors, bounded, paginated
Existing port or type: GraphReader.directCoauthors(id, ws, we, limit)
Status: PARTIAL. It is bounded but has no offset or cursor, so it is not
  paginated.
────────────────────────────────────────
Required operation: Bounded path (maxDepth, result limit, timeout)
Existing port or type: GraphReader.shortestPath(source, target, ws, we,
  maxDepth): Result[Option[PathView]]
Status: PARTIAL. It returns one path, with no result limit or timeout
  parameter. StorageError has no typed timeout case, only Transient and
  Permanent. Supporting work IDs are not on PathView. A hop's
  evidencePointer comes from coauthorEdge.
────────────────────────────────────────
Required operation: Component and community records by researcherId +
  window
Existing port or type: none. ResearcherNodeMetrics carries only
  componentId and communityId. The core types ComponentMembership and
  CommunityMembership exist, but no port reads them.
Status: MISSING
────────────────────────────────────────
Required operation: Cross-community edges
Existing port or type: none. The core type CrossCommunityEdge exists,
  but no port reads it.
Status: MISSING
────────────────────────────────────────
Required operation: Name -> candidate researcher IDs from
  entity-resolution outputs (confidence, source IDs, affiliations)
Existing port or type: none. Core has MatchDecision and
  CanonicalResearcher, but no lookup by name.
Status: MISSING
────────────────────────────────────────
Required operation: S3 Vectors query with metadata filters
Existing port or type: VectorIndex.query(indexName,
  VectorQuery(embedding, topK, Option[VectorFilter])), returning
  VectorMatch with VectorMetadata (textS3Uri, textHash)
Status: COVERED
────────────────────────────────────────
Required operation: Fetch text by textS3Uri
Existing port or type: ObjectStore.getBytes(ref: ObjectRef)
Status: PARTIAL. There is no s3://bucket/key -> ObjectRef parser. Only
  ObjectLayout.s3Uri(ref) goes the other way.
────────────────────────────────────────
Required operation: Write QuerySession
Existing port or type: QuerySessions.create
Status: COVERED
────────────────────────────────────────
Required operation: Write EvidenceBundle
Existing port or type: EvidenceStore.putBundle
Status: COVERED
────────────────────────────────────────
Required operation: Write audit record
Existing port or type: none. RunLedger is keyed by run stage, and
  ArtifactRegistry is for artifacts. Neither holds a per-query audit
  record.
Status: MISSING

BLOCKED-ON OWNER: storage-engineer (ports). Also domain-engineer, for the config keys and the audit record contract (see below).

BLOCKED-ON EVIDENCE: exact Scala signatures needed. These are proposals in the existing style (Result[...], storage-owned view types). The storage-engineer may adjust the shapes, but every capability must remain. All bounds must be refused, not clamped, as in GraphReaderLimits. All queries must stay parameterized templates.

1. Top-K ranking (GraphReader addition, or a new MetricReader port):
final case class RankedResearcher(researcherId: String, displayName: String, metricValue: Double, metrics: ResearcherNodeMetrics)
trait MetricReader:
  def topK(metric: CollaborationMetric, windowStartYear: Int, windowEndYear: Int,
           filter: ResearcherFilter, limit: Int): Result[Vector[RankedResearcher]]
final case class ResearcherFilter(topicIds: Vector[String], venueIds: Vector[String], institutionIds: Vector[String])
The ordering is exactly by the requested metric, with ties broken by researcherId ascending. The port needs one template per metric, selected from an enum lookup table. Component and community metrics also need a template. The rag module cannot import storage's CollaborationMetric mapping, so storage must document which metrics are available per node window.

2. Pagination, replacing or extending directCoauthors:
final case class PageRequest(limit: Int, afterCoauthorId: Option[String])
final case class Page[A](items: Vector[A], nextAfter: Option[String])
def directCoauthorsPage(researcherId: String, ws: Int, we: Int, page: PageRequest): Result[Page[CoauthorView]]
The order is jointPublicationCount DESC, coauthorId ASC, so the cursor must encode both. A plain afterCoauthorId does not work with that order.

3. Bounded paths with timeout and supporting works:
final case class PathQueryLimits(maxDepth: Int, resultLimit: Int, timeoutMillis: Long)
def paths(source: String, target: String, ws: Int, we: Int, limits: PathQueryLimits): Result[Vector[PathView]]
// new case in StorageError:
final case class Timeout(operation: String, timeoutMillis: Long) extends StorageError  // retryable
def edgeSupport(edgeId: String): Result[Vector[String]]  // supporting work IDs resolved from evidencePointer (coauthor-edge-support-v1 rows)
edgeSupport could instead live in CoauthorEdgeView as supportingWorkIds. It could also live in a new EdgeSupportReader. A hop needs coauthorEdge plus supporting work IDs, so one port must return both for a hop.

4. Component and community records:
trait AnalyticsReader:
  def componentOf(researcherId: String, ws: Int, we: Int): Result[Option[ComponentMembership]]
  def communityOf(researcherId: String, ws: Int, we: Int): Result[Option[CommunityMembership]]
  def crossCommunityEdges(communityId: String, ws: Int, we: Int, limit: Int): Result[Vector[CrossCommunityEdge]]
  def crossCommunityEdgeCount(communityId: String, ws: Int, we: Int): Result[Long]
  def communitiesConnected(communityId: String, ws: Int, we: Int): Result[Long]
The CommunityAnalystAgent needs the stored edge count and the number of connected communities. These must not be computed in rag from a truncated edge list.

5. Name -> candidates:
final case class NameCandidate(researcherId: String, displayName: String, confidence: Double,
    sourceIds: Vector[String], affiliations: Vector[String], coauthorSample: Vector[String],
    matchDecisionIds: Vector[String], policyVersion: String, runId: String)
trait ResearcherDirectory:
  def candidatesByName(normalizedName: String, limit: Int): Result[Vector[NameCandidate]]
The port must say how confidence is derived. It should be the stored MatchDecision.confidence of the accepted cluster, not a recomputed value. If entity-resolution output is not yet loaded into any store, the entity-resolution-engineer must also supply it. I could not find a loader or table for it in storage.

6. Text by URI:
object ObjectRef: def fromS3Uri(layout: ObjectLayout, uri: String): Result[ObjectRef]
It must be validated so that a URI outside configured buckets is refused. Otherwise textS3Uri from retrieved metadata, which is untrusted, would become an arbitrary S3 read.

7. Audit record:
final case class QueryAuditRecord(schemaVersion: String, queryId: String, runId: String,
    planJson: String, researcherIds: Vector[String], evidenceIds: Vector[String],
    metricVersions: Vector[String], promptVersions: Vector[String], modelVersions: Vector[String],
    inputTokens: Long, outputTokens: Long, latencyMillis: Long,
    verificationResult: VerificationStatus, answerHash: String, provenance: Provenance)
trait QueryAuditLog:
  def append(record: QueryAuditRecord): Result[Unit]   // strict create, key audit#<queryId>
  def get(queryId: String): Result[Option[QueryAuditRecord]]
The record type should preferably live in core, because it is a contract (domain-engineer). The storage-engineer would own the port and the DynamoDB table. Terraform for the table is infra-engineer territory.

BLOCKED-ON domain-engineer (config; I have no config location of my own). RetrievalConfig currently has only graphLimit, vectorLimit, maxPathDepth, contextTokenBudget, fusionPolicyVersion. Proposed additions under retrieval.* and rag.* (each with an env override as in the existing pattern):
- retrieval.pathResultLimit
- retrieval.pathTimeoutMillis
- retrieval.evidenceMaxItems
- retrieval.coauthorPageSize
- rag.resolver.minConfidence
- rag.resolver.minMargin
- rag.resolver.maxCandidates
- rag.llm.maxRetries
- rag.llm.maxOutputTokens
- rag.planner.promptVersion
- rag.planner.schemaVersion

Prompt and schema versions could stay in prompts/**, which I own, with only the selected version in config.

ASSUMPTIONS:
- The instruction was to stop and write no code if any operation is missing. I treated "PARTIAL" rows as missing when they lack a stated requirement (pagination, timeout, result limit).
- "Neptune timeout -> retryable failure" needs a typed timeout in StorageError. Today it would have to be inferred from the Transient message string. I rejected that approach.

RISKS:
- If the ports are added without the typed Timeout error, PathFinderAgent would have to parse error strings. This is fragile.
- The name directory (item 5) may depend on entity-resolution outputs that are not loaded into any store yet. If so, the blocker extends beyond storage.
- ResearcherMetricCache is an optional table. If it is absent in a deployment, metrics-by-window must come from GraphReader.getResearcher, which holds only the node window.

MANUAL ACTION REQUIRED: orchestrator to route the storage-engineer and domain-engineer additions above, then re-issue Phase 9 work order A. Any human ratification needed for the audit table (a new DynamoDB table is infrastructure) is for the orchestrator to decide.

BLOCKED-ON OWNER: storage-engineer, domain-engineer

NEXT GATE: re-run STEP 0 after storage-engineer and domain-engineer deliver, then the rag-engineer implementation, then sbt check and the benchmark queries, then retrieval and groundedness evaluation and code-reviewer.
