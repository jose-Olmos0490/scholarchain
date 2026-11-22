STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the full Phase 7 change set (storage module plus the build-engineer and domain-engineer repairs), against docs/agent-runs/phase-07/prompt.md and agent-report.md
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

FINDINGS:
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | modules/storage/src/main/scala/scholarchain/storage/graph/GraphPorts.scala:236, 254 (template text); modules/storage/src/test/scala/scholarchain/storage/GraphSpec.scala:294-296 | `directCoauthors` ends in `LIMIT $limit`, and every `shortestPath/N` template ends in `LIMIT $candidateLimit`. The report cites the Neptune compliance page for "LIMIT must be static" (report item 10) and lists `LIMIT $param` as UNVERIFIED (item 4). Only `coauthorEdge` uses a literal (`LIMIT 2`). | If the cited rule holds, 2 of the 4 read templates are rejected by Neptune on the first live query. This blocks the Phase 9 retrieval path. The fakes and stub tests cannot catch it, and GraphSpec asserts that `$candidateLimit` must be a parameter, which locks the defect in. The bound is meant to be enforced in the template, so this also weakens it. | storage-engineer |
| 2 | major | modules/storage/src/main/scala/scholarchain/storage/dynamo/DynamoStores.scala:373, 381-385; modules/storage/src/main/scala/scholarchain/storage/loader/DynamoLoader.scala:81-82 | The `researcherMetricCache` key is `researcher#<id>` plus `w#<start>-<end>`, with no runId or metricVersion. The `contentHash` is taken over the whole record, which includes `provenance.runId` and `metricVersion`. A second Spark run (new runId) writes the same researcher and window with different provenance. | `createOrSame` returns ConflictingContent, so every row of a later run is rejected as CONTENT_CONFLICT, even when the values are identical. DynamoDB then keeps serving run-1 metrics while Neptune nodes carry run-2 metrics. Storage cannot be refreshed without human deletion. This is stale and inconsistent data with no failure beyond a reject counter. | storage-engineer |
| 3 | major | modules/storage/src/main/scala/scholarchain/storage/dynamo/DynamoStores.scala:242-277 (putBundle), 288-299 (listForQuery); test gap at modules/storage/src/test/scala/scholarchain/storage/DynamoContracts.scala:288-293 | `putBundle(B1 for q)` succeeds. `putBundle(B2 for q)` with different evidence then creates B2's item and membership rows (createOrSame) and fails only at the BUNDLE header (AlreadyExists). A second `putBundle` of the identical B1 also fails with AlreadyExists, so an idempotent replay is not idempotent. | `listForQuery(q)` reads memberships, not the header. It returns B1 plus the rejected B2's evidence, so a claim can cite evidence that is not in the stored bundle. This breaks the evidence-integrity invariant. The test only covers re-put of the same bundle. | storage-engineer |
| 4 | minor | modules/storage/src/main/scala/scholarchain/storage/aws/DynamoItemStore.scala:90-95; modules/storage/src/main/scala/scholarchain/storage/aws/AwsSupport.scala:44-53 | A `TransactionCanceledException` whose reasons are TransactionConflict, ThrottlingError or ProvisionedThroughputExceeded is rethrown. `isTransient` checks status and code only, and that exception has status 400 and code "TransactionCanceledException", which is not in the transient set. The inline comment says such reasons are "classified as usual". | The querySession create (session plus idempotency marker transaction) fails permanently on a retryable contention. The retry claim in the report is not fully met for the transaction path. | storage-engineer |
| 5 | minor | modules/storage/src/main/scala/scholarchain/storage/aws/DynamoItemStore.scala:46-61; modules/storage/src/main/scala/scholarchain/storage/dynamo/DynamoStores.scala:119, 148 | A PutItem lands but the response times out. The retrier resends and gets ConditionalCheckFailed. | The caller sees AlreadyExists for its own write (ledger record, link cache, header). S3 handles the same ambiguity by comparing hashes, but strict DynamoDB creates do not. | storage-engineer |
| 6 | minor | modules/storage/src/main/scala/scholarchain/storage/graph/NeptuneCsvValidator.scala:144-145; modules/storage/src/main/scala/scholarchain/storage/loader/DynamoLoader.scala:90-98 | The requirement says evidencePointer "resolves to supporting work IDs". The validator checks only that the pointer is non-empty. Core's RecordContract guarantees the `coauthor-edge-support-v1:<edgeId>` format. No port or test resolves a pointer to work IDs, and DynamoLoader skips `coauthored_with_support`. The Neptune manifest has no support table. | The pointer resolves only to raw Parquet in the S3 graph bucket (the S3 manifest does include the table). The Neptune manifest is not checked for the matching support rows, and no reader can follow the pointer. This should be reported honestly, or a resolver or presence check added. | storage-engineer, with an orchestrator decision on the support table's home |
| 7 | minor | modules/storage/src/main/scala/scholarchain/storage/vectors/VectorWriter.scala:36-37 (and 28-33) | `textS3Uri = "s3://b/" + <30 KB abstract text>` matches `s3://[^/]+/.+`. documentType, sourceDataset and the ID fields have no format or length check. | The report says long text cannot enter the index, but only the type shape enforces that. A buggy caller can put up to 40 KB of text in a non-filterable field. A key-length cap (S3 key at most 1024 bytes) would close it. | storage-engineer |
| 8 | minor | modules/storage/src/main/scala/scholarchain/storage/vectors/VectorWriter.scala:194; modules/storage/src/main/scala/scholarchain/storage/aws/S3VectorsIndex.scala:57-61 | `actual != expected` compares `nonFilterableKeys` as an ordered Vector. | If the service returns the same four keys in another order, the writer refuses every write as Misconfigured. A set comparison would avoid this. | storage-engineer |
| 9 | minor | modules/storage/src/main/scala/scholarchain/storage/graph/NeptuneCsvValidator.scala:66, 135 | `relationshipEnds` stores a tuple for every relationship row. | The report says the validator holds "IDs only, not rows" in memory. Memory grows with edges, not just node IDs. The documentation claim is wrong, and large runs risk OOM. | storage-engineer |
| 10 | minor | modules/storage/src/main/scala/scholarchain/storage/graph/NeptuneCsvExporter.scala:251-279; modules/storage/src/main/scala/scholarchain/storage/aws/NeptuneAdapters.scala:40-43 | The researcher metrics overlay is a separate CSV with the same `:ID` as the base researcher file. It is loaded in one job with `failOnError=true` and `parallelism=MEDIUM`. | Concurrent updates to one vertex from two files in one job may fail or race. This is unverified, and the report does not list it among the live-cluster unknowns. Merging the properties into the base row would be safer. | storage-engineer |
| 11 | minor | modules/storage/src/main/scala/scholarchain/storage/aws/NeptuneAdapters.scala:108-131, 203-212 | A result row missing `jointPublicationCount` becomes 0. A missing `evidencePointer` or `runId` becomes "". `shortestPath` ties are broken within `candidateLimit` with no flag when the limit was reached. | Corrupt or partial graph data is returned as valid-looking views, and an inexact tie-break is not surfaced. This is unreported uncertainty. | storage-engineer |
| 12 | minor | modules/storage/src/main/scala/scholarchain/storage/dynamo/DynamoStores.scala:192-194, 208-219 | An Idempotency-Key containing `|` is truncated by `takeWhile(_ != '|')`, so the lookup returns None and DuplicateIdempotencyKey carries an empty queryId. `get` and `queryIdForIdempotencyKey` never check `expiresAt`. | An expired but undeleted session (TTL deletion can lag) is served. The idempotency marker also keeps blocking its key until deletion. | storage-engineer |
| 13 | minor | modules/storage/src/main/scala/scholarchain/storage/graph/NeptuneCsvExporter.scala:363-372, 393-401; modules/storage/src/test/scala/scholarchain/storage/NeptuneExportSpec.scala:207-228 | The UNEXPECTED_EDGE_TYPE branch has no test. The NOT_CANONICAL_PAIR branch is unreachable, because the core contract rejects the row first (the test hits ROW_DECODE). | One exporter path is untested and one is dead code. The exporter is otherwise exercised well, with literal expected CSV. | storage-engineer |
| 14 | minor | modules/storage/src/main/scala/scholarchain/storage/aws/S3ObjectStore.scala:70-71; modules/storage/src/main/scala/scholarchain/storage/manifest/LoadManifests.scala:106-115 | `putFile` ignores `sizeBytes`, but the fake checks it. `StagedFile.release` is never called anywhere (grep found no match). | The fake and the real adapter diverge on size checking. With ObjectStoreFileStager, temp files leak for every staged file. | storage-engineer |
| 15 | minor | modules/core/src/main/scala/scholarchain/core/config/StorageRuntimeConfig.scala:46-86, 224-229 | `DynamoTablesConfig` does not check that the five table names are distinct. The comment says `neptune.loaderRoleArn` is environment-only, but a file value is accepted. | Two tables configured to the same name would share a key space. The env-only rule on the ARN is a comment, not a check. | domain-engineer |

VALIDATION RESULTS: findings verified against files: yes for all 15 (each cited file and line was read). Findings 1 to 3 were traced through code and tests. I could not run anything. I found no test that exercises the failing input of findings 1, 2 or 3.

Review-order results:
1. Neptune
   - Node and relationship `:ID` values are copied unchanged from the decoded core records (NeptuneCsvExporter.scala:230-420).
   - COAUTHORED_WITH has one row per canonical pair and window. The exporter checks the pair order, and the validator re-checks it.
   - The path template is parameterized and depth-limited through a config-built lookup of `*1..N` texts (GraphPorts.scala:131, 205-209, 246-254). It is not formatted from a caller value.
   - Issues: findings 1, 6 and 10.
   - The ID match with Spark rests on the golden fixture the storage engineer built. RealRunSmoke is a main in the test tree and does not run in `sbt check`.
2. DynamoDB
   - The PK/SK designs serve the stated access patterns.
   - Every create is `attribute_not_exists(pk)` (DynamoItemStore.scala:19, 54, 80).
   - Ledger, link cache and registry are strict creates, so audit records cannot be overwritten.
   - The shared ItemStore seam applies the conditional put per table name, so per-table guarantees are not weakened.
   - Issues: findings 2, 3, 4, 5 and 12.
3. S3 Vectors
   - Same key with a different textHash is rejected (VectorWriter.scala:278-283).
   - An index mismatch is refused as Misconfigured. CLASSIC config against an ENHANCED index is refused rather than written.
   - Issues: findings 7 and 8.
   - The limit values (40/100 KB, 2/40 KB, 50 keys, 10 non-filterable keys, 500/100 batch, 4096 dimension) match what the report cites and what I recall of the service. I had no aws-docs tool, so I did not independently re-verify them against the documentation pages.
4. S3 and loaders
   - Manifest SHA-256, size and row count are verified before any load (LoadManifests.scala:58-119). The S3 PutObject also sends the manifest SHA-256 as the S3 checksum.
   - `If-None-Match: *` is used. The same hash is a Skipped outcome and a different hash is ConflictingContent.
   - Reload is idempotent and the run manifest has no time fields. Sound apart from finding 14.
5. Retry
   - Only throttling, 408, 429, 5xx and IO errors are retried (AwsSupport.scala:22-53).
   - Backoff is bounded and jittered, and the SDK's own retries are turned off (Retry.scala:52-77).
   - Issues: findings 4 and 5.
6. Hardcoding: a grep of `src/main` for Regions, ARNs, account IDs, `amazonaws.com` and bucket names found none. Everything comes from config or environment.
7. Tests
   - They are mostly meaningful, with literal expected CSV and the Dynamo contract suites.
   - Gaps: findings 1 (a test locks in `$candidateLimit`), 3, 13, and the adapters only ever run against stub SDK clients. The CSV exporter has no fake, but it is pure and covered by NeptuneExportSpec and RealRunSmoke, except for the branches in finding 13.
8. Repairs
   - Dependencies.scala keeps the value/structure split: parquet 1.16.0, hadoop 3.4.2, snappy-java 1.1.10.8 are exact pins, and no floating versions or duplicate artifacts appeared.
   - The new libraries are declared only in the `storage` set (Dependencies.scala:142-144). build.sbt is unmodified, as the report says.
   - Compile-scoped hadoop-client-runtime propagates to `services` through the storage dependency. The report already notes the roughly 68 MiB image growth.
   - The core config validation is correct: service-maximum bounds, `maxDelayMs >= baseDelayMs`, an http(s) endpoint check and a role-ARN regex. The only gap is finding 15.

TEST RESULTS: not run by reviewer. The report states `sbt check` passed with storage at 147 tests. I did not verify that.

ASSUMPTIONS:
- The Neptune compliance statement "LIMIT must be static" is taken from the report's own citation. I did not re-fetch it.
- Core's `RecordContract` enforces the evidence-pointer format, as the core tests in AnalyticsContractSpec indicate.

RISKS:
- Nothing has run against live AWS, so the Neptune templates, bulk-load merge behaviour and SDK conditional semantics are all unproven (the report says so).
- The report's open items (auxiliary-bucket choice, vector index mode, a home for the other DynamoDB manifest tables, PUBLISHED_IN and AFFILIATED_WITH edges) remain human decisions.
- The report's note that the guard hook blocked harmless commands may warrant a factory-engineer review.

MANUAL ACTION REQUIRED: Human decisions listed in the agent report (item a: where reports, logs and neptune-export live; item c: vector index mode, tables with no home). The human commits; I did not.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: storage-engineer repairs findings 1-3 (major) and, preferably, 4-14. domain-engineer handles finding 15. Then re-review. test-engineer's cross-module cases wait until after the repair.
