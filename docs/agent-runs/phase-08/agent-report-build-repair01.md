# build-engineer, Phase 8 repair 01 (final report, verbatim from session)

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 8 repair 01. Make the `rag` sbt project depend on `storage` as well as `core`. Change nothing else. Verify the graph is acyclic and run `sbt check`.

FILES CREATED: none
FILES MODIFIED: /home/olmos/scholarchain/build.sbt (one line: `lazy val rag` changed from `.dependsOn(core)` to `.dependsOn(core, storage)`)

DEPENDENCY CHOICE: I used a plain dependency, not `compile->compile;test->test`. The storage fakes that rag needs (`InMemoryVectorIndex`, `InMemoryObjectStore`, `InMemoryItemStore`, `InMemoryGraph`) live in `modules/storage/src/main/...`, so `compile->compile` already exposes them. Storage's Test sources (`modules/storage/src/test`) hold only test helpers and specs: `TestSupport`, `Fixture`, `Vectors` and `VectorContracts` (an `InMemoryVectorIndexSpec` contract suite), and the specs. rag does not need any of those. If rag later needs the storage test helpers, that is a separate work order.

MODULE DEPENDENCY GRAPH (internal edges only)
Before, from build.sbt prior to editing:
- core -> (none)
- hadoop -> core
- entityResolution -> core
- sparkAnalytics -> core
- storage -> core
- rag -> core
- services -> core, rag, storage
- adversarialTests -> core, hadoop, entityResolution, sparkAnalytics

After:
- core -> (none)
- hadoop -> core
- entityResolution -> core
- sparkAnalytics -> core
- storage -> core
- rag -> core, storage (changed)
- services -> core, rag, storage
- adversarialTests -> core, hadoop, entityResolution, sparkAnalytics

Cycle and boundary check:
- storage depends only on core, so there is no rag->storage->rag cycle.
- Nothing depends on services.
- The CLAUDE.md table allows rag to depend on `core` and `storage`, so the edge is permitted.
- hadoop, entityResolution and sparkAnalytics still do not depend on each other.
- sbt loaded the project definition without a cyclic-reference error.
- The "through ports only" rule for rag is a source-level discipline, not something the build enforces. Reviewers should check that rag imports only storage ports.

COMMANDS RUN:
- `sbt Test/compile` (before the full check): `[success] Total time: 7 s`.
- `sbt check`: finished with `[success] Total time: 24118 s (06:41:58.0)`. That runtime is almost certainly wrong. The WSL clock jumped partway through the run (log timestamps went from about 04:44 to 10:53), and tests took about 40 minutes on the wall clock. I also saw `markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run`.
- I did not capture the start of the `sbt check` output, because I piped it through `tail`. That means I did not see the output of the scalafmt checks or the full `Test/compile` step. They are steps of the alias, and the alias completed with `[success]`.
- Reads of the phase-08 evidence directory through grep were blocked by the guard hook (`docs/agent-runs/` is human-owned). The Read tool returned the file at `docs/agent-runs/phase-08/agent-report-rag-partial.md` as the single line `<paste rag-engineer report>`, so I never saw the rag-engineer's reasoning. I decided from the repository contents instead.

TEST RESULTS: The last three per-module result blocks in the tail of `sbt check` all show "All tests passed":
- 75 tests, 7 suites, 0 failed
- 66 tests, 8 suites, 0 failed
- 352 tests, 25 suites, 0 failed, 9 ignored (ignored tests are probably the AwsIntegration-tagged ones, which are excluded by default)

I did not see the result blocks for the other modules, including the rag module.

VALIDATION RESULTS:
- The marker `/home/olmos/scholarchain/.claude/.last-test-run` was updated by `markTestRun` after the tests passed. I did not touch it by hand.
- The only file I changed is `build.sbt`, and it is in my territory. No dependency versions, ledger structure, scopes or aliases changed.

ASSUMPTIONS:
- rag's storage use is limited to ports and the in-memory fakes in storage main.
- `Dependencies.rag` needs no change, because the storage dependency brings its libraries in transitively.

RISKS:
- rag now sees every storage compile dependency through `compile->compile`. Storage's `Dependencies.storage` libraries are on rag's compile classpath, including the AWS SDK and parquet libraries. If rag must stay free of those classes, storage needs its ports split into their own module. That is outside the current module table.
- The full `sbt check` is very slow here (about 40 minutes of wall clock, with the Spark and Hadoop adversarial suites dominating). Tool timeouts may push it to the background.
- The `services` assembly and Docker image now carry the same transitive storage libraries. That is unchanged, because services already depended on storage.
- `sbt check` ran while the rag-engineer's partially written `modules/rag` sources were present in the working tree. The tree compiled and tested cleanly, but the check says nothing about rag work that is still to come.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer

## Orchestrator verification (10:54 AM)
- Confirmed git diff: build.sbt is the only change (1 insertion, 1 deletion).
- Confirmed .claude/.last-test-run stamped 10:54:03, newer than build.sbt (03:39:13).
- An sbt-launch process was still running after the agent finished; not investigated.
