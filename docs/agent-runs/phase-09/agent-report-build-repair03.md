STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 9 repair 03a. Make sparkAnalytics tests reliable under `sbt check` (heap, fork, serial), build.sbt only, no test code changes. Report the duration and exit status of one final `sbt check`.

FILES CREATED: none
FILES MODIFIED: /home/olmos/scholarchain/build.sbt (project/* untouched)

Changes
1. `sparkAnalytics`: `Test / javaOptions ++= Seq("-Xms1g", "-Xmx3g")`.
   - `free -g` shows 7 GiB total, about 6 GiB available, 8 CPUs.
   - 3 GiB for the Spark test JVM leaves room for sbt and the OS.
   - The old default heap was 1/4 of RAM, about 1.8 GiB. That is the likely cause of the "Not enough memory to build and broadcast the table" failure.
2. The module was already forked (`Test / fork := true`) and serial (`Test / parallelExecution := false`) through `clusterTestSettings`. I left that unchanged.
3. Added `Global / concurrentRestrictions += Tags.limit(Tags.ForkedTestGroup, 1)`. Only one forked test JVM runs at a time across all projects, so the 3 GiB Spark heap does not compete with sibling modules' forked JVMs.
4. Removed the duplicate `test` step from the `check` alias. It is now `scalafmtCheckAll; scalafmtSbtCheck; Test/compile; markTestRun`.
   - `markTestRun` already depends on `Test/test` of every aggregated project, including adversarialTests. The separate `test` step ran the whole suite twice. That is why `check` took 30 minutes or more.
   - The marker semantics are unchanged. It is still written only after all tests pass, and it still fails if any test fails.
   - This is not a weakening, but code-reviewer should confirm.

Finding on the first run (first `sbt check` after changes 1 and 3)
- The first run exited 1 after 1877 s.
- The full `test` pass succeeded, including all of Spark (352 tests, 0 failures, 9 ignored). The PathsSpec OOM did not recur.
- The failure came in the second, duplicate test pass run by `markTestRun`. It was `OutOfMemoryError: unable to create native thread` (pthread_create EAGAIN) in rag's `BenchmarkSpec`, in the sbt JVM. It also crashed the sbt launcher.
- Cause: the non-forked rag tests start "rag-stage" threads in the sbt JVM, and they accumulated across two full passes. Removing the duplicate pass (change 4) avoided it.

COMMANDS RUN:
- `free -g`, `nproc`
- `sbt check` (run 1): exit 1, 1877 s, as described above
- `sbt check` (run 2, final, single foreground command, output redirected to a file, no pipe, timeout 5400000 ms): EXIT=0, duration 1863 s wall clock. sbt reported "Total time: 1850 s (0:30:50.0)".

TEST RESULTS (final run):
- Per-module totals, all with 0 failures: 212, 695, 216, 219, 75, 352 (9 ignored), 66.
- `markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run`. The marker file exists, written by sbt at 07:17.

VALIDATION RESULTS:
- scalafmtCheckAll and scalafmtSbtCheck passed. They ran before the tests in the final run, and the run exited 0.
- No test code or assertions were changed. No SparkSession change is needed, so there is no BLOCKED-ON spark-engineer.

ASSUMPTIONS:
- The dev host has 7 GiB RAM. `-Xmx3g` is sized for it. A smaller CI host would need a lower value.

RISKS:
- `check` still takes about 31 minutes. Almost all of that is Spark tests, and the one-forked-JVM limit serializes the modules' test groups.
- rag and any other non-forked modules still run tests inside the sbt JVM and spawn threads there. A single pass is fine now, but thread growth could come back as rag tests are added. The fix is to fork the rag tests, which would be a build-only change. I did not make it because it was out of scope.
- Spark tests are now bounded by a 3 GiB heap. If the Spark tests grow, they could still OOM.

MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer
