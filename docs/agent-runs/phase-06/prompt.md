# Phase 06 work order: temporal coauthorship graph and Spark analytics

## Orchestrator rules
- Delegate PART A and PART B to the spark-engineer agent, each as its own fresh delegation. Do not do the work inline.
- spark-engineer writes only inside modules/spark/** (sbt project sparkAnalytics) and its test tree.
- If a needed change belongs to another owner, spark-engineer returns BLOCKED-ON with exact evidence. The orchestrator routes it, the owner fixes only its artifact and runs sbt check, then spark-engineer is rerun in a fresh delegation. Expected routes:
  - Spark dependency/scope or run/runMain classpath problem -> build-engineer (Spark stays Provided; assembly scope unchanged)
  - Field missing from a core contract (CoauthorEdge, ResearcherMetrics, etc.) -> domain-engineer (add as optional/defaulted field, update contract tests)
  - Hadoop or entity-resolution output missing or malformed -> hadoop-engineer or entity-resolution-engineer
- Never report a command as passing unless its output was observed.

## Ground rules for every job
- Consume Hadoop outputs and entity-resolution decisions. Never reparse raw DBLP or source files.
- Take real input paths and formats from docs/agent-runs/phase-04/agent-report.md and docs/agent-runs/phase-05/agent-report.md. Do not guess. If local outputs are not on disk, regenerate them with the commands documented there (running another module is allowed; editing it is not).
- Use the Spark DataFrame/Dataset API. Do not add GraphX or GraphFrames; implement graph algorithms as iterative DataFrame joins (Scala 3 vs Scala 2.13 Spark binaries, and every algorithm must be explainable).
- Never collect a node, edge, or path table to the driver. collect/take/toLocalIterator only on small aggregates (counts, histograms, bounded benchmark-pair lists), each with a comment explaining the bound.
- Iterative algorithms: checkpoint directory and interval from configuration, configured maxIterations, and a reported converged/not-converged flag.
- Paths, windows, iteration limits, sample sizes, seeds, representative-work limit K, and benchmark pairs come from configuration via the Phase 3 loader. Nothing hardcoded.
- Every output row carries runId, windowStartYear, windowEndYear, schemaVersion. Every run writes a run manifest: inputs with counts/checksums, config, git SHA, seed, outputs, row counts.
- Deterministic: same inputs + config + seed -> same logical output. All ties break on the smallest ID.
- Output layout mirrors the spec's S3 layout (graph/nodes/type=..., graph/edges/type=..., graph/metrics/type=.../window=.../run_id=...) under the configured local root (local-run/ for local runs).

## Identity rules
- ACCEPTED researcher links map mentions to the validated canonical ID.
- A mention with no ACCEPTED link becomes its own researcher using the Phase 3 fallback ScholarChain researcher ID (DBLP author strings are already homonym-disambiguated, e.g. "Wei Wang 0001"). Never merge on name similarity. UNRESOLVED and CONFLICT never merge.
- Report researcher counts by ID source (validated external vs fallback).

## Metric definitions (use exactly these)
For researcher r in a window, papers = works in the window with an AUTHORED edge from r; n_p = number of DISTINCT canonical researchers on paper p.
- P_r = number of papers
- C_r = number of distinct coauthors
- T_r = sum over papers of (n_p - 1)
- A_r = T_r / P_r (0.0 when P_r = 0)
- M_r = max over papers of (n_p - 1); maximumTeamWorkIds = all works achieving M_r
- W_r = sum of jointPublicationCount over r's coauthor edges
Invariant: W_r == T_r, EXCEPT for researchers on works whose pairs Hadoop set aside as oversize teams. Count and report those exceptions; any other mismatch is an invariant failure.
- jointPublicationCount counts DISTINCT works (duplicate pair rows from one work count once).
- Windows: configured list including an all-years window plus fixed windows. A work belongs to a window if its year is in [start, end]. Works with no year appear only in the all-years window and are counted.

## Hand-checkable fixture (test oracle; must exist as a test fixture)
Works (year: authors): W1 2010: A,B,C | W2 2012: A,B | W3 2015: C,D | W4 2016: D,E,F | W5 2018: E,F | W6 2019: G,H | W7 2020: I
All-years expected:
- 8 edges: AB(2, 2010-2012), AC(1), BC(1), CD(1), DE(1), DF(1), EF(2, 2016-2018), GH(1)
- A,B: P2 C2 T3 A1.5 M2 [W1] W3 | C: P2 C3 T3 A1.5 M2 [W1] W3 | D: P2 C3 T3 A1.5 M2 [W4] W3
- E,F: P2 C2 T3 A1.5 M2 [W4] W3 | G,H: P1 C1 T1 A1.0 M1 [W6] W1 | I: P1 C0 T0 A0.0 M0 [W7] W0
- Components: {A..F} size 6, {G,H} size 2, {I} size 1; largest-component fraction 6/9; singletons 1
- Shortest path A->F length 3 (A-C-D-F); A->G no path
- Communities: {A,B,C}, {D,E,F}, {G,H} (assert grouping, not label values)
- crossCommunityDegree C=1, D=1, others 0; communitiesConnected C=2, D=2
- participationCoefficient C=D=4/9; A,B,E,F=0
- Coreness A..F=2; G,H=1; I=0
- Exact betweenness, if implemented for small graphs: C=6, D=6, others 0 (unnormalized, unordered pairs)
Window 2010-2015 expected: edges AB, AC, BC, CD only; D has P1 C1; E..I have no in-window papers (document whether they get no row or a P0 row).
Extra cases: self-pair (two mentions on one paper resolve to one researcher: pair dropped and counted, paper counted once); duplicate pair rows for one work; a 50-author paper (1225 pairs, M=49 for all); empty graph; oversize-team works set aside by Hadoop.
If an algorithm variant cannot reproduce a fixture expectation, report it. Do not special-case tests to pass.

## PART A: graph construction, breadth metrics, components
1. Canonical researcher and authorship builder: Researcher, Work, AUTHORED, and Venue/Topic/Institution tables from whatever the inputs actually contain (report missing enrichments; never invent). Referential-integrity check: every AUTHORED endpoint exists; violations counted and written to a reject output.
2. Temporal weighted coauthorship graph: map pair candidates to canonical IDs, drop and count self-pairs, order endpoints researcherA < researcherB, aggregate per window. Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, up to K representativeWorkIds, edgeId from the Phase 3 stable ID function, and a full support table (edge ID -> every work ID).
3. Researcher metrics per window: P_r, C_r, T_r, A_r, M_r, maximumTeamWorkIds, W_r, and the W_r/T_r invariant check. No combined score.
4. Connected components: iterative min-label propagation; componentId = smallest researcher ID in the component. Store componentId and componentSize. Report component count, largest-component fraction, singleton count per window.
Run with spark.sql.shuffle.partitions from config (use 8 locally so real shuffles happen).
Run sbt check, then the local bounded run via sbt "sparkAnalytics/runMain ...".
Report: STATUS block per CLAUDE.md, input paths used, row counts per table, self-pairs dropped, integrity violations, oversize-team exceptions, component stats, top 10 researchers by C_r with their P/T/A/M/W, partition counts, physical plans (explain) for the edge aggregation and one component iteration, exact commands and observed output. Then STOP.

## PART B: paths, communities, bridges, k-core, temporal, exports
5. Paths: BFS by iterative frontier joins, bounded by maxPathDepth. Benchmark pairs from config; if empty, select N pairs from the largest component with the configured seed and record them in the run manifest. Path evidence stores every hop with edge weight and supporting work IDs; among equal-length paths choose the lexicographically smallest. Estimate mean distance and 90th-percentile effective diameter from k seeded sample sources for components above a configured size; label it an estimate.
6. Communities: synchronous label propagation, unweighted, each node votes with its own label plus its neighbors' labels, ties to the smallest label, configured maxIterations, convergence reported. Store community membership, communitySize, and a cross-community edge table.
7. Bridges: crossCommunityDegree, communitiesConnected, participationCoefficient = 1 - sum over communities c of (k_rc / k_r)^2 using unweighted degree. Approximate betweenness (seeded sampled sources) is optional; if implemented, record k, seed, and that it is approximate.
8. k-core by iterative peeling; store coreness.
9. Temporal: per-window tables already exist; add a summary comparing consecutive windows (change in C_r, A_r, M_r, component size, bridge measures, community count and size distribution). Community IDs are window-local; state this limitation instead of comparing labels across windows.
10. Semantic-context inputs: structured fact records only (IDs, window, metrics, top topics/venues/institutions by count, representative work IDs and titles) for researcher profiles, communities, verified paths, and topic/institution summaries. No prose and no LLM; rag-engineer renders text in Phase 8.
11. Export: partitioned Parquet for all node, edge, metric, component, community, bridge, k-core, path-evidence, and semantic-input tables, plus one load manifest per target (neptune, dynamodb, s3vectors-input, s3) listing tables, paths, schema versions, row counts, partitions, file checksums. No store-specific formats (Neptune CSV etc. belong to storage-engineer in Phase 7).
EMR: no cluster exists until Phases 12-13. Run sbt packageJobs and record the sparkAnalytics jar path, checksum, and the exact spark-submit command that infra-engineer's run-bounded-pipeline.sh will use. Create no cloud resources.
Run sbt check and the full local bounded run.
Report: STATUS block, stage list with shuffle read/write per stage (from the Spark event log or a listener), partition counts, iterations and convergence for components/LPA/k-core, community count and size distribution, bridge-measure distribution and top 10, path test results, effective-diameter estimate, temporal windows used, integrity violations, output paths, manifest locations, jar checksum, exact commands and observed output.

## Amendments (from human pre-check)
- spark-graphx is already pinned in project/Dependencies.scala. Do not use it anyway: GraphX needs Long vertex IDs (string-ID hashing risks collisions), its label propagation can oscillate on two-node components, and its ShortestPaths returns distances without hop evidence. Leave the ledger unchanged; whether to remove the unused dependency is a later build-engineer decision.
- Scala 3 has no TypeTags, so spark.implicits case-class encoders are unreliable. Use the untyped DataFrame API (columns and Row) for Spark tables. Convert to and from core case classes only at the edges (tests and small outputs), with explicit mapping code. If an encoder or binary-compatibility problem still blocks compilation, return BLOCKED-ON build-engineer with the exact error instead of working around it.
- Before invoking test-engineer, the orchestrator routes build-engineer to make the adversarialTests project dependsOn sparkAnalytics and add the Provided Spark libraries to that test project only (same pattern as hadoopClient). build-engineer runs sbt check. This is a planned route, not a repair.
