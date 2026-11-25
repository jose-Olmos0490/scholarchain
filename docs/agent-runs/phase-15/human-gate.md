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

## 2026-11-25 ~13:10 — incident INC-20261125-debug-credential-logging
- Reviewed incident report; account IDs redacted by incident-responder at human request (2 replacements; greps 0).
- Decision: do not scale APIs to 0 (autoscaling min 1, Terraform drift, temporary credentials in private logs); contain by shipping the production logback fix today as a separate change.
- Deferred to the next fix batch: Neptune audit log credential capture, TLS on the dev ALB.
- After the fix is live: rotate API keys, revoke task-role sessions, decide on log retention/deletion.
- Accepted: incident-responder used simpler read-only command forms when the guard blocked compound commands; no guard or settings changed.
- Known: AWS account ID appears in 23 earlier tracked evidence files; not rewritten (private repo, ID is not a credential).
- Bedrock blocked account-wide: authorizationStatus NOT_AUTHORIZED for Titan Embed V1/V2, Cohere Embed v4, Nova Micro in us-east-1/us-east-2/us-west-2. put-foundation-model-entitlement does not exist in the CLI. AWS Support case (Account and billing) opened and instructor emailed on 2026-11-25.

## 2026-11-25 ~14:20 — logging fix deployed, incident contained
- Logging fix (service-engineer, code-reviewer APPROVE, 3 minor findings deferred) committed as 3c3e1a1; plan 47dd1a5c... (3 add / 0 change / 3 destroy, task definitions only) applied by the human; deployed by deploy-engineer. Smoke 7/8, same "evidence" failure (suspected cause: Bedrock account block). No rollback.
- Containment verified by the human with counts-only Logs Insights queries (the guard blocks agents from start-query):
  - New tasks (194a6c8e..., 72763c57...): 136 log lines, 0 credential/DEBUG matches, including 10 min before task start.
  - All matches since rollout are in the old task streams (query-api 6031e502...: 2130, last 20:05:06Z; graph-evidence-api ba974696...: 860, last 20:05:34Z), ending at old-task shutdown.
- Still open: rotate API token (sent over plain HTTP), old log material (30-day retention), Neptune audit log, TLS on the dev ALB.
- Bedrock: simulate-principal-policy for bedrock:InvokeModel (us-east-2) = allowed, AllowedByOrganizations = True; not IAM or SCP. Treated as an AWS-side account restriction; AWS Support case and instructor follow-up sent.

## 2026-11-25 ~14:30 — Phase 15 closed by the human architect
- Gate met: one measured scale-out (0 -> 2) and scale-in (2 -> 0) of the embedding worker (experiment C), UTC-synchronized metrics, raw data sealed with SHA256SUMS and committed, cost stated (~$1.50, over the $1 cap), failures reported (0 vectors written: Bedrock account block).
- Incident INC-20261125 CONTAINED (logging fix 3c3e1a1, code-reviewer APPROVE in review-report-04.md); not resolved.
- Not run, reported as limitations: D (query load), E (retrieval modes), F (poison message) — Bedrock-dependent or blocked by the ack-on-reject defect; A/B (EMR) moved to phase 15b.
- Open items carried forward: Bedrock account restriction (AWS Support case + instructor), "evidence" smoke failure, rejected items acknowledged instead of dead-lettered, API token rotation, existing log material, Neptune audit log, TLS on the dev ALB, embedding-worker log containment check on first run, tfstate read guard gap and guard false positives (factory-engineer), missing read-only AWS profile for agents.
