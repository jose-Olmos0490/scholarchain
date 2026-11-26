STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 16 read-only review of /home/olmos/scholarchain/docs/evaluation.md against the Part A outputs (f79d007)
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only: Read, Grep, Glob)

**Summary.** I found no numeric value in the tables that contradicts the derived or raw files I could read. REQUEST_CHANGES rests on one wrong flag claim (F1) and on material negative results and recorded causes that the draft omits or presents as unknown (F2 to F5). No text claims Part B results.

Paths below are relative to /home/olmos/scholarchain. "Draft" is docs/evaluation.md. "RUN" is partA-1b0e893-20261126T014300Z. The owner of every draft repair is documentation-engineer unless noted.

## FINDINGS

| # | Severity | Draft line | Claim or gap | Source | Required fix |
|---|---|---|---|---|---|
| F1 | major | 517-528 | "The 'landed' and 'unresolved' events ... are, all flagged †". This is false for the Phase 15 Terraform-state event, which has needs_confirmation=no and is not in the 41-row queue. The human decision says flagged rows stay unverified, so the draft marks the most serious secret-handling event as unverified when the record does not. | experiments/evaluation/raw/RUN/factory/ownership.csv:15; derived/RUN/factory/needs-human-confirmation.csv:22-28 | Remove † from that row and say five of the six are flagged. |
| F2 | major | 636-641, 797 | The draft says the outputs do not say why tests are ignored or whether each marks an open defect. The repo states both: all 24 are deliberate deferrals of known defects, 5 deferred findings are MAJOR, and the Phase 11 gate was NOT met. None of this appears in the draft. | docs/agent-runs/phase-11/human-gate.md:10-14, 23-40, 57 | State it and cite the gate (see answer 6). |
| F3 | major | 427-431, 778, 811 | The draft says intent of ack-despite-rejects is not established and asks the worker owner to confirm. The human gate already records it as a new defect and open item. The gate also records a Bedrock account-wide block as the cause of C's failure; the draft gives no hint of it. | docs/agent-runs/phase-15/human-gate.md:27, 36, 44, 47, 49-50 | Keep "Part A outputs do not establish it", then add what the Phase 15 gate records. Close section 9 row 5. |
| F4 | major | 660-682, section 8 | Section 5.3 reports the live revision and digests as matching but never says smoke failed: 7/8 on the live 3c3e1a1 deploy, the same "evidence" check as earlier deploys. The 6ce56c9 deploy report status is FAILED, which is in Part A raw data. | phase-15/human-gate.md:24, 39; raw/RUN/factory/work_orders.csv:32 | Add to 5.3 and section 8. |
| F5 | major | sections 3, 4, 8 | Omitted: credentials in DEBUG logs (query-api, embedding-worker) and the incident INC-20261125-debug-credential-logging; cost over cap driven by that log volume; API token sent over plain HTTP. These are Phase 15 records; the Part A raw data only hints (containment queries). The human accepts mentions without values. | phase-15/human-gate.md:24, 26-27, 29-32; phase-15/experiment-C-report.md:7-12; raw/RUN/factory/interventions.csv:29 | Add as negative results, with no values. |
| F6 | minor | 409, 808-809 | "The outputs do not explain the difference in reference points". The runner code and Phase 15 report do. Line 409 also pairs a 409.3 s duration after drain with 1868.2 s measured from watch start. The 23 s margin to the 1500 s drain timeout is omitted. | load-tests/queue_scenario.py:157-219; phase-15/experiment-C-report.md:61-64 | Use answer 5. Compare 409.3 with the runner's 391.5 s. |
| F7 | minor | 753 | "8 researcher pairs unresolved". 11 researcher pairs are UNRESOLVED (8 SAME, 3 DIFFERENT). | derived/RUN/er/confusion-matrix.csv:4-5 | Say "8 SAME researcher pairs". |
| F8 | minor | 681-682, section 3 | "Three code versions". Experiment C ran on 6ce56c9, a fourth, and section 3 never names its commit. | experiments/derived/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z/summary.md:12 | Add it. |
| F9 | minor | 541-545 | "Every flag is kept" (line 459), but guard cells for phases 07, 08, 10b, 12, 13, 15 and stop-hook cells 09, 11 contain flagged rows and carry only "min". Phase 15 also includes a flagged exact row. | raw/RUN/factory/hook_blocks.csv:11-18, 22-23, 31, 35 | Add †. |
| F10 | minor | 677-679 | For 2a196bb0 and 47dd1a5c the only recorded match is that deploy's own deploy report, so it is not independent corroboration. | raw/RUN/repro/plan-checksums-in-logs.json:2-3 | Say so. The human gate (phase-15:23, 39) records the prefixes. |
| F11 | minor | 655 | "observed 2026-11-26T01:00Z" for EMR: I found no timestamp for it in emr-list-clusters.json, commands.json or pipeline-evidence.csv. | those files | Cite a source or drop the time. |
| F12 | minor | 19-20, 375-382, 573-582, 645-656 | Several "derived" tables are literals typed into the script, not computed: experiment run status, review evidence gaps, pipeline evidence (including "failed", "no evidence found", EMR "0"), cost estimate. | experiments/evaluation/bin/part_a_other.py:97-99, 105-107, 183-186, 259-268 | Disclose as hand-entered (experiment-engineer owns the script). |
| F13 | minor | section 8 | Other repo-recorded limitations are absent: the scored ER run r2 is not byte-reproducible from the current tree; F14 and D6-D10 are open; AwsIntegration tests are excluded unless SCHOLARCHAIN_AWS_IT=true; D, E, F were not run because they were Bedrock-dependent or blocked by the ack defect. | modules/entity-resolution/KNOWN-LIMITATIONS.md:15-22, 69-76; build.sbt:24-38; phase-15/human-gate.md:49 | Add. That the clean-clone run excluded the AWS tests is my inference from the default. |
| F14 | minor | 754-755 | "may not be independent of system output" is interpretation inside the section 8 list, with no Interpretation heading. | rules at draft 19-21 | Label it. |

## Stale text after the later human decisions
The work order says the draft is to be kept as it is on the factory-count point. I report these as required but did not rest the verdict on them; the human decides whether "keep" covers them.

| Draft line | Text | Why stale |
|---|---|---|
| 452 | "The human gate does not mention factory counts." | Now false: human-gate.md:10 does. |
| 455-460 | Evidence does not say whether queued rows were accepted; needs a human statement. | The gate says automated counts are accepted as reported and flagged rows remain unverified. |
| 782 | "The human ruling file is identical ... records no verdicts." | The gate says it is not a ruling. |
| 807 | Section 9 row 1 asks the human to state what is ruled. | Answered. |

- Keeping the † marks and "unverified" treatment is consistent with the gate (subject to F1 and F9).
- The gate line (human-gate.md:10) is an uncommitted edit. Commit it before the draft relies on it.
- **The human gate does NOT record the account-ID and credential scan.** Line 8 records only the redaction and the checksum-line update. The draft currently makes no "human verified redaction" claim (lines 709-722). It should not add one citing the gate until the gate records the scan. Owner: human.
- My own pattern search for account-bearing ARNs and ECR hostnames in the uncompressed files of the raw run directory found no match. It did not cover gzipped files or bare 12-digit strings, and it is not a credential scan.

## Measured versus interpretation, negatives, Part B
- Separation is generally good; the exception is F14.
- Present: thin negatives, unresolved pairs counted as true negatives, recall drop, graph size, orphans, untested community and multi-hop behaviour, C failure, A/B/D/E/F not run, unmeasured cost, no pipeline replay.
- Missing: F2 to F5 and F13.
- Part B: none claimed. Lines 3-4, 734-740 and 801 mark retrieval and answer quality pending, with no numbers.

## Answer 5: the two timing mismatches

**Proven from files**
- Derived figures start at the first send record, 17:15:03.727 (runner/sends.jsonl, index 0), and end at the observation's atUtc (part_a_other.py:41-51, 80, 82).
- Runner figures use a monotonic clock started inside watch() (queue_scenario.py:162), which is called only after all sends and one "sent" sample (lines 270-272). That sample is stamped 17:15:32.049 (runner/observations.jsonl:2).
- The runner takes `elapsed` before the poll's AWS calls (line 175). atUtc is stamped after them (lines 91-102).
- Drain definitions differ. Derived uses the first empty poll, 17:39:53.760 (observations.jsonl:84), giving 1490.0. The runner uses the second consecutive empty poll (lines 203-209), 17:40:11.508 (line 85), giving drainedAfterSeconds 1476.7.
- Scale to zero is the same poll in both, 17:46:43.027 (line 107): 1899.3 derived, 1868.2 runner (run.json:21).
- phase-15/experiment-C-report.md:61-64 says runner seconds count from watch start and drain is the second empty poll.

**Arithmetic and inference**
- Origin gap: 17:15:32.049 minus 17:15:03.727 = 28.3 s.
- Per-poll AWS call time is not recorded. I infer about 2.8 s: (atUtc minus 17:15:32.049) minus the runner figure gives 2.76 s (drain), 2.78 s (zero) and 2.65 s (first running, 140.1).
- Scale to zero: 28.3 + 2.8 = 31.1 s = 1899.3 minus 1868.2.
- Drain: 31.1 minus one poll gap of 17.75 s = 13.35 s; observed difference 13.3 s.
- Consequently 409.3 s (derived scale-in) versus 391.5 s (runner, after drain mark) is that one poll gap.

**Not proven**
- The watch start instant is not recorded; I approximate it by the "sent" observation.
- I read the current load-tests/queue_scenario.py and did not confirm it is identical to the 6ce56c9 version that ran. The timestamps and the Phase 15 report agree with it.

Neither mismatch is a data error. They are different origins, drain definitions and stamping points.

## Answer 6: the 24 ignored tests

**Proven from files**
- tests/src/test/scala contains exactly 24 static `ignore(...)` sites, each with a defect comment. I found no ignore sites under modules/.
- 9 are Phase 05 D6-D10 entity-resolution deferrals, ordered by the human (KNOWN-LIMITATIONS.md:5-22): D6 1, D7 2, D8 4, D9 1, D10 1.
- 15 are Phase 11 deferrals, made because of the submission deadline (phase-11/human-gate.md:12-38):

| Defect | Tests | Severity | Subject |
|---|---|---|---|
| T1 | 2 | MAJOR | DynamoDB conflict/throttling classified permanent |
| T2 | 2 | MAJOR | Neptune rows read with silent defaults (provenance loss) |
| T3 | 1 | MINOR | directCoauthors skips the window check |
| T4 | 1 | MAJOR | JSON null record throws NPE |
| T5, T6 | 1 each | MINOR, policy pending | credential-shaped or exception text echoed |
| T7 | 1 | MAJOR | async job can stay RUNNING |
| T8 | 1 | MINOR | neighbors response overstates depth |
| T9 | 1 | MINOR, policy pending | model property name echoed |
| T10 | 1 | MINOR | non-UTF-8 input throws |
| T11 | 2 | MAJOR | chunker splits on ASCII whitespace only |
| T12 | 1 | MINOR | poison message coerces numeric runId |

- The derived clean-clone CSV shows all 24 in one summary line (579 succeeded, 24 ignored). That matches adversarialTests in phase-11/human-gate.md:10.

**Inference**
- The 24 in the clean-clone log are these same 24. Counts and the 579 total match, but I could not read the log's names.
- The defects are still unfixed at 1b0e893. The tests are still ignored and the last record says unrepaired; I did not re-check the production code.
- T5, T6 and T9 await a policy decision, so they are not all settled defects.

Record inconsistency: phase-08/human-gate.md:8 calls the then 9 ignored tests "AWS-tagged"; phase-10/human-gate.md:21 and the source show they are the entity-resolution ones. Do not cite the Phase 08 wording.

## Coverage

**Matched**
- Section 1: tables against derived ER CSVs; counts, Wilson table, comparison run and run identity also against raw er/summary.md.
- Section 2: row counts, integrity, duplicates, orphans, support, recomputation totals (1496), structure, paths, suite pass counts and the 50-edge audit, against derived CSVs only. 2315 rows and 462 files re-added by hand.
- Section 3: figures against derived perf CSVs; timeline also against raw observations, sends and run.json; 300/300/0/0 and 90600 also against the uncompressed Experiment C summary.md.
- Section 4: first-pass, event, severity, repair, hook and gap tables against derived CSVs. Ownership, interventions, work orders, hook blocks and rewrites also against raw CSVs. Sums 31/8/22/1, 52+8=60, 216, 17 and 32 re-added.
- factory-counts.csv and needs-human-confirmation.csv read identically line by line (41 rows, breakdown matches).
- Section 5: outcome and test summaries (2391 = sum of 8 lines), deploy records, ECS revisions, 3 of 3 ECR digests, pipeline record, empty EMR list, gate deviations. SHA256SUMS and pre-redaction file have 580 lines each and the two named lines differ; sources.sha256 has 71 lines. The first Spark attempt failure is visible at phase-14/logs/pipeline-local.log:484.

**NOT verified; do not count as matching**
1. Every checksum or byte claim: 3 of 3 Hadoop input checksums, 21 of 21 S3-vs-manifest checksums (read from the CSV only), 9 C raw files (CSV only), the 881e3e99 SHA-256 and "byte-identical", 71 sources "still match", all 580 lines pass, no other line differs from pre-redaction, redaction the only change.
2. Draft 698-701: regeneration exit 0, 580 files verified, clean git status. These are owner claims.
3. clean-check.log.gz: the `!!! IGNORED !!!` markers, ignored names as logged, [success]/[error] counts, success times, markTestRun line, absence of a commit name. Only the derived CSVs and clone-state.json carry them. phase-10/sbt-check.log shows the 9 entity-resolution names but not this run.
4. Gzipped graph inputs and binary Parquet tables: all graph recomputation and the DBLP trace rest on derived CSVs.
5. Bootstrap intervals and defined-resample counts: not recomputed.
6. raw worker_outcomes.jsonl (8.8 MB) not read: batch-line times, 0.2354, 71.08. Median poll interval 17.8 and task-seconds 3456 not recomputed.
7. Test JUnit XML, raw review_findings.csv, repairs.csv, blocked_on*.csv not read directly (derived CSVs only). Factory classifications not checked against the phase reports.
8. EMR observation time (F11). Draft links were not systematically checked.

## Report fields
TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: F1 yes, F2 yes, F3 yes, F4 yes (the "same check as earlier deploys" wording rests on the gate lines), F5 yes, F6 yes, F7 yes, F8 yes, F9 yes, F10 yes, F11 yes (absence in the three files named), F12 yes, F13 yes (clean-clone exclusion is inference), F14 yes. Stale-text rows verified yes.
ASSUMPTIONS: Phase 05, 11 and 15 records are admissible context for omissions, although the Phase 16 work order limits the performance summary to Experiment C raw data. If the human wants Part A limited strictly to its outputs, F3 to F5 should still be met with a short pointer, because the draft's current wording leaves readers thinking cause and intent are unknown.
RISKS: Publishing as is would understate known unrepaired MAJOR defects, a credential-logging incident and a failing smoke check.
MANUAL ACTION REQUIRED: Human: (1) record the account-ID and credential scan in docs/agent-runs/phase-16/human-gate.md if the draft is to rely on it; (2) commit the factory-count gate line; (3) decide whether the stale factory sentences stay. Separately, the aws-api MCP server reports it is entering end of development (https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md); I did not use it, and any .mcp.json change belongs to factory-engineer with human ratification.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none for this verdict; unverified items are listed above.
NEXT GATE: documentation-engineer repairs F1 to F14 (experiment-engineer for the script point in F12), then re-review.
