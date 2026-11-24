===== rag-engineer =====
STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: Phase 10b, work order 1 of 4. (1) typed Timeout and Overloaded StageFailure variants. (2) live Bedrock LlmClient adapter. (3) composition entry that builds AnswerWorkflow from injected ports.

FILES CREATED (all under /home/olmos/scholarchain/modules/rag/src):
- main/scala/scholarchain/rag/llm/BedrockLlmClient.scala
  - Contains the port BedrockInvoker, BedrockCall, BedrockReply, the BedrockError enum, BedrockRetryPolicy, Backoff, and BedrockLlmClient.
- main/scala/scholarchain/rag/llm/BedrockSdkInvoker.scala
  - SDK Converse binding, an exception classifier, and `live(region, profile, attemptTimeoutMillis)`.
- main/scala/scholarchain/rag/workflow/WorkflowComposition.scala
  - Contains WorkflowDependencies, WorkflowTuning, and WorkflowComposition.agents/workflow.
- test/scala/scholarchain/rag/llm/BedrockLlmClientSpec.scala
- test/scala/scholarchain/rag/llm/LlmFailureTypesSpec.scala

FILES MODIFIED (all under /home/olmos/scholarchain/modules/rag/src):
- main/.../agents/StageFailure.scala
  - Added `Timeout(operation, detail)`: code TIMEOUT, retryable.
  - Added `Overloaded(operation, detail)`: code OVERLOADED, retryable.
- main/.../llm/LlmClient.scala
  - Added `LlmFailure.Overloaded` and `LlmFailure.TimedOut`.
- main/.../llm/StructuredLlmCall.scala
  - Both new LlmFailure cases are retried like Transient, within the same 1+maxRetries bound.
  - If the last attempt ended in transport failure, exhaustion is typed as StageFailure.Overloaded or StageFailure.Timeout.
- main/.../workflow/AnswerWorkflow.scala
  - `recoverable` now treats Timeout and Overloaded as non-recoverable. Without this they would have been absorbed as an evidence gap.
- test/.../answer/WorkflowWorld.scala
  - The test Rig now builds its agents and workflow through WorkflowComposition, so the existing workflow specs exercise the composition entry.

BEHAVIOR MAPPING
- Existing callers still compile. StageRunner deadline and pool-full failures, and StorageError timeouts, still produce `Retryable`. I left them unchanged on purpose. services/QueryEngine matches on the "did not finish within" and "too many stages" detail markers of `Retryable`, so remapping them would break that service.
- Only the new LLM path produces Timeout and Overloaded.
- One behavior change: Overloaded or TimedOut model failures, once exhausted, are no longer reported as Rejected MODEL_OUTPUT_INVALID. A plain `Transient` failure still maps to MODEL_OUTPUT_INVALID, as before.

DESIGN
- Prompts and schemas: no new prompt versions, schemas or allowed operations. Existing versions are unchanged: planner v1, synthesis v3, query-plan-v1, answer-synthesis-v2.
- Model ID: it comes from `ModelConfig.synthesisModelId` through `BedrockLlmClient.forSynthesis`. The constructor rejects a blank ID. Nothing is defaulted, and the model ID is not hardcoded.
- Untrusted text: `system` and the untrusted JSON go in separate Converse parts (system block and user turn). The injection-shaped input is asserted to stay in the user turn.
- Hidden reasoning: only `text` content blocks are returned. Reasoning blocks are dropped.
- Failure details: SDK failure details carry the exception class and status only, never provider messages. The tests check that "leaked" text and a thrown "secret" message do not appear.
- Retries: only throttle (429), deadline, 5xx and connection failures are retried.
  - Backoff is exponential: min(max, base*2^(n-1)).
  - A refusal is never retried (Permanent).
  - A guardrail or content-filter stop is Permanent.
  - A `max_tokens` cut-off is returned as is, so schema validation rejects it.
  - The SDK client is built with its own retries off, so BedrockRetryPolicy is the only bound.
- Exhaustion mapping: 429 and 5xx become `LlmFailure.Overloaded`, deadlines become `TimedOut`, and other connection failures become `Transient`.
- Token usage: input and output tokens come from the Converse usage block into LlmResponse. The existing LlmUsage accounting is unchanged.
- No build edit was needed: the Bedrock runtime SDK is already in `Dependencies.rag`.

TEST RESULTS (observed)
- `sbt rag/test`: 242 passed, 0 failed.
  - Benchmark suite: 30/30, adversarial 22/22.
- Final `sbt check`: success, "Total time: 2435 s". Per-module totals in the log: 216, 78, 212, 695, 242, 75, 352 (9 ignored) and 66 passed, 0 failed.
- A first `sbt check` run was killed by my own timeout while it was still running the slow Spark and Hadoop suites. It did not fail.

COMMANDS RUN
- `sbt rag/compile`
- `sbt rag/test` (several times)
- `sbt fmt`
- `sbt check` (twice; the first was killed by a timeout, the second completed)

VALIDATION RESULTS
- New feature tests:
  - the shape and retryability of Timeout and Overloaded;
  - exhaustion typing and recovery after overload;
  - the maxRetries=0 bound;
  - the workflow failing retryable instead of absorbing a gap;
  - composition wiring;
  - adapter retries, backoff and caps, exhaustion mapping, refusal, a throwing invoker, guardrail and cut-off stops, and policy validation;
  - SDK exception classification.
- The injection and malformed-output tests from earlier phases still pass.

ASSUMPTIONS
- The Converse API is the invocation path. It was not verified against a live model, because no live AWS is used. A live test would be an AwsIntegration-tagged one.
- The model ID for planner and synthesis is `synthesisModelId`. The verifier is deterministic and makes no model call.

RISKS
- Services will throw a runtime MatchError. `sbt check` shows a compile warning (E029), non-fatal, at `/home/olmos/scholarchain/modules/services/src/main/scala/scholarchain/services/query/QueryEngine.scala:25`. `QueryEngine.toApi` has no case for `StageFailure.Timeout` or `StageFailure.Overloaded`, so it would throw if a live model call times out or is overloaded.
- No core config section exists for the LLM backoff (maxAttempts, baseDelayMs, maxDelayMs) or the per-attempt timeout. They are constructor parameters of BedrockRetryPolicy and `BedrockSdkInvoker.live`, with no defaults. Total model calls per stage are bounded by (1 + rag.llm.maxRetries) times maxAttempts.
- The `WorkflowTuning` fields plannerMaxQueryCodePoints, maxExcerptCodePoints and maxSemanticQueryCodePoints are injected. Only the services composition can supply them from configuration; the test world uses 500, 200 and 300.

MANUAL ACTION REQUIRED
- Route to `service-engineer`: map Timeout to 504 and Overloaded to 503, both retryable. Also decide whether to move the Retryable detail-marker mapping to the typed variants, which would need a StageRunner change that I can make on request.
- Route to `domain-engineer`: add LLM backoff and timeout configuration keys.

BLOCKED-ON OWNER: none for this work order (the follow-ups above go to service-engineer and domain-engineer).
BLOCKED-ON EVIDENCE: none.
NEXT GATE: retrieval and groundedness evaluation, code-reviewer

===== storage-engineer =====
STATUS: COMPLETE (with one routing note: the JobStore port itself lives outside storage, so the final service-side adapter is BLOCKED-ON service-engineer)
OWNER: storage-engineer
WORK ORDER: Phase 10b, work order 2 of 4. DynamoDB-backed job store, plus a report on the Phase 8 embedding queue consumer.

PORT LOCATION (evidence)
- The `JobStore` port is `scholarchain.services.query.JobStore` in /home/olmos/scholarchain/modules/services/src/main/scala/scholarchain/services/query/Jobs.scala. It is owned by service-engineer.
- It is typed on `JobRecord`, `JobState`, `JobError` (which uses services' `ErrorCode`) and rag's `StageTrace`. Storage may depend only on `core`, so it cannot implement that trait and I did not edit it.
- What I built instead is a neutral, durable `JobDocumentStore` in storage with the same operations. `JobDocument` carries the key fields plus an opaque `document: ujson.Obj`.
- BLOCKED-ON service-engineer for a small `DynamoJobStore extends JobStore` in modules/services. It maps `JobRecord` to and from `JobDocument` (`terminal = state.terminal`, `createdAtEpochSeconds = createdAtMillis / 1000`). It should:
  - map `create` to `Boolean` (`Left(AlreadyExists)` becomes `false`);
  - turn `Left` results from the other operations into whatever the services layer does for a store outage;
  - delegate `update` and `restart` to the same-named methods.

FILES CREATED (all under /home/olmos/scholarchain/modules/storage/src)
- main/scala/scholarchain/storage/dynamo/JobDocuments.scala
- main/scala/scholarchain/storage/queue/WorkQueue.scala
- main/scala/scholarchain/storage/queue/WorkConsumer.scala
- main/scala/scholarchain/storage/queue/EmbeddingWork.scala
- main/scala/scholarchain/storage/aws/SqsWorkQueue.scala
- test/scala/scholarchain/storage/JobStoreSpec.scala
- test/scala/scholarchain/storage/WorkQueueSpec.scala

FILES MODIFIED
- main/.../dynamo/DynamoPorts.scala: added `ItemStore.replaceIfHash`.
- main/.../dynamo/InMemoryItemStore.scala: fake implementation of `replaceIfHash`.
- main/.../aws/DynamoItemStore.scala: SDK implementation of `replaceIfHash`.
- test/.../DynamoSpec.scala: added the new method to the racing fake and a `replaceIfHash` adapter test.
- test/.../AwsIntegrationSpec.scala: added `JobDocumentStoreIT` (gated) and registered it in the gate spec.

1. JOB STORE: SCHEMA, KEYS, TTL
- No new table. Items live in the already-configured `querySession` table (`tables.querySession`), as the audit items already do. The table name comes from configuration. The constructor is `DynamoJobDocumentStore(items, table, ttlSeconds: Option[Long], maxAttempts = 5)`.
- Keys, with the generic string `pk` and `sk`:
  - job item: `pk = job#<jobId>`, `sk = JOB`, `kind = Job`. Attributes: `contentHash` (SHA-256 of the record), `record {jobId, queryId, principalId, terminal, createdAtEpochSeconds, document}`, and `expiresAt` when TTL is set.
  - marker item: `pk = jobq#<queryId>`, `sk = JOBQUERY`, `kind = JobQueryMarker`, with `record {jobId}` and `expiresAt` when TTL is set. It enforces one job per query and serves `findByQueryId`.
- `create` is a `TransactWriteItems` of both items, each with `attribute_not_exists(pk)`. A conflict returns `AlreadyExists(queryId)` or `AlreadyExists(jobId)`. It is all or nothing.
- `update` and `restart` are read, apply `f`, then `PutItem` with `ConditionExpression contentHash = :h` (new `ItemStore.replaceIfHash`). On a lost race they retry up to `maxAttempts` times against the fresh content, then return `Transient` (retryable).
  - `update` re-reads and sees a terminal job, so a late worker can never overwrite a recorded failure or success.
  - `restart` is the only path that touches a terminal job.
  - `f` may not change `jobId`, `queryId` or `createdAt` (`InvalidRecord`).
- TTL: `expiresAt = createdAtEpochSeconds + ttlSeconds`, set only when `ttlSeconds` is `Some`, and applied to the job and marker together. The job item embeds the result and trace, so the job is a disposable operational projection. The durable record of an answer is the audit record and the evidence bundle, neither of which has a TTL.
- Recommended wiring: `Some(settings.runtime.querySession.ttlSeconds)`, which `JobDocumentStoreIT` already uses.
- Guards: key-length limits, `#` and blank key parts, and the 400 KB item limit (`LimitExceeded`) before any write.
- Provenance: the job `document` is opaque service data, never interpreted. Principal and query IDs are stored. No secrets. The store reads no clock.

TERRAFORM NOTES (infra-engineer)
- No new table or key schema is needed. The `querySession` table must have TTL enabled on the attribute `expiresAt`; verify it is, since it already serves session items.
- The service task role needs these actions on that table: `PutItem`, `GetItem`, `Query` (if not already granted) and `TransactWriteItems`. `PutItem` is needed for the conditional replace.
- Item size is capped at 400 KB.
- If you would rather have a dedicated jobs table, it needs `pk` (S) and `sk` (S) keys and TTL on `expiresAt`. That would also need a core config key (domain-engineer).

2. EMBEDDING QUEUE CONSUMER
- Before this work order, Phase 8 had no queue, consumer, SQS adapter or DLQ code in modules/storage. Only the `sqs` dependency was declared in `project/Dependencies.scala`. Phase 8 had `VectorIngestor` (`stage`, then `ingest(run, manifest)`) and `ManifestStore`.
- I added a small consumer. Package `scholarchain.storage.queue`:
  - `trait WorkQueue`: `send(body): Result[String]`; `receive(max, visibilityTimeoutSeconds, waitSeconds): Result[Vector[QueueMessage]]`; `delete(receiptHandle): Result[Unit]` (this is the ack); `changeVisibility(receiptHandle, seconds): Result[Unit]`.
  - `QueueMessage(messageId, receiptHandle, body, receiveCount)`.
  - `InMemoryWorkQueue(nowMillis)`: fake with visibility timeout, receive counts and stale-handle rejection.
  - `ConsumerSettings(batchSize 1..10, visibilityTimeoutSeconds, waitSeconds, maxReceiveCount, maxMessagesPerRun, retryBackoffSeconds)`.
  - `WorkConsumer[A](queue, deadLetter: WorkQueue, decode, handle, settings, rejects: RejectSink, store = "embedding-queue")`, with `runOnce(max): Result[ConsumerReport]` and the bounded `drain(): Result[ConsumerReport]`.
  - `MessageOutcome`: `Acknowledged`, `Retry`, `DeadLettered`, `QueueFault`. `ConsumerReport` has `received`, `outcomes`, `acknowledged`, `retried` and `deadLettered`.
  - `EmbeddingWorkItem(schemaVersion, runId, snapshot)`, with `of`, `encode`, `decode`, and the version `embedding-work-item-v1`. The message body is only a pointer to the S3 manifest, never text.
  - `EmbeddingWorkPublisher(queue).publish(item)`.
  - `EmbeddingWorker.handler(manifests: ManifestStore, ingestor: VectorIngestor)` and `EmbeddingWorker.consumer(queue, deadLetter, manifests, ingestor, settings, rejects)`.
- Package `scholarchain.storage.aws`: `SqsWorkQueue(client: SqsClient, queueUrl, retrier)` and `SqsWorkQueue.client(settings, profile: Option[String] = None)`. The SDK's own retries are off.
- Behaviour:
  - Success deletes the message.
  - A transient failure leaves it for redelivery after `retryBackoffSeconds`.
  - A decode failure, a permanent failure or `receiveCount >= maxReceiveCount` copies it to the DLQ and records a reject, then deletes it. The delete happens only after the DLQ send succeeds.
  - A throwing handler is treated as transient.
  - Delivery is at least once. `VectorIngestor.ingest` skips unchanged vectors, so a redelivered message is safe.
  - An ingest report with `ledgerErrors` is treated as transient and retried.
- Queue URLs and DLQ URLs are not in core config. service-engineer must pass them in from the service's own config or env. A core config section (domain-engineer) and the SQS queue, DLQ and redrive policy plus IAM (infra-engineer) are still needed.
- Wiring for service-engineer: build `VectorIngestor` and `ManifestStore` as `IngestRig` does in the tests. Build `SqsWorkQueue` for the main queue and for the DLQ. Build `EmbeddingWorker.consumer(...)`. Call `drain()` or `runOnce()` in a loop outside the storage module. Set `visibilityTimeoutSeconds` longer than the longest ingest run.

3. BATCH, RETRY, IDEMPOTENCY
- Job transaction: 2 items (limit 100). Optimistic retry: 5 attempts by default. AWS transient errors use the existing `Retrier` and the configured retry policy.
- Queue: receive 1..10 messages, `maxMessagesPerRun` bounds a drain, `maxReceiveCount` bounds redelivery.
- Creates never overwrite. Updates are conditional on the content hash. The consumer is idempotent by design.

4. SERVICE LIMITS (read from AWS docs this session)
- SQS: 1 to 10 messages per `ReceiveMessage`; default visibility timeout 30 s, range 0 s to 12 h; message size 1 B to 1,048,576 B (1 MiB). Sources: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html and https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html
- `WaitTimeSeconds`: the API page I read states no maximum, so the code only checks that it is >= 0 and leaves the upper bound to the service.
- DynamoDB: item size 400 KB (checked before any write); transactions up to 100 items (the existing check). Source: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html. I did not re-read the transaction limit this session.

5. CLEANUP
- Job items expire by TTL, so there is no manual cleanup. Without TTL, deleting job and marker items is data deletion, which goes to the human architect (BLOCKED-ON human). The integration tests use unique UUID job IDs and query IDs and leave only TTL-bounded items.
- SQS test messages are deleted by the consumer.

COMMANDS RUN
- `sbt -batch storage/compile`: success.
- `sbt -batch scalafmtAll storage/test`: 242 tests passed.
- `sbt -batch check`: exit 0, total time 2767 s. It touched `.claude/.last-test-run` via markTestRun.
- AWS documentation lookups through the aws-docs MCP.

TEST RESULTS
- `sbt check` passed. Per-module counts seen in the log: 75, 352 (9 ignored, which I believe are the gated AWS integration tests) and 66 passed, 0 failed. Storage ran 242 tests passed before check; the final check total for storage was not separately extracted.
- New tests:
  - `JobDocumentStoreContract` and its fake spec (create once, one job per query, terminal guard, restart, key immutability).
  - `JobDocumentStoreDesignSpec`: layout and TTL, a lost race against a terminal winner, bounded contention, item size, key parts, `replaceIfHash`.
  - The `DynamoItemStore.replaceIfHash` stub test.
  - `WorkConsumerSpec`: ack, visibility and redelivery, exhausted receives, permanent and undecodable messages to the DLQ, DLQ-send failure, throwing handler, bounded drain, stale handle, work item codec, embedding worker on a missing manifest, embedding worker redelivery with the fake ingestor rig.
  - `SqsWorkQueueSpec`.
- The AWS integration test `JobDocumentStoreIT` is behind the `AwsIntegration` gate and was not run; it is cancelled without `SCHOLARCHAIN_AWS_IT=true`.

VALIDATION RESULTS
- Only module-scoped storage tests and `sbt check` were run. No AWS resources were touched, and no resources were created.
- No SQS integration test exists. It needs a queue from infra-engineer.

ASSUMPTIONS
- Reusing the `querySession` table is acceptable. Its TTL attribute is `expiresAt`, as the existing code assumes.
- Job TTL equals the session TTL.

RISKS
- The job item contains the full result. Large answers can hit the 400 KB limit, which is surfaced as `LimitExceeded`. Result offloading to S3 would be a design change.
- The conditional replace re-reads on conflict; heavy contention yields a retryable `Transient`.
- `SqsWorkQueue` has no AWS adapter test against real SQS.

MANUAL ACTION REQUIRED: none from the human for this work order.

BLOCKED-ON OWNER:
- service-engineer: the `DynamoJobStore` adapter in services (see above), plus the embedding-worker wiring.
- infra-engineer: SQS queue, DLQ, redrive policy and IAM; verify `querySession` TTL on `expiresAt`; IAM actions listed above.
- domain-engineer (optional): core config keys for the queue URLs and DLQ URL.
BLOCKED-ON EVIDENCE: the port at /home/olmos/scholarchain/modules/services/src/main/scala/scholarchain/services/query/Jobs.scala depends on services and rag types. `core` has no queue-URL config, and no SQS resources exist in infra/terraform (grep found none).
NEXT GATE: contract tests, bounded load test, code-reviewer

===== service-engineer =====
STATUS: BLOCKED-ON
OWNER: service-engineer
WORK ORDER: Phase 10b, work order 3 of 4 (typed 504/503 mapping, production composition roots, three entrypoints, failed-auth rate limit, root tests).

All five tasks are implemented and `sbt check` passes on the current tree. STATUS is BLOCKED-ON because three read adapters that the query-api answer workflow needs do not exist in storage. The live query-api therefore cannot become ready yet. Details are under BLOCKED-ON.

FILES CREATED (all under /home/olmos/scholarchain/modules/services/):
- src/main/scala/scholarchain/services/api/QueryRoutes.scala (query, jobs and explain routes, plus JobViews)
- src/main/scala/scholarchain/services/api/ResourceRoutes.scala (evidence and graph routes)
- src/main/scala/scholarchain/services/config/EntrypointSettings.scala
- src/main/scala/scholarchain/services/entrypoint/Shared.scala
- src/main/scala/scholarchain/services/entrypoint/CoreConfigs.scala
- src/main/scala/scholarchain/services/entrypoint/LiveWiring.scala
- src/main/scala/scholarchain/services/entrypoint/QueryApiMain.scala
- src/main/scala/scholarchain/services/entrypoint/GraphEvidenceApiMain.scala
- src/main/scala/scholarchain/services/entrypoint/EmbeddingWorkerMain.scala
- src/main/scala/scholarchain/services/query/DynamoJobStore.scala
- src/main/scala/scholarchain/services/security/AuthFailures.scala
- src/main/scala/scholarchain/services/server/HttpEndpoint.scala
- src/test/resources/logback-test.xml
- src/test/scala/scholarchain/services/AuthFailureSpec.scala
- src/test/scala/scholarchain/services/EntrypointConfigSpec.scala
- src/test/scala/scholarchain/services/EntrypointSpec.scala
- src/test/scala/scholarchain/services/JobStoreSpec.scala

FILES MODIFIED (all under modules/services/):
- src/main/resources/api-contract.md and src/main/resources/scholarchain-services.conf
- src/main/scala/scholarchain/services/api/ApiService.scala
- src/main/scala/scholarchain/services/config/ServiceSettings.scala
- src/main/scala/scholarchain/services/http/Http.scala
- src/main/scala/scholarchain/services/query/QueryEngine.scala
- src/main/scala/scholarchain/services/runtime/ServiceRuntime.scala
- src/main/scala/scholarchain/services/server/CaskServer.scala
- src/test/scala/scholarchain/services/AuthOpsSpec.scala, QueryApiSpec.scala, TestKit.scala and UnitSpec.scala

No file outside modules/services was edited by me. `sbt fmt` ran repo-wide and reported "Reformatted 10 Scala sources". I did not check which files those were. The `git status` listing after `fmt` shows only files that were already modified or untracked before this work order.

COMMANDS RUN:
- `sbt services/compile`
- `sbt services/test` (several iterations)
- `sbt services/testOnly scholarchain.services.AuthSpec`, `GraphEvidenceApiRootSpec`
- `sbt fmt`
- `sbt check` once. Exit 0, 2125 s, `markTestRun` touched `.claude/.last-test-run`.

TEST RESULTS (from the `sbt check` log):
- services: 137 run, 137 passed, 0 failed, 23 suites. Before this work order the module had 78 tests, so 59 are new.
- Other modules in the same run (not mine): 212, 695, 66, 75, 352 (9 ignored) and 242 tests, 0 failed.
- The scalafmt check passed. Compilation produced no warnings in services main.
- New tests cover:
  - typed Timeout/Overloaded mapping (unit and end to end)
  - auth-failure limiter and source identity
  - `DynamoJobStore`
  - entrypoint settings
  - `RefreshingApiKeys`
  - the three composition roots over real sockets
- The two typed-failure tests fail without the change (`StageFailure.Timeout` and `Overloaded` had no case in `FailureMapper`, which would raise a MatchError). I did not run them without the change.
- The old `AuthSpec` test hit the new limiter. I made it probe each route from its own source address. That is a test adaptation, not a behaviour change.

VALIDATION RESULTS:

1. Typed 504/503 mapping
   - `FailureMapper` (in QueryEngine.scala) now maps `StageFailure.Timeout` to 504 `STAGE_TIMEOUT` and `Overloaded` to 503 `OVERLOADED`. Both are retryable.
   - The match is exhaustive, so there is no E029 warning.
   - Remaining legacy path: a plain `Retryable` whose detail contains "did not finish within" or "too many stages" still maps to 504/503. It lives in one private `LegacyRetryable` object. StageRunner and `StageFailure.fromStorage` still emit `Retryable` for those cases.
   - To remove it, rag-engineer must make StageRunner and `fromStorage` emit `Timeout` and `Overloaded`. A plain `Retryable` without a marker stays 502.

2. Composition roots (the sbt project is `services`)
   - The pure roots are `QueryApiRoot.start`, `GraphEvidenceApiRoot.start` and `EmbeddingWorkerRoot.start`. They take ports and settings as values, and the tests use them with fakes.
   - The `main` classes call `open(sys.env)`, which builds the real adapters from configuration only.
   - All adapters that exist are wired:
     - Neptune: `NeptuneGraphReader`, `NeptuneMetricReader`, `SupportedPathReader`
     - DynamoDB: `DynamoPorts`, plus the new `DynamoJobStore` over `DynamoJobDocumentStore` on the `querySession` table, TTL `querySession.ttlSeconds`
     - S3 Vectors, S3, SQS
     - Bedrock: `BedrockLlmClient.forSynthesis` and `BedrockEmbedder`
     - Secrets Manager API keys
   - The live `open` paths, `LiveStores` and `LiveAuth` are not exercised against AWS. The tests only check that missing configuration produces a list of keys and no stack trace.
   - The worker is built the way the storage ingest tests build their rig: an ingestor with single-attempt index retries, `ManifestStore`, `DocumentStore`, a rate limiter and `LoggingRejectSink`. It uses `Embedder.forIndex`.
   - `DynamoJobStore` maps `create` conflict to `false`. Any other store failure throws `JobStoreException`, which the API answers as 503 `JOB_STORE_UNAVAILABLE` (retryable) or 500 (record unreadable). No downstream text is returned.

3. Entrypoints. None needs another entrypoint's services, and each `/readyz` lists only its own probes.

   | Entrypoint | Main class | Serves | Probes |
   |---|---|---|---|
   | query-api | `scholarchain.services.entrypoint.QueryApiMain` | POST /v1/query, GET /v1/jobs/{id}, GET /v1/jobs/{id}/result, GET /v1/explain/{queryId}, /healthz, /readyz, /metrics | dynamodb, neptune, s3Vectors, s3, apiKeys, plus the three pending-adapter probes |
   | graph-evidence-api | `scholarchain.services.entrypoint.GraphEvidenceApiMain` | GET /v1/graph/nodes/{id}, GET /v1/graph/nodes/{id}/neighbors, GET /v1/evidence/{id}, /healthz, /readyz, /metrics | dynamodb, neptune, apiKeys |
   | embedding-worker | `scholarchain.services.entrypoint.EmbeddingWorkerMain` | /healthz, /readyz only, on its own port | consumer, s3Vectors |

   - Any other path returns 404 for a valid key. On the worker's health port a key opens nothing, so any other path returns 401.
   - The worker loop calls `consumer.runOnce` repeatedly and checks the stop flag between receives.
     - It does not use `drain()`, because `drain()` cannot be interrupted between messages.
     - `maxMessagesPerRun` is passed through to `ConsumerSettings` but the loop does not use it.
   - SIGTERM runs a shutdown hook. It marks the worker not ready, lets the message in hand finish, and takes no new message. It waits at most `shutdownGraceSeconds`, then stops the health listener. If the loop dies unexpectedly, the process exits 1.
   - Readiness fails on any of: loop stopped or stopping, no progress for `pollStalenessSeconds`, or `maxConsecutiveFailures` receive failures in a row.
   - `IngestDeadline` interrupts a message that runs past `maxIngestRunSeconds` and returns `StorageError.Timeout`, so the message is redelivered. Interruption is best effort.
   - Start-up refuses configurations where `visibilityTimeoutSeconds <= maxIngestRunSeconds`, `pollStalenessSeconds <= waitSeconds + maxIngestRunSeconds`, or the DLQ URL equals the queue URL. It also refuses vector indexes with differing dimensions, because one embedder serves them all.

4. Failed-authentication rate limit (`AuthFailureLimiter`, `SourceIdentity`)
   - Counting is per source in a fixed window, with memory bounded by `maxTrackedSources`. Ended windows are dropped first, then the oldest live window is evicted. The clock is injected.
   - A blocked source gets 429 with reason `AUTH_FAILURE_LIMIT` and a `Retry-After` header. It is refused before its token is examined, and a success does not reset the count.
   - Source identity is the TCP peer, `exchange.getSourceAddress` in `CaskServer`. It is not a header. `X-Forwarded-For` is read only when `auth.failures.trustedProxyHops` is greater than 0 (set 1 behind the ALB).
     - The source is then the N-th entry from the right, which the proxy appended. Entries to its left are client-typed and ignored.
     - Fewer entries than hops, or a value that is not an IP literal, falls back to the peer. The default of 0 never reads the header.
   - Neither the token nor the source address is logged, and neither appears in a response. A test checks this.

5. Root tests over real local sockets
   - The query API root serves /healthz on an ephemeral port, 127.0.0.1 port 0.
   - A full query (POST /v1/query then GET /v1/jobs/{jobId}) goes through the socket and the cask transport.
   - Real-socket coverage also includes the 401 and 429 path from the TCP peer, the 404s for routes of another entrypoint, and readiness listing only the entrypoint's own probes.
   - The graph API and the worker are also covered. Worker tests cover queue consumption, DLQ, transient retry, receive failure and recovery, staleness, graceful shutdown, grace timeout and the ingest deadline.
   - `HttpEndpoint` starts Undertow with cask's `defaultHandler`, so it can bind port 0 and report the bound port. `cask.Main.main` cannot do either.

Route table and status mapping (unchanged, plus these additions):
- 429 `RATE_LIMIT` or `AUTH_FAILURE_LIMIT`
- 503 `NOT_READY`, `CIRCUIT_OPEN`, `OVERLOADED`, `JOB_STORE_UNAVAILABLE`
- 504 `STAGE_TIMEOUT` and `TIMEOUT`
- Authentication: bearer API keys held as SHA-256 digests in Secrets Manager. Keys are loaded at start-up, which fails closed, and re-read every `auth.keyRefreshSeconds`. A failed refresh keeps the previous keys.

Timeout policy and dependency graph: unchanged for the HTTP paths (`readTimeoutMillis`, `synchronousDeadlineMs`, `graphTimeoutMillis`). New:
- Model attempt timeout `llm.attemptTimeoutMillis`, with `llm.maxAttempts` / `baseDelayMs` / `maxDelayMs` backoff.
- `workflow.stageTimeoutMillis`.
- Worker `maxIngestRunSeconds` and `shutdownGraceSeconds`.
- The dependency graph is in the `ServiceRuntime` scaladoc.

Config keys, env and main-class needs, for build-engineer to package:
- All three mains are in the `services` project (`JavaAppPackaging`). The image needs one launcher per main class, selected via the launcher script's `-main` option or a per-entrypoint launcher script.
- Logging goes through logback-classic (Runtime-scoped), which is already a dependency.
- Services conf (`scholarchain-services.conf`, `scholarchain.services.*`). Keys with no value of their own, which the deployment must supply:

  | Key | Env var | Needed by |
  |---|---|---|
  | `serving.runId` | `SCHOLARCHAIN_SVC_RUN_ID` | query-api |
  | `serving.snapshot` | `SCHOLARCHAIN_SVC_SNAPSHOT` | query-api |
  | `auth.keySecretName` | `SCHOLARCHAIN_SVC_KEY_SECRET_NAME` | both APIs |
  | `workflow.promptsDir` | `SCHOLARCHAIN_SVC_PROMPTS_DIR` | query-api |
  | `embeddingWorker.queueUrl` | `SCHOLARCHAIN_SVC_EMBEDDING_QUEUE_URL` | worker |
  | `embeddingWorker.deadLetterQueueUrl` | `SCHOLARCHAIN_SVC_EMBEDDING_DLQ_URL` | worker |

  - `workflow.promptsDir` means `prompts/` must be copied into the image.
  - The serving values are not read by the graph API or the worker.
- Bind and port keys, with defaults in the conf:

  | Key | Env var | Default |
  |---|---|---|
  | `queryApi.bind.host` / `.port` | `SCHOLARCHAIN_QUERY_API_HOST` / `_PORT` | 0.0.0.0 / 8080 |
  | `graphEvidenceApi.bind.host` / `.port` | `SCHOLARCHAIN_GRAPH_API_HOST` / `_PORT` | 0.0.0.0 / 8081 |
  | `embeddingWorker.health.host` / `.port` | `SCHOLARCHAIN_WORKER_HEALTH_HOST` / `_PORT` | 0.0.0.0 / 8082 |

- Other new services keys, each with an env override of the form `SCHOLARCHAIN_SVC_*` (see the conf file):
  - `auth.failures.maxFailures`, `windowSeconds`, `maxTrackedSources`, `trustedProxyHops`
  - `auth.keyRefreshSeconds`, `auth.maxTokenChars`
  - `llm.maxAttempts`, `baseDelayMs`, `maxDelayMs`, `attemptTimeoutMillis`
  - `workflow.synthesisPromptVersion`, `synthesisSchemaVersion`, `stageTimeoutMillis`, `defaultWindowStartYear` / `EndYear` (optional, set together)
  - `workflow.plannerMaxQueryCodePoints`, `maxExcerptCodePoints`, `maxSemanticQueryCodePoints`
  - `embeddingWorker.consumer.*` (`batchSize`, `visibilityTimeoutSeconds`, `waitSeconds`, `maxReceiveCount`, `maxMessagesPerRun`, `retryBackoffSeconds`)
  - `embeddingWorker.maxIngestRunSeconds`, `idleBackoffMillis`, `pollStalenessSeconds`, `maxConsecutiveFailures`, `shutdownGraceSeconds`
- Core configuration read from the process environment through `ConfigLoader` (`SCHOLARCHAIN_ENV` is required):

  | Entrypoint | Core sections read |
  |---|---|
  | query-api | storage, Neptune, vector indexes, storage runtime, retrieval, operations, rag, models |
  | graph-evidence-api | storage runtime, retrieval, operations |
  | embedding-worker | `StorageSettings` only |

  - The worker also needs `SCHOLARCHAIN_VECTOR_INGEST_*`, `EMBEDDING_MODEL_ID`, `EMBEDDING_MODEL_VERSION` and `SCHOLARCHAIN_EMBEDDING_NORMALIZE`.
  - The query-api also needs `EMBEDDING_MODEL_VERSION` and `SCHOLARCHAIN_EMBEDDING_NORMALIZE`.

For infra-engineer (Terraform, SQS and IAM) I changed nothing. Needs:
- SQS embedding queue plus DLQ:
  - visibility timeout greater than `maxIngestRunSeconds`; keep the queue's own visibility timeout equal to or above the worker's `visibilityTimeoutSeconds` (the worker passes its value per receive)
  - redrive `maxReceiveCount` aligned with `consumer.maxReceiveCount`
  - encryption at rest
  - worker ECS autoscaling on queue depth
  - container health check on port 8082 `/healthz` and an ALB or ECS check on `/readyz`
- Three ECS services behind separate target groups. APIs use `/healthz` for liveness and `/readyz` for readiness. `trustedProxyHops=1` behind the ALB.
- Task roles, least privilege:
  - query-api:
    - DynamoDB read/write on the `querySession` table, including transactions and conditional writes, and read/write on the `evidenceBundle` table
    - `neptune-db:ReadDataViaQuery` and `neptune-db:GetEngineStatus`
    - S3 read on the rag bucket
    - S3 Vectors query/get/describe
    - `bedrock:InvokeModel` for the synthesis and embedding models, and Converse
    - `secretsmanager:GetSecretValue` on the key secret
    - the `querySession` table's TTL attribute is `expiresAt`
  - graph-evidence-api: Neptune read, DynamoDB read on `evidenceBundle`, Secrets Manager read.
  - embedding-worker:
    - SQS receive, delete, change-visibility, and send on the DLQ
    - S3 read/write on the rag bucket
    - S3 Vectors put/get/describe
    - DynamoDB on the run-ledger and artifact-registry tables
    - Bedrock `InvokeModel` for the embedding model
- Secrets Manager secret holding the API-key digest JSON.

ASSUMPTIONS:
- `StorageError.retryable` decides whether a job-store failure is a retryable outage or a non-retryable fault.
- The three entrypoints share one codebase and image, differing by main class.
- One embedder serves all vector indexes, so equal dimensions are required.
- The ALB is the only trusted proxy, so `trustedProxyHops=1` there.

RISKS:
- The live `open` and `main` paths are unexercised against AWS. `sbt check` needs no AWS. The Bedrock embedder request shape is not yet verified live (per its rag doc comment).
- If the store fails at the end of an async job, the job can stay `Running`. `QueryService.execute` runs the final `jobs.update` inside an executor task that swallows exceptions. That code was unchanged and is in my territory.
- `ExplainService` and `QueryService` call `JobStore` synchronously. With the durable store these calls can now fail, and `JobStoreException` is mapped only at the `ApiService.handle` boundary.
- The legacy `Retryable` text markers remain until rag changes StageRunner.
- The APIs stop Undertow immediately on SIGTERM and do not drain in-flight requests. ECS deregistration delay covers that in practice.
- The worker's `LoggingRejectSink` logs reject codes and keys only and does not retain them. The durable reject location is in the ingest report.
- Eviction of the oldest live auth-failure window lets an attacker who rotates through more than `maxTrackedSources` addresses reset an address's count. Each address still needs its own budget.
- `LiveStores.open` builds all five AWS SDK clients for every entrypoint. Building a client does not contact AWS, but each process needs the settings for all of them.

MANUAL ACTION REQUIRED:
- infra-engineer and build-engineer items above.
- Human architect: decide ordering of the storage adapters (below) before a deployment can take query traffic.

BLOCKED-ON OWNER: storage-engineer (three read adapters). rag-engineer (optional, to retire the legacy text markers).

BLOCKED-ON EVIDENCE:
- `ResearcherDirectory`: only `FakeResearcherDirectory`. The comment in `storage/directory/ResearcherDirectory.scala` says "There is no real adapter".
- `EdgeSupportReader`: only `FakeEdgeSupportReader` (`storage/graph/PathSupport.scala`). `SupportedPathReader` depends on it.
- `AnalyticsReader`: only `FakeAnalyticsReader` (`storage/graph/AnalyticsPorts.scala`). The comment there says "No real adapter exists yet because no store loads these records".
- `WorkflowDependencies` requires all three (`directory`, `edgeSupport`, `analytics`).
- Behind the explicit `WorkflowReadPorts` port, I composed `PendingReadAdapters`. It refuses with `StorageError.Unsupported`, invents no data, and reports each of the three as failing in `/readyz` (`researcherDirectoryAdapter`, `edgeSupportAdapter`, `analyticsAdapter`). Once storage delivers the adapters, replace `PendingReadAdapters.ports` and `.probes` in QueryApiMain.scala. Everything else is wired.
- The Neptune runtime adapters (`NeptuneGraphReader`, `NeptuneMetricReader`) and the Bedrock LLM client and embedder do exist and are wired.

NEXT GATE: API tests (passed), sbt check (passed), code-reviewer.

===== build-engineer =====
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 10b, work order 4 of 4. One container image per service entrypoint (query-api, graph-evidence-api, embedding-worker). The sbt aliases `check` and `dockerLocal` must keep working.

DESIGN
- I chose three independently deployable images that share one `docker/Dockerfile.service`. Build args set the main class and health port. Each image has its own staged build context.
- The sbt-native-packager `DockerPlugin` produced a single image with a single main class. I removed it from `services` and kept `JavaAppPackaging`, which still supplies `Universal/stage`.
- New sbt tasks on `services`:
  - `dockerStageContexts` needs no Docker daemon. It writes `modules/services/target/docker/<svc>/{payload,docker}`. `payload/lib` holds the staged jars. `payload/prompts` is copied for query-api only.
  - `dockerBuildLocal` runs `docker build` for all three images and never pushes.
- Aliases:
  - `dockerLocal` now runs `services/dockerBuildLocal`.
  - `dockerStage` is new and runs `services/dockerStageContexts`.
- Image tags are `scholarchain-query-api:0.1.0`, `scholarchain-graph-evidence-api:0.1.0` and `scholarchain-embedding-worker:0.1.0`.
- Ports: query-api 8080, graph-evidence-api 8081, embedding-worker 8082.
- Runtime base image: `eclipse-temurin:17.0.21_9-jre-noble@sha256:ae8ca912a35034f0edf84bf2f9d15b087829127778aa262cd20dffb281bb9285`.
  - I got the digest from the registry by pulling the tag. It is the multi-arch index digest.
  - It matches Java 17 (`javaRelease` = "17").
  - The Dockerfile `BASE_IMAGE` arg has no default, so a hand-run build can't use an unpinned image. sbt builds the reference from the ledger.
- User: non-root `scholarchain`, UID and GID 10001, `USER 10001:10001`, nologin shell, no home directory.
- Filesystem:
  - `/app` is root-owned and read-only to the service user. I confirmed that a `touch` in `/app` is denied.
  - The layout works with `--read-only --tmpfs /tmp`.
  - The images contain no secrets and no env values. Configuration comes from `SCHOLARCHAIN_*` at run time.
- `docker/launch.sh` ends in `exec java`, so the JVM is PID 1 and receives SIGTERM. Its flags:
  - `-XX:InitialRAMPercentage=25.0`
  - `-XX:MaxRAMPercentage=75.0`
  - `-XX:+ExitOnOutOfMemoryError`
  - `-XX:+UseContainerSupport`
  - `-Djava.io.tmpdir=/tmp`
  - There is no `-Xmx`.
  - GC is left to JVM ergonomics (Serial on small containers, G1 otherwise). Deployments can add flags through `JAVA_TOOL_OPTIONS`.
- `HEALTHCHECK` runs `docker/healthcheck.sh`, which calls `curl` against `/healthz` on `HEALTH_PORT`. The base image ships curl, which I confirmed, so I added no extra dependency. It runs as a separate process and does not affect PID 1.
- `.dockerignore` is new. It is deny-all with allowances for `docker/` and `modules/services/target/docker/`, and it excludes `.env*`, `*.pem` and `*.key`. The sbt contexts contain only staged content.
- I made no compose file. None existed, and the work order said to add one only if it matched an existing pattern.

VALIDATION OBSERVED
- `sbt services/dockerStageContexts` staged all three contexts without a daemon.
- `sbt dockerLocal` built all three images. Docker 29.1.3 is available and its daemon was running.
- Non-root: `id` in each image showed uid=10001 and gid=10001. `docker inspect` showed User `10001:10001` and the entrypoint `/app/bin/launch.sh`.
- Prompts are present only in query-api. Both other images return "No such file" for `/app/prompts`.
- Real services with a minimal env (`SCHOLARCHAIN_ENV`, `SCHOLARCHAIN_SVC_RUN_ID`, `SCHOLARCHAIN_SVC_SNAPSHOT` and `SCHOLARCHAIN_SVC_PROMPTS_DIR` set to fake values):
  - All three exit with code 2 and a fail-fast list of the missing configuration. query-api and graph-evidence-api name the retrieval, operations and model variables. embedding-worker names the SQS queue and dead-letter queue URLs.
  - graph-evidence-api also needs `AWS_REGION`, five buckets, `NEPTUNE_*` and five `VECTOR_INDEX_*` names.
  - I did not supply fake values for a full wiring. The real services need Neptune, S3 and Bedrock wiring, which I treated as AWS. So I did NOT observe a real service answer `/healthz`.
- Container mechanics, tested with a stand-in jar (a tiny HTTP server with a `/healthz` endpoint and a shutdown hook) mounted into `/app/lib`, run with `--read-only --tmpfs /tmp -m 512m`:
  - Docker reported the container `healthy`.
  - `/proc/1/cmdline` showed `java ... -cp /app/lib/* Stub`, so the JVM is PID 1.
  - The JVM max heap was 389 MB under the 512 MB limit, which is percentage-based.
  - `docker stop` took 0.57 s. The shutdown hook ran with pid1=1 and the exit code was 143, so SIGTERM was delivered.
- `sbt check`:
  - The first run (1232 s into the run) failed in the `tests` project with `Cannot run program "chmod": error=11` and `pthread_create failed (EAGAIN)`. The failing case was "case 12 (oracle): a seeded random graph". This looks like host thread or process exhaustion in the WSL environment. I did not change anything between the two runs.
  - The second run, on the same tree, exited 0. All suites showed `failed 0`: 137, 212, 695, 242, 242, 75, 352 (9 ignored) and 66 tests. `markTestRun` touched `.claude/.last-test-run`. Total time was 2165 s.
  - `scalafmtCheckAll`, `scalafmtSbtCheck` and `Test/compile` passed as part of that run.

FILES CREATED: /home/olmos/scholarchain/docker/Dockerfile.service, /home/olmos/scholarchain/docker/launch.sh, /home/olmos/scholarchain/docker/healthcheck.sh, /home/olmos/scholarchain/.dockerignore, /home/olmos/scholarchain/project/ServiceImage.scala
FILES MODIFIED: /home/olmos/scholarchain/build.sbt, /home/olmos/scholarchain/project/Dependencies.scala. The ledger delta adds `Dependencies.temurinJreDigest` (a structural addition outside `Versions`) and updates a comment. No version values changed. Two Docker plugin values went away with the plugin: `dockerBaseImage` (`eclipse-temurin:17.0.21_9-jre-noble`) and the Universal `-J-XX` memory options.

COMMANDS RUN: `sbt services/dockerStageContexts`, `sbt dockerLocal`, `docker pull` of the base tag, `docker run` and `docker inspect` checks, `sbt check` twice. The first `sbt check` failed on environment thread exhaustion and the second passed. Early `sbt` runs failed on build.sbt compile errors (a class defined in build.sbt, and Scala 2.12 lambda syntax). I fixed these by moving the class to `project/ServiceImage.scala`.
TEST RESULTS: see `sbt check` above.
VALIDATION RESULTS: see above.

ASSUMPTIONS
- `docker build` uses the legacy builder here, so I used `RUN chmod` instead of `COPY --chmod`.
- The build-time class `ServiceImage` lives in `project/`, which is build structure.
- The sbt build env surface is unchanged: only `SCHOLARCHAIN_AWS_IT`. Container run-time env is the services' own `SCHOLARCHAIN_*` surface.

RISKS
- Real service startup in a container is unverified. It needs the full config set and AWS access, which the orchestrator or deploy phase must supply.
- Images are about 619 MB uncompressed. `jre-noble` is Ubuntu-based and includes curl.
- `dockerBuildLocal` needs a Docker daemon. `sbt check` does not run it, so image builds are not covered by `check`.
- `sbt check` takes about 36 min here. The thread-exhaustion flake in the `tests` project (Spark oracle case) may recur on this host.
- The base image digest must be refreshed together with `temurinJreTag`. `dependency-updater` owns the tag value. The digest field is mine.
- The Scala `services` doc build prints unrelated scaladoc link warnings from `StageFailure`/`StageRunner`, which sit in service-engineer territory.

MANUAL ACTION REQUIRED: none. The images exist locally only, nothing was pushed, and no AWS calls were made. Nine unrelated containers that were already running on the host were left untouched.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer

===== storage-engineer (repair-01) =====
STATUS: COMPLETE (code and fakes; the live path is gated on one infra item, see BLOCKED-ON)
OWNER: storage-engineer
WORK ORDER: Phase 10b repair-01 (control rule 11 routing). Implement the three missing real read adapters in modules/storage, behind the existing ports, with feature tests.

STATE FOUND
The working tree already held an uncommitted, unformatted draft of the three adapters, the loader and their tests. I reviewed it, fixed it, formatted it, and made `sbt check` pass. I did not start from nothing.

ADAPTERS
All three are in package `scholarchain.storage.dynamo`, file `modules/storage/src/main/scala/scholarchain/storage/dynamo/GraphReadModel.scala`. All read ONE DynamoDB table, the graph read model. The port signatures are unchanged.

1. `DynamoResearcherDirectory(items: ItemStore, table: String, runId: String, maxLimit: Int)` implements `ResearcherDirectory`.
   - Config: `table` comes from `StorageSettings.requireGraphReadModelTable`. That is `dynamodb.tables.graphReadModel`, defaulting to `<dynamodb.tablePrefix>-<environment>-graph-read-model`. `maxLimit` is `retrieval.graphLimit` (settings `graphLimit`). `runId` is the loaded run.
   - Layout: `pk = dir#<runId>#<foldedName>`, `sk = c#<inverted confidence>#<researcherId>`. An ascending query gives `confidence DESC, researcherId ASC`. The item holds `DirectoryEntry{candidate: NameCandidate, provenance}`.
   - Behaviour: a limit above `maxLimit` is refused with `LimitExceeded`. A blank name or a limit under 1 gives `InvalidRecord`. A misfiled item gives `InvalidRecord`.
   - Data comes from the Spark `researcher` table. The name fold is in `directory/NameFold.scala`. `coauthorSample` is empty because the canonical researcher record carries no coauthors, and none are invented.
2. `DynamoEdgeSupportReader(items: ItemStore, table: String, maxRowsPerEdge: Int)` implements `EdgeSupportReader`.
   - Layout: `pk = esup#<runId>#<edgeId>`, `sk = w#<workId>`. The run ID comes from `edge.runId`, and the pointer must equal `CoauthorEdgeSupport.supportRef(edgeId)`.
   - It returns distinct work IDs, sorted. If an edge has more rows than `maxRowsPerEdge` it fails with `LimitExceeded` and never returns a partial list. `SupportedPathReader` still enforces that rows == `jointPublicationCount`.
   - Data comes from the Spark `coauthored_with_support` table.
3. `DynamoAnalyticsReader(items: ItemStore, table: String, runId: String, limits: GraphReaderLimits)` implements `AnalyticsReader`.
   - `limits` come from `StorageSettings.graphReaderLimits`.
   - Component and community membership: `pk = comp#<run>#<researcher>` or `comm#<run>#<researcher>`, `sk = w#<start>-<end>`. Each is a point `get`.
   - Cross-community edges: `pk = xedge#<run>#<community>#<window>`, `sk = e#<inverted jointCount>#<edgeId>`. One item is stored under each endpoint community. Order is `jointPublicationCount DESC, edgeId ASC`, bounded by the graph limit.
   - Connectivity: `pk = xconn#<run>#<community>#<window>`, with `sk = o#<other>` link rows and `sk = M` as a marker row.
   - `crossCommunityEdgeCount` and `communitiesConnected` are exact server-side counts (`Select=COUNT`, through the new `ItemStore.count`). They are readable only when the marker exists. Otherwise they return `NotFound`, never a guessed zero.
   - Data comes from the Spark `components`, `community_membership` and `cross_community_edge` tables.

WHAT WRITES THE DATA
- `scholarchain.storage.loader.GraphReadModelLoader(items, table, chunkSize, rejects, expectedTarget = DYNAMODB)`, in `loader/GraphReadModelLoader.scala`. It loads the Parquet tables of a verified Spark load manifest into the read model table.
- Nothing has run it against real AWS yet. It is not wired into a runnable job. Any entry point or script that runs it is outside storage (infra scripts or an operator step).
- Data-layout caveat: the Spark DynamoDB manifest lists researcher, support, components and community_membership. `cross_community_edge` is in the Neptune and S3 manifests. With the DynamoDB manifest the loader still loads the four tables, writes no connectivity markers, and sets `connectivityStored=false` with a note. Cross-community counts then stay `NotFound`. To populate them, run the loader with a manifest that lists `cross_community_edge`. That is a spark-engineer manifest decision, and the loader's `expectedTarget` parameter supports it.
- `DynamoLoader` reports the read model tables as owned by `GraphReadModelLoader`.

SCHEMAS, KEYS, METADATA
- Item schema versions: `researcher-directory-entry-v1`, `community-link-v1`, `community-connectivity-marker-v1`, plus the core contracts for support, membership and cross-edge records.
- Every item carries provenance (run ID, policy, schema) and a `contentHash`. No TTL is set anywhere, because none of this data is disposable.
- Supporting changes in storage:
  - `ItemStore.replaceIfHash` and `ItemStore.count`, implemented in `DynamoItemStore` and `InMemoryItemStore`.
  - `StorageSettings.graphReadModelTable: Option[String]`, with the `requireGraphReadModelTable` extension.

BATCH, RETRY, IDEMPOTENCY
- Loader writes are conditional puts (`attribute_not_exists(pk)`), one item per request. BatchWriteItem cannot carry conditions. `batch.dynamoBatchWrite` (at most 25) is the chunk size for progress counting. Transient errors are retried by the DynamoItemStore retrier.
- An existing item with identical content is counted `unchanged`. Different content gives a recorded `CONTENT_CONFLICT` reject, and the stored item is left as it is. An undecodable row gives a `ROW_DECODE` reject. An invalid row or one from another run gives an `INVALID_RECORD` reject.
- Cross-community edges are stored first. Markers are withheld if any cross edge was rejected. Reloading creates nothing.
- One caveat: a marker shared by two researchers of the same community counts as `unchanged` on the first load and on reload. The test asserts this.

SERVICE LIMITS: I did not need to look up any new limit this round. The only limit used is `DynamoItemLimits.MaxItemBytes` (the DynamoDB item size limit), which already existed in the code and which the code enforces on every read model item. I did not re-verify it against current AWS documentation this session.

CLEANUP: No cleanup procedure is needed beyond the existing ones, and I deleted nothing. Read model items are keyed by run ID, so a superseded run's items are inert. Removing them is data deletion, which goes to the human architect.

FILES CREATED (all under /home/olmos/scholarchain/modules/storage/src/)
- main/scala/scholarchain/storage/dynamo/GraphReadModel.scala
- main/scala/scholarchain/storage/loader/GraphReadModelLoader.scala
- main/scala/scholarchain/storage/directory/NameFold.scala
- test/scala/scholarchain/storage/ReadModelSpec.scala
- test/scala/scholarchain/storage/ReadModelContracts.scala
- test/scala/scholarchain/storage/ReadModelData.scala
- The tree also holds earlier-session files I did not touch this session: dynamo/JobDocuments.scala, aws/SqsWorkQueue.scala, queue/* and their specs (JobStoreSpec, WorkQueueSpec).

FILES MODIFIED (same root)
- StorageSettings.scala, aws/DynamoItemStore.scala, dynamo/DynamoPorts.scala, dynamo/InMemoryItemStore.scala, loader/DynamoLoader.scala
- directory/ResearcherDirectory.scala and graph/PathSupport.scala, whose fake-only comments now point at the real adapters
- graph/AnalyticsPorts.scala
- test/AwsIntegrationSpec.scala, which adds `ResearcherDirectoryIT`, `EdgeSupportReaderIT`, `AnalyticsReaderIT` and `JobDocumentStoreIT` behind the existing AwsIntegration gate
- test/DynamoSpec.scala

This session I fixed `ReadModelSpec.scala` (fixture and expectation bugs, listed under TEST RESULTS) and ran scalafmt over the storage sources. The scalafmt run reformatted 4 main and 4 test files, all within modules/storage. `git status` showed the same set of paths before and after it.

COMMANDS RUN (all output observed)
- `sbt check`, first run: failed on scalafmtCheck in storage.
- `sbt storage/scalafmt storage/Test/scalafmt`: success.
- `sbt check`, second run: `storage / Test / test` failed with 6 failures, all in `GraphReadModelLoaderSpec`.
- `sbt storage/test`: confirmed the same 6 failures.
- `sbt storage/testOnly scholarchain.storage.GraphReadModelLoaderSpec`, run three times while fixing: failures 6, then 3, then 1, then 0 (14 of 14 passed).
- `sbt check`, final run: success, with `markTestRun: tests passed; touched .claude/.last-test-run`.

TEST RESULTS
- The final `sbt check` passed. Reported suites: 75 tests (7 suites), 66 tests (8 suites), 352 tests (25 suites), all passed.
- The 352-test run is the storage run, as inferred from the suite counts. The earlier `storage/test` run showed 306 succeeded and 6 failed before the fix.
- The six failures were test-fixture errors, not adapter bugs:
  - The fixture used "decision:d1", which is not a valid stable-ID kind. It now uses `StableIds.matchDecisionId`.
  - The community_membership expectation counted 6 created items. The correct figure is 5, with 1 unchanged, because two researchers in one community share a marker.
  - The reload expectation was off by that same shared marker.
  - The foreign-run row was an invalid singleton component. It is now valid, so it exercises the intended `INVALID_RECORD` path.
- No live AWS was used. The new IT suites are skipped without the AwsIntegration profile.

VALIDATION RESULTS: The fake and the real adapters (over `InMemoryItemStore`) both pass the same three contract suites (`ResearcherDirectoryContract`, `EdgeSupportReaderContract`, `AnalyticsReaderContract`). The loader-to-adapter round trip is tested.

ASSUMPTIONS
- One shared table with prefixed partition keys, and the table name coming from `dynamodb.tables.graphReadModel` or the prefix default.
- An adapter instance is bound to one run ID, which services must supply (for example the latest loaded run). Edge support takes its run ID from the edge itself.
- The directory matches the already-normalized name exactly. The fold function is in `NameFold`.

RISKS
- The table does not exist in Terraform (no match for `graph-read-model`, `graphReadModel` or `graph_read_model` under infra/ or modules/core/src/main). Nothing can run live until it exists.
- The core config table list does not name the read model table. The settings default derives it from the table prefix.
- I did not verify that the loader's run order of manifests matches the actual Spark output, because there is no live run.
- The integration test class names exist but are unexecuted.
- The `ReadModelSpec` file I edited this session was written by an earlier session. I did not re-audit the rest of it beyond the failing tests.

MANUAL ACTION REQUIRED
- Provision the table. Key schema: string `pk` (partition) and string `sk` (sort), on-demand, no TTL. Read-only runtime role on Query and GetItem. Loader role with PutItem.
- A human or operator runs the loader once per run.
- Decide whether the Spark DynamoDB manifest should list `cross_community_edge`.
- Services: replace `PendingReadAdapters.ports` and `.probes` in QueryApiMain.scala with `DynamoResearcherDirectory`, `DynamoEdgeSupportReader` (wrapped in `SupportedPathReader`) and `DynamoAnalyticsReader`.

BLOCKED-ON OWNER: infra-engineer (the table and the IAM for the read model table). Optionally service-engineer for the wiring above. Optionally spark-engineer for the manifest decision.
BLOCKED-ON EVIDENCE: No DynamoDB table named `graph-read-model` exists in infra/terraform (grep found nothing). I invented no data and no layout beyond what the loader writes. The three adapters read exactly what `GraphReadModelLoader` writes.
NEXT GATE: contract tests, bounded load test (needs the table), code-reviewer

===== service-engineer (rerun after repair-01) =====
STATUS: COMPLETE (code and fakes only; no live AWS behavior observed)
OWNER: service-engineer
WORK ORDER: Phase 10b rerun after repair-01. Wire the three Dynamo read adapters into the query-api root, verify the DynamoDB JobStore wiring, add tests.

SUMMARY
- Removed `PendingReadAdapters`, the refusing stand-ins and the three always-failing probes.
- Added `ReadAdapters.open(items, storage, runId)` in QueryApiMain.scala. It builds:
  - `DynamoResearcherDirectory(items, table, runId, graphLimit)`
  - `DynamoEdgeSupportReader(items, table, graphLimit)`
  - `DynamoAnalyticsReader(items, table, runId, graphReaderLimits)`
- `SupportedPathReader(neptuneGraph, reads.edgeSupport)` is unchanged and now sits on the real support reader.
- Table name: `StorageSettings.requireGraphReadModelTable`. If it is unset, start-up fails with an error naming the key `dynamodb.tables.graphReadModel`, never a value.
- Run ID: `svc.serving.runId`, which is `scholarchain.services.serving.runId` / `SCHOLARCHAIN_SVC_RUN_ID` and was already required by the query API.
- Limits: `retrieval.graphLimit` (`SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT`) and the existing path-depth and path-timeout settings. Nothing is hardcoded.
- Real probes replace the pending ones. Each makes one bounded read and reports false on any store error. The names are unchanged: `researcherDirectoryAdapter`, `edgeSupportAdapter`, `analyticsAdapter`.
  - `researcherDirectoryAdapter` calls the directory adapter's `candidatesByName`.
  - `analyticsAdapter` calls the analytics adapter's `crossCommunityEdges`.
  - `edgeSupportAdapter` queries the `esup#<run>#readiness-probe` partition of the table directly. The support adapter needs a real edge, so it cannot be called with a probe key.
- The JobStore wiring was confirmed correct and is now factored into `QueryJobs.store(items, storage)`:
  - `DynamoJobStore` over `DynamoJobDocumentStore(items, dynamoTables.querySession, Some(querySession.ttlSeconds))`.
  - Table and TTL both come from configuration.
  - No fix was needed.
- Other roots: no other root used the pending adapters. The graph/evidence API and the embedding worker never needed them. No change there.

ROUTE TABLE / STATUS MAPPING / TIMEOUT POLICY / AUTH: unchanged this round. `/readyz` returns 503 with `error.details.checks` naming the failing probes. `/healthz` stays 200 and is independent of probes.

DEPENDENCY GRAPH: `services` depends on `core`, `rag` and `storage`, as before. The query API now also depends on the graph read model table for directory, support and analytics reads.

DEPLOYMENT MUST SUPPLY (for infra/build follow-up)
- The graph read model DynamoDB table:
  - Config key `dynamodb.tables.graphReadModel`. If unset, it defaults to `<SCHOLARCHAIN_DDB_TABLE_PREFIX>-<env>-graph-read-model`.
  - Key schema: string `pk` and string `sk`, on-demand, no TTL.
  - Query-api task role: `dynamodb:GetItem` and `dynamodb:Query` on that table.
- `SCHOLARCHAIN_SVC_RUN_ID` must be the run the loader loaded into the table. A mismatch gives empty reads and NotFound counts, and the probes still report ok.
- `SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT`, now also the directory limit and the per-edge support-row limit.
- The query-session table and TTL (existing keys) for the job store.
- No new services config keys were added.
- Someone must run `GraphReadModelLoader`, per the storage report. It is not wired into any job.

FILES CREATED: none
FILES MODIFIED (all under /home/olmos/scholarchain/modules/services/)
- src/main/scala/scholarchain/services/entrypoint/QueryApiMain.scala
- src/test/scala/scholarchain/services/EntrypointSpec.scala

COMMANDS RUN (output observed)
- `sbt -batch fmt`
- `sbt -batch services/Test/compile`. It failed twice (a missing storage extension import, then a test that subclassed the final `InMemoryItemStore`), then succeeded.
- `sbt -batch check`. I ran it twice by mistake: the first call's output was filtered through grep, which hid the result lines, so I re-ran it. The final run passed, with `markTestRun: tests passed; touched .claude/.last-test-run`.
- `sbt -batch "services/testOnly scholarchain.services.QueryApiRootSpec"`

TEST RESULTS
- The final `sbt check` passed with 0 failures in every suite group: 140, 212, 695, 312, 242, 75, 66 and 352 tests (the 352 group had 9 ignored, which are the AwsIntegration-gated suites).
- I did not attribute the groups to modules, except `QueryApiRootSpec`: 10 of 10 passed.
- Tests in `QueryApiRootSpec` (EntrypointSpec.scala), all of which fail without the change, because `ReadAdapters` and `QueryJobs` did not exist:
  1. The adapters are real. Probes are ok over an empty store, the directory and component reads return "none" (not `Unsupported`), the limit is the configured graph limit (`LimitExceeded` above it), and the cross-community count is `NotFound`.
  2. A missing read model table name stops composition with an error naming `dynamodb.tables.graphReadModel`.
  3. When the item store fails, all three probes go red. Over a real socket `/readyz` returns 503 naming exactly the three probes, and `/healthz` returns 200.
  4. With the real probes over a healthy fake store, `/readyz` returns 200.
  5. The job store is `DynamoJobStore` on the configured query-session table.
- The existing tests "builds from fakes and serves /healthz over a real socket", for all three roots, still pass.
- Removed the two tests that asserted `PendingReadAdapters` behavior. They are superseded by the tests above.

VALIDATION RESULTS: The roots build with fakes and serve `/healthz` and `/readyz` over a real local socket. `LiveStores`, `QueryApiMain.open` and `wire` are not exercised by tests. They need real AWS clients, so their wiring is verified by compilation only.

ASSUMPTIONS
- One run ID per query-api deployment, taken from `serving.runId`. Edge support takes its run from the edge itself.
- `retrieval.graphLimit` doubles as the per-edge support-row cap (see RISKS).
- The probe window year 2000 passes `checkWindow` (confirmed by the tests passing).

RISKS
- An edge with more joint publications than `graphLimit` fails support reads with `LimitExceeded` and so fails path evidence. A dedicated cap key would need a new config key, which was not added without a decision.
- Probes show reachability and permissions of the table only. They cannot tell whether the loader has run for the configured run ID.
- Cross-community counts return `NotFound` until the loader runs with a manifest that lists `cross_community_edge` (a spark-engineer manifest decision, per the storage report).
- The read model table does not exist in Terraform, so nothing can run live. No live-AWS behavior is claimed.
- The test `LiveFixtures` env map duplicates the storage test env, because the storage test tree is not visible to services.

MANUAL ACTION REQUIRED
- Provision the table and IAM (infra-engineer).
- Set `SCHOLARCHAIN_SVC_RUN_ID` and the other keys above (infra/build).
- Run the loader once per run (operator).
- Decide whether the Spark DynamoDB manifest should list `cross_community_edge` (spark-engineer).

BLOCKED-ON OWNER: infra-engineer, for the graph read model table and its IAM. This blocks live operation only, not this code.
BLOCKED-ON EVIDENCE: Storage report repair-01: no `graph-read-model` table under infra/terraform. I did not re-grep infra, since it is outside my territory.
NEXT GATE: API tests, sbt check (passed), code-reviewer
