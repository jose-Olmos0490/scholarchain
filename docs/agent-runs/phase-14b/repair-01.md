STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Repair LoadMain (LoadArgs, LoadRunner, LoadMain, LoadMainSpec) per /tmp/phase14b/review-report.md.

FINDINGS FIXED: 1, 2, 3, 4, 5, 6, 7 (all seven). None left unfixed.
- 1 (major): LoadRunner.runStep counts new rejects per step. If there are any, it writes a new ledger state `LedgerState.COMPLETED_WITH_REJECTS` (the detail JSON carries a "rejects" count) instead of SUCCEEDED. The skip-if-already-recorded check matches only SUCCEEDED with an identical detail, so a rerun after a conflict records a new attempt and prints no "already recorded". The new enum case is in modules/storage/.../dynamo/DynamoPorts.scala (rank 1). It is inside my territory, so no core change was needed and nothing is BLOCKED-ON.
- 2 (major): New tests in LoadMainSpec.
  - A fake item store holds a conflicting existing item. LoadMain.execute (dry run, with an injected store) exits 3, records CONTENT_CONFLICT, and makes no replace call. The stored item stays.
  - The ledger shows COMPLETED_WITH_REJECTS at attempts 1 and 2 on rerun, and never SUCCEEDED.
- 3: Tests added for the attempt increment after FAILED (FAILED attempt 1, then SUCCEEDED attempt 2) and for a non-directory `--spark-output` (exit 2). I did not test an invalid ledger-sequence env var or live-mode argument errors.
- 4: DynamoLoader is now called only when a metric cache is configured. With no cache the step logs a skip. Any Misconfigured or other error from DynamoLoader now fails the step. A test covers the no-cache path and a failing manifest with remote `s3://` paths.
- 5: SamplingItemStore counts and samples items written through putAllIfAbsent. A test covers it.
- 6: SparkOutputStager accepts only regular files that are not symlinks and whose real path is inside the Spark output root. Absolute paths outside the root are refused. The suffix lookup needs at least 2 trailing segments unless the path has a single segment. Tests cover an outside path, a symlink, and a valid suffix match.
- 7: LoadArgs reports an invalid path as a parse error (exit 2). In LoadMain, work-dir creation failure exits 2 and AWS client construction failure exits 2, each with a message. Any other unexpected exception during the load exits 1 with "LOAD FAILED". A test covers the bad path, the non-directory and the unwritable work dir. The client-construction catch has no test, because it would need a live client.

FILES CREATED: none new (the four LoadMain files were already untracked).
FILES MODIFIED (all under /home/olmos/scholarchain/modules/storage/src):
- main/scala/scholarchain/storage/dynamo/DynamoPorts.scala
- main/scala/scholarchain/storage/loader/LoadArgs.scala
- main/scala/scholarchain/storage/loader/LoadRunner.scala
- main/scala/scholarchain/storage/loader/LoadMain.scala (`execute` takes an optional `dryItems` store factory so tests can inject a store; `finish` is now `private[storage]`)
- test/scala/scholarchain/storage/LoadMainSpec.scala
COMMANDS RUN: sbt fmt; sbt check (run twice: the first run failed to compile the new test file, the second run passed after I fixed it)
TEST RESULTS: sbt check passed. The storage suite reported 75 tests, all passed. The last project line reported 579 tests, 0 failed, 24 ignored. markTestRun touched .claude/.last-test-run.
VALIDATION RESULTS: Nothing was run against AWS. No AWS client was built. build.sbt and everything outside modules/storage are untouched. The review verdict still needs a re-run.
ASSUMPTIONS:
- A work-dir or client-construction failure counts as a configuration problem (exit 2).
- A new ledger state is acceptable because the ledger sort key embeds the state name and the enum is read and written by name.
RISKS:
- Old readers of the run ledger that match only SUCCEEDED or FAILED will see the new state COMPLETED_WITH_REJECTS. Only the storage module reads it today.
- The stager no longer accepts symlinked or out-of-root manifest paths. A real Spark output that relies on symlinks would now fail with MissingFile.
- A reject-recording rerun adds one ledger entry per attempt, by design.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review, then the bounded load test.
