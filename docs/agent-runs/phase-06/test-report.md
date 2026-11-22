STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 06 adversarial Spark tests (fresh delegation; repair-01 step 5). Cases 1-14 are written under tests/src/test/scala/scholarchain/adversarial/spark/ with no production edits. Writes stayed under tests/** only.

All 44 new tests pass, the full `sbt check` passed, and no production defect was found. There is one low-severity resource observation for spark-engineer (under RISKS), with no failing test.

FILES CREATED:
- /home/olmos/scholarchain/tests/src/test/resources/log4j2-test.properties (WARN-level test logging)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkAdv.scala
  - Local SparkSession through GraphPipeline.configure; checkpoints go to tests/target/spark-adversarial-scratch.
  - Fixture builder rebuilt independently, because module test classes are not on this classpath. Inputs are wire JSON encoded from core case classes and read with the job's own `Schemas` in FAILFAST mode.
  - `SparkSuite` base: shared outputs are released after each suite.
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/Oracle.scala
  - Independent single-machine reference for the JOBS.md definitions: windows, edges and weights, P/C/T/M/maximumTeamWorkIds/W, components, synchronous LPA, coreness by peeling, bridge measures, cross-community edges.
  - Shortest paths are found by exhaustive enumeration of all shortest paths, taking the lexicographic minimum. This is not the production greedy walk.
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkGraphShapeSpec.scala (cases 1, 2, 6, 9)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkPairsWindowsSpec.scala (cases 3, 4, 7, 8)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkPathsAdvSpec.scala (case 10)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkLpaTieSpec.scala (case 11)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkDeterminismSpec.scala (case 12)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkDriverCollectScanSpec.scala (case 13)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/spark/SparkIdentityAdvSpec.scala (cases 5, 14)
- A temporary diagnostic, TmpPathDepthProbe.scala, was created and deleted. It is not in the tree.

FILES MODIFIED: none outside the files above. No production, build or constitutional file was touched.

COMMANDS RUN (I checked `pgrep -af sbt` before every sbt start; only one sbt ran at a time):
1. `sbt -batch adversarialTests/scalafmt adversarialTests/Test/scalafmt adversarialTests/Test/compile`: one compile error in my test (the name `col` was shadowed). Fixed, then recompiled successfully.
2. `sbt -batch "adversarialTests/testOnly scholarchain.adversarial.spark.*"` (run 1): 41 passed, 3 failed. All 3 were SparkPathsAdvSpec, with `OutOfMemoryError: Java heap space` during task serialization.
3. `sbt -batch "adversarialTests/testOnly scholarchain.adversarial.spark.SparkPathsAdvSpec"` in a fresh JVM: 4 passed, 0 failed. This isolated the cause: heap accumulates in the shared forked JVM (default max heap is about 1.9 GB on this 8 GB machine). It is not a path defect.
4. Rerun after adding afterAll cache release: 42 passed, 2 failed (paths, broadcast out-of-memory).
5. Temporary probe of Paths.shortestPaths on a 9-hop chain, maxDepth 1 to 9 (then deleted):
   - The optimized plan stays at 5,395 characters for NOT_FOUND and 20,652 for a 9-hop FOUND.
   - RDD lineage stays at about 1.1k to 1.5k characters.
   - So there is no plan or lineage blow-up with depth. Heap still in use after GC grew from 460 to 1,160 MB across 9 calls in one JVM.
6. Test-side mitigation:
   - Shared outputs moved into releasable memo holders, cleared in afterAll together with clearCache, unpersist of every RDD, and System.gc().
   - The test session caps the SQL and UI status store.
   - The paths suite now evaluates each depth once.
   - Rerun of `adversarialTests/testOnly scholarchain.adversarial.spark.*`: Tests: succeeded 44, failed 0, canceled 0, ignored 0 (7 suites, 9 min 37 s).
7. `timeout 4500 sbt -batch check`, started 17:33.
   - Format check, Test/compile and the `test` pass all succeeded. The `test` pass took 2206 s.
   - The second pass inside markTestRun would have finished about one minute before my own 4500 s wrapper fired. I killed only the `timeout` wrapper process (SIGKILL), so the line `exit=137` in the log comes from that wrapper. The sbt JVM itself kept running to completion.
   - Final output: "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run" and "[success] Total time: 1996 s, completed 6:44:04 PM". The marker file mtime is 2026-11-21 18:44:04.
   - Note: an older check.log from a previous run was already in the shared scratchpad. I discarded those lines and report only my own run.
8. No sbt JVM is left running (checked with pgrep).

TEST RESULTS: passed=44 failed=0 skipped=0 errored=0 for the new adversarial Spark tests, observed in the final targeted run and again in both passes of `sbt check`.

| Case | Spec | Result |
|---|---|---|
| 1. empty graph | SparkGraphShapeSpec | 1/1 PASS |
| 2. disconnected graph | SparkGraphShapeSpec | 3/3 PASS |
| 3. self-pairs after resolution | SparkPairsWindowsSpec | 2/2 PASS |
| 4. duplicate pair rows | SparkPairsWindowsSpec | 1/1 PASS |
| 5. UNRESOLVED/CONFLICT never merged | SparkIdentityAdvSpec | 2/2 PASS |
| 6. 50-author team | SparkGraphShapeSpec | 2/2 PASS |
| 7. works with no year | SparkPairsWindowsSpec | 1/1 PASS |
| 8. window boundary years | SparkPairsWindowsSpec | 1/1 PASS |
| 9. skewed hub | SparkGraphShapeSpec | 2/2 PASS |
| 10. no route, maxDepth boundary | SparkPathsAdvSpec | 4/4 PASS |
| 11. LPA tie-breaking | SparkLpaTieSpec | 8/8 PASS |
| 12. determinism | SparkDeterminismSpec | 4/4 PASS |
| 13. driver-collect source scan | SparkDriverCollectScanSpec | 6/6 PASS |
| 14. identity constraints | SparkIdentityAdvSpec | 7/7 PASS |

`sbt check` per-project counts, identical in both passes:
- adversarialTests: 25 suites, 352 succeeded, 0 failed, 9 ignored (308 pre-existing + 44 new).
- The other projects: 654, 212, 75 and 66 succeeded, all with 0 failed and 0 ignored.

VALIDATION RESULTS:
- Each new test can fail:
  - Every oracle comparison checks exact values row by row.
  - The scan spec runs its scanner on planted violations first: `out.edges.collect()`, `toLocalIterator()`, `first()`, `take(1000)`, `head()`, `collectAsList()` and `show(20)` are all flagged. Removing a bound comment flags that line, and a comment too far above the statement does not count.
- Against the real tree, the scan found at least 15 action sites, and all carry bound comments. One Scala Vector `.take(` in Paths.scala is allow-listed by its verbatim source line; a test fails if that line changes.
- Contract-violation checks return zero violations on every table kind exercised: edges, support, metrics, components, researchers, authored, communities, crossEdges, bridges, coreness, paths, facts, windowChanges, conflicts.

DEFECTS FOUND: none. No production assertion failed.

COVERAGE BY REQUIREMENT (what each case exercises beyond the spark-engineer module tests):
1. PART A and PART B together on empty input with benchmark pairs. Every window reports NO_PATH, every table is empty, all loops converge, and the window summaries are valid.
2. Six components including isolated researchers, compared to the oracle in 3 windows. Paths across components report NO_PATH. A windowed cut of a path breaks it. The distance estimate uses the largest component, with ties going to the smallest componentId. An edgeless graph is also covered.
3. One DBLP key twice on a paper: the A-A pair is dropped and counted per window (1/1/0), and the paper counts once. Variant: the same key with an ACCEPTED link splits as SAME_WORK_MENTIONS, and there is still no self-edge.
4. Repeated, swapped-orientation and cross-work duplicate pair rows give jointPublicationCount = distinct works, support rows match, and W == T.
5. An UNRESOLVED link that would otherwise create a chain does not join the cluster, so the coauthor merges cleanly and provenance cites only the ACCEPTED decision. CONFLICT and REJECTED decisions, including a DBLP-to-DBLP CONFLICT, give no merge and no conflict record.
6. 1225 edges and support rows, M = 49 with maximumTeamWorkIds = [BIG50], one community, coreness 49, oracle agreement, and a second paper that does not change M.
7. A forged pair row with a year for a year-less work is rejected as PAIR_YEAR_MISMATCH and no edge appears in any window. Year-less-only researchers appear only in the all-years window as singletons with coreness 0.
8. Years 2000, 2009, 2010, 2015, 2016, 2020, 2021 and 2025: inclusive at both ends. One pair with works in 2015 and 2016 splits exactly at the boundary, with window-scoped edge IDs.
9. A 150-spoke hub with repeated heavy edges, compared to the oracle (including LPA ignoring weights). Exact P, T, W and C for the hub, coreness, and 2-hop paths through the hub.
10. A path of length exactly maxDepth (7) is FOUND in both directions. maxDepth 6 gives NOT_FOUND_WITHIN_DEPTH. Other component, unknown researcher, and endpoints cut by a window give NO_PATH. maxDepth = 1 is covered. Hop evidence (weight-2 hop with 2 works, edge IDs) and the lexicographic minimum are checked against enumeration.
11. Communities.step on hand-made states covers:
   - all-tied votes go to the smallest label, with the own label included
   - more votes beat a smaller label
   - a tie between two 2-vote labels goes to the smaller one
   - the self-vote counts
   - ordinal order ("researcher:10" < "researcher:9", "B" < "a")
   - synchronous update (async would differ)
   - window isolation
   - an end-to-end check against the oracle rule including the converged flag
   No hashed-ID-order fixture outcome is encoded.
12. 18 tables, path queries, distance estimates and window summaries are identical across:
   - two identical runs
   - a run with reversed input order and 7 shuffle partitions
   The fixture includes an ORCID merge, a chain conflict, sampled paths, and year-less and oversize works. A seeded random graph agrees with the oracle.
13. Source scan of modules/spark/src/main, proven able to fail. A second pattern forbids any collect, take, head or first called directly on node, edge or path table names.
14. The identity constraints:
   - Chain through an OpenAlex-OpenAlex link stays split with reasons {SAME_WORK_MENTIONS, MULTIPLE_DBLP_AUTHOR_KEYS} and 5 members.
   - A DBLP-side ORCID and an OpenAlex ORCID that differ give MULTIPLE_ORCIDS. URL and lowercase forms of one ORCID merge.
   - Two OpenAlex IDs give MULTIPLE_OPENALEX_AUTHOR_IDS. URL and lowercase forms of one ID merge.
   - Homonym keys "Wei Wang 0001/0002" linked to one author are split. "Wei Wang" stays separate.
   - Three clean clusters that propose the same ORCID ID are evaluated together and split.
   - maxClusterSize 3: a cluster of 3 merges, a cluster of 4 gives CLUSTER_TOO_LARGE, and an unlinked 5-mention cluster gives no conflict.

ASSUMPTIONS:
- Expected values come from the definitions in modules/spark/JOBS.md and repair-01.md, implemented independently in Oracle.scala. The production code was not used as the oracle.
- The ORCID check character is computed by my own ISO 7064 implementation. The test asserts it reproduces the ORCID documentation example 0000-0002-1694-233X.
- An unlinked cluster larger than maxClusterSize is not a conflict, per rule 3 ("no ACCEPTED link is not a merge").

RISKS:
- Observation for spark-engineer, low severity, no failing test:
  - Persisted frames that are never released. Paths.scala:235 (`queries.cache()`), Paths.scala:196 (`nextP` in the BFS) and Paths.scala:266 (the forward-walk `nxt`, one per step) are never unpersisted. The final Iterative/KCore states are not released either.
  - A single production run is unaffected. Repeated calls in one driver JVM accumulate heap: in the shared test JVM this caused the OutOfMemoryError seen in runs 1 and 4. I mitigated it on the test side only.
- The adversarial JVM runs at the default heap of about 1.9 GB. The new suites add about 10 minutes to `sbt check`, which now takes about 37 min per `test` pass. Because markTestRun runs the tests a second time, `sbt check` takes about 70 min in total, over the 25-30 min the work order estimated. Raising the test heap or making markTestRun reuse results is build-engineer's decision, not mine.
- The source scan is lexical. A driver action reached through an alias (for example `val f = df.collect _`) would not be detected.

MANUAL ACTION REQUIRED: none. Optional: build-engineer could review the duration of `sbt check` and markTestRun's second test pass.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer fresh review of the full Phase 6 diff (repair-01 step 5), including these tests/** files.
