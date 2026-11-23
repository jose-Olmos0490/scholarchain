STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 9 repair 01, step 3a. Add QueryAuditRecord to core with a contract test and a frozen golden. Add the 11 retrieval.* and rag.* config keys with defaults, env overrides and validation. Change nothing outside modules/core.

FILES CREATED (all from the earlier partial run; I reviewed and kept them):
- modules/core/src/test/resources/golden/v1/query-audit-record.json
- modules/core/src/test/scala/scholarchain/core/QueryAuditRecordSpec.scala

FILES MODIFIED (all from the earlier partial run; reviewed, kept unchanged, nothing needed fixing):
- modules/core/src/main/scala/scholarchain/core/model/Runtime.scala (QueryAuditRecord)
- modules/core/src/main/scala/scholarchain/core/schema/SchemaVersions.scala (QueryAuditRecord = "query-audit-v1", registered in `supported`)
- modules/core/src/main/scala/scholarchain/core/validation/RecordContract.scala (given RecordContract[QueryAuditRecord])
- modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala (RetrievalConfig +4 fields; new ResolverConfig, RagLlmConfig, PlannerConfig, RagConfig; `ScholarChainConfig.rag`)
- modules/core/src/main/resources/reference.conf (defaults and doc block)
- modules/core/src/main/resources/scholarchain/env-overrides.conf (11 env bindings)
- modules/core/src/test/resources/golden/v1/SHA256SUMS (+1 line; I confirmed the checksum matches the file: 7ea3d71e...876d)
- modules/core/src/test/scala/scholarchain/core/{ConfigLoaderSpec, Fixtures, GoldenCases, SchemaVersionSpec, WireContractSpec}.scala
I made no edits in this run. Nothing outside modules/core was touched. docs/agent-runs/phase-09/ is untracked and not mine.

QueryAuditRecord (schema "query-audit-v1"), 15 fields in this order:
schemaVersion, queryId, runId, planJson, researcherIds, evidenceIds, metricVersions, promptVersions, modelVersions, inputTokens, outputTokens, latencyMillis, verificationResult, answerHash, provenance.
- This matches item 7 of the blocked report. There is no field for reasoning or raw prompts, and a test enforces that.
- Validation: provenance is required; the schema version must be supported; queryId and runId must be well-formed IDs; researcherIds and evidenceIds must be well-formed and distinct; planJson and the version entries must be non-blank; the three counters must be >= 0; answerHash must be `sha256:<64 lower-case hex>`.
- Golden sample (excerpt): {"schemaVersion":"query-audit-v1","queryId":"query:4b375f...","inputTokens":1200,"outputTokens":240,"latencyMillis":850,"verificationResult":"VERIFIED","answerHash":"sha256:744bf056...074e","provenance":{...}}. The full file is the golden above.
- Storage sort key AUDIT#<queryId> is documented in the Scaladoc. The port and table are storage-engineer and infra territory.

Config keys, defaults and env variables:
- retrieval.pathResultLimit = 10, SCHOLARCHAIN_RETRIEVAL_PATH_RESULT_LIMIT, min 1
- retrieval.pathTimeoutMillis = 5000, SCHOLARCHAIN_RETRIEVAL_PATH_TIMEOUT_MILLIS, min 1
- retrieval.evidenceMaxItems = 50, SCHOLARCHAIN_RETRIEVAL_EVIDENCE_MAX_ITEMS, min 1
- retrieval.coauthorPageSize = 25, SCHOLARCHAIN_RETRIEVAL_COAUTHOR_PAGE_SIZE, min 1
- rag.resolver.minConfidence = 0.8, SCHOLARCHAIN_RAG_RESOLVER_MIN_CONFIDENCE, in [0,1]
- rag.resolver.minMargin = 0.1, SCHOLARCHAIN_RAG_RESOLVER_MIN_MARGIN, in [0,1]
- rag.resolver.maxCandidates = 5, SCHOLARCHAIN_RAG_RESOLVER_MAX_CANDIDATES, min 1
- rag.llm.maxRetries = 2, SCHOLARCHAIN_RAG_LLM_MAX_RETRIES, min 0 (0 means no retry)
- rag.llm.maxOutputTokens = 1024, SCHOLARCHAIN_RAG_LLM_MAX_OUTPUT_TOKENS, min 1
- rag.planner.promptVersion = "query-planner-prompt-v1", SCHOLARCHAIN_RAG_PLANNER_PROMPT_VERSION, non-empty string only
- rag.planner.schemaVersion = "query-plan-v1", SCHOLARCHAIN_RAG_PLANNER_SCHEMA_VERSION, non-empty string only

Configuration precedence is unchanged: environment variables override the environment file, which overrides reference.conf defaults.

COMMANDS RUN:
- `git diff`, `git status`, and reads of the untracked files and the blocked report, to inspect the earlier work
- `sha256sum` of the golden
- `sbt check`, once, in the foreground. The harness moved it to the background after 180s. I did not start a second copy; I waited for the same process and read its results. The early `pgrep` and `kill` attempts were my own wait loops. The `kill` was denied and nothing was killed.

TEST RESULTS:
- The `sbt check` process exited 0, and `.claude/.last-test-run` was refreshed at 19:24 by markTestRun.
- Summed over modules/*/target/test-reports/*.xml: 1274 tests, 0 failures, 0 errors.
- QueryAuditRecordSpec: 13 tests, 0 failures. ConfigLoaderSpec: 50 tests, 0 failures.
- The captured console output file was empty because of the `| tail` pipe I used before the harness backgrounded it. The pass evidence is the exit code, the test-run marker and the XML reports, not console text.
- The full run took about 65 minutes, almost all of it in the Spark tests.

VALIDATION RESULTS:
- Golden checksum matches SHA256SUMS.
- The new config tests cover: defaults load; the keys are not required; env overrides apply for all 11; the variables are bound in env-overrides.conf; positive limits reject 0, negatives and non-numbers; maxRetries accepts 0 but rejects negatives; confidence and margin accept 0 and 1 but reject values outside [0,1]; blank version strings are rejected.
- The wire tests cover: the frozen 15-field set and order, dropped and renamed fields, missing provenance, an unsupported schema version, malformed or duplicate IDs, a bad answer hash, negative counters, and an unknown verification status.

ASSUMPTIONS:
- Defaults are my earlier-run choices, not specified in the work order; the orchestrator may adjust them.
- The default prompt and schema version strings are placeholders until rag-engineer's prompts/** define the real selected versions.

RISKS:
- The new `rag` section is a required field of `ScholarChainConfig`. Any module that constructs `ScholarChainConfig` by hand needs updating; `sbt check` over all modules passed, so none are broken today.
- Adding 4 fields to the `RetrievalConfig` case class is source-incompatible for any hand constructor of it. None exists in the repo.
- Items 1 to 6 of the blocked report (ports, StorageError.Timeout, pagination, directory, ObjectRef.fromS3Uri) are storage-engineer territory. Item 7's storage port (QueryAuditLog) and DynamoDB table are storage-engineer and infra-engineer.

MANUAL ACTION REQUIRED: Orchestrator to route the storage-engineer step and infra table decision, then re-issue rag-engineer work order A. The human commits.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer, then storage-engineer step 3b
