STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: Add a runnable loader entry point `scholarchain.storage.loader.LoadMain` to modules/storage. Owned paths only. Nothing was run against AWS.

FILES CREATED (all under /home/olmos/scholarchain/modules/storage):
- src/main/scala/scholarchain/storage/loader/LoadArgs.scala (argument parser, `LoadStep` enum)
- src/main/scala/scholarchain/storage/loader/LoadRunner.scala (runner, Spark-layout locator and stager, staging-only GraphLoader, sampling item store)
- src/main/scala/scholarchain/storage/loader/LoadMain.scala (entry point)
- src/test/scala/scholarchain/storage/LoadMainSpec.scala (9 tests)
FILES MODIFIED: none. build.sbt was not touched.

COMMANDS RUN:
- `sbt fmt`
- `sbt "storage/testOnly scholarchain.storage.LoadMainSpec"`: 9 of 9 passed
- `sbt check`: success, 2030 s. Suites passed with 75, 579 (24 ignored) and 66 tests, 0 failed. `markTestRun` touched `.claude/.last-test-run`.

TEST RESULTS: `sbt check` passes on the current tree. LoadMainSpec covers:
- argument parsing: defaults, `--flag=value`, and refusals for missing, malformed, unknown and duplicate arguments and bad targets
- a dry run on the fixture with an empty environment and no config: row counts, DynamoDB item kinds, sample pk/sk lines and planned S3 keys
- a manifest of another snapshot or run, or a missing manifest, exits 1
- a live run without configuration exits 2 before any client is built
- a reload on fakes: the second run reports no `created=N` with N>0, writes no second ledger entry and leaves the S3 objects byte-identical
- S3 keys land under `run_id=<hex>/`, and the Neptune CSV lands in the NeptuneExport area
- a damaged input fails the step, writes nothing and records a FAILED ledger attempt 1
- the stager refuses `s3://` paths and `..` segments

VALIDATION RESULTS:
- Not exercised: a real dry run against `modules/spark/local-run/graph`. That directory has no `load-manifests/`, so only the fixture was used.
- Live mode (AWS clients, S3ObjectStore, DynamoItemStore) is wired but untested by design. Its adapters have their own AWS-profile suites.

BEHAVIOR
- Arguments: `--spark-output <dir> --run-id <run:64hex> --snapshot <label> [--dry-run] [--targets dynamo,s3,neptune-csv] [--work-dir <dir>]`. Exit codes: 0 loaded, 1 failed, 2 bad arguments or configuration, 3 loaded with rejects.
- Manifests are found at `<dir>[/graph]/load-manifests/target=<neptune|dynamodb|s3>/run_id=<hex>/load-manifest.json`. The manifest's runId and provenance.snapshot must equal the arguments, or the load fails.
- Spark's own manifest paths are resolved by their trailing segments under the output directory, so a local copy of the Spark output works. `s3://` paths and `..` segments are refused. The Spark output must be copied down first.
- Dry run: builds no AWS client and needs no env. It runs the real loaders against InMemoryItemStore and InMemoryObjectStore and prints per-table rows, item counts by kind, sample items and planned S3 keys. It writes only to the local work dir.
- Live run, `dynamo` step, in this order:
  - verifies the DYNAMODB manifest and runs `GraphReadModelLoader` on it.
  - runs `GraphReadModelLoader` a second time on the NEPTUNE manifest, if present. `cross_community_edge` is listed only there, and the second pass adds the connectivity markers. Items already stored are `unchanged`.
  - runs `DynamoLoader` for `researcher_collaboration` only if `dynamodb.tables.researcherMetricCache` is configured; otherwise it logs "metric cache not loaded".
- Live run, `s3` step: `S3Loader` writes `graph/run_id=<hex>/<table>/<rel>` (`semantic_fact` goes to the rag bucket) and `reports/run_id=<hex>/storage/s3-run-manifest.json`.
- Live run, `neptune-csv` step: `GraphLoadWorkflow` verifies the manifest, exports to CSV, validates, and uploads to `<neptuneExport prefix>/run_id=<hex>/csv/`. The graph loader is a staging-only stub, so no Neptune bulk load is started. No Neptune request is made, though the SDK client object is constructed as in `AwsClients`.
- Names and config:
  - Bucket, table, prefix, batch and retry settings all come from `StorageSettings.load(sys.env)`.
  - The read model table is `dynamodb.tables.graphReadModel`, defaulting to `<prefix>-<env>-graph-read-model`.
  - The ledger table is `dynamodb.tables.runLedger`.
  - The `reports` and `neptune-export` prefixes live in the rag bucket, as in LiveWiring and the Terraform README.
  - Optional env var `SCHOLARCHAIN_STORAGE_LOAD_LEDGER_SEQUENCE` sets the ledger sequence (0..99999). It defaults to 0.
- RunLedger: one entry per step, stage `load-<step>`, with a detail derived from the manifest (target, snapshot, rows per table). An existing SUCCEEDED entry with an identical detail is skipped. Otherwise the attempt number is max+1. A FAILED entry is recorded when a step fails.
- Writes are conditional (`createOrSame`, `putIfAbsent`, S3 `If-None-Match`). Differing content becomes a `CONTENT_CONFLICT` reject in `<work-dir>/rejected-load.jsonl`; the stored item stays.
- Batch and retry: the chunk size is `batch.dynamoBatchWrite`, at most 25 (BatchWriteItem maximum). Items are written one conditional put per request, because BatchWriteItem cannot carry a condition. Dry run falls back to the 25 maximum if no env is set. Retry is the existing `Retrier` with `retry.*` config, and the SDK's own retries stay off. I did not fetch new service limits from the AWS docs; the loaders already carry their documented citations.

COMMAND FOR A HUMAN LIVE LOAD (after `/deploy` has made the tables and buckets exist, and with the env from the storage config exported):
```
sbt "storage/runMain scholarchain.storage.loader.LoadMain --spark-output <local-copy-of-spark-output> --run-id run:<64hex> --snapshot <label> --targets dynamo,s3,neptune-csv --work-dir <scratch-dir>"
```
Always run the same command with `--dry-run` first. Credentials come from the default AWS chain (`AWS_PROFILE` or a role). The write permissions needed are S3 PutObject on the graph and rag buckets, and DynamoDB PutItem, GetItem and Query on the read model and run ledger tables. Those are IAM, and `infra-engineer` owns them.

ASSUMPTIONS
- `--spark-output` is a local copy of the Spark output. Spark's S3 output must be downloaded first, which is a read-only operation.
- The auxiliary areas (reports, neptune-export) live in the rag bucket, as in LiveWiring.
- A ledger sequence of 0 is acceptable by default.

RISKS
- The Neptune bulk load is not started here. A separate step, outside this work order, must call the Neptune loader adapter against `neptune-export/run_id=<hex>/csv/`. That needs `NEPTUNE_LOADER_ROLE_ARN` and Neptune access.
- The second `GraphReadModelLoader` pass re-verifies the NEPTUNE manifest's checksums, so there is extra local read time.
- Rejects exit with code 3 rather than 0, so a wrapper script must treat 3 as "check the rejects file".
- Reloading with changed Spark output for the same run_id is a conflict reject, not an overwrite. This is intended.
- The live path has no automated test here; its AWS adapters are covered by the existing profile-gated suites.

MANUAL ACTION REQUIRED: human runs the dry run, then the live command, with a working AWS profile. Terraform-created tables and buckets are required first.

BLOCKED-ON OWNER: none. No build.sbt change is needed, since `sbt "storage/runMain ..."` works with the current build. A runnable assembly or a `Compile / mainClass` entry for packaging would be `build-engineer`'s, and was not requested.
BLOCKED-ON EVIDENCE: n/a

NEXT GATE: contract tests (passed in `sbt check`), a human dry run, a bounded load test, code-reviewer
