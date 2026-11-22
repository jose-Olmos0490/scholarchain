STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 06 fresh read-only review of the full Phase 6 diff against HEAD da4bf8a (repair-01 step 5). Evidence read: docs/agent-runs/phase-06/{prompt.md, repair-01.md, agent-report.md, test-report.md, human-gate.md, part-a-run-manifest.json}.
VERDICT: REQUEST_CHANGES

**Summary.** The metric definitions, the components/LPA/k-core/BFS algorithms, smallest-ID tie-breaking, the ratified identity constraints and the bound comments on driver collects all match the work order, and I checked them line by line. Three problems block approval:
- Paths.bfs tries to release its cached frames each level, but it unpersists the wrong objects, so every BFS level's frames stay cached.
- `maxClusterSize` counts mentions, not people. With the configured value of 50, any author who has more than 50 mentions and at least one ACCEPTED link is split and gets a conflict record.
- human-gate.md still says ACCEPTED and still contains unfilled placeholders.

The LPA fixture deviation is real (I re-derived it by hand) and the tests report it honestly.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only: Read, Glob, Grep; one GitHub MCP read failed with 401 Bad credentials)

FINDINGS:

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | modules/spark/src/main/scala/scholarchain/spark/graph/Paths.scala:196, 198-199, 210, 212-217, 235, 266 | Any BFS with 2 or more levels, for example the distance estimate on a large component. `visited`/`frontier` are reassigned to `Iterative.wrap(...)` (:214-215), which returns a new uncached Dataset (a checkpoint or createDataFrame leaf). On the next level, `oldVisited.unpersist()`/`oldFrontier.unpersist()` (:216-217) do nothing, so every `visitedNext` (:199) and `frontierP` (:210) stays cached. `nextP` (:196), every forward-walk `nxt` (:266) and `queries.cache()` (:235) are never released either. | Cached data grows with depth × sources × component size (MEMORY_AND_DISK). This is more than the test-report observation: the unpersist calls exist but release nothing. test-engineer already hit OOMs in a shared JVM, and EMR runs will see executor memory pressure and disk spill. Fix: keep the persisted handles and unpersist those, as ConnectedComponents/Iterative/KCore do. | spark-engineer |
| 2 | major | modules/spark/src/main/scala/scholarchain/spark/identity/IdentityResolver.scala:301, 330 (clusterSize = countDistinct(mentionId), including every DBLP mention of the key anchor) | A DBLP author key with 51 papers plus one ACCEPTED link to an OpenAlex mention that has a valid ORCID, with maxClusterSize=50 (local-run.properties:17 and the spark-submit command). | The cluster gets CLUSTER_TOO_LARGE. Every mention falls back to the DBLP-key ID, the ORCID-based ID is lost, and an IdentityConflict is written. On the full DBLP corpus this hits prolific authors systematically. Nothing is wrongly merged, but identity quality drops for exactly the most-connected researchers, and the conflict count is inflated with false positives. "Cluster size" needs a human decision: mentions, or distinct identity anchors (DBLP keys and external IDs). The adversarial test (maxClusterSize 3) encodes the mention-count meaning. | spark-engineer (meaning ratified by human; config doc wording is domain-engineer's) |
| 3 | major | docs/agent-runs/phase-06/human-gate.md:3-6 | The file says "Decision: ACCEPTED" and still contains placeholders (`[N] works`, `[betweenness not implemented / approximate with k=..]`). The LPA fixture decision is still open. | If committed as-is, the phase evidence records acceptance before the open human decision (LPA deviation) and before this review. Human territory; reported only. | Human architect |
| 4 | minor | modules/spark/src/main/scala/scholarchain/spark/graph/GraphBuilder.scala:540-556 (C and W from coauthor edges only); asserted in modules/spark/src/test/scala/scholarchain/spark/ExtraCasesSpec.scala:112 | Oversize work O(P,Q,R) and work N(P,Z). The work-order definition C_r = distinct coauthors on r's papers gives C_P = 3; the code gives 1. | C_r is not "exactly" the prompt.md:31 definition for researchers with oversize-team works. The deviation is documented (agent-report PART A assumption 4, JOBS.md:569) and flagged by the wt_invariant exception, but no human has ratified it. | spark-engineer (human ratification) |
| 5 | minor | GraphBuilder.scala:652-660 (EXCEPTION_YEARLESS_WORK) | A year-less work in the all-years window: W < T, and the status is EXCEPTION_YEARLESS_WORK. | prompt.md:36 allows only the oversize exception ("any other mismatch is an invariant failure"). I checked the logic: a mismatch is excused only when W == Tpairable, and Tpairable differs from T only through oversize or year-less works. So no other failure is masked. The new exception class still needs human ratification. | spark-engineer (human ratification) |
| 6 | minor | ExtraCasesSpec.scala:99-171; tests/src/test/scala/scholarchain/adversarial/spark/SparkPairsWindowsSpec.scala:167 | No test combines an oversize or year-less work with a dropped pair on a normal work for the same researcher. | The "exception does not mask a real failure" property (W != Tpairable leads to INVARIANT_FAILURE) is correct in the code but untested. A regression that compared W against T minus the oversize part only loosely would still pass. | spark-engineer (module test) / test-engineer |
| 7 | minor | IdentityResolverSpec.scala:29-60; SparkIdentityAdvSpec.scala:142-144; SparkPairsWindowsSpec.scala:89-92 | Every chain or same-work test also trips MULTIPLE_DBLP_AUTHOR_KEYS, or involves one key whose split changes nothing. | No test covers the case where SAME_WORK_MENTIONS is the only violation and the split changes the outcome: one DBLP key with ACCEPTED links to two author slots of one OpenAlex work record. The reason list is asserted exactly, so removing the check would still fail a test; the gap is only in outcome coverage. | test-engineer / spark-engineer |
| 8 | minor | modules/spark/src/main/scala/scholarchain/spark/io/InputCatalog.scala:109-126; run/LoadManifests.scala:70-80, 94 | The EMR spark-submit command (agent-report.md:522-550) with s3:// inputs and outputs. | The driver streams every input file and every output Parquet file through a single SHA-256 pass. That is fine for bounded runs; on the full corpus it pulls all bytes from S3 through the driver. The risk is documented. It must be fixed before any non-bounded run (for example, reuse the Hadoop manifest checksums and compute output checksums on executors). | spark-engineer |
| 9 | minor | graph/KCore.scala:104, 138, 142; graph/Iterative.scala:63, 86-94; graph/ConnectedComponents.scala:128, 138 | Any run. | The last persisted state of each loop is returned but never unpersisted (the known observation). Unlike finding 1, the intermediate rounds are released correctly. | spark-engineer |
| 10 | minor | docs/agent-runs/phase-06/part-a-run-manifest.json | This file is in human-owned territory and is not one of the listed evidence files. I cannot tell from the file who wrote it. | If the orchestrator or an agent wrote it, that is an ownership violation (the orchestrator may write only agent-report/review-report/test-report). The human should confirm that they placed it. | Human architect / orchestrator |
| 11 | nit | run/GraphPipeline.scala:240 | The distance-estimate BFS depth comes from graph.components.maxIterations. | No dedicated config key. Documented as assumption 2; acceptable, but a later config change to components silently changes the estimate's bound. | spark-engineer (domain-engineer for any new key) |
| 12 | nit | identity/IdentityResolver.scala:321-331; modules/spark/JOBS.md:183; agent-report.md:184 | — | MULTIPLE_DBLP_PIDS exists in core (Analytics.scala:75) and appears in the report's zero-count list, but the Spark rule does not implement it. "Never fires" is true only because it is not implemented. | spark-engineer |
| 13 | nit | spark/Udfs.scala:95-110 | A coauthor slot with a malformed ORCID. | Malformed ORCID/OpenAlex values become null without being counted, so they can neither trigger MULTIPLE_ORCIDS nor show up in any statistic. | spark-engineer |
| 14 | nit | graph/Temporal.scala:80-90 | — | ResearcherWindowChange rows carry from/to window columns, not windowStartYear/windowEndYear (prompt.md:19). Documented, and the test was adapted. | spark-engineer |
| 15 | nit | agent-report.md:520-551 (spark-submit) | — | The jar name is 0.1.0 while Versions.DefaultCodeVersion is 0.2.0. Algorithm values (maxClusterSize=50, seeds, limits) are literals in the recorded command; infra-engineer's script must take them from configuration. The per-run checkpoint dir (GraphPipeline.scala:191) is never cleaned up, so S3 objects accumulate (cost). | spark-engineer (record) / infra-engineer (script) |

**LPA fixture deviation (pending human decision; reported, not decided).** I re-derived it by hand using Communities.scala:37-59 (synchronous, self-vote, ties to the smallest label).
- With IDs ordered C<E<G<H<I<F<D<A<B:
  - Iteration 1: A,B,C,D become C; E,F become E; G,H become G; I stays I.
  - Iteration 2: D sees C,C,E,E. The 2-2 tie goes to C, nothing changes, and the run converges in 2 iterations.
  - Result: {A,B,C,D}, {E,F}, {G,H}, {I}.
  - Bridge values: D has cross 2 and pc 4/9; E and F have cross 1 and pc 0.5.
- With IDs ordered A<…<I: the result is {A,B,C}, {D,E,F}, {G,H}, {I}, converged in 3 iterations, matching the oracle.

The tests are honest:
- CommunitySpec.scala:41-47 computes the ID order from production StableIds.
- Both outcomes are asserted against hand derivations (:49-147).
- The OrderedKeys fixture (Fixture.scala:264-278) changes only the input keys, not the algorithm.
- There is no special-casing in Communities.scala.
- The adversarial Oracle (tests/.../Oracle.scala:104-118) implements the same rule independently.

**Other checks:**
- **Metric definitions:** P, C, T, A, M, maximumTeamWorkIds, W and the distinct-work jointPublicationCount match prompt.md:29-37 (GraphBuilder.scala:410-439, 494-556, 613-651), except findings 4 and 5. Bridge measures match the work order (Communities.scala:154-206). communitiesConnected counts the researcher's own community; this is documented. Coreness by peeling with a level jump is correct (KCore.scala:50-82).
- **Paths:** the path choice is correct. BFS runs from the target, then a greedy smallest-neighbour forward walk at distance remaining-1 gives the lexicographic minimum (Paths.scala:245-274). It is checked against exhaustive enumeration in the Oracle (:161-190).
- **Identity rule** (IdentityResolver.scala:107-340):
  - Clusters use ACCEPTED links only, with unknown endpoints rejected (:108-117).
  - Same-work-record check (:284-289), ≤1 ORCID and ≤1 OpenAlex ID (:323-328), maxClusterSize (:330).
  - Violating clusters fall back to the DBLP-key ID and get an IdentityConflict with reason codes (:219-245).
  - Colliding external IDs are regrouped and split (:157-168).
  - The chain-case test is present (IdentityResolverSpec.scala:29-60).
- **Interpretations I assessed:**
  - "Same work = same work record" is acceptable, because the extra MULTIPLE_DBLP_AUTHOR_KEYS guard already prevents any merge of two DBLP keys. Residual risk: a single wrong-slot ER link stays undetected when no ORCID/OpenAlex ID conflicts; this is documented as an ER-side guard.
  - "Constraints apply only to clusters with an ACCEPTED link" is sound: without a link, a cluster is one DBLP key plus singletons, so nothing can be merged.
- **Driver collects:** every collect is on an aggregate or limited result with a bound comment. The source-scan test is shown to fail on planted violations (SparkDriverCollectScanSpec.scala:96-140).
- **Iteration bounds and checkpointing:** all come from config (SparkAnalyticsSettings, ScholarChainConfig.scala:424-608), and converged flags are reported for components, LPA and k-core. The identity loop refuses to emit when it has not converged (IdentityResolver.scala:256-263). shufflePartitions comes from config, with AQE coalescing disabled (GraphPipeline.scala:106-112).
- **Ownership:** each changed path matches its owner in docs/agents.md, except finding 10, which I could not attribute. project/Dependencies.scala is not in the diff, so the value/structure split does not apply. build.sbt:204-213 uses the existing Provided sparkSql.
- **Schemas:** SchemaVersions.scala:41-53 is additive only, with no existing version changed.
- **Secrets:** local-run.properties contains no secrets (paths, run IDs, numeric parameters).

TEST RESULTS: not run by reviewer.

VALIDATION RESULTS (each finding checked against the file):
- #1: yes. I read Paths.scala:169-218, 234-274 and Iterative.wrap (Iterative.scala:23-25).
- #2: yes. IdentityResolver.scala:298-330, local-run.properties:17, JOBS.md:172.
- #3: yes. human-gate.md:1-6.
- #4: yes. GraphBuilder.scala:540-556, ExtraCasesSpec.scala:99-122.
- #5: yes. GraphBuilder.scala:519-531, 652-660.
- #6: yes. Grep of WtFailure/WtException* across the module and tests/ test trees; ExtraCasesSpec.scala:99-171.
- #7: yes. Grep of SAME_WORK_MENTIONS across the test trees.
- #8: yes. InputCatalog.scala:109-139, LoadManifests.scala:70-95.
- #9: yes. KCore.scala:97-169, Iterative.scala:62-94, ConnectedComponents.scala:82-138.
- #10: partly. I read the file content; its author cannot be determined with read tools.
- #11: yes. GraphPipeline.scala:240.
- #12: yes. Grep of MULTIPLE_DBLP_PIDS.
- #13: yes. Udfs.scala:95-110.
- #14: yes. Temporal.scala:71-102.
- #15: partly. The command text is from agent-report.md; Versions.scala:12 and GraphPipeline.scala:191 were read. I did not inspect the jar.
- Not verified: that the existing golden files (golden/v1/*.json for pre-Phase-6 records) and their SHA256SUMS lines are byte-identical to HEAD. I have no git access, and the GitHub read returned 401. Only additivity in SchemaVersions.scala was verified. I did not read WindowSummaries.scala, CoreRecords.scala, Schemas.scala, StageMetricsListener.scala, the explain output files, or the core AnalyticsContracts in full.

ASSUMPTIONS:
- The changed-file scope is the orchestrator's git-status snapshot plus my Globs. The domain-engineer and build-engineer claims were checked only through the files I read (ScholarChainConfig.scala:325-608, env-overrides/reference.conf keys, SchemaVersions.scala, build.sbt:150-218, .gitignore).
- Findings 4 and 5 are documented deviations. I rate them minor because they need ratification, not code changes, unless the human rejects them.

RISKS:
- The real sample is degenerate: every component is one clique. All PART B logic is validated only by fixtures and the oracle.
- If a window's component loop does not converge, a deep pair can be reported NO_PATH instead of NOT_FOUND_WITHIN_DEPTH (Paths.scala:354-358; documented).
- The source scan is lexical; aliased actions escape it (documented by test-engineer).
- `sbt check` now takes about 70 minutes (test-report). That is build-engineer's call.

MANUAL ACTION REQUIRED:
- Human: decide the LPA fixture deviation (accept the ID-order-dependent result, or change the oracle or the algorithm).
- Human: decide what maxClusterSize counts (finding 2).
- Human: ratify or reject the C_r/W_r oversize exclusion and the year-less exception (findings 4 and 5).
- Human: revise human-gate.md (finding 3) and confirm who wrote part-a-run-manifest.json (finding 10).
- Human: delete the invalid run d6487046… and the superseded run 308a4868… as previously requested.

BLOCKED-ON OWNER: none for this review
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: owner repair of each finding:
- spark-engineer: #1, #2 (after the human decision), #6/#7 module tests, #8, #9, nits
- test-engineer: #6/#7 adversarial cases
- human: #3, #10, and the ratifications above

Then a fresh re-review.
