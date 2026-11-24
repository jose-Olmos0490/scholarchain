# Phase 12 Human Gate
Reviewer: Jose | Date: 2026-11-24

## Reviewed
- agent-report.md: Attempt 1 (infra-engineer BLOCKED-ON build-engineer for .gitignore), build-engineer .gitignore fix, Attempt 2 (infra + scripts)
- review-report.md: initial REQUEST_CHANGES (0 CRITICAL, 6 MAJOR, 6 MINOR); re-review after repair-01: APPROVE (F1-F6 FIXED; new MINOR N1-N4)
- repair-01.md (saved verbatim by human; agent write correctly blocked by guard)
- Human re-ran locally: terraform fmt -check OK; validate Success; shellcheck -x OK; checkov 552 passed / 0 failed / 66 skipped (inline per-resource skips, each with a justification; 559 passed / 59 failed with no skips)

## Plan reviewed (offline, structural review only)
- Saved plan (enable_emr=false, enable_neptune=false): 196 add / 0 change / 0 destroy. Neptune-on variant: 203 add / 0 change / 0 destroy. No stateful replacements or destroys.
- Offline plans use placeholder account 000000000000 and are marked DIRTY; they cannot be applied or deployed (deploy.sh refuses them).
- 9 IAM roles scoped to specific ARNs; wildcards only where AWS defines no resource scope (ecr:GetAuthorizationToken, RegisterTaskDefinition, log delivery).
- Estimated cost (us-east-1 list, unverified): ~230 USD/month with Neptune (min 1 NCU ~117), ~115 without; EMR ~0.72 USD/h only while running, 1 h idle auto-termination. Budget alarm 250 USD/month.
- COST BOUND I APPROVE: deployment lives <= 48 hours total for Phases 14-15, then cleanup.sh.

## Decision
ACCEPTED. Terraform authored and statically validated. NOT applied.
The real ONLINE plan (scripts/plan.sh --env dev, clean tree, real terraform.tfvars with my own /32 CIDR and real owner) will be generated, checksummed and signed at the start of Phase 14 before any apply. Apply is human-only.

## Human decisions
- F1: alb_ingress_cidrs required, no default; validation rejects 0.0.0.0/0 and ::/0. I will set my own public IP /32.
- F5: protect_stateful=true by default (Neptune deletion protection + final snapshot, DynamoDB and ALB deletion protection). Cleanup requires a reviewed apply with protect_stateful=false first.
- F6: cleanup.sh TTY check implemented. Guard-hook wrapper extension (factory-engineer, constitutional) DEFERRED post-submission.
- F2, F3, F4 fixed as planned.

## Deferred
- MINOR 7-12 (initial review) and N1-N4 (re-review): post-submission, owner infra-engineer.
- Guard-hook wrapper coverage (F6 part 2): factory-engineer, needs ratification.

## Code/infrastructure mismatches carried forward (must be resolved before Phase 14/15 data loading)
- M1: no producer enqueues EmbeddingWorkItem (rag-engineer / storage-engineer)
- M3: no runnable loader entrypoint for Neptune/DynamoDB read model (storage-engineer; also a Phase-10b deferral)
- M8: no EMR entry point for entity resolution (entity-resolution-engineer)
- M5: S3 Vectors index type must be verified as ENHANCED after apply
- UNVERIFIED in AWS: Neptune engine 1.4.0.0, EMR release label, Bedrock model access (no read-only profile configured)

## Control events
- infra-engineer correctly returned BLOCKED-ON build-engineer when .gitignore lacked *.tfplan / terraform.tfvars.
- guard-dangerous.sh blocked agent writes to human-owned repair-01.md and blocked commands containing secret/tfstate/apply text; agents rephrased without bypassing.

## Manual changes
- Human saved repair-01.md and wrote this file. No Terraform or script edited by hand.

## Accepted commit
See commit.txt
