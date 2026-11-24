STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the uncommitted storage loader entry point (LoadArgs, LoadRunner, LoadMain, LoadMainSpec) under modules/storage.
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

FINDINGS:
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | modules/storage/src/main/scala/scholarchain/storage/loader/LoadRunner.scala:208-209 (also 155 and LoadMain.scala:55-67) | A live reload where an existing DynamoDB item has different content. The loaders record CONTENT_CONFLICT rejects and return Right. | `runStep` writes a SUCCEEDED ledger entry for the step even though rows were rejected. Only the exit code 3 shows the problem. A later run with the same manifests then logs "already recorded" and skips the ledger write. The ledger therefore claims success for a partial load. Fix: record the step's reject count, or a distinct state or detail, and exclude it from the skip match. | storage-engineer |
| 2 | major | modules/storage/src/test/scala/scholarchain/storage/LoadMainSpec.scala (missing case) | Pre-seed the item store with a conflicting item, run the load, then check the stored item. | Nothing tests the "no unconditional overwrite" requirement through LoadMain, nor exit code 3 (finish, LoadMain.scala:62-66). If the loader were switched to an overwriting put, every test here would still pass. The reload test only covers the identical-content path. | storage-engineer |
| 3 | minor | modules/storage/src/test/scala/scholarchain/storage/LoadMainSpec.scala:161-185 | FAILED then successful retry. Also a rerun after the manifest row counts change. | The ledger attempt logic (`record`, LoadRunner.scala:317) is only tested at attempt 1. Attempt increment after FAILED (expected 2), and writing a new SUCCEEDED entry when the detail differs, are untested. No test covers the `--spark-output` not-a-directory exit 2 branch (LoadMain.scala:42), live-mode argument errors with a valid env, or an invalid `SCHOLARCHAIN_STORAGE_LOAD_LEDGER_SEQUENCE`. | storage-engineer |
| 4 | minor | modules/storage/src/main/scala/scholarchain/storage/loader/LoadRunner.scala:257-263 | A `StorageError.Misconfigured` from `DynamoLoader.load` that is not about the metric cache. Examples: the stager's remote-path Misconfigured at line 52, or `grouped(0)` conditions. | Every `Misconfigured` is swallowed, logged as "metric cache not loaded", and the step is treated as success. The ledger then records SUCCEEDED and the exit code is 0. This is only partly masked, because `readModel` runs first on the same manifest. Researcher metrics could still go silently unloaded. Match only the missing-cache case. | storage-engineer |
| 5 | minor | modules/storage/src/main/scala/scholarchain/storage/loader/LoadRunner.scala:103-104 | Dry run where a loader uses `putAllIfAbsent`. | `SamplingItemStore.putAllIfAbsent` delegates without counting or sampling. The dry-run "by kind" counts and samples can undercount what a live run would write. | storage-engineer |
| 6 | minor | modules/storage/src/main/scala/scholarchain/storage/loader/LoadRunner.scala:62-67 | A manifest file path that is absolute and outside `--spark-output`, or a symlink. | `SparkOutputStager` accepts any existing absolute path outside the root. The `..` and `://` checks do not cover this, and neither does a symlink. The SHA-256 verification afterwards limits the impact to a failed load. The bare filename suffix lookup (`segs.drop(i)` down to one segment) can also pick an unrelated file with the same name. Consider confining the path to the root. | storage-engineer |
| 7 | minor | modules/storage/src/main/scala/scholarchain/storage/loader/LoadMain.scala:46-47, 136-163 | An unwritable `--work-dir`, an invalid path (`InvalidPathException` in `Paths.get`), or an exception from `AwsClients.forRuntime`. | These throw uncaught instead of returning the documented exit codes 1 and 2. The JVM exits with a stack trace. | storage-engineer |

Checked and found correct, with evidence:
- Dry run builds no AWS client. `dry` (LoadMain.scala:69-108) uses only InMemory stores and `StorageSettings.load(env).toOption`. Missing configuration falls back to `ServiceLimits`.
- Live mode reads configuration only from `StorageSettings`. Dry-run table names are placeholder strings that are never sent to AWS. I found no hardcoded bucket, account or endpoint, no env-file reads, and no secrets. Only the bucket and table names are printed.
- Neptune is never called. `StagingOnlyGraphLoader` returns a marker job, and `status` returns Unsupported.
- DynamoDB writes are conditional. `DynamoItemStore` uses `attribute_not_exists(pk)`. `replaceIfHash` is the only hash-conditioned put, and `LoadRunner` does not use it. The conflict handling is `createOrSame`, which turns a conflict into a reject. The ledger uses `putIfAbsent` with the attempt and state in the sort key.
- The S3 test confirms keys land under `run_id=<hex>/` in the Graph area. `LoadArgs` validates the run ID through `ObjectKeys.runHex`. The manifest run ID and snapshot are cross-checked (LoadRunner.scala:171-178).
- The `s3://` and `..` refusals are in the stager and are tested.
- Ownership: only the four files under modules/storage are untracked. `build.sbt` and `project/**` do not appear in git status.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: yes for 1-7 (read directly, not executed). Finding 4's runtime effect, and whether any collaborator already guards it, was not run.
ASSUMPTIONS:
- `sbt check` was not observed, so I cannot confirm the tree compiles or the tests pass.
- The Spark manifest is a trusted input. It carries SHA-256 hashes that are verified before use.
RISKS:
- The ledger can read SUCCEEDED for a load that rejected rows (finding 1).
- A bounded live load has not been exercised here. Only fakes were tested.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer repairs findings 1-2 at minimum (3-7 recommended), then `sbt check` and re-review.
