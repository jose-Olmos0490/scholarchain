STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only re-review of experiments/ and load-tests/ (untracked, HEAD 55b0d3b) against docs/agent-runs/phase-15/review-report.md findings 1-19 and 21, the six required outcomes, and human decisions 1, 2, 3 and the one on finding 21.
VERDICT: REQUEST_CHANGES

Summary
- There is no blocker, and I found no unguarded AWS write.
- The six required outcomes are met.
- Human decision 3 and the finding 21 decision are met. Decisions 1 and 2 are only partly met.
- Findings 3, 5 and 19 are partly fixed. The others in scope are fixed.
- C and F are blocked and the tools refuse them. That fails closed, but the experiments cannot run.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

## Findings 1-19 and 21

Paths are under /home/olmos/scholarchain. "Test" means I judged by reading that the named test would fail on the old behaviour as the first review describes it. The old source is not available (untracked), and I ran nothing.

| # | Status | Evidence (file:line) | Test check, and residual for PARTIAL |
|---|---|---|---|
| 1 | FIXED | load-tests/queue_scenario.py:104-136, 293-294 | load-tests/tests/test_load_tools.py:408-423; would fail |
| 2 | FIXED | experiments/bin/collect.py:480-501, 531-533 | test_experiments.py:326-355; would fail |
| 3 | PARTIAL | Fixed for load tools: load-tests/sclib.py:76-133, query_load.py:339, queue_scenario.py:227. Open: scripts/run-experiment.sh:155-160; experiments/manifests/A-emr-baseline.yaml:6-7, B-emr-burst.yaml:6-7 | Input: `run-experiment.sh --manifest .../B-emr-burst.yaml` with approval PENDING and `blocked: true`. It runs the same pipeline as A and records it as B. Owner: infra-engineer. Tool tests (test_load_tools.py:288-301, 352-357) would fail on old code |
| 4 | FIXED | query_load.py:373-388; D-query-load.yaml:32, 103-104 | test_load_tools.py:152-163, test_experiments.py:146-154; would fail |
| 5 | PARTIAL | Main defect fixed: queue_scenario.py:139-150, 263, 271-287. Open: F-poison-message.yaml:10-11, 105-106 | Input: the second valid duplicate rewrites every vector (not idempotent). There are then two or more new SUCCEEDED batches and F reports COMPLETED. The idempotency purpose is still not checked. Owner: experiment-engineer. test_load_tools.py:425-443 would fail on old code |
| 6 | FIXED | experiments/bin/build_plots.py:260, 325-335 | test_experiments.py:724-734; would fail |
| 7 | FIXED | build_plots.py:203-212, 342-364, 397-420, 471-485 | test_experiments.py:736-784; would fail. Related new defect is R4 |
| 8 | FIXED | build_plots.py:329-330 | test_experiments.py:814-818; would fail |
| 9 | FIXED | build_plots.py:423-440; collect.py:231-239 | test_experiments.py:473-498, 820-829; would fail |
| 10 | FIXED | collect.py:441-452, 486, 538-543 | test_experiments.py:405-422; would fail |
| 11 | FIXED | collect.py:180-209; build_plots.py:572-576 | test_experiments.py:424-436; would fail. Images are still those at collection time, now labelled as such |
| 12 | FIXED | query_load.py:99-101, 108-124 | test_load_tools.py:181-191; would fail |
| 13 | FIXED | query_load.py:39-41, 320-334, 137-142; queue_scenario.py:218-222 | test_load_tools.py:193-208, 455-461; would fail |
| 14 | FIXED | query_load.py:43, 204-209 | test_load_tools.py:210-227; would fail |
| 15 | FIXED | collect.py:425-430, 505-552 | test_experiments.py:386-403; would fail |
| 16 | FIXED | collect.py:464-470, 561-568; build_plots.py:630-632 | test_experiments.py:438-461; would fail |
| 17 | FIXED | queue_scenario.py:195-207 | test_load_tools.py:463-468; would fail |
| 18 | FIXED | sclib.py:122-133; collect.py:356-359 | test_load_tools.py:303-319, test_experiments.py:373-375; would fail |
| 19 | PARTIAL | Tests added: test_load_tools.py:229-256, 485-519; test_experiments.py:463-471. Open: scripts/check.sh:124-182 and build.sbt:322-327 | Input: remove the message cap, then run `sbt check` or `scripts/check.sh`. Both can still pass because neither runs the Python tests. Owner: build-engineer (sbt check), infra-engineer (check.sh). The new tests themselves would catch the named regressions |
| 21 | FIXED | D-query-load.yaml:84-88; E-retrieval-modes.yaml:78-82; query_load.py:97-98 | test_experiments.py:164-169. It checks manifest text only |

## Required earlier outcomes

1. MET. Accepted, refused and outcome-unknown counts are tallied per batch and written in `finally` (queue_scenario.py:109-136, 288-299).
2. MET. Missing variables or inputs give a nonzero exit before the first AWS call. AWS-side omissions and failures leave no run directory, and a rerun works (collect.py:495-501, 531-533, 551-552).
3. MET. Both tools call `require_runnable` before reading targets or calling anything. Limits on this are in R7.
4. MET. A level that hits its time cap is marked incomplete and the next one runs. Mean latency is recorded, not used as a stop (query_load.py:282-293, 384-388).
5. MET. With no put there is no ledger entry; a failed put leaves only FAILED entries. Both give status FAILED (queue_scenario.py:283-287). The ledger key pattern matches the real writer (DynamoStores.scala:116-117; DynamoPorts.scala:12-15; VectorIngestor.scala:525-572).
6. MET, with nit N1. Unmeasured values render as `missing` and plot lines break at gaps (build_plots.py:87-88, 128-140, 203-212).

## Human decisions

**Decision 1: PARTIAL.**
- No confirmed write means FAILED: met.
- Cost estimate for embedding calls: met.
- Disjoint subsets for C and F: not met. Both manifests are `blocked: true`.

Verification of "not implementable with the current message contract":
- What holds:
  - A message carries only `schemaVersion`, `runId` and `snapshot` (EmbeddingWork.scala:10-11).
  - The handler reads that run's whole manifest (EmbeddingWork.scala:44-47).
  - `ingest` iterates every item (VectorIngestor.scala:245-250).
  - A manifest is one immutable object per run ID (IngestionDocuments.scala:175-188).
  - So two subsets of one run ID cannot be selected.
- What is overstated:
  - The message contract does not stop C and F from naming two different run IDs, each with its own manifest of disjoint items.
  - Vector keys do not include the run ID (VectorIngestor.scala:341-347), so disjoint items stay disjoint in the index.
  - What is missing is a way to stage a manifest without ingesting it.
- Option "stage without indexing" (rag-engineer): holds.
  - `VectorIngestor.stage` is already a separate public method (VectorIngestor.scala:155).
  - Only `IngestionRunner` chains it to `ingest` (IngestionRunner.scala:142-143).
  - It needs no message, worker or deployment change.
  - Disjointness can come from `--index` or separate input directories. `--limit` alone cannot do it, because it takes the first n documents with no offset (RenderPipeline.scala:258-270).
  - The two runs would have new run IDs. The human should confirm that this satisfies "subsets of the staged run".
- Option "item selection" (storage-engineer): holds technically, but is larger. It needs a new message field or version, a handler change and a redeploy.
- Not verified: that any staged run exists, or that it is unindexed.

**Decision 2: PARTIAL.**
- Explicit flag: met. It is `store_true` with `allow_abbrev=False`, and no environment variable or manifest field enables it (collect.py:561, 568).
- Lists every missing item: met for collection inputs (variables, files, short records, failed polls, empty metrics, logs, deployment). Not met as worded for the three cases the author names:
  - Planned levels that never ran are visible in run.json and `load-plan.csv`, but not in `missingInputs`.
  - Nulls inside records are shown in tables but not listed.
  - A metric with 1 datapoint in an 18-minute window seals without `--allow-missing`, with nothing listed (collect.py:284-286). The test fixture has exactly this shape (test_experiments.py:575-576).
- My judgement: reading "item" as "collection input", this is acceptable. The decision says "every missing item", so the narrower reading needs an explicit human ruling (R3).
- The list is in `collection.json` inside the sealed directory, not in `record.json`.
- Shown as missing in plots and tables: met for listed items, except R4 and R12.

**Decision 3: MET.**
- D run limit 2400 s (D-query-load.yaml:32), load limit 2100 s (:104), $0.036 per request (:101).
- E $0.0375 per request (E-retrieval-modes.yaml:91).
- D's worst case by my arithmetic is 2025 s, within 2100 s.

**Finding 21 decision: MET in code and manifests.**
- The `SC_ALLOW_PLAINTEXT_HTTP=1` opt-in is kept.
- The token appears only in the request header (query_load.py:132). I found no path that prints or writes it.
- Issuing a short-lived token and revoking it are human actions that no code can check.

## Cost arithmetic and caps

One embedding pass is 500 items x 500 tokens x $0.00002 per 1000 tokens = $0.005. The price and counts are unverified assumptions, as the manifests say.

| Exp | Expected | Put-failure total | Redelivery ceiling (embedding) | Cap |
|---|---|---|---|---|
| C | 0.10 + 0.01 = 0.11 (correct) | 0.10 + 1.50 = 1.60 (correct) | 1500 passes = 7.50 (correct) | 1.00; does not cover put failure |
| F | 0.05 + 0.01 = 0.06 (correct) | 0.05 + 0.01 = 0.06 (correct) | 10 passes = 0.05 (correct) | 1.00; covers it |

- The C note "cap covers that case up to 300 items" is correct.
- D: 250 x 0.036 = 9.00 against a cap of 15. E: 80 x 0.0375 = 3.00 against a cap of 5. For both, the request cap binds before the cost cap.
- D and E cost caps are enforced on an estimate (query_load.py:193-195). C and F caps are declared only (R9).

All six approval blocks are still `PENDING_HUMAN_APPROVAL` with null approver and date (A, B lines 13-16; C, F lines 12-15; D, E lines 11-14).

## Safety of new changes

- The only direct AWS write is `sqs send-message-batch`. It is guarded by approval, by `SC_ALLOW_QUEUE_WRITES=1` and by one attempt (queue_scenario.py:227-230, 116-118).
- `query_load` causes server-side writes and model spend, behind the approval check.
- The new ledger call is a read-only `dynamodb query`. The table name is not written out.
- The collector issues only get, describe and filter calls.
- Staging and rename are sound by reading.
- The total request deadline works; see nit N3.

## FINDINGS

Ordered by severity. "EE" is experiment-engineer. No blocker.

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| R1 | major | experiments/manifests/C-embedding-backlog.yaml:16-17, 108-110; F-poison-message.yaml:16-17, 108-110; modules/rag/src/main/scala/scholarchain/rag/ingest/IngestionRunner.scala:142-143 | The human wants to run C and F with disjoint items. There is no stage-only path, so two unindexed runs cannot be produced. | Decision 1 is not met and neither experiment can run. The manifest text also overstates the cause (see decision 1). | rag-engineer (stage-only), then EE (preconditions); human to confirm that new run IDs are acceptable |
| R2 | major | scripts/run-experiment.sh:155-160; experiments/manifests/B-emr-burst.yaml:6-7, 17-18 | The pipeline runner is started for B, or for an unapproved A. | Residual of finding 3. EMR spend without an approval check, and an A-shaped run recorded as a burst result. | infra-engineer |
| R3 | minor | experiments/bin/collect.py:284-286, 338-380 | D stops on ERROR_RATE after level 1, or a metric has datapoints for 1 of 18 minutes. | `missingInputs` does not list the absent levels or minutes. In the metric case the run seals without `--allow-missing`. | EE; human to rule on "every missing item" |
| R4 | minor | experiments/bin/build_plots.py:309-310, 335, 393 | The load tool is killed before writing run.json (or run.json is truncated), the JSONL lines are clean, and the run is collected with `--allow-missing`. | `short` is false and `completed` is None, so partial phases, modes and sends are labelled `complete`. For example, 100 of 300 sends reads as complete. `load-plan.csv` correctly reads missing. No test checks `dataStatus` here. | EE |
| R5 | minor | scripts/check.sh:124-182; build.sbt:322-327 | A regression in a cap or guard. | Residual of finding 19: the repository gates do not run the Python tests. | build-engineer, infra-engineer |
| R6 | minor | load-tests/queue_scenario.py:283-287; experiments/manifests/F-poison-message.yaml:105-106 | A duplicate valid message rewrites all vectors. A run where most items are rejected and one batch is written behaves similarly. | Residual of finding 5: COMPLETED needs only one new put batch. Idempotency and completeness are not checked. | EE |
| R7 | minor | load-tests/sclib.py:112-133 | A copy of a manifest anywhere (for example a temporary directory), with a typed-in approval, `args` naming itself and a matching file name. Or an uncommitted approval edit, run directly. | The tool runs. Approval is not tied to a committed file under experiments/manifests/. The test fixtures rely on this (fakes.py:186-203). | EE |
| R8 | minor | load-tests/queue_scenario.py:233-237; C and F manifests, line 108 | C and F are unblocked and given the same `SC_EMBEDDING_RUN_ID`. | Nothing enforces disjointness. F depends on C and would report NO_VECTORS_WRITTEN. | EE |
| R9 | minor | experiments/manifests/C-embedding-backlog.yaml:84-94; load-tests/queue_scenario.py:153-215 | Puts fail on every message in C. | Estimated 1.60 against a cap of 1.00. The tool does not stop on new FAILED ledger entries, and messages stay queued after it exits, so spend can continue. Disclosed in the manifest, but nothing stops it. | EE; human to accept before approving C |
| R10 | minor | experiments/bin/collect.py:22-27, 496 | A healthy D or E run with no 5xx and no throttling, where CloudWatch publishes no zero series. | `--allow-missing` becomes routine, and it also waives unrelated omissions such as a missing runner directory. This depends on live CloudWatch behaviour, which I did not verify. | EE |
| R11 | minor | experiments/bin/collect.py:488-493 | `--pipeline-record` points at an earlier run at the same commit. | Only the commit SHA is compared, not the time window. The wrong stages and snapshots are sealed. | EE |
| R12 | minor | experiments/bin/build_plots.py:456-468; collect.py:269-271 | Some `batch received` lines are unparsed and the run is sealed with `--allow-missing`. | `worker-outcomes.csv` shows undercounted totals with no marker. Only the missing-inputs list says so. | EE |
| N1 | nit | experiments/bin/build_plots.py:260 | Every request in a phase fails. | `abstained` reads 0 although nothing was answered. | EE |
| N2 | nit | load-tests/sclib.py:88-92 | `preconditions: 'BLOCKED: x'` as a string instead of a list, with `blocked: false`. | The string is iterated by character, so the BLOCKED entry is not detected. | EE |
| N3 | nit | load-tests/query_load.py:137-142 | A trickling response passes the total deadline. | The abandoned request stays open while the next one starts, so server-side concurrency can exceed the level. | EE |
| N4 | nit | load-tests/queue_scenario.py:271-279 | The second ledger query returns unparseable output. | `ValueError` is not caught. run.json is written, but the tool ends with a traceback and the evidence status stays NOT_CHECKED. | EE |
| N5 | nit | experiments/bin/collect.py:505 | The collector is killed with SIGKILL during staging or just before the rename. | A `.partial-*` directory remains under experiments/raw/, is not git-ignored and could be committed. | EE |
| N6 | nit | experiments/bin/collect.py:442 | `--manifest` differs from the record's `manifest` field. | The argument is silently ignored for content. The name check still applies. | EE |

TEST RESULTS: not run by reviewer. The orchestrator reports load-tests 44/44 and experiments 51/51. I counted 44 test methods in load-tests; I did not count the experiments suite or verify that either passes.

VALIDATION RESULTS: findings verified against files: yes for R1-R9, R11, R12 and N1-N6 (code paths by reading). R10 is verified in code, but its trigger depends on live CloudWatch behaviour.

ASSUMPTIONS:
- The first review's description of the old behaviour is accurate; I could not diff against it.
- repair-01.md and agent-report.md are context, not evidence.

RISKS:
- Not verified: live CloudWatch metric names and log formats; AWS CLI behaviour (query pagination, `AWS_MAX_ATTEMPTS`, TERM handling); the deployed worker build; tfvars overrides; model prices and item counts; executable bits; the guard hook's treatment of direct tool invocation.
- I opened no Terraform file, state, tfvars, env or credential file. The bounds test reads variables.tf, but I did not check its result.
- I read build.sbt by name, outside the listed search directories. I did not search from the repository root or from infra/.
- The open item in repair-01 (the guard does not block recursive searches that reach Terraform state) remains for factory-engineer and the human.

MANUAL ACTION REQUIRED:
- Rule on whether two new run IDs satisfy decision 1, and which option to build.
- Rule on what "every missing item" covers (R3).
- Do not start A or B on the assumption that the approval block or `blocked` is enforced.
- Redeploy the storage fix before C, E or F, if that has not been done.
- For D and E, issue a short-lived token and revoke it after the runs.
- Notice from the connected `aws-api` MCP server: it is reaching end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md. I did not use it.

BLOCKED-ON OWNER: none for this review.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: owner repair (experiment-engineer for R3, R4, R6-R12 and the nits; rag-engineer for R1; infra-engineer for R2; build-engineer and infra-engineer for R5), the two human rulings, then re-review.
