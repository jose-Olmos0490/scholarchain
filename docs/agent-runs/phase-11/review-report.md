```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 11 PART 2, adversarial read-only review (Phase 10/10b, the build.sbt wiring change, the new tests/ suites, and the 12 test-engineer defects)
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only: Read, Grep, Glob)

SCOPE LIMITS (what I could not verify)
- I have no git or shell. I could not read the HEAD~4..HEAD diff, run tests, or check which tests fail. I read the current files directly.
- The 15 failing-test counts and the pass counts in the pasted reports are unverified by me.
- I did not read all 16 new spec files line by line. I verified the production-side claim behind each of defects 1 to 11 by reading the production code. I did not trace the decode path for defect 12.
- I did not verify that each failing test fails for the stated reason. I only checked that the stated production behavior exists in the code.
- I did not read the full services, rag and storage modules. Coverage beyond the cited lines is by sampling.

FINDINGS (all verified against files unless noted)
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | MAJOR | /home/olmos/scholarchain/modules/storage/src/main/scala/scholarchain/storage/aws/DynamoItemStore.scala:89-95 and aws/AwsSupport.scala:44-53 | `TransactWriteItems` is cancelled with reason `TransactionConflict` or `ThrottlingError`. The code rethrows the exception. `isTransient` checks only the top-level error code. `TransactionInProgressException` is listed, but a cancellation reason is never inspected. The result is `Permanent`. | A normal contention case on `DynamoJobStore.create` and the other transactional puts is not retried. The API shows a non-retryable store failure (the pasted report says 500 rather than 503). Jobs fail under load. | storage-engineer |
| 2 | MAJOR | /home/olmos/scholarchain/modules/storage/src/main/scala/scholarchain/storage/aws/NeptuneAdapters.scala:124-143 (`NeptuneRows.s`, `l`, `d`, `edge`) | A Neptune row has no `edgeId`, `runId` or `evidencePointer`, or has `jointPublicationCount="three"`. The code returns `Right(CoauthorEdgeView("", ..., ""))` and reads the count as 0. | This breaks the provenance invariant in CLAUDE.md. An edge with empty provenance and a fabricated count of 0 flows into evidence and claim bindings. The count is a graph-sourced "exact count", so a silent 0 is a wrong answer. The in-memory adapter `InMemoryGraph.scala:164-207` has the same default pattern. | storage-engineer |
| 3 | MINOR | /home/olmos/scholarchain/modules/storage/src/main/scala/scholarchain/storage/aws/NeptuneAdapters.scala:190-199 | `directCoauthors` with window 2013 to 2010. It makes a Neptune call and returns an empty `Right`. | It spends a query and reports "no coauthors" for an invalid window. I did not check whether `directCoauthorsPage` or the templates validate the window, so I could not tell whether this is peculiar to this method. | storage-engineer |
| 4 | MAJOR | storage Dynamo read-model readers (`DynamoAnalyticsReader.componentOf`, `m.runId`). I did not locate the exact line. | A stored item whose `record` is JSON `null`. The reader throws `NullPointerException`. | Not verified by me beyond the test-engineer's report. It is plausible, since the other reads in this layer do no null check. A corrupt row crashes the request thread instead of returning `InvalidRecord`. I rate it MAJOR only if the NPE is not caught upstream. | storage-engineer |
| 5 | MINOR | /home/olmos/scholarchain/modules/storage/src/main/scala/scholarchain/storage/common/Retry.scala:83-84 (`describe`) | The exception message is copied into `StorageError.Transient` and `Permanent`. The real SDK message may carry ARNs, account IDs, table names or request IDs. The test's `AKIA...` input is synthetic. | `services` does not repeat storage messages (`QueryService.scala:305-331`, `StorageErrors.toApi`), so the leak does not reach HTTP. It can reach logs, S3 rejects and ingest reports. I rate it MINOR because credential text in SDK messages is unlikely. Whether storage must redact is a design choice. | storage-engineer |
| 6 | MINOR | /home/olmos/scholarchain/modules/storage/src/main/scala/scholarchain/storage/vectors/VectorIngestor.scala:493 | An embedder throws with arbitrary text. The text goes into the reject detail, and rejects are written to S3. | The embedder is Bedrock, so the text is service-controlled. This is low risk, but the text goes to durable storage unfiltered. | storage-engineer |
| 7 | MAJOR | /home/olmos/scholarchain/modules/services/src/main/scala/scholarchain/services/resilience/Resilience.scala:168-175 and query/QueryService.scala:249-285 | The async job's final `jobs.update(Succeeded/Failed)` throws `JobStoreException`, for example during a DynamoDB outage after the workflow ends. `BoundedExecutor.submit` catches `NonFatal` and drops it. The job stays `Running`, and nothing finishes it. | The job is stuck `Running`, and a client polling it never sees a result. The idempotency key then maps to the stuck job, so a retry with the same key replays the stuck job. This is the most operationally important defect. The fix is a retry on completion, a reaper, or a deadline on `Running`. | service-engineer |
| 8 | MINOR | /home/olmos/scholarchain/modules/services/src/main/scala/scholarchain/services/graph/GraphService.scala:54, 134, 173-200 | `neighborMaxDepth=3`, request `depth=3`. `fetch` calls only `directCoauthorsPage`, which is depth 1, and echoes `"depth" -> q.depth`. | The response claims a depth that was not traversed. It is latent because the shipped config is 1. The echoed depth is part of the evidence. | service-engineer |
| 9 | MINOR | /home/olmos/scholarchain/modules/rag/src/main/scala/scholarchain/rag/llm/JsonSchema.scala:78 | Model JSON with an extra key named `ZZ-LLM-MARKER-4`. The rejection detail contains `$.<key>: property is not allowed`. | Model-controlled text is echoed into a rejection that is logged and traced. This is untrusted-text leakage, and it conflicts with the doc claim of "never the offending value". | rag-engineer |
| 10 | MINOR | /home/olmos/scholarchain/modules/rag/src/main/scala/scholarchain/rag/ingest/IngestionRunner.scala:43 | `Files.readAllLines(f, UTF_8)` on a file of `0xFF` bytes throws `MalformedInputException`. | The typed refusal with exit code 2 is bypassed and the CLI dies with a stack trace. I did not read `IngestMain` to see whether it catches this elsewhere. | rag-engineer |
| 11 | MAJOR | /home/olmos/scholarchain/modules/rag/src/main/scala/scholarchain/rag/chunk/Chunker.scala:46 | `Token = "\\S+".r` without Unicode classes, so `\s` is ASCII only. Text separated by NBSP, U+3000 or thin spaces is one token. | A single 28 KB chunk goes over the embedding limit. That means a Bedrock failure or truncated embeddings on CJK and NBSP-heavy text. It silently breaks chunking for non-ASCII corpora. | rag-engineer |
| 12 | MINOR | embedding work item decoder (`EmbeddingWorkItem` in storage). I did not locate it. | `{"runId":7}` is coerced to the string "7". | The report says the manifest store refuses it later. Plausible, and not verified by me. | storage-engineer |
| 13 | MAJOR | /home/olmos/scholarchain/modules/entity-resolution/KNOWN-LIMITATIONS.md (items D6 to D10, as reported) | The 9 `ignore`d tests are D6 (an LLM ACCEPT citing only name evidence merges two "Wei Zhang"), D7 (family-first name order gives a REJECTED at confidence 1.0), D8 (no confusable folding, and a heuristic REJECTED recorded as certain) and D9/D10. | This is entity-resolution over-merging and reported certainty. D6 can over-merge. D7 and D8 mean a REJECTED decision at confidence 1.0 where the result is a heuristic guess, so uncertainty is unreported. The human deferred them in Phase 05 repair-01. I did not read the file, so I cannot confirm the dispositions. | entity-resolution-engineer, human ratification |
| 14 | MINOR | /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/services/EntrypointAdvSpec.scala:261, 280 and LoggingAndQueueAdvSpec.scala:32 | `assume(noLiveSecretsConfigured())` and `cancel("logback is not on the test classpath")`. | These tests turn into "canceled" instead of failing when the environment is configured or logback is absent. They will go green with no assertion run. The two assertions concern fail-closed behavior and log redaction. The canceled count should be checked on every run. | test-engineer |
| 15 | MINOR | /home/olmos/scholarchain/build.sbt:261-275 | `adversarialTests` now has the Compile classpath of storage, rag and services. It also has `hadoop-client-api` and `hadoop-client-runtime` (shaded, from storage), `hadoopClient` and `sparkSql` (Provided), and two SLF4J providers, as the test-engineer noted. | Classpath conflicts are possible in future Spark tests. The test run is already about 39 minutes. This does not affect production assemblies, because `adversarialTests` has no assembly. Checking that production assemblies carry one SLF4J provider is still worth doing. | build-engineer |

CHECK OF THE 12 TEST-ENGINEER DEFECTS (real and correctly owned)
- 1, 2, 3: real and owned by storage-engineer. 1 and 2 are confirmed in code; 3 is plausible and unconfirmed beyond the method body.
- 4: owner correct, but not independently confirmed.
- 5 and 6: real behavior, but severity is MINOR and rests on a design assumption that storage redacts SDK text. The tests assert a contract that no document states. I found no redaction requirement in CLAUDE.md. Treat these two tests as expressing a policy that the owner or human must ratify, not as a spec violation.
- 7: real and owned by service-engineer.
- 8: real, owned by service-engineer, latent.
- 9, 10, 11: real and owned by rag-engineer.
- 12: owner correct, severity low, unverified.
- No defect is mis-owned. I found no test asserting the wrong contract by reading, other than 5 and 6, which assert an unratified contract.

NOT-FINDINGS (checked, no defect)
- The build.sbt change (`.dependsOn(core, hadoop, entityResolution, sparkAnalytics, storage, rag, services)`) is acyclic. No production module depends on `adversarialTests`. The production `dependsOn` lines at 133-206 are unchanged. The Phase 10b graph matches CLAUDE.md: rag depends on core and storage, and services depends on core, rag and storage. The change makes `adversarialTests` the only project that sees all of them, which is allowed for a test-only leaf.
- `Dependencies.scala` was not touched, so the value/structure split is not at issue.
- All new test files are under `tests/**`, the designated test territory. No ownership violation by test-engineer.
- The unsaved build-engineer report (no listed owner for `docs/agent-runs/phase-11/blocked-on-01-*.md`) is a process gap, not a code defect. The orchestrator should record it.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: 1 yes, 2 yes, 3 partial (method body only), 4 no (report only), 5 yes, 6 yes, 7 yes, 8 yes, 9 yes, 10 yes (call site), 11 yes, 12 no (report only), 13 no (report only), 14 yes, 15 yes.

ASSUMPTIONS:
- The test-engineer's pass and fail counts are as reported.
- Defects 5 and 6 rest on the assumption that storage should not echo SDK exception text.

RISKS:
- The full `AnswerWorkflow` with the semantic-context agent and real vector index is not driven under `tests/`, as the test-engineer acknowledged.
- No AwsIntegration or live-AWS verification. The SDK stubs prove classification logic only.
- Timing-sensitive tests (150 ms and 50 ms sleeps) may be flaky.

MANUAL ACTION REQUIRED:
- Human decision on D6 to D10.
- Human decision on whether storage and rag must redact SDK and model text (defects 5, 6 and 9).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: owner repair of findings 1, 2, 4, 7, 11 first (MAJOR), then the MINOR ones. Route storage items to storage-engineer, services items to service-engineer, and rag items to rag-engineer. Then re-run `sbt check` and re-review. Finding 13 stays a human call.
```
