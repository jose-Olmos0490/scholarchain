# Phase 15 human gate (in progress)

## 2026-11-25 05:20 — experiment tooling accepted with open findings
- Reviewed: review-report.md and review-report-02.md (code-reviewer verdict REQUEST_CHANGES, no blocker, no unguarded AWS write).
- Accepted with known risk: finding 3 partial (run-experiment.sh does not enforce approval for A/B; A and B stay unapproved and will not be run), finding 5 partial (F duplicate case), finding 19 partial (Python tests not in sbt check / check.sh; routed to infra-engineer).
- Decision 1 (disjoint C/F input) not yet met; only experiment C is approved.
- Guard blocked agent-report-02.md and review-report-02.md writes (suffixed names unowned in docs/agents.md); human copied them from the orchestrator scratchpad.
- Manual changes: none to code.
- Manual intervention disclosed: during phase 14b/14c the human ran LoadMain and IngestMain (--limit 700, run:f6023de3..., snapshot 2026-11-01) directly from the shell, about 5 ingestion attempts, all rejected by the INDEX_UNAVAILABLE defect fixed in 55b0d3b. C reuses this run and snapshot.

## 2026-11-25 ~10:30 — infra gaps, review round 3, C approval
- Accepted: worker step scaling on visible messages (0 -> 2 -> 0), new outputs (DLQ URL, ALB URL, RunLedger table), approval gate in run-experiment.sh, Python tests in check.sh.
- First dev plan (from dirty tree, checksum 6229ec96...) discarded; re-planned after commit.
- code-reviewer round 3 verdict: REQUEST_CHANGES (no critical findings). Findings accepted as known risks; no further repair rounds this phase due to the submission deadline.
- Known risk: experiments run as IAM user CS441_Dev with AdministratorAccess (not least privilege).
- Known risk: C cost (~$1.60 estimate, ~$7.50 redelivery ceiling); run monitored manually.
- Known risk: approval gate in run-experiment.sh has no test; run-bounded-pipeline.sh run directly bypasses it. Routed to test-engineer after submission.
- Known risk: checkov flags Neptune backup retention (pre-existing file).
- Scope: only experiment C runs in Phase 15. F, D, A, B, E not run; reported as limitations.
- C run ID and snapshot supplied by the human from shell history (IngestMain run). C approved by Jose Olmos on 2026-11-25.

## 2026-11-25 ~12:30 — deploy 6ce56c9 and experiment C
- Plan 2a196bb0... (7 add / 0 change / 4 destroy, no IAM or stateful changes) applied by the human; deployed by deploy-engineer. package.sh run by orchestrator because the guard blocks deploy-engineer from it (constitutional gap, routed to factory-engineer).
- Smoke 7/8: "evidence" check failed, identical to Phase 14. Human kept the build; deploy-engineer's automatic rollback to 2365bf5 was denied by the human (it would have reverted fix 55b0d3b). Dev ALB is plain HTTP; API token sent unencrypted (known risk; token to be rotated).
- Experiment C (run C-embedding-backlog-6ce56c9-20261125T171458Z): worker scaled 0 -> 2 at 17:17:37Z and 2 -> 0 at 17:46:43Z; 300 messages drained; 0 of 90,600 vectors written (EMBEDDING_FAILED, Bedrock ValidationException). Result FAILED under the no-vector rule; kept as-is.
- Cost ~$1.50 exceeded the $1.00 cap, mostly CloudWatch log ingestion (2.8 GB) caused by DEBUG logging.
- New defects: credentials in DEBUG logs (query-api, embedding-worker); rejected items acknowledged instead of retried/dead-lettered; Bedrock rejection cause unknown.
