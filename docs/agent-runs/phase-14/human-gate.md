# Phase 14 Human Gate — Apply and Deploy

Decision: ACCEPTED AS PARTIAL. Infrastructure applied; application rollout incomplete.

## Reviewed
- Terraform plans and checksums (plan.sha256, logs/plan-output*.log); every applied plan had 0 destroys and 0 stateful replacements.
- Apply logs (logs/apply-output*.log), deploy-engineer reports (agent-report*.md), repair-01 work order, report and review.

## Deviations and manual interventions (human)
1. Region moved us-east-1 -> us-east-2: account SCP denies creates in every other Region (logs/denied-actions.txt, dry-run probes).
2. AWS account upgraded Free -> Paid plan by the human: Free plan rejected the Neptune engine (FreeTierRestrictionError).
3. terraform.tfvars (git-ignored) edited by the human: aws_region, model IDs, enable_neptune toggled off then on.
4. repair-01 (infra-engineer): DynamoDB tag values contained commas; Neptune backup retention parameterized.
5. API key secret value set by the human with put-secret-value (token hash only; token never committed), then a manual ECS force-new-deployment of graph-evidence-api.
6. An unplanned `terraform apply` without a plan file was started by mistake and cancelled at the variable prompt; nothing was applied by it.

## Resulting state (us-east-2, account <ACCOUNT_ID>)
- Applied: VPC, ALB, ECS cluster + 3 services, ECR, DynamoDB tables, S3 buckets, S3 Vectors bucket + 5 indexes, SQS + DLQ, KMS, Secrets Manager, IAM, Neptune Serverless cluster + writer, alarms.
- Failed: CloudWatch dashboard (invalid widget 5 metrics body) -> routed to infra-engineer.
- graph-evidence-api: 1/1 running, listening on 8081.
- query-api: 0/1, exits on missing serving.runId/serving.snapshot; no serving run is loaded in AWS because no loader entry point exists (GraphLoadWorkflow has no caller) -> routed to storage-engineer.
- embedding-worker: desired 0 by design (scales on SQS backlog).
- smoke-test.sh: not passed; blocked on query-api serving data.

## Remaining risk
- Neptune, NAT gateway and ALB incur cost until scripts/cleanup.sh runs.
- Phase 14 gate items "smoke query returns evidence-backed response" and "no critical alarm" are NOT yet met; the follow-up phase must close them.

Accepted commit hash: see commit.txt

## Update — completion (Nov 24, ~11:40 CST)
- Serving run run:f6023de3… (snapshot 2026-11-01) loaded into the DynamoDB graph read model (843 items) with LoadMain (phase-14b). The S3 copy step failed on a loader path bug, deferred to storage-engineer.
- repair-02 (infra-engineer): service-discovery replacement drift and dashboard metrics format. code-reviewer NOT run (disclosed).
- repair-03 (infra-engineer): ALB listener rules split to at most 5 path patterns per rule. code-reviewer report attached.
- deploy-engineer deployed commit 2365bf5: query-api and graph-evidence-api on task def rev 5 (agent-report-attempt3.md). Its smoke test failed only on ALB connectivity.
- Human fix: tfvars alb_ingress_cidrs set to the architect's IP /32 and allow_plaintext_http = true (dev only; the bearer token crosses HTTP from one allowed IP; the token is rotated at cleanup). Reviewed plans applied.
- Human ran scripts/smoke-test.sh with --base-url http://<alb>: 9/10 PASS (healthz, readyz, query, replay, idempotent-replay, no-token 401, bad-operation 422). FAIL: evidence fetch, because the query returned no evidence ID. Neptune and S3 Vectors are not loaded and the S3 copy step failed, so no evidence is available yet. Smoke log: smoke-test-final.log.
- Decision: ACCEPTED with the documented evidence gap. Gate item "evidence-backed response" remains open.
