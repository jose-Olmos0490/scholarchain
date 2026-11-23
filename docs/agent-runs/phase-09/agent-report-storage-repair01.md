# storage-engineer repair 01 (orchestrator relay summary; agent's verbatim report not captured)

STATUS: COMPLETE
OWNER: storage-engineer
Duration: 1h 21m 28s (64 tool uses)

sbt check ran once in the foreground with no pipe and exited 0, and the agent saw SBT_EXIT=0. It ran format check, compile, tests and markTestRun, and took 2072 s. All three test blocks passed with 0 failures. The storage module has 229 tests, 56 of them new. The AWS integration tests did not run.

Scope: every write is under modules/storage/. The modules/core and docs/agent-runs entries in git status were already there.

Final port signatures (package scholarchain.storage):
- GraphReader: directCoauthorsPage(researcherId, windowStartYear, windowEndYear, page: PageRequest): Result[Page[CoauthorView]] with an opaque base64url cursor over (count, coauthorId); paths(source, target, windowStartYear, windowEndYear, limits: PathQueryLimits): Result[Vector[EdgePath]].
- MetricReader: loadedWindows and topK(metric, windowStartYear, windowEndYear, filter: ResearcherFilter, limit): Result[Vector[RankedResearcher]]. Ties break by researcherId ASC.
- AnalyticsReader: componentOf, communityOf, crossCommunityEdges, crossCommunityEdgeCount, communitiesConnected.
- PathReader: paths(...) returns SupportedPath with supporting work IDs on each hop. Implemented as SupportedPathReader(graph, EdgeSupportReader); rag should depend on this port.
- ResearcherDirectory: candidatesByName(normalizedName, limit): Result[Vector[NameCandidate]], ordered by stored confidence DESC then researcherId ASC; confidence never recomputed.
- ObjectRef.fromS3Uri(layout, uri): Result[ObjectRef]: refuses foreign buckets, .. segments, keys over 1024 bytes.
- QueryAuditLog: append and get. Items in the existing QuerySession table, sort key AUDIT#<queryId>. Strict conditional create; repeat returns AlreadyExists. No TTL on audit items.
- StorageError: gains Timeout(operation, timeoutMillis), retryable = true.

Template IDs: getResearcher; directCoauthors; directCoauthorsPage/first; directCoauthorsPage/after; coauthorEdge; shortestPath/<d> and paths/<d> for d = 1..maxDepth; loadedWindows; topK/<METRIC> and topK/<METRIC>/topics for 9 metrics (18 templates).
Supported topK metrics: PUBLICATION_COUNT, DISTINCT_COAUTHOR_COUNT, TOTAL_COAUTHOR_OCCURRENCES, AVERAGE_COAUTHORS_PER_PAPER, MAXIMUM_COAUTHORS_ON_ONE_PAPER, WEIGHTED_DEGREE, CORENESS, COMPONENT_SIZE, COMMUNITY_SIZE.

Fake-only ports: AnalyticsReader, EdgeSupportReader, ResearcherDirectory (PathReader runs on fakes until EdgeSupportReader has a real adapter).
Real adapter + fake: MetricReader and GraphReader (Neptune), QueryAuditLog (DynamoDB).

Metrics per window: Researcher nodes carry metrics for one export window only; topK on other windows is refused with Unsupported naming the loaded windows. Bridge metrics, SHORTEST_PATH_LENGTH and COMMUNITIES_CONNECTED are refused for topK. Venue and institution filters are refused.

RISKS / GAPS:
1. Analytics: no store holds per-community crossCommunityEdgeCount or communitiesConnected; components, community membership and cross-community edges are not exported to Neptune (spans spark-engineer, infra-engineer, storage-engineer).
2. Edge support: no per-edge index for coauthored_with_support Parquet rows; a real adapter needs a DynamoDB table (infra-engineer) and a loader (storage).
3. Name directory: existing stores lack normalized name, source IDs, MatchDecision IDs; needs an entity-resolution output loader or a normalized-name node column. No loader added.
4. Neptune paths/<d> and topK templates unverified against a live cluster (paths uses join() and nested ALL(... range ...)); bounded AWS run needed.
5. Neptune timeout hint is a bounds-checked literal in query text (not parameterizable); tests show hostile IDs never reach query text.
6. Breaking changes inside storage: GraphReaderLimits, GraphReader, DynamoPorts, StorageSettings. rag's DynamoPorts(items, tables, ttl) call unchanged.
7. QueryAuditLogIT (skipped without AWS profile) writes random-ID audit items with no delete path.

NEXT GATE: code-reviewer on this diff; human decision on gaps 1-3; then rag-engineer reruns STEP 0 against the new signatures.
