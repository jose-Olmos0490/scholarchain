# ScholarChain Spark analytics: jobs 1-11

Owner: spark-engineer. Main class: `scholarchain.spark.run.GraphPipeline`.
Phase 06 PART A covers jobs 1-4 of the work order: canonical tables, the temporal weighted
coauthorship graph, collaboration metrics, and connected components. PART B covers jobs 5-11:
shortest paths and the sampled distance estimate, label-propagation communities, bridge
measures, k-core, the temporal comparison, semantic-input facts, and per-target load manifests.
One run of the main class executes both parts.

All Spark tables use the untyped DataFrame API. Core case classes are used only at the edges:
tests, the window summaries, and the distributed contract check `CoreRecords.withViolations`.
No node, edge or path table is collected to the driver. Every `collect` is on a bounded
aggregate, and the code comments the bound at each one. GraphX and GraphFrames are not used.

## Configuration

Everything comes from the core loader (`ConfigLoader.loadSection(SparkAnalyticsSettings, env)`):

| Key | Used for |
|---|---|
| `datasets.dblpSubset.startYear/endYear` | scope window recorded on non-windowed tables; all-years window |
| `graph.windows`, `graph.includeAllYearsWindow` | analysis windows (all-years window first) |
| `graph.coauthorPolicyVersion` | `CoauthorEdge.policyVersion`, edge IDs |
| `graph.representativeWorkLimit` (K) | `representativeWorkIds` cap |
| `graph.identity.version`, `graph.identity.maxClusterSize` | identity rule |
| `graph.components.maxIterations` | component, identity and work-link loops |
| `spark.hadoopInputRoot`, `spark.hadoopRunId` | Hadoop J09 export tables of one run |
| `spark.matchDecisionsDir` | entity-resolution run directory (`decisions/*.jsonl`) |
| `spark.outputRoot` | output root (`modules/spark/local-run` locally) |
| `spark.shufflePartitions` | `spark.sql.shuffle.partitions` |
| `spark.checkpointDir`, `spark.checkpointInterval` | reliable checkpoints of iterative loops (`<dir>/<run hex>`) |
| `community.algorithm` | must be `label-propagation` (the only implemented algorithm; anything else fails the load) |
| `community.maxIterations` | label-propagation iteration bound |
| `graph.kcore.maxIterations` | k-core peeling round bound |
| `graph.paths.maxDepth` | BFS depth bound of path queries |
| `graph.paths.benchmarkPairs` | benchmark path queries (run in every window); when empty, sampling below |
| `graph.paths.samplePairCount`, `graph.paths.seed` | sampled path queries per window |
| `graph.diameter.sampleSources`, `graph.diameter.seed`, `graph.diameter.minComponentSize` | distance estimate |
| `graph.semanticFacts.topEntityLimit`, `graph.semanticFacts.memberLimit` | semantic fact list bounds |

`bridge.*` is not read: betweenness is out of scope (human scope decision for PART B), so the
optional `BridgeMeasures.betweenness*` fields are always unset.

The local values are in `config/local-run.properties`.

These fixed SQL settings apply to every run (`GraphPipeline.configure`):

- `spark.sql.adaptive.coalescePartitions.enabled=false`, so the configured partition count is kept.
- Partition type inference is off.
- The session time zone is UTC.

Each output leaf directory (`window=<s>-<e>/run_id=<hex>` or `run_id=<hex>`) is written
separately with mode Overwrite. Only this run's own leaves are replaced, so a rerun of the same
run ID replaces identical content, and other runs are never touched. An empty leaf still gets a
schema-only Parquet file, so it stays readable.

**Lineage cuts.** Intermediate tables are reused by several downstream plans. Without a cut,
each reuse would copy the whole upstream plan tree, and the plans would grow multiplicatively.
The cuts are made with `Lineage.cut`: an eager reliable checkpoint to `spark.checkpointDir`, or a
local checkpoint when no directory is set. They are applied to:

- identity: phase-1 group stats, members, groups and mention identity
- AUTHORED
- mapped pairs
- edges
- researcher-paper rows
- per-researcher base aggregates

## Run identity and manifest

The run ID is `StableIds.runId(manifest)`. The manifest is a `run-manifest-v1` record. It contains:

- **Inputs:** each Hadoop table directory and each decision file. Its checksum is the SHA-256 of
  the sorted listing of `<relative path> <sha256 of bytes>` lines.
- **Logical parameters:** windows, K, identity version and maximum cluster size, maximum
  iterations, and the Hadoop run ID.
- **Policy and metric versions:** the code constants in `Versions.scala`, plus the entity-resolution
  policy versions and the Hadoop run ID.
- **Code version:** the `--code-version` argument, which carries the Git SHA.

Locations and execution settings do not change the logical output, so they are not part of the
run ID. They are recorded in `run-summary.json`, which is written next to the manifest. The
summary also holds:

- input row counts and checksums
- output paths, row counts, file counts, and contract-violation counts
- window summaries and statistics
- iteration reports
- per-stage task and shuffle metrics (`StageMetricsListener`)
- the paths of the explain plans

Seeds: `graph.paths.seed` (sampled path pairs) and `graph.diameter.seed` (distance-estimate
sources) are logical parameters, so they are part of the run ID; the summary repeats them and
lists every path query (`pathQueries`) with its selection (BENCHMARK or SAMPLED).

## Output layout (under `spark.outputRoot`)

```text
graph/nodes/type=researcher|work|venue|topic|institution/run_id=<hex>/
graph/edges/type=authored|has_topic/run_id=<hex>/
graph/edges/type=coauthored_with|coauthored_with_support/window=<s>-<e>/run_id=<hex>/
graph/metrics/type=researcher_collaboration|components|wt_invariant|window_summary/window=<s>-<e>/run_id=<hex>/
graph/metrics/type=communities|bridges|coreness/window=<s>-<e>/run_id=<hex>/
graph/edges/type=cross_community/window=<s>-<e>/run_id=<hex>/
graph/paths/type=path_evidence/window=<s>-<e>/run_id=<hex>/
graph/semantic/type=semantic_fact/window=<s>-<e>/run_id=<hex>/
graph/temporal/type=researcher_window_change/windows=<from>_<to>/run_id=<hex>/
graph/identity/type=identity_conflict/run_id=<hex>/
graph/rejects/type=integrity/run_id=<hex>/
graph/load-manifests/target=neptune|dynamodb|s3vectors-input|s3/run_id=<hex>/load-manifest.json
graph/runs/run_id=<hex>/{run-manifest.json, run-summary.json, explain-*.txt}
```

Every row carries `runId`, `windowStartYear`, `windowEndYear` and `schemaVersion`. Non-windowed
tables carry the scope window, which is the DBLP subset years. Record shapes:

| Table | Record shape |
|---|---|
| researcher | core `CanonicalResearcher` plus `idSource`, `identityStatus`, `idSourceCount`, `mentionCount` |
| work | core `WorkRecord` plus `linkedWorkIds` |
| authored | core `AuthorshipRecord` plus `teamSize`, `idSource`, `identityStatus` |
| coauthored_with | core `CoauthorEdge` |
| coauthored_with_support | core `CoauthorEdgeSupport` |
| researcher_collaboration | core `ResearcherMetrics` (with `communityId` and `coreness` from PART B; `bridgeMetric`/`bridgeScore` always unset) |
| communities | core `CommunityMembership` |
| cross_community | core `CrossCommunityEdge` |
| bridges | core `BridgeMeasures` |
| coreness | core `CorenessRecord` |
| path_evidence | core `PathEvidence` |
| semantic_fact | core `SemanticFact` |
| researcher_window_change | core `ResearcherWindowChange` (no window columns of its own: the record defines from/to window years) |
| components | core `ComponentMembership` |
| identity_conflict | core `IdentityConflict` |
| integrity | core `RejectRecord` |
| has_topic | core `GraphEdge` |
| window_summary | core `WindowGraphSummary` |
| wt_invariant | `wt-invariant-v1`, see below |

Venue, topic and institution are the Hadoop rows passed through unchanged. Their provenance is
the Hadoop provenance; the top-level `runId` is this run.

## Identity (rule `sc-identity-cluster-rule-v1`)

This is the human-ratified rule from Phase 06 repair 01, implemented in `identity/IdentityResolver.scala`.

1. **Identity graph.** The nodes are every author mention (DBLP, OpenAlex, Crossref) plus one
   anchor node per DBLP author key. DBLP author keys are homonym-disambiguated, for example
   "Wei Wang 0001". There are two kinds of edge:
   - ACCEPTED researcher decisions whose two endpoints are known mentions
   - DBLP mention -> its key anchor

   UNRESOLVED, CONFLICT and REJECTED decisions add nothing. No edge is ever built from name
   similarity. An ACCEPTED decision that names an unknown mention is ignored and written as an
   integrity reject (`DECISION_UNKNOWN_MENTION`).
2. **Clusters** are the connected components of that graph, computed with the same min-label
   propagation as job 4. If the loop does not converge, the run fails.
3. **No ACCEPTED link.** The cluster is not a merge (`UNLINKED`). Each DBLP mention gets the
   fallback ID `StableIds.researcherIdScholarChain(identityVersion, dblpAuthorKey)`.
4. **At least one ACCEPTED link.** The cluster is merged only if it has none of these violations:
   - `SAME_WORK_MENTIONS`: two members on one work record, that is, two author slots of one
     source record. Mentions of the same paper in different sources are on different work
     records (`work:dblp`, `work:openalex`, `work:doi`). They are the expected shape of a correct
     merge (ER reason `SAME_WORK_SAME_POSITION`), so they are not a violation.

     A first implementation grouped works through ACCEPTED work links. On the real inputs it
     split all 37 linked clusters, because each DBLP mention was linked to the OpenAlex or
     Crossref mention of the same slot. The regression test
     "cross-source mentions of one author slot merge" covers this.
   - `MULTIPLE_ORCIDS`: more than one distinct normalized ORCID.
   - `MULTIPLE_OPENALEX_AUTHOR_IDS`: more than one distinct OpenAlex author ID.
   - `MULTIPLE_DBLP_AUTHOR_KEYS`: more than one DBLP key.
   - `CLUSTER_TOO_LARGE`: more mentions than `maxClusterSize`.

   A violating cluster is not merged (`CONFLICT_SPLIT`). Each DBLP mention keeps its fallback
   ID, and one `IdentityConflict` lists all of the cluster's reasons, members and decisions.
   The chain case is the canonical example: X and Y are on one paper, and both are linked to A1.
   The cluster then has `SAME_WORK_MENTIONS` and `MULTIPLE_DBLP_AUTHOR_KEYS`, so it stays split.
5. **ID of a merged cluster.** The ID follows the frozen precedence:
   - the ORCID, if the cluster has exactly one
   - else the OpenAlex author ID, if exactly one
   - else the fallback ID of its single DBLP key

   There are no dblp person pids in the inputs, so `MULTIPLE_DBLP_PIDS` never fires.
   Two merged clusters can propose the same external-ID researcher ID. Both are then evaluated
   as one group, which always has two DBLP keys, so neither is merged and the conflict is
   recorded. Without this check, two clusters would silently merge through a shared ORCID.

Researcher-level attributes:

- **identityConfidence:** the minimum confidence of the group's ACCEPTED decisions for a merged
  cluster. It is 1.0 for a DBLP-key identity (UNLINKED or CONFLICT_SPLIT), which DBLP asserts.
- **affiliationInstitutionIds:** come from OpenAlex `AFFILIATED_WITH` edges of members of a merged
  cluster.

Limitations:

- An unlinked DBLP key whose own mentions carry an ORCID still gets the fallback ID. Only
  ACCEPTED links validate an external ID.
- Two DBLP keys of one person are not merged unless ER links them.
- Duplicate DBLP keys on one work, which Hadoop deduplicates, produce a self-pair. The builder
  drops and counts it.
- `SAME_WORK_MENTIONS` compares work records, not papers. ER could wrongly link a DBLP slot to
  the OpenAlex slot of a different coauthor on the same paper, and that link would be merged
  if no other constraint fired. ER's `SAME_WORK_SAME_POSITION` evidence guards this case;
  Spark does not.

## Job 1: canonical tables

**Inputs.** The Hadoop export tables of one run: `spark_author_paper` (author-paper-v1),
`spark_author_mention`, `spark_work`, `spark_coauthor_pair` (v1/v2), `spark_oversize_team`,
`spark_venue`, `spark_topic`, `spark_institution`, and `spark_graph_edge`. The four
match-decision files are also inputs. Each table is read with an explicit schema in FAILFAST mode.

**Outputs:**

- **Works:** the DBLP works (graph scope). Non-DBLP works are identity and enrichment evidence only.
- **AUTHORED:** one row per author-paper mention.
- **Researchers:** the canonical researchers that author at least one graph work.
- **Venue, topic and institution:** pass-through.
- **HAS_TOPIC:** OpenAlex `HAS_TOPIC` edges re-anchored to the DBLP work through a direct ACCEPTED
  work link (policy `sc-has-topic-via-accepted-work-link-v1`).

**Referential integrity.** Rejects are written as `reject-v1` rows with these reasons:

| Reason | Cause |
|---|---|
| `UNSUPPORTED_SCHEMA_VERSION` | an input row has an unsupported schema version |
| `DUPLICATE_AUTHORED_MENTION` | a mention appears more than once; the row with the smallest (workId, position) is kept |
| `AUTHORED_WORK_MISSING` | the work is not a DBLP work node |
| `AUTHORED_RESEARCHER_MISSING` | the DBLP mention has no author key |
| `PAIR_MISSING_YEAR` | a pair row has no year |
| `PAIR_MENTION_NOT_AUTHORED` | a pair endpoint is not an AUTHORED mention of the same work |
| `PAIR_YEAR_MISMATCH` | the pair year differs from the work year |
| `HAS_TOPIC_TOPIC_MISSING` | the topic does not exist |
| `DECISION_UNKNOWN_MENTION` | an ACCEPTED decision names an unknown mention |

Every AUTHORED endpoint therefore exists. The tests check this with anti-joins.

**Plan.**

- Identity is built from one component loop over the identity graph.
- A window function partitions author-paper rows by mentionId to find duplicates (one shuffle).
- Joins are sort-merge or broadcast as AQE decides. Work IDs are a small side.
- `authored` is cut (checkpointed) because it feeds pairs, metrics and the write.

## Job 2: temporal weighted coauthorship graph

1. Each pair candidate is mapped through AUTHORED on both (mention, work). Each endpoint
   becomes a canonical researcher.
2. The window year is the work's year from AUTHORED.
3. Self-pairs (`rA == rB`) are dropped and counted per window (`selfPairsDropped`).
4. Endpoints are ordered with `least`/`greatest`, so `researcherA < researcherB`.
5. Window assignment is a broadcast nested-loop join with the configured windows.
6. **Support table.** `groupBy(window, a, b, workId)` gives one row per distinct supporting work,
   so duplicate pair rows, and two mention pairs that resolve to one researcher pair, count once.
7. **Edges.** `groupBy(window, a, b)` gives:
   - `jointPublicationCount` = the number of distinct works
   - first and last year
   - `representativeWorkIds` = the K smallest work IDs
   - `supportingWorkIdsRef = coauthor-edge-support-v1:<edgeId>`
   - `edgeId = StableIds.coauthorEdgeId(a, b, start, end, policyVersion)`
8. **Shuffle boundaries.** There are two pair -> AUTHORED joins and two aggregations: support,
   then edges. They are hash-partitioned on (window, a, b[, workId]) into `spark.shufflePartitions`
   partitions. `edges` is cut (checkpointed) because it is reused by metrics, components and the
   write. The physical plan of the aggregation, before the cut, is written to
   `explain-edge-aggregation.txt`.

**Year-less works.** `CoauthorEdge` v1 has `firstCollaborationYear: Int`, so an edge cannot be
supported only by year-less works. Hadoop emits no pairs for a year-less work: J07 rejects them
with `MISSING_YEAR`. A pair row with a null year is rejected (`PAIR_MISSING_YEAR`). Year-less
works still count in the all-years window's P, T and M, and in `yearlessWorkCount`.

## Job 3: researcher collaboration metrics (`sc-collaboration-metrics-v1`)

For researcher r in window w, the papers are the window's works with an AUTHORED edge from r.
`n_p` is the number of DISTINCT canonical researchers on paper p.

| Measure | Definition |
|---|---|
| P_r `publicationCount` | number of papers |
| C_r `distinctCoauthorCount` | number of distinct coauthors in the window's coauthorship graph (edge count of r) |
| T_r `totalCoauthorOccurrences` | sum over papers of (n_p - 1) |
| A_r `averageCoauthorsPerPaper` | T_r / P_r. Every emitted row has P_r >= 1 |
| M_r `maximumCoauthorsOnOnePaper` | max over papers of (n_p - 1) |
| `maximumTeamWorkIds` | all papers achieving M_r, sorted |
| W_r `weightedDegree` | sum of `jointPublicationCount` over r's edges |

There is no combined score. Only researchers with at least one paper in the window get a row;
no P0 rows are written. In the fixture's 2010-2015 window, E..I have no row.

**Invariant.** W_r must equal T_r, except for researchers with oversize-team or year-less works,
because those works produce no pairs. The table `wt_invariant` therefore stores:

- T, W, and `pairableCoauthorOccurrences` (T over works that are dated and not oversize)
- the oversize and year-less work counts
- a status:
  - `EQUAL`: W == T
  - `EXCEPTION_OVERSIZE_TEAM`, `EXCEPTION_YEARLESS_WORK`, or both: W != T but W == pairable T
  - `INVARIANT_FAILURE`: any other mismatch, such as a missing pair row

Oversize-team works contribute to P, T and M, but not to C or W, because Hadoop set their pairs
aside.

**Plan.**

1. `groupBy(workId)` gives `n_p`, joined with the oversize work IDs.
2. A window join, then `groupBy(window, researcher)` gives P, T, M and the pairable T.
3. A join back finds `maximumTeamWorkIds`.
4. The edges are exploded to both endpoints and grouped by (window, researcher) to give C and W.
5. These are joined with the components.

## Job 4: connected components (`sc-components-min-label-v1`)

Each window's graph has the window's researchers as nodes and its coauthor edges as edges.
Labels start as the node ID. Each iteration does three things:

1. joins the directed edge list with the labels on (window, node)
2. computes `groupBy(window, node).min(label)` over the messages
3. joins back to the labels

These are the shuffle boundaries. The plan of iteration 1 is in `explain-component-iteration-1.txt`.

At the fixed point, `componentId` is the smallest researcher ID in the component, and
`componentSize` is the member count.

**Convergence.** A window has converged at the first iteration in which none of its labels
changed. That iteration count includes the confirming round, so a path of diameter d needs
d + 1 iterations. The loop stops when every window has converged, or at
`graph.components.maxIterations`. A window that has not converged is reported with
`converged = false`. Its labels are partial: they under-merge and never wrongly merge.

**Lineage, checkpointing and caching.**

- Each iteration's labels are persisted (MEMORY_AND_DISK).
- The per-window changed counts are the only collected values, one row per window.
- The labels are re-wrapped as a leaf relation over their RDD. Without this, the logical plan
  doubles every iteration.
- Every `spark.checkpointInterval` iterations, the labels are reliably checkpointed to
  `spark.checkpointDir/<run hex>`, which also truncates the RDD lineage.
- The previous iteration's cache is released once the next one is materialized.

**Window summary (`WindowGraphSummary`).** For each window:

- researcherCount, workCount and yearlessWorkCount
- coauthorEdgeCount and selfPairsDropped
- oversizeTeamWorkCount
- componentCount, largestComponentSize and singletonComponentCount
- the iteration report

The largest-component fraction is largestComponentSize / researcherCount. PART B fills
`communityCount`, `communitySizeHistogram` (sizes ascending), `maxCoreness`, and adds the
label-propagation and k-core iteration reports after the components report.

## Shared iteration plumbing (PART B)

`graph/Iterative.scala`. Every PART B algorithm processes all windows together, keyed by
`(windowStartYear, windowEndYear, ...)`. The undirected graph of a window is used as a directed
edge list with both orientations (`Iterative.directed`, one `distinct` shuffle). Each iteration's
state is persisted (MEMORY_AND_DISK), then re-wrapped as a leaf relation over its RDD, or
reliably checkpointed every `spark.checkpointInterval` iterations (`Iterative.wrap`), so neither
the logical plan nor the RDD lineage grows with the iteration count. The previous state's cache
is released after the next one is materialized. Collected per iteration: one changed/alive count
per window, or one frontier size.

## Job 5: shortest paths with hop evidence (`sc-shortest-path-bfs-lexmin-v1`)

**Queries.** Each configured benchmark pair is queried in every window, in its configured
direction. With no benchmark pair, each window's largest component (ties: smallest componentId)
contributes `samplePairCount` pairs: its members are ranked by `xxhash64(seed, researcherId)` (then
ID), the first `2 * samplePairCount` are collected (bound), and pairs are formed as (m0,m1),
(m2,m3), ... then the remaining index pairs in lexicographic order. Windows whose largest
component has fewer than 2 members get no sampled query. `pathId` is the frozen
`StableIds.pathId(source, target, window, coauthorPolicyVersion, runId)`.

**Algorithm.** A multi-root BFS from every query's TARGET, all queries and windows at once, by
frontier joins: frontier join directed edges on `(window, node)`, `distinct`, anti-join visited on
`(window, qid, node)`. A query leaves the frontier once its source is reached (broadcast join with
the query table). Depth bound `graph.paths.maxDepth`. The path is then rebuilt FORWARD from the
source: each step takes the smallest neighbour (ordinal ID order) whose distance to the target is
one less (one `groupBy(window, qid).min` per step, at most maxDepth steps). Every step's choice
is the smallest feasible next node and every feasible prefix extends to a shortest path, so the
result is the lexicographically smallest node sequence among all shortest paths.

**Evidence.** Each hop carries the windowed edge's `edgeId` and `jointPublicationCount` (join with
the edge table on the canonical pair) and every supporting work ID (support table, sorted). The
supporting-work count equals the weight (core contract).

**Status.** `FOUND` (length = hops <= maxDepth); `NOT_FOUND_WITHIN_DEPTH` when both endpoints are in
one component of the window but no path of length <= maxDepth exists; `NO_PATH` when they are in
different components or an endpoint has no paper in the window.

**Limitations.** If the component loop of a window did not converge, its componentIds
under-merge and a deep path beyond maxDepth may be reported `NO_PATH` instead of
`NOT_FOUND_WITHIN_DEPTH`. The visited table holds (queries x reached nodes) rows: bounded for the
configured query count.

## Distance estimate (`sc-distance-estimate-sampled-bfs-v1`)

Human scope decision: one seeded sampled estimate per window, for that window's largest
component only, if it has at least `graph.diameter.minComponentSize` members. `k =
graph.diameter.sampleSources` sources are the members with the smallest `xxhash64(diameter.seed,
id)` (collected, bound k). One multi-root BFS (no stop node) runs to exhaustion, bounded by
`graph.components.maxIterations` levels (a converged component loop proves the component's
diameter is below that bound; `bfsExhausted` reports whether the frontier emptied). The only
collected value is the distance histogram (windows x distances).

- mean distance = sum(d x pairs at d) / reached (source, node) pairs, d >= 1
- p90 effective diameter = the smallest d with cumulative fraction >= 0.9 (no interpolation)

It is labelled ESTIMATE in the run summary and the report. It is exact only when k >= component
size (`exactAllSources = true`). It is written to `run-summary.json` (`analytics.distanceEstimates`),
not to a table: no core record exists for it, so it is in no load manifest.

## Job 6: communities (`sc-communities-lpa-sync-v1`)

**Definition.** Synchronous label propagation on the unweighted simple graph of the window.
Labels start as the researcher ID. In each iteration every node counts one vote for its own
current label plus one vote per neighbour's current label (each distinct coauthor once,
regardless of `jointPublicationCount`), and takes the label with the most votes; ties go to the
smallest label. All nodes update from the previous iteration's labels. Stops when no label of any
window changed, or at `community.maxIterations`; a window that never had a change-free iteration
reports `converged = false` (synchronous LPA can oscillate; the self-vote makes the classic
two-node oscillation impossible). `communityId` is the final label (a researcher ID);
`communitySize` the member count. Isolated researchers are singleton communities.

**ID-order dependence (important).** Ties are broken on the smallest label, and in the first
iteration every vote count is 1, so the result depends on how researcher IDs sort. The work
order's fixture expectation ({A,B,C}, {D,E,F}, {G,H}) holds when the IDs sort like the names
A < B < ... < I. With the fixture's default DBLP keys "A".."I", the hashed IDs sort
C < E < G < H < I < F < D < A < B, and the same algorithm gives {A,B,C,D}, {E,F}, {G,H}, {I}
(hand derivation in `CommunitySpec`). Both cases are tested; the algorithm is not special-cased.

**Plan per iteration.** Join directed edges with labels on `(window, a)` (shuffle); union with
self-votes; `groupBy(window, node, label).count` (shuffle); `row_number` over
`partitionBy(window, node).orderBy(votes desc, label asc)` (shuffle); join back to the previous
labels on `(window, node)` to flag changes. Physical plan of iteration 1:
`explain-lpa-iteration-1.txt`.

**Cross-community edges.** Every windowed coauthor edge whose endpoints' communities differ, with
both community IDs and its weight (two joins on `(window, researcher)`).

**Limitation.** Community IDs are window-local labels. They are never compared across windows.

## Job 7: bridge measures (`sc-bridge-measures-v1`)

For researcher r in window w, with unweighted degree `k_r` and `k_rc` = number of r's neighbours
in community c:

| Measure | Definition |
|---|---|
| `degree` | `k_r` (distinct coauthors) |
| `crossCommunityDegree` | sum of `k_rc` over c != r's own community |
| `communitiesConnected` | number of distinct communities in r's CLOSED neighbourhood, i.e. r's own community plus every community with `k_rc > 0`. It COUNTS THE OWN COMMUNITY: a researcher with all neighbours in its own community (or none) has 1 |
| `participationCoefficient` | `1 - sum_c (k_rc / k_r)^2`, computed as `(k_r^2 - sum_c k_rc^2) / k_r^2` from exact integer sums (deterministic, no floating-point summation order), 0 when `k_r = 0` |

Fixture (IDs ordered like the names): C and D have crossCommunityDegree 1, communitiesConnected 2,
participation 4/9; all others 0, 1, 0. No betweenness (human scope decision); the optional
fields stay null. There is no combined bridge score; `ResearcherMetrics.bridgeMetric/bridgeScore`
stay unset.

**Parameters:** none beyond the community assignment. **Approximation:** none (exact given the
communities). **Limitations:** the measures inherit LPA's ID-order dependence; participation is
unweighted by design (work order).

**Plan.** Directed edges join neighbour communities on `(window, b)`, `groupBy(window, a, cb)` for
`k_rc`, join own community, `groupBy(window, a)` aggregates; left join from all members so
isolated researchers get degree 0.

## Job 8: k-core (`sc-kcore-peeling-v1`)

coreness(r) = the largest k such that r is in the k-core (maximal subgraph with every degree >= k).
Iterative peeling, all windows together, per-window level `k_w` (starts at 0, kept on the driver,
one value per window, broadcast as a tiny table each round):

1. degree of every alive node over edges with both endpoints alive (two joins with the alive set,
   `groupBy(window, a)`)
2. `kEff = max(k_w, min alive degree in the window)` (`groupBy(window)`); jumping past empty levels
   is exact because the alive set is then already a (min degree)-core
3. alive nodes with degree <= kEff are removed with coreness kEff; `k_w := kEff`

Every round removes at least one node of each non-empty window. A window converges when no node
is alive. At `graph.kcore.maxIterations`, still-alive nodes get NO coreness row (never a guessed
value); the window reports `converged = false` and the summary counts them (`kcoreUnpeeled`).
`CorenessRecord.degree` is the full window degree. Physical plan of round 1:
`explain-kcore-peel-1.txt`.

## Job 9: temporal comparison (`sc-temporal-window-change-v1`)

The fixed windows (the all-years window excluded) are sorted by start year; each adjacent pair
(from, to) gives one `ResearcherWindowChange` row per researcher with a metrics row in either
window (full outer join on researcherId). Snapshots hold P, C, A, M, componentSize, communitySize,
crossCommunityDegree, communitiesConnected, participationCoefficient and coreness; a side without
a row is null. Rows are written per pair under `windows=<from>_<to>`. Window-level changes
(researchers, edges, components, largest component, community count and size histogram, bridging
researchers, max coreness) and per-pair researcher counts are in `run-summary.json`
(`analytics.temporal`).

**Limitation (stated in the temporal summary).** Community and component IDs are window-local, so
only sizes and measures are compared across windows, never labels.

## Job 10: semantic-input facts (`sc-semantic-facts-v1`)

Structured `SemanticFact` records only: IDs, window, metric values copied from the computed
tables, ranked entities with counts, related researcher IDs and representative works with their
source titles. No prose and no model call; rendering text belongs to rag-engineer (Phase 8).
Names and titles are untrusted source text, truncated to 1024 code points
(`SemanticFact.truncateText`); a blank name falls back to the entity ID as label.

| Subject | One fact per | Metrics | Ranked lists | relatedResearcherIds | representativeWorks (<= K) |
|---|---|---|---|---|---|
| RESEARCHER | researcher with a paper in the window | P, C, T, A, M, W, componentSize, and when present communitySize, crossCommunityDegree, communitiesConnected, participationCoefficient, coreness | topics and venues of the researcher's in-window works (distinct works per entity); institutions = affiliated mentions per institution of the merged identity (not dated) | coauthors by jointPublicationCount desc, ID asc (<= memberLimit) | year desc, workId asc |
| COMMUNITY | community | COMMUNITY_SIZE | topics/venues of members' in-window works; institutions by number of affiliated members | members by weightedDegree desc, ID asc (<= memberLimit) | works with most members, then workId |
| PATH | FOUND path | SHORTEST_PATH_LENGTH | none | the path nodes in hop order (not truncated; <= maxDepth + 1) | the smallest supporting work of each hop, in hop order |
| TOPIC | topic with an in-window work (HAS_TOPIC) | PUBLICATION_COUNT | venues | researchers by in-window works on the topic | year desc, workId asc |
| INSTITUTION | institution with an affiliated researcher active in the window | PUBLICATION_COUNT (in-window works of affiliated researchers) | topics, venues | affiliated researchers by P desc | year desc, workId asc |

Ranked lists are sorted by count desc, then entity ID asc, bounded by `topEntityLimit`.
**Limitation:** affiliations are researcher-level (not dated), so INSTITUTION facts attribute every
in-window paper of an affiliated researcher to the institution.

## Job 11: export and load manifests

Every table is partitioned Parquet (see layout). One `LoadManifest` per target is written to
`graph/load-manifests/target=<t>/run_id=<hex>/load-manifest.json`, validated with the core contract:

| Target | Tables |
|---|---|
| `NEPTUNE` | researcher, work, venue, topic, institution, authored, has_topic, coauthored_with, researcher_collaboration, components, community_membership, cross_community_edge, bridge_measures, coreness |
| `DYNAMODB` | researcher, coauthored_with, coauthored_with_support, researcher_collaboration, components, community_membership, bridge_measures, coreness, path_evidence, window_summary, researcher_window_change |
| `S3VECTORS_INPUT` | semantic_fact |
| `S3` | every core-record table of the run |

Each table entry lists location, record type, schema version, partition columns, row count and
every Parquet file of this run with size and `sha256:`. `wt_invariant` is not a core record and is
in no manifest. No store-specific format (Neptune CSV, DynamoDB items, vectors) is produced: that
is storage-engineer's (Phase 7/8). File checksums are streamed on the driver (bounded run).

## EMR packaging

`sbt packageJobs` builds `modules/spark/target/scala-3.*/scholarchain-spark-analytics-assembly-<version>.jar`
(Spark Provided; scala3-library bundled; scala-library 2.13 supplied by EMR). The job reads its
configuration only from the environment (core loader), so on EMR the variables of
`config/local-run.properties` are passed with `--conf spark.yarn.appMasterEnv.<VAR>=...` and
`--conf spark.executorEnv.<VAR>=...` (only the driver loads configuration; executors need none),
with `s3://` locations for the input, output and checkpoint roots. The exact command is in the
Phase 06 PART B report.

## Output invariants checked on every run

- Every written row of the derived tables is mapped to its core record and validated with the
  core contract, on executors. The run summary reports the violation count and up to 5 samples
  per table. The derived tables are researcher, authored, has_topic, coauthored_with,
  coauthored_with_support, researcher_collaboration, components, identity_conflict and
  integrity. The tests require zero violations.
- W_r/T_r invariant: the failure and exception counts are reported.
- Every row's `runId` equals the manifest's run ID. The integration test checks this.

## Risks and limitations

- **Driver checksums.** Input checksums are computed on the driver by streaming every input file.
  This suits the bounded run. For the full DBLP corpus, the Hadoop run's own manifest checksums
  should be reused instead, which would change the RunInput formula.
- **Component iterations.** Min-label propagation needs (component diameter + 1) iterations.
  `graph.components.maxIterations` must exceed the largest expected diameter. Otherwise the run
  reports `converged = false`, or fails for identity clusters.
- **Edge-case skew.** A window's giant component concentrates the labels' `min` aggregation on
  hash partitions of node IDs, not on one key, so it is not skewed by component. A single 1000-author
  paper still produces about 500k pair rows on one work ID in the support aggregation.
- **Pair-less coauthors.** Coauthors on oversize-team works are absent from C_r and W_r. This is
  reported per researcher in `wt_invariant`.
- **PART B iteration counts.** LPA can oscillate; non-convergence is reported, not hidden. k-core
  needs about (number of distinct coreness values + peel waves) rounds. BFS levels are bounded by
  `graph.paths.maxDepth` (paths) or `graph.components.maxIterations` (distance estimate).
- **Shuffle documentation.** Per human scope decision, shuffle boundaries are documented with
  `explain()` plans (edge aggregation, component iteration 1, LPA iteration 1, BFS frontier step 1,
  k-core peel round 1) plus partition counts; per-stage listener metrics are also in the summary.

## Semantic JSONL export (`SemanticJsonlExport`)

Flat input of rag `IngestMain --input-dir`, written to `<spark.outputRoot>/graph/semantic-jsonl/`.
`GraphPipeline` runs it after the tables are written; it can also be run alone against an
existing run (same configuration environment as the pipeline):

```
sbt "sparkAnalytics/runMain scholarchain.spark.run.SemanticJsonlExport --local [--run-id run:<hex>]"
```

| File | Line type | Source tables | Sort key |
|---|---|---|---|
| `semantic-facts.jsonl` | core `SemanticFact` | `semantic_fact` (all windows) | `factId`, then line |
| `paths.jsonl` | core `PathEvidence` | `path_evidence` (all windows, every status) | `pathId`, then line |
| `papers.jsonl` | `{work, venueName, abstractText}` | `work` left join `venue` | `workId`, then line |

- **DAG and shuffles.** Per file: Parquet scan, JSON rendering UDF, `repartition(1)` (the one
  exchange), `sortWithinPartitions`, text write, rename of the single part file, then a count of
  the written file. `papers.jsonl` adds one left join on `venueId` (Spark's own broadcast rule).
- **No** caching, checkpoint, explicit broadcast or driver collect of data rows.
- **Determinism.** Same run tables give byte-identical files. A rerun replaces them.
- **Provenance.** Every record keeps its `provenance` (and `runId`, `metricVersion` where the
  contract has them). `export-manifest.json` records the run ID, metric version and line counts.
- **Directory holds one run.** The directory is not partitioned by run ID; the last export wins.
- **Limitations.** `abstractText` is always null (the pipeline carries no abstract text). One
  task writes each file, which suits the bounded run only.

