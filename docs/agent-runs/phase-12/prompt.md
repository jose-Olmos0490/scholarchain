Phase 12: create the AWS infrastructure in Terraform. Run these as separate delegated work orders, in order.

PART 1 - Use the infra-engineer agent to create the ScholarChain AWS infrastructure under infra/terraform and the approved operational scripts under scripts/.

First inspect the repository to derive real requirements: build.sbt and modules/services (deployable services, entrypoints, ports, health routes), modules/storage (S3 prefixes, DynamoDB tables and keys including the JobStore, Neptune loader and read-model, S3 Vectors bucket and the five indexes), modules/rag (Bedrock model config keys), modules/core config loader (every environment variable name), datasets/dataset-manifest.yaml (raw prefixes). Do not invent tables, indexes, env vars or services that the code does not use; list any mismatch as a finding.

Layout: infra/terraform/modules/<module> and infra/terraform/environments/dev. Pin Terraform (>= 1.10) and the AWS provider to exact versions that support aws_s3vectors_vector_bucket and aws_s3vectors_index. Region and account are variables (default region us-east-1); no hardcoded account IDs.

Required modules:
1. Foundation: pinned providers, default_tags (course=cs441, project=scholarchain, environment, owner, expiration), AWS Budgets alarm variable. Remote state: document an S3 backend with use_lockfile in docs-free form as infra/terraform/environments/dev/backend.tf.example; the dev environment uses local state for the course (documented limitation).
2. Network: VPC across 2 AZs, public subnets only for the ALB, private app and data subnets, one NAT gateway (cost-bounded, documented), VPC endpoints for S3 and DynamoDB (gateway), security groups chained by role (ALB -> services -> Neptune), no 0.0.0.0/0 ingress except ALB 443/80.
3. Identity and encryption: least-privilege IAM roles for EMR (service + instance profile), ECS task execution, each ECS task role (runtime read-only to Neptune and vectors where possible), Step Functions, loaders, deployment execution. Separate runtime and deployment roles. Scope every policy to specific ARNs. KMS key (or AWS-managed encryption, justified). Secrets Manager secrets declared by name only, never values.
4. Data lake: raw, curated, graph, rag, artifacts, logs, reports buckets (or prefixes, justified) with versioning where needed, SSE, public-access block, lifecycle for intermediate data, force_destroy=false.
5. EMR: Hadoop + Spark release, managed scaling with bounded min/max (small defaults), log URI, auto-termination. Spot task capacity only if failure behavior is documented. Default count variable so the cluster is NOT created unless enabled (enable_emr=false).
6. DynamoDB: every table the storage and services code uses, with exact partition/sort keys from code, on-demand billing, PITR on audit and ledger tables, TTL only where code expects it.
7. Neptune: Neptune Serverless with bounded min/max NCU, subnet and parameter groups, encryption, backup retention, deletion_protection variable (true for persistent envs, false for dev), IAM auth, loader role, read-only runtime access. Behind enable_neptune toggle.
8. S3 Vectors: vector bucket and the five indexes (researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1) with dimension and distance metric taken from the embedding config in code; non-filterable metadata keys for textS3Uri and textHash.
9. OpenSearch: NOT deployed (optional tier not implemented). State this in infra/terraform/README.md.
10. Runtime compute: ECR repos with IMMUTABLE tags and scan_on_push; ECS cluster; Fargate task definitions and services for each deployable service found in code, image tag = var.git_sha; ALB with health checks on the real health/ready routes; Cloud Map service discovery; ECS service auto scaling (target tracking on CPU or request count, bounded min/max); embedding worker scaling on SQS backlog per task.
11. Workflow and messaging: SQS queues with DLQs and redrive (embedding work, async jobs as the code uses them), Step Functions state machine for the bounded pipeline (EMR step submission) if practical, otherwise documented.
12. Observability: CloudWatch log groups with retention, a dashboard (ECS tasks, ALB p95, SQS depth, DLQ count, DynamoDB throttles, Neptune), alarms (5xx, p95 latency, DLQ > 0, budget).
13. Cost and safety: bounded defaults, terraform.tfvars.example without secrets, deletion protection for stateful resources when environment != dev, explicit cleanup notes.

Required scripts in scripts/ (bash strict mode, no interactive prompts, greppable "==> STEP" markers, fail loudly): plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh.
- plan.sh writes the plan to /tmp/scholarchain-plans/<env>-<gitsha>.tfplan (never into the repo), prints the full plan, records sha256 checksum, add/change/destroy counts, IAM and stateful changes to docs-free output.
- deploy.sh refuses a dirty tree and requires APPROVED_PLAN_SHA256 to match the plan file before any apply; tags images by git SHA.
- cleanup.sh lists every targeted resource and requires CONFIRM_CLEANUP=<env>-<account-id> before destroying anything.
- smoke-test.sh checks /healthz, /readyz, one bounded query, one evidence fetch, idempotency, and one expected error.

Verification (run all, never apply): terraform fmt -check -recursive; terraform init -backend=false and terraform validate in environments/dev; terraform plan -input=false -out=/tmp/scholarchain-plans/dev-$(git rev-parse --short HEAD).tfplan with toggles enable_emr=false, enable_neptune=true; bash -n and shellcheck (if installed) on every script; checkov -d infra/terraform (if installed).
If .gitignore lacks .terraform/, *.tfstate*, *.tfplan or terraform.tfvars, return BLOCKED-ON build-engineer instead of editing it.
Do not run terraform apply or destroy. Do not create any AWS resource. Do not edit application source.
Report: file tree, module dependency graph, plan summary (add/change/destroy counts), estimated high-cost resources with monthly estimates (NAT, Neptune min NCU, ALB, Fargate, EMR when enabled), stateful resources, IAM roles and their scoped actions, security-scan results, required human actions, and every mismatch between code and infrastructure.

PART 2 - Use the code-reviewer agent for a read-only review of infra/terraform and scripts/ with the PART 1 report. Review: secrets in source or plan output; IAM wildcards on actions or resources; public exposure of buckets, databases, or Neptune; unbounded capacity; stateful resources without deletion safeguards; scripts that can apply or destroy without the documented approval; mismatches with the tables, indexes, env vars and services in code; cost risks. For each finding give severity, file and line, impact, owner. Return APPROVE or REQUEST_CHANGES. Do not edit any file.

PART 3 - Orchestrator: save the PART 1 report verbatim to docs/agent-runs/phase-12/agent-report.md and the PART 2 report verbatim to docs/agent-runs/phase-12/review-report.md. Present a repair plan for CRITICAL and MAJOR findings grouped by owner. Do not start any repair. Do not write human-gate.md. Stop for human review.
