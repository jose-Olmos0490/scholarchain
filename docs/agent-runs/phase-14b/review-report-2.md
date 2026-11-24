STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only RE-REVIEW of the uncommitted storage loader changes (LoadArgs, LoadRunner, LoadMain, LoadMainSpec, DynamoPorts) against findings 1-7 in /tmp/phase14b/review-report.md.
VERDICT: APPROVE (no critical or major findings remain; the minor items below are advisory)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

PER-FINDING STATUS
| # | Status | Evidence |
|---|---|---|
| 1 | FIXED | LoadRunner.scala:214 takes the reject count before the step. Lines 226-235 write `COMPLETED_WITH_REJECTS` when the step added rejects, with a "rejects" count in the detail (321). The skip-if-recorded check at 336-337 fires only when the new state is SUCCEEDED and an existing entry is SUCCEEDED with an identical detail, so a rejects entry can never match. The new enum case is at DynamoPorts.scala:18, with rank 1. The wire format is `WireEnum.readWriter(values.toIndexedSeq)` (line 21), so it is read and written by name. The ledger sort key (DynamoStores.scala:117) embeds `rank-state`, so there is no key collision with SUCCEEDED or FAILED. Grep of every `LedgerState` use shows only storage code: LoadRunner, VectorIngestor (constructs SUCCEEDED/FAILED, no matching on the state) and storage tests. There are no exhaustive matches in `core`, `services` or other modules. The SUCCEEDED hits in scripts and services are unrelated job or run statuses. The test at LoadMainSpec.scala:276-293 asserts COMPLETED_WITH_REJECTS at attempts 1 and 2, no "already recorded" on the rerun, and no SUCCEEDED entry. It would fail if the fix were reverted. |
| 2 | FIXED (test fidelity is partly weak, see N1) | LoadMainSpec.scala:227-252 defines `ConflictStore`. The first ResearcherDirectoryEntry is treated as the victim. `putIfAbsent` returns AlreadyExists and `get` returns a different contentHash. Calls to `replaceIfHash` on the victim are counted. The test at 254-274 asserts exit 3 through `LoadMain.execute`, `replaced == 0`, a CONTENT_CONFLICT line in the output, and `rejected > 0` on a second run. `finish` returning 3 is asserted. If the loader used a replace path on the victim, `replaced` would be above 0 and the test would fail. If the conflict were not turned into a reject, exit 3 and `rejected > 0` would fail. The ledger never SUCCEEDED is asserted at 292. The port has no unconditional `put`, so a replace through `replaceIfHash` is the only overwrite path. That is what the test guards. |
| 3 | PARTIALLY FIXED | Added: attempt increment after FAILED (LoadMainSpec.scala:295-309) and a non-directory `--spark-output` giving exit 2 (357-374). Still untested: an invalid `SCHOLARCHAIN_STORAGE_LOAD_LEDGER_SEQUENCE` (LoadMain.scala:153-158), live-mode argument errors with a valid configuration, and a SUCCEEDED entry written when the detail differs. The repair report admits the first two. Minor. |
| 4 | FIXED (test fidelity is weak, see N2) | LoadRunner.scala:277-288. With `metricCache == None` the step logs a skip and returns Right. With `Some`, `DynamoLoader.load` runs and any error, including Misconfigured, propagates through the for-comprehension. There is no blanket swallow of Misconfigured any more. |
| 5 | FIXED | `SamplingItemStore.putAllIfAbsent` (LoadRunner.scala:116-119) counts and samples every item when the inner write succeeds. Test at LoadMainSpec.scala:391-397. The test calls the store directly. It does not drive a loader that uses `putAllIfAbsent`. |
| 6 | FIXED | LoadRunner.scala:63-74. A candidate is accepted only if it is a regular file with NOFOLLOW_LINKS (rejecting symlinked files) and its real path is inside the real root (rejecting outside absolute paths and symlinked parent directories). The suffix lookup needs at least 2 trailing segments unless the path has one segment. IOException maps to MissingFile and InvalidPathException to InvalidManifest. Test at LoadMainSpec.scala:376-389 covers an absolute path outside the root, a symlink pointing outside, and a valid suffix match. It would fail with the old accept-any-existing-path logic. |
| 7 | FIXED | LoadArgs.scala:92-96 turns InvalidPathException into a parse error, so exit 2. LoadMain.scala:56-63 wraps work-dir creation in Try and exits 2. 165-168 wrap AWS client construction in Try and exit 2. 71-77 catch NonFatal around the whole load and exit 1 with "LOAD FAILED". Test at LoadMainSpec.scala:357-374 covers a NUL-byte path, a non-directory and an unwritable work dir. The client-construction catch is untested, as the repair report admits. |

NEW FINDINGS (all minor, advisory)
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| N1 | minor | LoadMainSpec.scala:227-274 | `ConflictStore` never stores the victim. `get` is faked, and the comment "the stored item still has the other content" is not backed by an assertion on the inner store. | The "no overwrite" proof depends on the `replaced` counter and the fake, not on real stored state. A replace through some other port method would not be caught. Add an assertion that the inner store holds no item for the victim key. | storage-engineer |
| N2 | minor | LoadMainSpec.scala:311-355 | The "failing" half of the Misconfigured test uses remote paths in the manifest. That fails inside `GraphReadModelLoader` (the readModel step) before `DynamoLoader` runs. The no-cache half only asserts `ok(...)`. | The test would still pass if the old behaviour of swallowing Misconfigured from `DynamoLoader` were restored. It does not exercise finding 4's actual path. It should assert that `researcher_collaboration` items are absent in the no-cache run, and inject a failing metric cache for the propagate case. | storage-engineer |
| N3 | minor | LoadRunner.scala:116-119 | If an inner `putAllIfAbsent` returns Right while skipping items that already exist, the dry-run counts would include them. | Small dry-run overcount. This only matters if that is the port's semantics (not checked). | storage-engineer |
| N4 | minor | LoadRunner.scala:69-74 | The 2-segment suffix match can still select an unrelated file under the root with the same last two segments. | The SHA-256 verification after staging bounds the impact to a failed load. Accepted. | storage-engineer |

Verified clean:
- Ownership: the work-order status shows only storage files changed. I cannot run `git`, and the status snapshot is from session start. The files I read are all under modules/storage. Grep for `LedgerState` across the repo found no use outside modules/storage. I did not independently confirm that `build.sbt` and `project/**` are unchanged beyond the status snapshot.
- Secrets and hardcoded names: none found. Live mode reads bucket and table names from `StorageSettings`. The dry-run table names are placeholder strings.
- The dry run builds no AWS client.

TEST RESULTS: not run by reviewer. The repair report claims `sbt check` passed, 75 storage tests and 579 tests in total with 0 failed. I did not observe this.
VALIDATION RESULTS: findings verified against files: yes, for all of 1-7 and N1-N4 (read directly, not executed).
ASSUMPTIONS: the repair report's `sbt check` result is true. `putAllIfAbsent` semantics in N3 were not checked.
RISKS: the stager no longer accepts symlinked or out-of-root manifest paths. A real Spark output laid out through symlinks would fail with MissingFile (the repair report notes this). Any future ledger consumer must treat COMPLETED_WITH_REJECTS as not-success.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate and then the bounded load test. Optionally, storage-engineer tightens tests N1, N2 and the remaining untested paths in finding 3.
