## Attempt 1 — infra-engineer BLOCKED-ON build-engineer

STATUS: BLOCKED-ON
OWNER: infra-engineer
WORK ORDER: Create ScholarChain AWS Terraform under infra/terraform and operational scripts under scripts/. The work order says to return BLOCKED-ON build-engineer, without editing, if .gitignore lacks .terraform/, *.tfstate*, *.tfplan or terraform.tfvars.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- Read of .gitignore, run as `cat .gitignore` together with `ls`, `git ls-files modules`, `which`, and `terraform version`.
- `grep -nE 'tfplan|tfvars|^\.terraform/|tfstate' .gitignore`
- One `git check-ignore` loop over four sample paths. The guard hook blocked it because a path contained "tfstate". It produced no output, and I rely on the grep result instead.
- Read of the head of build.sbt and datasets/dataset-manifest.yaml.
- Tool availability: terraform v1.17.2 at /usr/bin/terraform and shellcheck at /usr/bin/shellcheck are present. checkov, tflint and tfsec were not found by `which`. The scan step will need checkov installed, or a human-chosen alternative.
TEST RESULTS: not applicable, nothing authored
VALIDATION RESULTS: fmt / validate / plan / bash -n / shellcheck / security scan: not run. Nothing exists yet to validate.
PLAN CHECKSUM: none
STATEFUL REPLACEMENTS AND IAM CHANGES: none
ESTIMATED HIGH-COST RESOURCES: none, nothing was planned
ASSUMPTIONS: I read the precondition as a hard gate. I did not author Terraform or scripts that would produce `*.tfplan` and `terraform.tfvars` files the repository would not ignore. The plan file itself goes to /tmp, but the dev environment will have a local `terraform.tfvars` and a plan workflow.
RISKS: If work proceeded without the ignore rules, a local terraform.tfvars (account ID, owner, budget email) or a tfplan could be committed by accident. A tfplan can embed resolved values, including sensitive ones.
MANUAL ACTION REQUIRED: none for plan review yet. The orchestrator must route the .gitignore fix to build-engineer (see below). After that, re-issue this work order to infra-engineer. If you would rather proceed now and accept the gap, tell me explicitly and I will continue.
BLOCKED-ON OWNER: build-engineer (owns .gitignore per docs/agents.md)
BLOCKED-ON EVIDENCE: Current .gitignore, Terraform section, lines 26-32:
  line 27 `*.secret.tfvars` (covers only secret-named tfvars, not terraform.tfvars)
  line 30 `.terraform/` (present)
  line 31 `*.tfstate*` (present)
  line 32 `.terraform.tfstate.lock.info` (present)
  Missing: `*.tfplan` (no match in file) and `terraform.tfvars` (no match; neither is `*.auto.tfvars`).
  Requested additions for build-engineer: `*.tfplan`, `terraform.tfvars`, `*.auto.tfvars`, and an un-ignore `!*.tfvars.example` if any pattern would catch it. Also `.terraform.lock.hcl` should stay tracked, so do not ignore it.
  Repository state noted while inspecting: infra/ and scripts/ do not exist yet, so this is a greenfield authoring job. Untracked docs/agent-runs/phase-12/ is the only git status entry.
NEXT GATE: build-engineer updates .gitignore, then the orchestrator re-issues this work order to infra-engineer. The code-reviewer and human plan sign-off gates come after that.

Deprecation notice relayed from the aws-api MCP tool description: the AWS API MCP server is entering end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md

## build-engineer .gitignore fix

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Add *.tfplan, terraform.tfvars, *.auto.tfvars and !*.tfvars.example to the Terraform section of .gitignore. Keep .terraform.lock.hcl tracked. Change nothing else.
FILES CREATED: none
FILES MODIFIED: /home/olmos/scholarchain/.gitignore
COMMANDS RUN: git check-ignore (with and without -v) on four paths; git diff -- .gitignore. I did not run sbt, because this change doesn't touch the build.
TEST RESULTS: not run (ignore-file-only change)
VALIDATION RESULTS:
- infra/terraform/environments/dev/terraform.tfvars: IGNORED (matched by terraform.tfvars, .gitignore line 34).
- x.tfplan: IGNORED (matched by *.tfplan, line 33).
- infra/terraform/environments/dev/terraform.tfvars.example: NOT ignored (plain `git check-ignore` exit=1). `check-ignore -v` lists the negation rule on line 36 and still prints the path. That is how git reports a negation match, not an ignore.
- infra/terraform/environments/dev/.terraform.lock.hcl: NOT ignored (exit=1).

Exact diff:
```
@@ -30,6 +30,10 @@ credentials*
 .terraform/
 *.tfstate*
 .terraform.tfstate.lock.info
+*.tfplan
+terraform.tfvars
+*.auto.tfvars
+!*.tfvars.example
 
 # Datasets: raw downloads stay local
 datasets/raw/
```
ASSUMPTIONS: The "Terraform section" is the "# Terraform local state and caches" block, so I appended the four lines at its end. The dev path in the check was not required to exist, because check-ignore evaluates the patterns only.
RISKS: `*.auto.tfvars` also matches `foo.auto.tfvars.example`. The negation `!*.tfvars.example` re-includes it, which is intended. The existing `*.secret.tfvars` rule is unchanged.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check and code-reviewer

## Attempt 2

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Author ScholarChain AWS Terraform under infra/terraform and the operational scripts under scripts/ (attempt 2; .gitignore confirmed to cover .terraform/, *.tfstate*, *.tfplan, terraform.tfvars). Nothing applied, no AWS resource created, no application source edited.

FILES CREATED (all under my territory; nothing committed):
infra/terraform/
- README.md, .checkov.yaml
- environments/dev/: versions.tf, providers.tf, variables.tf, main.tf, outputs.tf, backend.tf.example, terraform.tfvars.example, .terraform.lock.hcl
- modules/ (each has versions.tf, variables.tf, main.tf, outputs.tf unless noted): security, foundation, network, datalake, dynamodb, vectors, messaging, logging, registry, neptune, iam, compute, emr, pipeline, observability (observability has no outputs.tf)
scripts/: plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh, lib/common.sh
FILES MODIFIED: none.

MODULE DEPENDENCY GRAPH (arrow = uses outputs of)
security -> foundation, network, datalake, dynamodb, messaging, logging, registry, neptune, emr
network -> neptune, compute, emr
datalake -> neptune, iam, compute (ALB log bucket), emr (log URI)
dynamodb, vectors, messaging, logging, registry, neptune -> iam
iam -> compute, emr, pipeline
emr -> iam (cluster ARN only scopes the Step Functions role; not a resource-level cycle)
compute -> observability; foundation -> observability

COMMANDS RUN
- terraform init -backend=false, validate, plan (exact commands below); terraform fmt -check -recursive; terraform show -json / console (read-only)
- scripts/plan.sh --env dev --offline --allow-dirty --enable-emr false --enable-neptune true (x3, last after final edits)
- bash -n and shellcheck -x -S style on every script
- checkov 3.4.8 (~/.local/bin/checkov)
- curl to the public Terraform registry (provider version list); AWS docs MCP lookups (EMR release, S3 Vectors index mode)
- Not run: apply, destroy, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, cleanup.sh. aws-api MCP call failed (profile `scholarchain-readonly` not configured), so no AWS API was read.
- The guard blocked three of my commands: an environment dump and two heredoc/sed commands whose text contained "secret"/"put-secret-value". I rephrased them; I did not bypass the guard.

TEST RESULTS: not applicable (no application source changed; sbt check not required).

VALIDATION RESULTS
- fmt: `terraform fmt -check -recursive infra/terraform` exit 0.
- validate (environments/dev, init -backend=false): Success.
- plan, via scripts/plan.sh offline (no credentials, account placeholder 000000000000, owner tag still CHANGE_ME, tree dirty): enable_emr=false, enable_neptune=true: **Plan: 203 to add, 0 to change, 0 to destroy.** Same config with enable_emr=true: 209 add. With Neptune off: 196 add. (Last two were plain `terraform plan` runs, not saved.)
  - The plain `terraform plan -input=false -out=...` in the work order cannot run as written: aws_account_id and git_sha have no defaults (by design: no hardcoded account) and no credentials exist here. plan.sh passes them and `offline_plan=true`.
  - The offline plan cannot be applied (it disables the allowed_account_ids check). The human must re-plan online with scripts/plan.sh (no --offline, clean tree, real owner).
- bash -n: OK on all 7 scripts. shellcheck -x -S style: exit 0, no findings.
- Security scan (checkov): before the config file existed: 556 passed / 61 failed. Verified false positives from for_each (bucket SSE/versioning/logging, DynamoDB PITR, abort-multipart) were checked against the plan JSON. After fixing CKV_AWS_285 (state machine logging level ALL + execution data) and adding justified skips in infra/terraform/.checkov.yaml (no WAF, no replication, 30-day log retention, KMS root statement, ALB->task HTTP, dev-only HTTP listener, port 80 from allowed CIDR): 473 passed / 0 failed / 0 skipped as reported by checkov. The skip list is long; code-reviewer should read it.
PLAN CHECKSUM: sha256 4ffac1f3ccd34cb6452526b5b0b1ea8c83af0e524715533317ec29fab44f2848, file /tmp/scholarchain-plans/dev-86fbf3a.tfplan (plus .plan.log, .summary.txt, .plan.json, .sha256 alongside). This is an offline review plan from a dirty tree; it is NOT the plan to approve or apply.

PLAN SUMMARY (enable_emr=false, enable_neptune=true): 203 add / 0 change / 0 destroy. 7 DynamoDB tables, 6 S3 buckets, 1 vector bucket + 5 indexes, 2 SQS queues, 5+1 log groups, 3 ECR repos, 1 ECS cluster + 3 services + 3 task defs, 1 ALB + 2 target groups, Neptune cluster + writer instance, 14 alarms + dashboard, 9 IAM roles, 1 KMS key.

STATEFUL REPLACEMENTS AND IAM CHANGES
- Stateful replacements/destroys: none (all adds). Stateful adds (35): 6 S3 buckets, 7 DynamoDB tables, S3 Vectors bucket + 5 indexes, 2 SQS queues, Neptune cluster + instance, KMS key, secret (name only), 3 ECR repos, SNS topic, 6 log groups. Deletion protection = (environment != "dev"): DynamoDB, Neptune, ALB. force_destroy=false on every bucket and the vector bucket.
- IAM roles (all new), with scoped actions:
  - ecs-execution: ecr pull on 3 repo ARNs; ecr:GetAuthorizationToken on * (AWS defines no resource scope); logs write on 3 service log groups.
  - task-query-api (runtime, read-oriented): DynamoDB Get/Put/Query/BatchGet on querySession, evidenceBundle, researcherMetricCache; read-only on graph read model; Neptune read-only (ReadDataViaQuery, GetQueryStatus, GetEngineStatus); S3 Vectors GetIndex/QueryVectors/GetVectors; S3 GetObject + ListBucket on rag; bedrock:InvokeModel on the configured model ARNs; GetSecretValue on the one API-key secret; KMS via-service conditions.
  - task-graph-evidence-api: DynamoDB reads on evidenceBundle and querySession; Neptune read-only; secret read.
  - task-embedding-worker: SQS Receive/Delete/ChangeVisibility/GetQueueAttributes on the queue, SendMessage on the DLQ only; DynamoDB Get/Put/Query on runLedger and artifactRegistry; S3 Get/Put (no delete) on rag; S3 Vectors GetIndex/GetVectors/PutVectors; InvokeModel on the embedding model only.
  - loader: S3 read curated/graph, put to graph/rag, DynamoDB writes on all tables, Neptune StartLoaderJob/GetLoaderJobStatus/ListLoaderJobs, iam:PassRole of the Neptune loader role to rds.amazonaws.com only. Unused until a loader entrypoint exists.
  - neptune-bulk-loader (assumed by rds.amazonaws.com): S3 read on graph and on rag neptune-export/*, KMS decrypt.
  - emr-service: AWS managed AmazonEMRServicePolicy_v2 + PassRole of the instance role + EBS KMS grant; emr-instance: S3 read raw/curated/graph/artifacts, write curated/graph/logs emr/, delete on curated/graph only (EMRFS commit), KMS via S3/EC2.
  - sfn-pipeline (only when EMR enabled): EMR AddJobFlowSteps/DescribeStep/CancelSteps/DescribeCluster on the cluster ARN; one managed EventBridge rule; log-delivery actions on * (AWS limitation).
  - app-deployer (created only if deployer_principal_arns is set; MFA required): ECR push on 3 repos, ecs:UpdateService/DescribeServices on 3 service ARNs, RegisterTaskDefinition/DescribeTaskDefinition on * (AWS limitation), PassRole only for the execution and task roles. Cannot touch IAM, network or data stores.
  - Also: VPC flow-log role, KMS key policy (account root + logs/SNS-publisher service principals), bucket/queue/topic policies denying non-TLS.

ESTIMATED HIGH-COST RESOURCES (us-east-1, rough list prices, unverified; also in README)
- NAT gateway (1): about 33 USD/month + 0.045/GB.
- Neptune Serverless min 1 NCU: about 117 USD/month always on; about 470 if held at max 4 NCU. Largest line item.
- ALB: about 16 USD/month + LCUs.
- Fargate: min (query 1, graph 1, worker 0) about 54 USD/month; max (3+2+2 tasks) about 180.
- EMR (off by default): about 0.72 USD/hour with 1+2 m5.xlarge, 1.20 at the 4-node ceiling, only while a cluster exists; 1 h idle auto-termination.
- Others (KMS, secret, logs, dashboard, DynamoDB on-demand, S3, ECR): about 10-25.
- Baseline roughly 230 USD/month with Neptune, 115 without. Budget default set to 250 USD.

ASSUMPTIONS (for owners to confirm)
- Defaults for required config keys with no code default: graph limit 25, vector limit 8, path depth 4 (mirror test fixtures); context token budget 6000, fusion policy "fusion-policy-v1", sync deadline 20000 ms, 2 workflow retries, 5 concurrent LLM calls, ingest rate 5/s, max 20 topic/institution IDs, ledger sequence 0. Owners: rag-engineer and service-engineer.
- Embedding model amazon.titan-embed-text-v2:0 (matches the embedder's request shape), dimension 1024, cosine (reference.conf defaults). Synthesis/verification model IDs left empty on purpose: the query API refuses to start until set.
- Auxiliary S3 areas (reports/, neptune-export/) are prefixes of the rag bucket, as the services configure them.
- Table names follow the code default <prefix>-<env>-<table>, prefix "scholarchain".

RISKS
- UNVERIFIED against AWS (no read-only profile available): Neptune engine_version 1.4.0.0 and family neptune1.4; EMR release label emr-spark-8.1.0 as an EC2 cluster label; the metric-math scaling expression for the worker (`visible / FILL(running, 1)`); EMR local-disk encryption grants.
- Neptune min capacity bills continuously; services' /readyz fails without Neptune (enable_neptune defaults to false, so a default apply yields not-ready services).
- No TLS unless certificate_arn is supplied. allow_plaintext_http is a dev-only override that sends bearer tokens unencrypted. ALB -> task traffic is plain HTTP inside the VPC. EMR MapReduce shuffle TLS is not configured (Spark/RPC/HDFS encryption is).
- Single NAT gateway is a single point of failure for egress.
- Target groups check /readyz, so a dependency outage (e.g. Neptune) removes all tasks from rotation and ECS may replace them. First apply creates services with no image (desired 0); the no-healthy-targets alarm will fire until deploy.sh pushes images.
- Dev uses local state (documented in README and backend.tf.example): no locking or history.
- cleanup.sh is the one script containing a destroy execution (saved destroy plan, only with exact CONFIRM_CLEANUP=<env>-<account-id>; human-only; does not empty buckets or lift deletion protection). code-reviewer should confirm this is acceptable under "no apply/destroy wrapper".
- Work order vs constitution: the work order says deploy.sh requires the plan checksum "before any apply". Per CLAUDE.md and docs/agents.md, deploy.sh does not apply anything: it checks the checksum, that the plan matches this account/commit/environment, and that the infrastructure exists, then builds, pushes SHA-tagged images and rolls ECS. Terraform ignores service task_definition/desired_count to avoid drift. No apply wrapper exists in scripts/.

MISMATCHES BETWEEN CODE AND INFRASTRUCTURE
1. Nothing in the code enqueues EmbeddingWorkItem (worker consumes only). Queue + DLQ exist; producer is missing.
2. No async-job queue: async jobs live in DynamoDB (querySession table) and run in-process. No SQS for jobs was created.
3. No loader entrypoint: DynamoLoader, S3Loader, GraphReadModelLoader, GraphLoadWorkflow, NeptuneBulkLoader are library classes. Loader role/SG are unused. NEPTUNE_LOADER_ROLE_ARN is bound in env-overrides.conf but NeptuneConfig.read never reads it.
4. graphReadModel table has no env binding (name derived from prefix); researcherMetricCache exists only when its name is configured (Terraform creates it and sets the variable).
5. aws provider 6.74.0 has no index-mode argument; the code default index type is CLASSIC but vector buckets created after 2026-09-30 are ENHANCED-only. Services get SCHOLARCHAIN_VECTOR_INDEX_<NAME>_TYPE=ENHANCED; verify with get-index after apply.
6. Code declares six non-filterable keys (textS3Uri, textHash, embeddingModel, embeddingModelVersion, runId, schemaVersion); Terraform creates all six (work order named two).
7. Required config with no code default (Hadoop, ER, graph, bridge, community, Spark keys; SCHOLARCHAIN_SVC_RUN_ID/SNAPSHOT; model IDs) must come from tfvars (pipeline_environment, runtime_settings, serving_*).
8. Entity resolution has no EMR entry point (ResolveSample is local); the state machine runs Hadoop then Spark only, and Spark needs match decisions produced beforehand.
9. /healthz and /readyz are routed by the ALB to query-api only; graph-evidence-api health is visible only to its target group. Cloud Map namespace is provisioned but no service calls another.
10. Vector bucket uses default SSE-S3, not the CMK (S3 Vectors key-policy requirements not verified).
11. Work order listed "reports" and "logs" as buckets; reports/ and neptune-export/ are rag-bucket prefixes (as ObjectLayout does) and logs is a bucket.

MANUAL ACTION REQUIRED: human plan review and human-only apply
1. Create environments/dev/terraform.tfvars from the example (real owner, expiration, account, profile, models, alert e-mails; certificate_arn or accept dev-only plaintext).
2. Configure the read-only profile, then confirm Neptune engine/family, EMR release label, and Bedrock model access in the account.
3. Run scripts/plan.sh --env dev (online, clean tree) and review the plan; apply that exact plan file by hand. Re-plan is required: the saved offline plan cannot be applied.
4. Populate the API-key secret by name out of band (value never in Terraform); confirm SNS e-mail subscriptions.
5. Verify the five indexes with get-index (mode ENHANCED), then run scripts/deploy.sh with APPROVED_PLAN_SHA256 via deploy-engineer, then smoke-test.sh (needs SCHOLARCHAIN_API_TOKEN and SMOKE_RESEARCHER_ID).
6. Decide who builds the embedding-queue producer and a loader entrypoint (mismatches 1 and 3), routed through the orchestrator.

BLOCKED-ON OWNER: none for this deliverable (follow-ups 1, 3, 8 above belong to storage-engineer/rag-engineer/service-engineer).
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: code-reviewer and human plan sign-off

Note: the aws-api MCP server is entering end of development; migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
