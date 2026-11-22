## PART A

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 06 PART A rerun after repair 01 (fresh delegation): canonical researcher, authorship and enrichment tables; temporal weighted coauthorship graph with an edge support table; P/C/T/A/M/W metrics with the W_r/T_r invariant check; connected components. I implemented the human-ratified identity rule. PART B was not started. Authoritative work order: /home/olmos/scholarchain/docs/agent-runs/phase-06/prompt.md.

**Summary.** `sbt check` passes on the current tree, including 38 Spark tests. The local bounded run over Hadoop run-b4 and ER r2 finished cleanly: 0 contract violations, 0 integrity rejects, 0 W_r/T_r failures, 0 identity conflicts. All fixture expectations for PART A reproduce, with no special-casing. The first real run found a defect in my same-work check; it is fixed and covered by a new test. That first run's output is still on disk and needs human cleanup (see MANUAL ACTION REQUIRED).

FILES CREATED (all under /home/olmos/scholarchain/modules/spark/):

Main sources, src/main/scala/scholarchain/spark/:
- SparkAnalyticsSettings.scala: loads the config section through the core loader.
- Versions.scala
- Schemas.scala: explicit read schemas.
- Udfs.scala: Java UDFn wrappers over the core StableIds formulas and normalizers.
- Windows.scala
- Lineage.scala
- CoreRecords.scala: maps Row to core records and runs a distributed contract check.
- identity/IdentityResolver.scala
- graph/ConnectedComponents.scala
- graph/GraphBuilder.scala
- graph/WindowSummaries.scala
- io/InputCatalog.scala
- run/GraphPipeline.scala: the main class.
- run/RunStatistics.scala
- run/StageMetricsListener.scala

Tests, src/test/scala/scholarchain/spark/:
- Fixture.scala
- SparkTestSupport.scala
- KnownGraphSpec.scala
- ExtraCasesSpec.scala
- IdentityResolverSpec.scala
- ConnectedComponentsSpec.scala
- SettingsSpec.scala
- PipelineIntegrationSpec.scala
- src/test/resources/log4j2-test.properties

Other:
- config/local-run.properties: the local env values.
- JOBS.md: definitions, identity rule, partitioning, shuffles, caching, checkpoints, limitations.
- local-run/**: output of the local run (generated).

FILES MODIFIED: none outside modules/spark.

COMMANDS RUN (output observed):
1. Read-only inspection: the work order, repair-01, the prior reports, core config/model/ids/contracts, build.sbt, Dependencies.scala, Hadoop J07/J09 code, and the input tables (zcat, python3 -I).
2. `sbt -batch sparkAnalytics/compile`. The first attempt had 9 errors (an unclosed comment, `identity` shadowed by a package name, the createDataset/Encoders call). After fixes it printed `[success]`.
3. `sbt -batch "sparkAnalytics/testOnly ...KnownGraphSpec"`. The first attempt aborted with OOM: the component-loop logical plan doubled every iteration. I fixed this by re-wrapping each iteration's labels as a leaf relation plus a reliable checkpoint every interval. The rerun passed 9/9.
4. `sbt -batch sparkAnalytics/test`. The first full run failed in 4 suites, for two reasons:
   - Plan blow-up across reused intermediates: 6.2 MiB task binaries and an OOM in explain. Fixed by `Lineage.cut`, an eager checkpoint at stage boundaries.
   - Parquet read-back of an empty partitionBy write failed with UNABLE_TO_INFER_SCHEMA. Fixed by writing per leaf, `window=<s-e>/run_id=<hex>`.

   The rerun passed 37/37 in 252 s.
5. `sbt -batch sparkAnalytics/scalafmtAll sparkAnalytics/Test/compile`: success.
6. First local run (exit 0, run ID `run:d6487046...`). It exposed the defect below, so I treat that run as invalid:
   - **Defect:** all 37 linked identity clusters were split with SAME_WORK_MENTIONS.
   - **Cause:** I had grouped works through ACCEPTED work links. The DBLP, OpenAlex and Crossref mentions of the same author slot then counted as "two mentions on one work".
   - **Check:** a python3 -I simulation over the inputs: 37 linked clusters, 0 with two members on the same raw work record, 0 with more than one DBLP key.
   - **Fix:** the rule now compares work records, which is the literal ratified rule. I added a regression test ("cross-source mentions of one author slot merge"); it fails under the old grouping.
7. `sbt -batch "sparkAnalytics/scalafmtAll" "sparkAnalytics/testOnly ...IdentityResolverSpec ...PipelineIntegrationSpec"`: 15/15 passed.
8. Final local run (exit 0, `[success] Total time: 68 s`):
   `env $(grep -v '^#' modules/spark/config/local-run.properties | xargs) sbt -batch "sparkAnalytics/runMain scholarchain.spark.run.GraphPipeline --local --code-version da4bf8a8c4aa58c9af6a160c98bb3e7c54be7835-dirty-sparksrc.b2b825f5a1d2"`
   The code version is the HEAD SHA plus a sha256 fingerprint of modules/spark/src and modules/spark/config, because the tree is uncommitted.
9. `sbt -batch check` (background, exit 0):
   - Tests: core 654, 212, 75, adversarial 308 (+9 ignored), spark 38. All succeeded, 0 failed.
   - `markTestRun: tests passed; touched .claude/.last-test-run`
   - `[success] Total time: 1174 s`
   - `find modules/spark -newer .claude/.last-test-run` (excluding target) returns nothing.

TEST RESULTS (sparkAnalytics, 38/38 passed in the final `sbt check`), case by case:

KnownGraphSpec (the hand-checkable fixture):
1. All-years window has exactly 8 edges: AB(2, 2010-2012), AC(1), BC(1), CD(1), DE(1), DF(1), EF(2, 2016-2018), GH(1). PASS.
2. Per-researcher metrics. PASS.
   - A, B: P2 C2 T3 A1.5 M2 [W1] W3
   - C: P2 C3 T3 A1.5 M2 [W1] W3
   - D: P2 C3 T3 A1.5 M2 [W4] W3
   - E, F: P2 C2 T3 A1.5 M2 [W4] W3
   - G, H: P1 C1 T1 A1.0 M1 [W6] W1
   - I: P1 C0 T0 A0.0 M0 [W7] W0
3. W_r == T_r for every row in every window. PASS.
4. Components {A..F} size 6, {G,H} size 2, {I} size 1; componentId is the smallest member ID. PASS.
5. Window summary: largest fraction 6/9, 1 singleton, 8 edges, 7 works, converged. PASS.
6. Window 2010-2015: edges AB, AC, BC, CD only. D has P1 C1 T1 M1 W1. A and C are as expected. E..I have no row; I chose no row over a P0 row. The component is {A,B,C,D} of size 4. PASS.
7. The edge support table lists every work (AB: W1, W2). representativeWorkIds is capped at K. supportingWorkIdsRef equals CoauthorEdgeSupport.supportRef. PASS.
8. Every output row satisfies its core contract and carries runId, windowStartYear, windowEndYear and schemaVersion. PASS.
9. Nine fallback identities, all UNLINKED. PASS.

ExtraCasesSpec:
1. Self-pair: the pair is dropped and counted (selfPairsDropped=1), the paper counts once, T uses distinct researchers, W=T. PASS.
2. Duplicate pair rows of one work: jointPublicationCount stays 2. PASS.
3. A 50-author paper: 1225 edges; M=C=T=W=49 for all; component size 50. PASS.
4. Empty graph: no rows; summaries are 0 and converged; contract-valid. PASS.
5. Oversize-team work (Hadoop sets its pairs aside): it counts in P, T and M but gives no edges. The status is EXCEPTION_OVERSIZE_TEAM, not a failure, and oversizeTeamWorkCount=1. PASS.
6. Year-less work: it appears only in the all-years window, with yearlessWorkCount=1, and gets EXCEPTION_YEARLESS_WORK. PASS.
7. A pair row with a null year is rejected as PAIR_MISSING_YEAR and never becomes an edge. PASS.
8. A missing pair row is detected as INVARIANT_FAILURE, which shows the check is not vacuous. PASS.
9. Integrity: two AUTHORED_WORK_MISSING and one PAIR_MENTION_NOT_AUTHORED rejects; every AUTHORED endpoint exists (anti-joins return 0). PASS.

IdentityResolverSpec:
1. Chain case: X and Y share paper XY, and both have ACCEPTED links to A1. The cluster stays split; X and Y keep their own fallback IDs, and the X-Y edge is kept. One IdentityConflict is written with [SAME_WORK_MENTIONS, MULTIPLE_DBLP_AUTHOR_KEYS], 3 members, contract-valid. PASS.
2. An ACCEPTED link to a mention with exactly one ORCID gives researcher:orcid for all mentions of that DBLP key. PASS.
3. Cross-source mentions of one author slot merge (regression test for the defect above). PASS.
4. An ACCEPTED link to an OpenAlex author with no ORCID gives researcher:openalex. PASS.
5. UNRESOLVED, CONFLICT and REJECTED decisions never merge. PASS.
6. MULTIPLE_ORCIDS: not merged. PASS.
7. MULTIPLE_OPENALEX_AUTHOR_IDS: not merged. PASS.
8. CLUSTER_TOO_LARGE with maxClusterSize=2: not merged. PASS.
9. Two DBLP keys reaching the same ORCID through separate clusters are not silently merged (MULTIPLE_DBLP_AUTHOR_KEYS). PASS.
10. Homonym keys "Wei Wang 0001" and "Wei Wang 0002" never merge. PASS.
11. An ACCEPTED decision naming an unknown mention is ignored and rejected as DECISION_UNKNOWN_MENTION. PASS.

ConnectedComponentsSpec (3/3 PASS):
- A 7-node path converges in 7 iterations (diameter 6, plus one confirming round).
- With maxIterations=3: converged=false, partial labels only (n7 gets label n4), never a wrong merge.
- Groups are isolated; a group with no edges converges in 1 iteration; an empty group is still reported.

SettingsSpec (2/2 PASS): the all-years window comes first, then the dev windows; a missing key is reported with its env var.

PipelineIntegrationSpec (4/4 PASS), on files laid out like J09 and the ER run directory:
- The run ID equals StableIds.runId of the written manifest, and every row carries it.
- All tables have 0 contract violations, and the counts match.
- The layout is correct.
- Determinism: a second run gives the same run ID and identical rows.

Fixture expectations that belong to PART B were not run: path A->F of length 3, no path A->G, communities, crossCommunityDegree, participation, coreness, betweenness.

VALIDATION RESULTS (local bounded run, run ID `run:308a4868d7982a312526fac1b28f8258dfc17311e3eecfc5a1082f7eb90d67df`)

Env vars and config, from modules/spark/config/local-run.properties:
- SCHOLARCHAIN_ENV=dev
- SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION=sc-coauthor-edge-policy-v1
- SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW=true
- SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT=5
- SCHOLARCHAIN_GRAPH_IDENTITY_VERSION=v1
- SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE=50
- SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS=50
- SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT=target/phase04/run-b4
- SCHOLARCHAIN_SPARK_HADOOP_RUN_ID=run:fa68b48b0cfb87ca7514ba90feeeabdb926a3cb60fb6a9483affdb4169fdb315
- SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR=modules/entity-resolution/local-run/er-v1/run_id=7449422852604780fd2de54fcaa1ff18ebe1cf761c260d8cf4de08b368ce4e15
- SCHOLARCHAIN_SPARK_OUTPUT_ROOT=modules/spark/local-run
- SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS=8
- SCHOLARCHAIN_SPARK_CHECKPOINT_DIR=modules/spark/target/spark-checkpoints
- SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL=3
- From dev.conf: windows 2000-2005, 2006-2010, 2011-2015, 2016-2020, 2021-2025; subset years 2000-2025, which is also the all-years window.

Inputs (rows / files / checksum):
- Hadoop, under /home/olmos/scholarchain/target/phase04/run-b4/scholarchain/spark_*/snapshot=2026-11-01/run_id=fa68b48b…:
  - spark_author_paper: 89 / 27 / sha256:bf05b6c1…
  - spark_coauthor_pair: 167 / 26 / sha256:7eeed1d2…
  - spark_work: 69 / 32
  - spark_author_mention: 193 / 2
  - spark_oversize_team: 0 / 0 (empty-listing hash e3b0c442…)
  - spark_venue: 13
  - spark_topic: 63
  - spark_institution: 32
  - spark_graph_edge: 182
- ER decisions, under /home/olmos/scholarchain/modules/entity-resolution/local-run/er-v1/run_id=7449422852604780fd2de54fcaa1ff18ebe1cf761c260d8cf4de08b368ce4e15/decisions: accepted 77, conflict 3, rejected 1, unresolved 11.

Output row counts (all under /home/olmos/scholarchain/modules/spark/local-run/graph/; contract violations 0 for every table that is checked):

| Table | Rows | Files |
|---|---|---|
| nodes/type=researcher | 89 | 8 |
| nodes/type=work | 24 (DBLP graph-scope works) | 7 |
| nodes/type=venue | 13 | |
| nodes/type=topic | 63 | |
| nodes/type=institution | 32 | |
| edges/type=authored | 89 | 8 |
| edges/type=has_topic | 12 | 7 |
| edges/type=coauthored_with | 334 (all windows) | 45 |
| edges/type=coauthored_with_support | 334 | 43 |
| metrics/type=researcher_collaboration | 178 | 39 |
| metrics/type=components | 178 | 30 |
| metrics/type=wt_invariant | 178 | 39 |
| metrics/type=window_summary | 6 | 11 |
| identity/type=identity_conflict | 0 | 1 (schema-only) |
| rejects/type=integrity | 0 | 1 (schema-only) |

Identity:
- Researchers by ID source (89): ORCID 9, OPENALEX 2, SCHOLARCHAIN_FALLBACK 78. By status: ORCID/MERGED 9, OPENALEX/MERGED 2, FALLBACK/UNLINKED 78.
- Identity groups: MERGED 37, UNLINKED 108. The identity loop converged in 3 iterations.
- Identity conflicts by reason: 0 total. SAME_WORK_MENTIONS, MULTIPLE_ORCIDS, MULTIPLE_OPENALEX_AUTHOR_IDS, MULTIPLE_DBLP_PIDS, MULTIPLE_DBLP_AUTHOR_KEYS and CLUSTER_TOO_LARGE are each 0.

Graph checks:
- Self-pairs dropped: 0.
- Integrity violations: 0 (no reject reason fired).
- Oversize-team exceptions: 0. The input has no oversize teams; spark_oversize_team is empty.
- W_r/T_r invariant: failures 0, exceptions 0. All 178 researcher-window rows are EQUAL (2000-2025: 89, 2000-2005: 20, 2006-2010: 5, 2011-2015: 16, 2016-2020: 13, 2021-2025: 35).

Component stats per window (count / largest-component fraction / singletons / iterations / converged):

| Window | Researchers | Works | Edges | Components | Largest | Fraction | Singletons | Iterations | Converged |
|---|---|---|---|---|---|---|---|---|---|
| 2000-2025 (all years) | 89 | 24 | 167 | 24 | 8 | 0.0899 | 0 | 2 | true |
| 2000-2005 | 20 | 7 | 23 | 7 | 5 | 0.2500 | 0 | 2 | true |
| 2006-2010 | 5 | 2 | 4 | 2 | 3 | 0.6000 | 0 | 2 | true |
| 2011-2015 | 16 | 5 | 32 | 5 | 8 | 0.5000 | 0 | 2 | true |
| 2016-2020 | 13 | 3 | 26 | 3 | 6 | 0.4615 | 0 | 2 | true |
| 2021-2025 | 35 | 7 | 82 | 7 | 8 | 0.2286 | 0 | 2 | true |

The sample has 0 year-less works. Each component is one paper's team: no DBLP author key appears on two works, so P=1 for everyone.

Metric distributions, all years: P [1, 1]; C, T, M, W [1, 7] with mean 3.753; A [1.0, 7.0]. The other windows are in run-summary.json.

Top 10 researchers by C_r, all-years window. Ties break on the smallest researcher ID; all 10 are SCHOLARCHAIN_FALLBACK with P=1 C=7 T=7 A=7.0 M=7 W=7 componentSize=8:
1. Ajay Joshi (sc…1c23dbfc)
2. Li Jiang 0002 (…2512aa6a)
3. Zongwu Wang (…414e6fcf)
4. Mahmoud Zangeneh (…4399cc1f)
5. Zhezhi He (…48684781)
6. Bennett B. Goldberg (…4f578906)
7. Tianyu Yang (…74ab0ffb)
8. Fangxin Liu (…8caa70f7)
9. Aydan Uyar (…90060ef8)
10. Wenbo Zhao 0005 (…9671e862)

Partitions and stages:
- spark.sql.shuffle.partitions=8, with AQE coalescing disabled so the 8 partitions are kept.
- 673 stages. Tasks per stage: 463 stages had 8 tasks; the rest had 1-56.
- 544 stages shuffled. Total shuffle read 4,203,005 B, write 4,189,694 B.
- The largest single-stage shuffle: stage 198, 8 tasks, read 334 records, write 334 records (edge aggregation).

Physical plans, full formatted text in graph/runs/run_id=308a…/explain-edge-aggregation.txt and explain-component-iteration-1.txt.

Edge aggregation:
`Scan ExistingRDD (checkpointed mapped pairs) -> Filter(!selfPair) -> BroadcastNestedLoopJoin Inner BuildRight (window condition; LocalTableScan of 6 windows broadcast) -> Project(least/greatest) -> HashAggregate partial_min(year) -> Exchange hashpartitioning(windowStart, windowEnd, researcherA, researcherB, workId, 8) -> HashAggregate min(year) -> ObjectHashAggregate partial(count, min, max, collect_list) -> Exchange hashpartitioning(windowStart, windowEnd, researcherA, researcherB, 8) -> ObjectHashAggregate -> Project(edgeId UDF, slice K, provenance)`

Component iteration 1:
`Scan ExistingRDD (labels) -> Exchange hashpartitioning(window, node, 8) -> Sort -> SortMergeJoin LeftOuter` with the messages. The messages are built as `Union(edges a->b, b->a) -> HashAggregate distinct -> Exchange(window, a, b) -> Exchange hashpartitioning(window, a, 8) -> SortMergeJoin Inner (labels on window, a) -> SortAggregate min(label) by (window, b) -> Exchange hashpartitioning(window, b, 8)`. The explain was taken after the directed-edge cache was released, so it shows the distinct being recomputed; inside the loop it is cached.

Run manifest and summary:
- Manifest: graph/runs/run_id=308a…/run-manifest.json (run-manifest-v1). It records 13 inputs with checksums, the logical config, the policy and metric versions (including entityResolutionPolicyVersions=er-v1 and hadoopRunId), and the code version.
- Companion run-summary.json: input and output row counts, paths, files, contract-violation counts, window summaries, statistics, the identity iteration count, explain paths and per-stage metrics.
- Seed: none; PART A has no sampled step.

ASSUMPTIONS:
1. **Identity rule.** Clusters are formed from ACCEPTED researcher links plus a DBLP author-key anchor. A cluster with no ACCEPTED link is not a merge; it gets the fallback ID per DBLP key.
   - The rule is checked only on clusters with at least one ACCEPTED link. A failing cluster gets fallback IDs for all its members and one IdentityConflict.
   - "Same work" means the same work record, not the same paper across sources. Grouping works through ACCEPTED work links made every legitimate cross-source merge fail on real data, as shown above.
   - If two merged clusters would claim the same external researcher ID, both are split and recorded as MULTIPLE_DBLP_AUTHOR_KEYS.
   - The inputs carry no dblp pids, so MULTIPLE_DBLP_PIDS never fires.
2. **No-year works.** CoauthorEdge v1 requires `firstCollaborationYear: Int`, so a year-less work can never support an edge. The real Hadoop output cannot produce one: J07 rejects year-less pairs. Year-less works count in the all-years window's P, T, M, workCount and yearlessWorkCount, and they give a W_r/T_r EXCEPTION_YEARLESS_WORK. A pair row with a null year is rejected as PAIR_MISSING_YEAR.
3. **Researchers with no papers in a window** get no metrics row; no P0 rows are written.
4. **C_r and W_r come from the coauthor graph.** Coauthors on oversize-team works are therefore absent from C and W. The invariant table reports this as EXCEPTION_OVERSIZE_TEAM, using W == T over pairable works.
5. **Graph scope** is DBLP works and DBLP mentions. Non-DBLP works and mentions are identity and enrichment evidence only. HAS_TOPIC is re-anchored to DBLP works only through direct ACCEPTED work links.
6. **Researcher-level fields:**
   - identityConfidence is the minimum ACCEPTED confidence for merged researchers and 1.0 for DBLP-key identities.
   - displayName is the DBLP author key.
7. **Metric and policy versions** are code constants recorded in the manifest. Output locations are left out of the run ID.

RISKS:
- Driver-side input checksums stream every input file. That is fine for bounded runs, but for the full corpus the Hadoop manifest checksums should be reused instead.
- Min-label propagation needs (diameter + 1) iterations. maxIterations must cover that. An unconverged identity loop fails the run; an unconverged component loop reports converged=false.
- Spark still warns "Broadcasting large task binary" (2.0–4.3 MiB) on some output/statistics jobs. Lineage cuts bound the plans but do not remove these warnings.
- The SAME_WORK check does not catch an ER link between different author slots of the same paper across sources. That guard sits with ER's SAME_WORK_SAME_POSITION; it is documented in JOBS.md.
- The real sample is degenerate for components: 24 single-paper teams, P=1 for everyone. The component, window and invariant logic is exercised by the fixtures, not by the real data.

MANUAL ACTION REQUIRED:
- Human: delete the invalid first local run before committing. I did not delete it, because the escalation policy reserves data deletion for humans. It is every path matching `modules/spark/local-run/**/run_id=d6487046057b33fb57b6cdc5f98a67f2278bce1f443803e41b77359be55589a6` (46 directories), plus `modules/spark/target/spark-checkpoints/d6487046…`. The valid run is `run_id=308a4868…`.
- Human: check the fixture before PART B, as the work order requires.
- Human: decide whether to commit modules/spark/local-run (9.7 MB of Parquet) or ignore it. .gitignore belongs to build-engineer.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer; then the human fixture check before the PART B delegation.

## PART B

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: Phase 06 PART B (fresh delegation), following docs/agent-runs/phase-06/prompt.md items 5-11 plus EMR packaging, as changed by the human scope decisions. The changes from the order: no betweenness; one seeded effective-diameter estimate for the largest component of each window; shuffle documentation by explain() plans plus partition counts; `sbt clean` before `sbt packageJobs`; one sbt process at a time.

**Summary.** PART B is implemented, `sbt check` passes on the final tree, and the local bounded run over the real inputs finished with 0 contract violations in every checked table.

The most important finding is a fixture deviation in communities. The specified label-propagation algorithm reproduces the expected grouping {A,B,C},{D,E,F},{G,H} only when the researcher IDs sort like the names A<B<…<I. The fixture's real hashed IDs sort C<E<G<H<I<F<D<A<B. With those IDs the same algorithm gives {A,B,C,D},{E,F},{G,H},{I}, and the bridge values change with it. I did not special-case the algorithm. I tested both orderings against hand derivations. This needs a human decision (see MANUAL ACTION REQUIRED).

**Chain-case test (open item from PART A).** It exists and asserts exactly the ratified case.
- File: /home/olmos/scholarchain/modules/spark/src/test/scala/scholarchain/spark/IdentityResolverSpec.scala
- Test: "chain case: X and Y share a paper and both link to A1; the cluster stays split"
- What it asserts: X and Y keep their own fallback IDs, the X-Y edge is kept, no self-pair, and one IdentityConflict is written with [SAME_WORK_MENTIONS, MULTIPLE_DBLP_AUTHOR_KEYS] and 3 members that satisfies the contract.
- Why the human may not have found it: the whole of modules/spark/ is still untracked in git (`?? modules/spark/`).

FILES CREATED (all under /home/olmos/scholarchain/modules/spark/):
- src/main/scala/scholarchain/spark/graph/Iterative.scala
- src/main/scala/scholarchain/spark/graph/Communities.scala (LPA, cross-community edges, bridges)
- src/main/scala/scholarchain/spark/graph/KCore.scala
- src/main/scala/scholarchain/spark/graph/Paths.scala (BFS, hop evidence, sampling, distance estimate)
- src/main/scala/scholarchain/spark/graph/Temporal.scala
- src/main/scala/scholarchain/spark/graph/SemanticFacts.scala
- src/main/scala/scholarchain/spark/graph/Analytics.scala (PART B orchestration)
- src/main/scala/scholarchain/spark/run/LoadManifests.scala
- src/main/scala/scholarchain/spark/run/AnalyticsStatistics.scala
- src/test/scala/scholarchain/spark/CommunitySpec.scala
- src/test/scala/scholarchain/spark/PathsSpec.scala

FILES MODIFIED (all under modules/spark/):
- Versions.scala: PART B versions; default code version 0.2.0.
- SparkAnalyticsSettings.scala: community, kcore, paths, diameter and semanticFacts sections; community.algorithm must be "label-propagation".
- Udfs.scala: semanticFactId and truncateText.
- CoreRecords.scala: row-to-record mapping and contract kinds for the 7 new records.
- graph/GraphBuilder.scala: adds researcherInstitutions (per-institution counts of affiliated mentions).
- graph/WindowSummaries.scala: community count and histogram, max coreness, LPA and k-core iteration reports.
- run/GraphPipeline.scala: runs PART B, writes the new tables and load manifests, adds three explain files.
- Tests: Fixture.scala (adds OrderedKeys and HandCheckableOrdered), SparkTestSupport.scala, SettingsSpec.scala (+2 tests), PipelineIntegrationSpec.scala (+2 tests; the window-column check now accepts the from/to years of ResearcherWindowChange).
- config/local-run.properties: PART B values.
- JOBS.md: jobs 5-11, packaging and risks. It documents that communitiesConnected counts the researcher's own community.

No files outside modules/spark/** were changed.

COMMANDS RUN (output observed for each):
1. `sbt -batch sparkAnalytics/compile`: [success].
2. `sbt -batch "sparkAnalytics/scalafmtAll" "sparkAnalytics/Test/compile"`: the first attempt had 2 errors, where `identity` was shadowed by package scholarchain.spark.identity in tests. Fixed with Predef.identity; the rerun was [success].
3. First targeted testOnly (CommunitySpec, PathsSpec, SettingsSpec): failed with AMBIGUOUS_REFERENCE `coreness`, because metrics already carried coreness. I stopped that obsolete run. Fix: drop communityId and coreness before re-joining in Temporal and SemanticFacts.
4. Same targeted testOnly: "Tests: succeeded 28, failed 0", [success] 195 s.
5. `sbt -batch sparkAnalytics/scalafmtCheckAll sparkAnalytics/test`: 65 passed, 1 failed. The failure was the integration window-column assertion, which did not expect ResearcherWindowChange's from/to window columns. I fixed the assertion.
6. `sbt -batch sparkAnalytics/scalafmtAll "sparkAnalytics/testOnly scholarchain.spark.PipelineIntegrationSpec"`: 6/6 passed, exit 0.
7. Local bounded run, exit 0, "[success] Total time: 150 s":
   `env $(grep -v '^#' modules/spark/config/local-run.properties | xargs) sbt -batch "sparkAnalytics/runMain scholarchain.spark.run.GraphPipeline --local --code-version da4bf8a8c4aa58c9af6a160c98bb3e7c54be7835-dirty-sparksrc.c4aee103862a"`
8. `sbt -batch clean packageJobs`: exit 0. Built the hadoop jar and modules/spark/target/scala-3.3.7/scholarchain-spark-analytics-assembly-0.1.0.jar.
9. `sha256sum` of the jar (taken after packaging and again after check, same value) and `unzip -l` (0 org/apache/spark classes, no scala/Predef.class, scala3 LazyVals present).
10. `sbt -batch check`: exit 0.
    - First test pass: 654, 212, 75, 308 (+9 ignored), 66, all with 0 failed; [success] 1519 s.
    - Second pass inside check, same counts; [success] 1456 s.
    - "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run".
    - `find modules/spark -newer .claude/.last-test-run` (excluding target and local-run) returned nothing.
    - `pgrep sbt` returned 0 processes; no background sbt is left running.

TEST RESULTS (sparkAnalytics 66/66 in the final sbt check). Fixture cases:

Communities and bridges, with name-ordered IDs (keys like "A 0002" chosen so the IDs sort A<…<I):
- Communities {A,B,C},{D,E,F},{G,H},{I}, converged in 3 iterations. PASS.
- crossCommunityDegree C=1, D=1, all others 0. PASS.
- communitiesConnected C=2, D=2, all others 1 (I has degree 0 and gets 1). PASS.
- participationCoefficient C = D = 4/9, all others 0. PASS.
- The only cross-community edge is C-D. PASS.
- Betweenness fields are null. PASS.

Communities and bridges, with the default IDs (order C,E,G,H,I,F,D,A,B), hand-derived:
- Communities {A,B,C,D},{E,F},{G,H},{I}, converged in 2. PASS.
- D: cross 2, connected 2, pc 4/9. PASS.
- E and F: cross 1, connected 2, pc 0.5. PASS.
- A, B, C, G, H, I: cross 0, connected 1, pc 0. PASS.
- This is the documented deviation from the work-order oracle.

k-core:
- A..F = 2, G and H = 1, I = 0 under both orderings, converged. PASS.
- Window 2010-2015: A, B, C = 2 and D = 1. PASS.
- With kcore.maxIterations=1: converged=false, only I gets a row, unpeeled count 8. PASS.

Other community checks:
- LPA with maxIterations=1 reports converged=false. PASS.
- Every community, cross-edge, bridge, coreness and metrics row satisfies its contract. PASS.
- Metrics carry communityId and coreness; bridgeMetric and bridgeScore stay unset. PASS.
- Window summary: communityCount 4; histogram (1,1),(2,1),(3,2); maxCoreness 2; 3 iteration reports. PASS.

Paths:
- A->F: FOUND, length 3, via A-C-D-F. Hops are A-C (weight 1, [W1]), C-D (1, [W3]), D-F (1, [W4]), and every edgeId exists in the edge table. PASS.
- A->G: NO_PATH, no hops, no length. PASS.
- F absent from 2010-2015 gives NO_PATH. PASS.
- With maxDepth=2, A->F is NOT_FOUND_WITHIN_DEPTH. PASS.
- Lexicographic tie: on the square P-Q-S / P-R-S, P->S goes through min(Q,R), and X->S is [P, min(Q,R), S]. PASS.
- With no benchmark pairs, sampling gives 3 distinct pairs from the largest component, deterministically; window 2016-2020 gives exactly {DE, DF, EF}; all sampled paths are FOUND and contract-valid. PASS.

Distance estimate:
- With k=6 (all sources), it is exact: histogram 1:14, 2:8, 3:8; mean 1.8; p90 = 3. In 2010-2015, mean 8/6 and p90 = 2. PASS.
- With k=2: exactAllSources=false, and the result is deterministic across runs. PASS.

Temporal, facts and settings:
- 2010-2015 -> 2016-2020: 9 rows. D's snapshots are P1/C1/component 4, then P1/C2/component 3. A has no "to" side and E has no "from" side. Contract-valid. PASS.
- Semantic facts: C's metrics are P2, C3, CORENESS 2, W3, componentSize 6; related researchers [D,A,B]; works [W3, W1] with their source titles. The PATH fact lists [A,C,D,F]. Counts: RESEARCHER 9, COMMUNITY 4, PATH 1. PASS.
- Settings: PART B keys load; a non-label-propagation algorithm is rejected. PASS.

Integration and regression:
- PART B tables are written with 0 violations; sampled queries and the seed are recorded; the three explain files contain an Exchange. PASS.
- There are 4 valid load manifests with per-file sha256 and sizes, which I verified against the bytes. PASS.
- The determinism test now also covers the 7 new tables. PASS.
- The 38 PART A tests are unchanged and pass.

VALIDATION RESULTS (run run:501e3dab6a5521b205d0d744f2c98aecda7fef1c4ccfe18c10344829663309ed; inputs: Hadoop run-b4 run:fa68b48b…, ER r2 run_id=74494228…)

Output row counts. All tables are under /home/olmos/scholarchain/modules/spark/local-run/graph/ and every contract-checked table has 0 violations.

| Table | Rows | Files |
|---|---|---|
| researcher | 89 | 8 |
| work | 24 | 7 |
| venue | 13 | 2 |
| topic | 63 | 2 |
| institution | 32 | 2 |
| authored | 89 | 8 |
| has_topic | 12 | 7 |
| coauthored_with | 334 | 45 |
| coauthored_with_support | 334 | 43 |
| researcher_collaboration | 178 | 39 |
| components | 178 | 30 |
| wt_invariant | 178 | 39 |
| metrics/type=communities | 178 | 30 |
| edges/type=cross_community | 0 | 6 |
| metrics/type=bridges | 178 | 39 |
| metrics/type=coreness | 178 | 39 |
| paths/type=path_evidence | 28 | 17 |
| semantic/type=semantic_fact | 278 | 98 |
| temporal/type=researcher_window_change | 123 | 31 |
| identity_conflict | 0 | 1 |
| integrity_reject | 0 | 1 |
| window_summary | 6 | 11 |

Semantic facts by subject: RESEARCHER 178 (one per researcher-window metrics row), COMMUNITY 48, PATH 28. The remaining 24 are TOPIC and INSTITUTION together; I derived that total and did not count them separately.

Integrity: 0 integrity rejects, 0 identity conflicts, 0 self-pairs, 0 W_r/T_r failures. No identity, edge or metric numbers changed from PART A.

Stages and partitions:
- spark.sql.shuffle.partitions=8, with AQE coalescing disabled.
- 2380 stages; tasks per stage: 8 tasks in 1974 stages, 1 in 140, 2 in 82, 16 in 66, 7 in 33.
- Total listener shuffle: read 12,334,651 B, write 11,284,771 B.
- Per-stage metrics are in run-summary.json.

Explain plans (files in graph/runs/run_id=501e…/):
- LPA iteration 1 (explain-lpa-iteration-1.txt):
  - SortMergeJoin of the old labels with the winners.
  - Votes: Union(neighbour votes via SortMergeJoin on hashpartitioning(window, a, 8), self votes), then HashAggregate on hashpartitioning(window, node, label, 8) for the vote count.
  - Exchange hashpartitioning(window, node, 8), WindowGroupLimit, Window(row_number) for the winner.
  - Join back on (window, node).
  - The iteration-1 plan still contains the upstream component-label plan (hashpartitioning on componentId), because the initial labels are not a leaf yet. Later iterations are re-wrapped leaves.
- BFS frontier step 1 (explain-bfs-frontier-step-1.txt; the executed, cached AQE plan):
  - BroadcastHashJoin Inner (frontier with the directed edges, which were materialized with HashAggregate distinct on hashpartitioning(window, a, b, 8)).
  - HashAggregate distinct on hashpartitioning(window, qid, node, 8).
  - BroadcastHashJoin LeftAnti against visited.
- k-core peel 1 (explain-kcore-peel-1.txt):
  - SortMergeJoins of the directed edges with alive-a and alive-b on hashpartitioning(window, a/b, 8).
  - HashAggregate degree on hashpartitioning(window, a, 8).
  - groupBy(window) min degree.
  - BroadcastHashJoin with the per-window level table.
  - SortMergeJoin LeftOuter back into the state on (window, node).

Iterations and convergence per window (components / LPA / k-core; all converged):

| Window | Components | LPA | k-core |
|---|---|---|---|
| 2000-2025 | 2/50 | 2/30 | 7/100 |
| 2000-2005 | 2 | 2 | 4 |
| 2006-2010 | 2 | 2 | 2 |
| 2011-2015 | 2 | 2 | 2 |
| 2016-2020 | 2 | 2 | 3 |
| 2021-2025 | 2 | 2 | 5 |

No unpeeled nodes in any window.

Communities (count; size x number of communities):
- 2000-2025: 24; 2x10, 3x4, 4x3, 5x2, 6x2, 7x1, 8x2
- 2000-2005: 7; 2x4, 3x1, 4x1, 5x1
- 2006-2010: 2; 2x1, 3x1
- 2011-2015: 5; 2x4, 8x1
- 2016-2020: 3; 2x1, 5x1, 6x1
- 2021-2025: 7; 3x2, 4x2, 6x1, 7x1, 8x1

On this sample, communities equal the components, because every component is a single paper's team (a clique).

Bridges:
- Every window has 0 bridging researchers; meanPC = maxPC = 0, maxCross = 0, maxCommunitiesConnected = 1.
- The top 10 for 2000-2025 (ordered by pc desc, cross desc, ID asc) all have cross 0 and pc 0. The first is researcher:openalex:6c4442cc… (degree 3), then researcher:openalex:d6b3b582…, researcher:orcid:206cd749…, researcher:orcid:27322f6a…, researcher:orcid:2e47f765…, researcher:orcid:49383942…, researcher:orcid:5cdfa5e8…, researcher:orcid:73859d15…, researcher:orcid:bc4022e6…, researcher:orcid:ddfd6583….
- cross_community has 0 rows. This is expected for the degenerate sample; the bridge logic is exercised by the fixtures.

Coreness histograms (coreness:count):
- 2000-2025: 1:20, 2:12, 3:12, 4:10, 5:12, 6:7, 7:16
- 2000-2005: 1:8, 2:3, 3:4, 4:5
- 2006-2010: 1:2, 2:3
- 2011-2015: 1:8, 7:8
- 2016-2020: 1:2, 4:5, 5:6
- 2021-2025: 2:6, 3:8, 5:6, 6:7, 7:8

Paths:
- No benchmark pairs are configured, so pairs were SAMPLED with graph.paths.seed=20261121 and samplePairCount=5, from each window's largest component.
- 28 queries: 5 per window, except 2006-2010, which has 3 because its largest component has only 3 members.
- All 28 are FOUND with length 1 (each component is a clique) and weight 1.
- Every query (pathId, source, target) is listed in run-summary.json under "pathQueries" and "analytics.paths".

Effective-diameter ESTIMATE (graph.diameter.seed=20261122, configured k=4, minComponentSize=3; largest component only):

| Window | Component size | k | Mean distance | p90 | Exact? |
|---|---|---|---|---|---|
| 2000-2025 | 8 | 4 | 1.0 | 1 | no (estimate) |
| 2000-2005 | 5 | 4 | 1.0 | 1 | no (estimate) |
| 2006-2010 | 3 | 3 | 1.0 | 1 | yes (all sources) |
| 2011-2015 | 8 | 4 | 1.0 | 1 | no (estimate) |
| 2016-2020 | 6 | 4 | 1.0 | 1 | no (estimate) |
| 2021-2025 | 8 | 4 | 1.0 | 1 | no (estimate) |

The values are trivially 1 because each component is a clique.

Temporal (windows used: all-years 2000-2025 plus fixed 2000-2005, 2006-2010, 2011-2015, 2016-2020, 2021-2025; consecutive fixed pairs only):

| Pair | Researchers | Edges | Components | Largest | Communities | maxCoreness | In both windows |
|---|---|---|---|---|---|---|---|
| 2000-2005 -> 2006-2010 | 20->5 | 23->4 | 7->2 | 5->3 | 7->2 | 4->2 | 0 |
| 2006-2010 -> 2011-2015 | 5->16 | 4->32 | 2->5 | 3->8 | 2->5 | 2->7 | 0 |
| 2011-2015 -> 2016-2020 | 16->13 | 32->26 | 5->3 | 8->6 | 5->3 | 7->5 | 0 |
| 2016-2020 -> 2021-2025 | 13->35 | 26->82 | 3->7 | 6->8 | 3->7 | 5->7 | 0 |

The limitation that community and component IDs are window-local is stated in the summary JSON and the report.

Load manifests (all under /home/olmos/scholarchain/modules/spark/local-run/graph/load-manifests/):

| Target | Path | Tables | Files |
|---|---|---|---|
| neptune | target=neptune/run_id=501e3dab…/load-manifest.json | 14 | 264 |
| dynamodb | target=dynamodb/run_id=501e3dab…/load-manifest.json | 11 | 332 |
| s3vectors-input | target=s3vectors-input/run_id=501e3dab…/load-manifest.json | 1 (semantic_fact) | 98 |
| s3 | target=s3/run_id=501e3dab…/load-manifest.json | 21 | 466 |

Run manifest and summary: /home/olmos/scholarchain/modules/spark/local-run/graph/runs/run_id=501e3dab6a5521b205d0d744f2c98aecda7fef1c4ccfe18c10344829663309ed/{run-manifest.json, run-summary.json}.

Jar:
- Path: /home/olmos/scholarchain/modules/spark/target/scala-3.3.7/scholarchain-spark-analytics-assembly-0.1.0.jar
- Size: 5,211,088 B
- sha256: 70c22c4a50eabea8ef65efcd81dee35db8f05521f0296a788222bc85b9aca578 (built after `sbt clean`)

Exact spark-submit for infra-engineer's run-bounded-pipeline.sh (EMR emr-spark-8.1.0, Spark 4.1.1). The angle-bracket values are deployment configuration, not hardcoded. Only the driver reads configuration.
```
spark-submit --deploy-mode cluster --master yarn \
  --class scholarchain.spark.run.GraphPipeline \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_ENV=<env> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION=sc-coauthor-edge-policy-v1 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_INCLUDE_ALL_YEARS_WINDOW=true \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_REPRESENTATIVE_WORK_LIMIT=5 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_IDENTITY_VERSION=v1 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_IDENTITY_MAX_CLUSTER_SIZE=50 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_COMPONENTS_MAX_ITERATIONS=50 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_COMMUNITY_ALGORITHM=label-propagation \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_COMMUNITY_MAX_ITERATIONS=30 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_KCORE_MAX_ITERATIONS=100 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_PATHS_MAX_DEPTH=6 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_PATHS_SAMPLE_PAIR_COUNT=5 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_PATHS_SEED=20261121 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_DIAMETER_SAMPLE_SOURCES=4 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_DIAMETER_SEED=20261122 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_DIAMETER_MIN_COMPONENT_SIZE=3 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_SEMANTIC_TOP_ENTITY_LIMIT=5 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_GRAPH_SEMANTIC_MEMBER_LIMIT=10 \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_HADOOP_INPUT_ROOT=s3://<data-bucket>/hadoop/<hadoop-run-prefix> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_HADOOP_RUN_ID=<run:hadoop-run-id> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_MATCH_DECISIONS_DIR=s3://<data-bucket>/entity-resolution/<er-run-dir> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_OUTPUT_ROOT=s3://<graph-bucket>/<prefix> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_SHUFFLE_PARTITIONS=<n> \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_CHECKPOINT_DIR=s3://<scratch-bucket>/spark-checkpoints \
  --conf spark.yarn.appMasterEnv.SCHOLARCHAIN_SPARK_CHECKPOINT_INTERVAL=3 \
  s3://<artifact-bucket>/jars/scholarchain-spark-analytics-assembly-0.1.0.jar \
  --code-version <git-sha>
```
`--local` is omitted on EMR. No cloud resources were created.

ASSUMPTIONS:
1. Benchmark pairs, when configured, are queried in every window. With none configured, sampling is per window, from that window's largest component.
2. BFS depth for the distance estimate is bounded by graph.components.maxIterations, because there is no separate config key. The p90 is the smallest d whose cumulative fraction is >= 0.9, without interpolation.
3. communitiesConnected counts the researcher's own community, so a researcher with degree 0 gets 1.
4. When k-core stops at maxIterations, the unpeeled nodes get no coreness row and the window reports converged=false.
5. ResearcherMetrics now carries communityId and coreness. bridgeMetric and bridgeScore stay unset, because no single score is produced.
6. Institution counts in semantic facts are researcher-level affiliated-mention counts, which are not dated.
7. Configured values: community.algorithm must equal "label-propagation", and bridge.* is not read.
8. The distance estimate goes into run-summary.json only, not a table, because no core record exists for it.
9. wt_invariant is not in any load manifest, because it is not a core record.

RISKS:
- LPA results depend on how the researcher IDs sort; this is the fixture deviation above.
- The real sample is degenerate: every component is a clique, so there are 0 bridges and all paths have length 1. The PART B logic is exercised by the fixtures, not by the real data.
- Load-manifest file checksums and input checksums are streamed on the driver. This is fine for bounded runs but must be revisited for the full corpus.
- If a window's component loop has not converged, a deep pair could be reported NO_PATH instead of NOT_FOUND_WITHIN_DEPTH.
- Spark warns "Broadcasting large task binary" (2 MiB) and "Asked to cache already cached data". Neither is functional.
- The EMR environment-variable passing has not been validated on a cluster.

MANUAL ACTION REQUIRED:
1. Human decision on the community/bridge fixture deviation:
   - (a) accept the specified ID-order-dependent LPA, with both tested outcomes; or
   - (b) change the fixture expectation or the algorithm definition.
   No algorithm change was made.
2. The invalid PART A run d6487046… is still on disk for human deletion. The superseded valid PART A run 308a4868… is also still on disk; the current run is 501e3dab…. I did not delete either.
3. modules/spark/ is untracked; the human commits it.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: Spark tests, sample run, output invariants, code-reviewer (and test-engineer adversarial Spark cases per repair-01 step 5)
