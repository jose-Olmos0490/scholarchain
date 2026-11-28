# Phase 16b review report

## Delegation 8: code-reviewer

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 16b, delegation 8 of 8: read-only review of the whole phase diff against base 721eaa7 (docs/agent-runs/phase-16b/prompt.md)
VERDICT: REQUEST_CHANGES

I found no key leak, no over-broad GetSecretValue grant, no change to the Bedrock adapters and no missing Terraform environment variable. The verdict rests on F1 (vectors can be silently lost with the new provider) and F2 (the phase gate has not been shown on the final tree). The work order lets the human accept findings in human-gate.md.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

FINDINGS:

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent | Human decision? |
|---|---|---|---|---|---|---|
| F1 | major | modules/storage/src/main/scala/scholarchain/storage/queue/EmbeddingWork.scala:45-58; modules/storage/src/main/scala/scholarchain/storage/vectors/VectorIngestor.scala:470-497 | Embedding worker with a wrong or revoked Voyage key (readiness still passes and `gated` lets it poll), or a 429 that outlasts 2 retries with delays capped at 5 s | The ingestor catches the exception and rejects every item as EMBEDDING_FAILED. `ledgerErrors` stays empty, so the handler returns success and the message is deleted: no redelivery, no DLQ. The vectors are missing; only the rejects output records it. A put batch is up to 500 items (about 4 Voyage requests), so one failed request also discards the batches already paid for. The handler predates this phase, but the new provider makes the case much more likely, and Voyage's typed failure kind is ignored. | storage-engineer (no storage delegation exists in this phase) | Yes: add a repair delegation, or accept in human-gate.md with a re-publish procedure |
| F2 | major | docs/agent-runs/phase-16b/agent-report.md; test-report.md | The only recorded `scripts/check.sh` PASS is delegation 6. The adversarial tests and the rag and entity-resolution repairs came later. test-report.md ends at STATUS: BLOCKED-ON with 7 failures. | The gate "sbt check and scripts/check.sh pass" is not shown for the final tree. The last `sbt check` (ER repair, 2556 passed, 0 failed) is reported, but check.sh was not rerun and no test-engineer rerun is recorded. | orchestrator (run via infra-engineer and test-engineer) | No, route |
| F3 | minor | README.md:170; modules/entity-resolution/src/main/scala/scholarchain/er/run/ResolveSample.scala:216 | Run the documented replay command (`--adjudicator fake`) without SCHOLARCHAIN_LLM_PROVIDER | It now stops with a config error, although fake and off modes call no model. The documented reproduction step is false. docs/data-lineage.md may need the same (not checked). | documentation-engineer; or entity-resolution-engineer if the section should load only in `llm` mode | No, route (the orchestrator picks the owner) |
| F4 | minor | docs/agent-runs/phase-16b/agent-report.md (service-engineer and infra-engineer reports) | Reading these claims as operating facts: "a wrong key ... could dead-letter messages" and "with maxReceiveCount 5, a failing message could reach 15 requests" | Both are contradicted by the code in F1: an embedding failure acknowledges the message on the first delivery. The record would mislead the cost and recovery analysis. | orchestrator (record a correction; do not rewrite the verbatim reports) | No |
| F5 | minor | modules/entity-resolution/src/main/scala/scholarchain/er/adjudication/AnthropicAdjudicator.scala:207-237; Adjudication.scala:399-415 | HTTP 200 with `stop_reason: "max_tokens"` and partial JSON | The partial text is cached as the answer. The cache key omits the token limit, so raising the limit does not re-ask, and the pair stays UNRESOLVED. | entity-resolution-engineer | No, route |
| F6 | minor | modules/rag/src/main/scala/scholarchain/rag/agents/SemanticContextAgent.scala:97-101 | Voyage returns 429 or times out on a query embedding | `EmbeddingProviderException.kind` is discarded and the result is a generic `StageFailure.Failed`, not Overloaded or Timeout, so the client is not told it can retry. Same behaviour existed for Bedrock. | rag-engineer | No, route or accept |
| F7 | minor | modules/rag/src/main/scala/scholarchain/rag/embed/VoyageEmbedder.scala:58; only reader is IngestMain.scala:151 | Any query-api or embedding-worker run with Voyage | Voyage token usage is never logged or recorded in the services. Manual cost tracking has no data from these two paths. | service-engineer (rag-engineer if the adapter should report it) | Yes: route now or accept (use the provider console) |
| F8 | minor | infra/terraform/environments/dev/terraform.tfvars.example:7 | Copy the example and fill in the placeholders | `aws_region = "us-east-1"`, but the work order environment is us-east-2. plan.sh passes the resolved region, which limits the harm. | infra-engineer | No, route |
| F9 | minor | modules/rag/src/main/scala/scholarchain/rag/llm/AnthropicLlmClient.scala:95-99; AnthropicAdjudicator.scala:220-221 | The API answers with a model name that is neither the configured ID nor `<id>-YYYYMMDD` | Provenance silently records the configured ID as the model that answered, which was not observed. Trade-off of the D4 redaction fix. The live test's `models=` line therefore does not prove which model answered. | rag-engineer, entity-resolution-engineer | Yes: accept, or ask for a "model name not recognised" marker |
| F10 | minor (process) | docs/agent-runs/phase-16b/agent-report.md | (a) service-engineer's grep matched terraform.tfstate and its backup. (b) After the guard blocked a scripted edit containing the KMS decrypt grant, infra-engineer wrote the same grant with the Edit tool. | (a) breaks "never read tfstate". (b) is a retry around a security-escalation block. Both were disclosed; neither is a code defect. | human | Yes: acknowledge in human-gate.md |

JUDGMENTS REQUESTED

1. Second Anthropic adapter in entity resolution: acceptable, and I recommend ratifying it. `entityResolution` may depend only on `core`. The alternative puts network code in `core`. Status classification, retry bound, delay rule, key validation, error-type allowlist, redirect, depth bound, model-name rule and interrupt handling match; adversarial tests attack both. Differences: ER treats empty text as transient; schema reduction is separate. Drift is a continuing risk. It deviates from the work-order wording, so it needs a human decision.
2. Voyage batch hard cap 128: consistent with "do not rely on 1000", and Terraform validates the same bound. It narrows "keep it configurable" to "configurable downward only". Human decision to confirm.
3. `max_workflow_retries` 2 to 0 and `llm_max_retries` 1: not in the work order. Core allows 0, and the README states the trade-off. The 8-Anthropic-request bound is consistent with the code. Human decision to confirm. A real tfvars that overrides `runtime_settings` keeps its old value.
4. rag changes that affect Bedrock: `outputSchema` is ignored by the Bedrock adapter. The depth bound, interrupt stop and new message also apply to Bedrock output. All defensive; no regression seen. Acceptable.
5. Terraform environment coverage: no missing new variable for query-api, embedding-worker or graph-evidence-api for anthropic+voyage or bedrock+bedrock. Under bedrock, SYNTHESIS_MODEL_ID and VERIFICATION_MODEL_ID default to empty and are omitted, so query-api refuses to start until set (documented). From reading only; no plan ran.
6. Delegation 2 live commands: they still match the code. Neither has ever run. The Anthropic test uses an empty bundle, so it does not show that 1024 output tokens are enough for real synthesis.

IAM, secrets and redaction: GetSecretValue is limited to the selected providers' secret ARNs. The worker's `kms:Decrypt` (infra/terraform/modules/iam/main.tf:296-316) is conditioned on both ViaService = Secrets Manager and the Voyage SecretARN context. No secret value or version is in Terraform. Pattern search of the diff and phase-16b records found no real key, account ID or email.

Voyage: input_type is document for ingest and query for retrieval; output_dimension comes from config; normalization and dimension checks are present.

Tests: the ones read (InterruptSpec, AnthropicAdaptersAdvSpec, the canary diff) assert concrete outcomes and request counts. ProviderWiringSpec was only skimmed; the other new specs were not read.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: F1 yes; F2 yes (archived reports only); F3 yes (data-lineage.md not checked); F4 yes; F5 yes; F6 yes (HTTP status not traced); F7 yes; F8 yes; F9 yes; F10 yes (agents' own disclosures; no state file opened).
ASSUMPTIONS: the scratchpad diff and new-file list are the complete phase change; reported test and check results are accurate.
RISKS: Anthropic and Voyage shapes unverified until the live tests. Terraform expressions unproven without a plan. Response body size unbounded. Provider spend has no code cap (accepted). User questions and retrieved text will leave AWS.
MANUAL ACTION REQUIRED: decide F1, F7, F9, F10 and judgments 1-3; sign off the IAM, KMS and secret changes; run both live tests. The aws-api MCP server states it is reaching end of development: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: owner repair or recorded acceptance of each finding (F1 and F2 first), rerun of sbt check and scripts/check.sh on the final tree, then re-review
```

## Orchestrator notes

- F4 correction: the two statements in the service-engineer and infra-engineer reports about dead-lettering and up to 15 requests per failing message are wrong according to F1. Per the reviewer's reading, an embedding failure acknowledges the message on the first delivery. The verbatim reports are left unchanged.
- F2: still open. The last full `sbt check` (entity-resolution repair) passed with 2556 succeeded, 0 failed, 24 ignored, and it included the adversarial tests. `scripts/check.sh` will be rerun after the remaining repairs.
- Stopped here for human decisions (F1, F7, F9, F10, judgments 1-3). F3, F5, F6 and F8 are not yet routed; they will be repaired in the same round.

## Human decisions after the first review (verbatim, recorded by the orchestrator)

> Decisions:
> 1. Fix. Add a storage-engineer repair: a provider auth failure or exhausted 429 must NOT delete the queue message; it stays for redelivery and goes to the DLQ after maxReceiveCount. Same fix also covers the earlier ack-on-reject defect, so Experiment F can run.
> 2. Accept. Voyage cost comes from the provider console and goes in the final report.
> 3. Accept.
> 4. Ratify the separate entity-resolution Anthropic adapter.
> 5. Confirm the hard maximum of 128 for the Voyage batch size.
> 6. Confirm workflow retries 0 and LLM retries 1.
> 7. Acknowledged. I will record both breaches in human-gate.md. I accept the KMS grant as written: it is scoped by kms:ViaService and the SecretARN encryption context and matches the work order. The guard gap (Edit tool not covered) is a known limitation for the final report.
>
> IAM/KMS/secret sign-off: I approve items 1-4 under delegation 6 as listed.
>
> Now route the other findings to their owners (README replay command, caching of truncated adjudications, retryable embedding errors, us-east-1 in the tfvars example). Then rerun scripts/check.sh and the full review on the final tree. Stop only for REQUEST_CHANGES that need me, or at APPROVE. No plan, apply, deploy or commit.

## Re-review on the final tree: code-reviewer

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 16b, full read-only re-review of the whole phase diff on the final tree (base 721eaa7)
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)
```

I found no critical or major defect. All findings below are minor and none blocks approval. The two human live tests are still a separate, unmet gate.

**F1 is fixed.** I traced EmbeddingWork, VectorIngestor, WorkConsumer, IngestDeadline and the worker wiring under /home/olmos/scholarchain/modules/.
- Every unwritten item produces a Rejected or RejectedKeyCollision result, so I found no path that acknowledges a message with an unwritten item.
- Provider auth failure and exhausted 429 become Transient. The message is redelivered and dead-lettered on receive 5.
- Content-only rejects are dead-lettered on the first receive, after the valid items are written. A mix of content and provider rejects is redelivered.
- A redelivery skips vectors already written (same text hash, model and version).
- A missing key stops the worker taking messages.

One gap remains in ledger recording (N1). It does not lose vectors.

**Other repairs.**
- F3: the README command now sets the provider variables.
- F5: only `end_turn` is returned and cached.
- F6: the four embedding failure kinds map to typed stage failures.
- F8: the tfvars example says us-east-2.
- F2: the gate file shows `CHECK RESULT: PASS`.
- I found no regression from the depth bound, the model-name rule or the interrupt stops.

**Also checked.**
- Only the new provider config sections require the provider keys, and I found no `ConfigLoader.load` call outside core tests, so Spark and Hadoop are unaffected.
- The Bedrock IAM statements are guarded against empty resource lists.
- `IngestMain` uses in-memory index bindings, so the Phase 15 Titan ingestion attempts leave no model binding that would block Voyage.
- A pattern search of the diff found no key or real account ID.

**FINDINGS** (paths are relative to /home/olmos/scholarchain/)

New in this review:

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent | Human decision or route |
|---|---|---|---|---|---|---|
| N1 | minor | modules/storage/src/main/scala/scholarchain/storage/vectors/VectorIngestor.scala:404-413, 592-613; queue/EmbeddingWork.scala:34-35 | PutVectors succeeds but the run-ledger write fails | The message is kept. On redelivery the items are skipped as unchanged, no ledger entry is written, and the message is acknowledged. The ledger never records the write, including old and new hashes for updates. The new doc comment says acknowledgement requires the ledger to have recorded the writes, which is false. The behaviour predates this phase; no test in EmbeddingRedeliverySpec covers it. | storage-engineer | Route (fix, or correct the comment and record the limit) |
| N2 | minor | experiments/tests/test_experiments.py:158-159; experiments/manifests/F-poison-message.yaml:106-107, 135; C-embedding-backlog.yaml:115-120 | A put or embed failure during F or a rerun of C | The test still asserts "a put that fails does not fail the message" and passes although that is now false. A failure can cost up to 5 passes per message: F is 10 passes, not 2; C is 1500, not 300 (about 7.5 USD on the manifest's Titan figures, over its 1.0 cap). F's precondition names only fix 55b0d3b, so a worker without this fix would satisfy it. | experiment-engineer | Route before the human approves F; any manifest approval stays human |
| N3 | minor | README.md:45, 87-88; docs/evaluation.md:483-506 | A reader follows the AWS prerequisites or the defect list | README still names only Bedrock ports and Bedrock access, and does not mention the provider variables or secrets. evaluation.md lists ack-on-reject as open; it is a historical record and needs a "fixed in 16b, not yet run live" note. | documentation-engineer | Route (Phase 17 is fine) |

Open items the orchestrator did not route (my judgement: all minor, none blocks):

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent | Human decision or route |
|---|---|---|---|---|---|---|
| K1 | minor | infra/terraform/environments/dev/variables.tf:8 (also backend.tf.example:24) | A direct terraform run with no `aws_region` set | Targets us-east-1. The example file and plan.sh limit the risk. | infra-engineer | Route before any plan |
| K2 | minor | README.md:42 | Reader checks the identity-resolution stage | "fake adjudicator only" is true of recorded runs but omits the new `llm` mode. | documentation-engineer | Route |
| K3 | minor | infra/terraform/environments/dev/main.tf (embedding-worker environment) | One failed Voyage request in a put batch of 500 | Up to 500 items are discarded and paid for again on each redelivery, up to 5 times. | infra-engineer | Route, or human accepts the cost |
| K4 | minor | modules/storage/src/main/scala/scholarchain/storage/queue/WorkConsumer.scala:100-104 | Revoked Voyage key still held, large backlog | No pause after a retried message, so the backlog moves to the DLQ and needs a human redrive. No data is lost, and this matches decision 1. | storage-engineer or service-engineer | Human may accept; record a redrive procedure |
| K5 | minor | modules/entity-resolution/src/main/scala/scholarchain/er/adjudication/BedrockAdjudicator.scala:27 | A truncated Bedrock answer | Would be cached. Unreachable from the run entry point today. | entity-resolution-engineer | Route before Bedrock adjudication is enabled |
| K6 | minor | modules/services/src/main/scala/scholarchain/services/query/QueryEngine.scala:76 | Transient query-embedding failure | The client is told "a store was unavailable". The status is right, the wording is wrong. | service-engineer | Route |
| K7 | minor | modules/rag/src/main/scala/scholarchain/rag/llm/AnthropicLlmClient.scala:85-107 | `stop_reason: max_tokens` | Returned as success. Cut-off JSON then fails parsing or schema validation, so I see no wrong answer being accepted; the cause is reported as invalid output. | rag-engineer | Route or accept |
| K8 | minor (process) | docs/agent-runs/phase-16b/agent-report.md:916-918 | Reading the archive as verbatim evidence | Some reports are condensed (disclosed), against the phase evidence format. The final check.sh run on this tree is also not yet recorded there, and test-report.md holds only the first, BLOCKED-ON run. | orchestrator; human acknowledges | Record the final check.sh result; human acknowledges the rest |
| K9 | minor (process) | docs/agent-runs/phase-16b/agent-report.md:936 | storage-engineer's grep over infra/terraform with no file filter | A further breach of the F10 kind. By its own account no content was shown. Not a code defect. | human | Human acknowledgement in human-gate.md |

```text
TEST RESULTS: not run by reviewer. I read the orchestrator's gate lines (all PASS, CHECK RESULT:
  PASS). The count of 2574 succeeded, 0 failed, 24 ignored is as reported; I did not search the
  full log.
VALIDATION RESULTS: findings verified against files: N1 yes (code reading); N2 yes; N3 yes;
  K1 yes; K2 yes; K3 yes (no PUT_VECTORS match in *.tf); K4 yes; K5 partly (class located,
  stop-reason behaviour taken from the ER report); K6 yes (line located; the mapping path to it
  was not traced); K7 yes; K8 yes; K9 yes (the agent's disclosure only).
ASSUMPTIONS: the scratchpad diff and new-file list are the complete phase change. Human decisions
  quoted in review-report.md are settled.
RISKS: Anthropic and Voyage request and response shapes are unverified until the live tests.
  Terraform expressions are unproven without a plan. Response body size is unbounded. Provider
  spend has no code cap (accepted). Not every new test file was read in full, and the services
  canary and ProviderWiring tests were not re-read.
MANUAL ACTION REQUIRED: run both live tests (Anthropic structured call; Voyage batch of 8 at
  dim 1024); acknowledge K8 and K9 and record both earlier breaches in human-gate.md; record
  the open findings there as accepted or routed.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human live tests and human-gate.md. Any owner repair of N1-N3 or K1-K7 made before
  commit needs sbt check and a re-review of that change.
```

Orchestrator note: K8's missing check.sh record has since been added to test-report.md.
