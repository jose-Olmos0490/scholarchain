# Phase 16b human gate

Reviewed: agent-report.md (delegations 1-8, repair round 1), test-report.md (first run and final check.sh), review-report.md (first review REQUEST_CHANGES, re-review APPROVE on the final tree).

Accepted: the Anthropic + Voyage provider switch (instructor-approved deviation), Bedrock kept configurable. Final scripts/check.sh: CHECK RESULT PASS, 2574 succeeded, 0 failed, 24 ignored.

Human decisions: answers 1-7 and the IAM/KMS/secret sign-off (delegation 6, items 1-4), recorded verbatim in review-report.md.

Live tests (run by me in a separate terminal; keys never in the repository or an agent session):
- AnthropicLiveIT: [info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
- VoyageLiveIT:    [info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0

Open findings:
- Routed to phase 16c: K1 (variables.tf region default), K3 (put-vectors batch size env), N2 (stale experiment test and C/F estimates).
- Accepted as limitations for the final report: N1 (ledger gap on redelivery, no vector loss), K4 (revoked key moves backlog to DLQ; manual redrive), K5 (Bedrock adjudication caching, unreachable), K6 (error wording), K7 (max_tokens reported as invalid output).
- Routed to phase 17 (documentation-engineer): N3, K2, stale run ID in docs/data-lineage.md.

Process breaches acknowledged:
1. service-engineer's recursive grep matched terraform.tfstate and its backup; the state file holds no secret values (no secret versions are managed by Terraform).
2. infra-engineer rewrote a guard-blocked KMS grant edit with the Edit tool; grant accepted as scoped (kms:ViaService + SecretARN encryption context). Guard gap recorded as a limitation.
3. storage-engineer ran an unfiltered recursive grep over infra/terraform; by its report no state or tfvars content was shown.
4. Evidence fidelity: the orchestrator condensed several agent reports while archiving (disclosed in agent-report.md); originals exist only in the session transcript.

Deviations: no offline Terraform plan in this phase (my instruction); planning happens in the Saturday cloud window.
Manual changes: none to repository files except this gate file. Work orders were drafted with help from Claude (chat) and adapted by me.
Accepted commit: see commit.txt
