# ScholarChain infrastructure (Terraform)

Owner: `infra-engineer`. Agents author and plan; **a human applies**. Nothing in this
repository applies, destroys or mutates AWS. All infrastructure lives here; there is no
CloudFormation, CDK or console-only resource.

## Layout

```text
infra/terraform/
  README.md                         this file
  .checkov.yaml                     scan configuration, every skip justified
  environments/dev/
    versions.tf                     Terraform >= 1.10.0, aws provider = 6.74.0 (exact)
    providers.tf                    provider, allowed_account_ids, default_tags
    variables.tf  main.tf  outputs.tf
    backend.tf.example              S3 backend with use_lockfile (not active)
    terraform.tfvars.example        placeholders only, no secrets
  modules/
    security/       KMS key, Secrets Manager secret NAME (no value)
    foundation/     alarm SNS topic, AWS Budgets alert
    network/        VPC, 2 AZ, subnets, 1 NAT, S3+DynamoDB gateway endpoints, security groups
    datalake/       raw, curated, graph, rag, artifacts, logs buckets
    dynamodb/       the seven tables the code uses
    vectors/        S3 Vectors bucket and the five indexes
    messaging/      embedding work queue + dead-letter queue
    logging/        CloudWatch log groups
    registry/       ECR repositories (immutable tags, scan on push)
    neptune/        Neptune Serverless + bulk-loader role     (enable_neptune)
    iam/            task, execution, loader, EMR, Step Functions, deployment roles
    compute/        ECS cluster, Fargate services, ALB, Cloud Map, auto scaling
    emr/            EMR cluster with managed scaling          (enable_emr)
    pipeline/       Step Functions state machine              (enable_emr)
    observability/  dashboard and alarms
```

Module dependency graph (an arrow means "uses outputs of"):

```text
security --> foundation, network, datalake, dynamodb, messaging, logging, registry, neptune, emr
network  --> neptune, compute, emr
datalake --> neptune, iam, compute (ALB log bucket), emr (log URI)
dynamodb --> iam, observability          vectors --> iam
messaging --> iam, compute, observability   logging --> iam, compute, pipeline
registry --> iam, compute                neptune --> iam
iam      --> compute, emr, pipeline      emr --> iam (cluster ARN scopes the Step Functions role)
compute  --> observability               foundation --> observability
```

The `iam <-> emr` edge is not a cycle at resource level: the EMR cluster needs the EMR
roles, and only the Step Functions role policy needs the cluster ARN.

## How to use (human steps)

1. `cp environments/dev/terraform.tfvars.example environments/dev/terraform.tfvars`, edit it.
2. `scripts/plan.sh --env dev` (a `--allow-dirty` plan is written as `<env>-<sha>.DIRTY.tfplan`, marked `dirty=true`, and `deploy.sh` refuses it; `deploy.sh` also refuses an owner tag of `CHANGE_ME`; writes `/tmp/scholarchain-plans/dev-<sha>.tfplan`, prints
   the full plan, the sha256, add/change/destroy counts, IAM and stateful changes).
3. The human reviews the plan, checks the account, Region and cost, and **applies that exact
   plan file by hand** (`terraform -chdir=... apply <plan file>`). No script and no agent
   does this.
4. Put the API-key digests into the secret named by the output `api_keys_secret_name`
   (out of band; Terraform never sees a value).
5. `APPROVED_PLAN_SHA256=<sha256> scripts/deploy.sh --env dev` pushes images tagged by Git
   SHA and rolls the services. Then `scripts/smoke-test.sh --env dev`.

First apply creates ECS services with desired count 0 and no image. Until `deploy.sh` pushes the
first images the target groups have no healthy targets, so the no-healthy-targets alarm is
expected to fire once.

## Decisions and trade-offs

| Topic | Decision | Why / cost of the choice |
|---|---|---|
| State | Local state in dev | Course environment. No locking, no history: one applier only, state kept out of the repository. `backend.tf.example` shows the S3 backend with `use_lockfile = true` for persistent environments. |
| Provider pins | Terraform `>= 1.10.0`, aws `= 6.74.0` | 6.74.0 provides `aws_s3vectors_vector_bucket` and `aws_s3vectors_index` (verified by `terraform validate`). |
| Account | `aws_account_id` variable, provider `allowed_account_ids` | Nothing hardcoded; a wrong account makes the provider refuse. `offline_plan` turns the check off for review plans that can never be applied. |
| NAT | One NAT gateway | Cost bound. Single point of failure for egress; tasks reach Bedrock, S3 Vectors, SQS, Secrets Manager and ECR through it. S3 and DynamoDB use gateway endpoints. |
| Encryption | One customer-managed KMS key for S3, DynamoDB, SQS, SNS, Neptune, logs, ECR, secret | One key to review. The logs bucket uses SSE-S3 because ALB and S3 log delivery cannot write SSE-KMS objects. The vector bucket uses default SSE-S3 (S3 Vectors key-policy needs not verified). |
| Buckets | `raw`, `curated`, `graph`, `rag`, `artifacts`, `logs` | The code names four buckets (raw, curated, graph, rag). `reports/` and `neptune-export/` are **prefixes of the rag bucket**, exactly as the services lay them out (`ObjectLayout`). `force_destroy = false` everywhere. |
| Deletion protection | `protect_stateful` (default `true`, independent of the environment name) | When true: Neptune `deletion_protection=true`, `skip_final_snapshot=false` with a final snapshot identifier; DynamoDB `deletion_protection_enabled=true` on every table; ALB deletion protection. PITR stays on the run-ledger, query-session, evidence-bundle and artifact-registry tables. Cleanup needs a reviewed apply with `protect_stateful=false` first (see Cleanup). |
| ALB ingress | `alb_ingress_cidrs`: required, no default | Validation rejects `0.0.0.0/0` and `::/0` in the environment and the network module. Set it to your own public IP /32 (`terraform.tfvars.example` shows `203.0.113.10/32`); `plan.sh` also accepts `--alb-ingress-cidr`. |
| Fargate | query-api 1 vCPU/2 GB (1-3), graph-evidence-api 0.5/1 (1-2), embedding-worker 0.5/1 (0-2) | Bounded min/max, CPU target tracking for the APIs. The worker uses step scaling on SQS depth: +1 or +2 on visible messages, to 0 after 5 minutes with nothing visible or in flight. On-demand Fargate only. |
| Rollout | Terraform owns the baseline task definition; `deploy.sh` registers revisions | `aws_ecs_service` ignores `task_definition` and `desired_count`; task definition revisions are never deregistered (`skip_destroy`) so rollback targets exist. |
| EMR | 1 primary + 2 core (m5.xlarge), managed scaling 2-4, idle auto-termination 1 h, no task group | Spot was left out: a reclaimed node loses shuffle output and the steps have no restart story. |
| Pipeline | Step Functions: Hadoop step then Spark step, 2 h timeout each, no retry | A re-run is a new run. Entity resolution is not an EMR step (no cluster entry point exists). |
| OpenSearch | **Not deployed.** The optional tier is not implemented in the code. | No cost, no resources. |

## Mismatches between the code and the infrastructure

1. **No producer for the embedding queue.** The worker consumes `EmbeddingWorkItem`s from SQS
   but nothing in the code enqueues them. The queue and DLQ exist; the producer is missing.
2. **No async-job queue.** Asynchronous query jobs are stored in DynamoDB (`querySession`
   table) and run in-process (`DynamoJobStore`); no job queue was created.
3. **No loader entrypoint.** `DynamoLoader`, `S3Loader`, `GraphReadModelLoader`,
   `GraphLoadWorkflow` and `NeptuneBulkLoader` are library classes with no `main`. The loader
   role and security group exist and are unused until an entrypoint exists. `NEPTUNE_LOADER_ROLE_ARN`
   is bound in `env-overrides.conf` but `NeptuneConfig.read` never reads it.
4. **`graphReadModel` has no environment binding.** The table name is derived as
   `<tablePrefix>-<environment>-graph-read-model`; the table is named that way. `researcherMetricCache`
   exists only when its name is configured; Terraform creates it and sets the variable.
5. **Index mode.** `aws_s3vectors_index` has no index-mode argument. Vector buckets created on or
   after 2026-09-30 default to ENHANCED and cannot hold CLASSIC indexes, while the code default is
   CLASSIC. The services get `SCHOLARCHAIN_VECTOR_INDEX_<NAME>_TYPE=ENHANCED`; `S3VectorsIndex.describe`
   compares it with the deployed index, so verify with `get-index` after the first apply.
6. **Non-filterable keys.** The code (`IndexDefinition.NonFilterableMetadataKeys`) declares six:
   `textS3Uri`, `textHash`, `embeddingModel`, `embeddingModelVersion`, `runId`, `schemaVersion`.
   Terraform creates all six, not only the two named in the work order.
7. **Embedding shape.** `reference.conf` defaults dimension 1024 and cosine; the Bedrock embedder
   sends `{inputText, dimensions, normalize}` (Titan Text Embeddings V2). Default model:
   `amazon.titan-embed-text-v2:0`.
8. **Required values with no code default.** Retrieval limits, context budget, fusion policy
   version and operations limits (`runtime_settings`), the synthesis and verification model IDs,
   `SCHOLARCHAIN_SVC_RUN_ID` and `SCHOLARCHAIN_SVC_SNAPSHOT`, and every Hadoop, ER, graph, bridge,
   community and Spark key (`pipeline_environment`) are application decisions. Defaults here are
   assumptions for the owning engineers to confirm.
9. **Readiness depends on Neptune.** The query and graph services probe Neptune in `/readyz`.
   With `enable_neptune = false` they never become ready.
10. **Health routes.** The ALB routes `/healthz` and `/readyz` to the query API only. The graph
    API's `/readyz` is used by its target group; it has no public health URL.
11. **Cloud Map is not used by the code.** No service calls another; the namespace is provisioned
    because the work order asks for discovery.
12. **Work order vs constitution.** The work order lets `deploy.sh` gate "any apply". The
    constitution forbids any apply wrapper, so `deploy.sh` only rolls the application and uses the
    plan checksum as evidence that a reviewed plan exists for this commit, account and environment.
    `cleanup.sh` (human-only) is the single script that can execute a destroy plan, and only after
    the exact `CONFIRM_CLEANUP=<env>-<account-id>` string.

## Unverified items (no read-only AWS profile was available)

The named profile `scholarchain-readonly` was not configured in the authoring session, so nothing
below was checked against AWS: Neptune `engine_version` and parameter-group family
(`1.4.0.0` / `neptune1.4`), the EMR release label (`emr-spark-8.1.0` as an EC2 cluster label), the
worker step-scaling behaviour from zero, and S3 Vectors quotas. They are
variables; confirm before the first apply.

## Estimated monthly cost (us-east-1, order of magnitude, unverified list prices)

| Item | Default | Approx. USD / month |
|---|---|---|
| NAT gateway | 1, always on | 33 + 0.045 per GB |
| Neptune Serverless | min 1 NCU, max 4 | 117 at the minimum, 470 if held at the maximum (+ storage, I/O) |
| ALB | 1 | 16 + LCU usage |
| Fargate | 1 + 1 + 0 tasks, max 3 + 2 + 2 | 54 at the minimum, about 180 at the maximum |
| EMR | off (`enable_emr = false`) | about 0.72 USD/hour with 1 + 2 nodes, 1.20 at the 4-node ceiling, only while a cluster exists |
| KMS, secret, logs, dashboard, DynamoDB on-demand, S3, ECR | small | 10-25 |

With Neptune enabled the baseline is roughly 230 USD per month; without it, roughly 115. The
default budget alert is 250 USD (`budget_limit_usd`).

## Security scan

`checkov -d infra/terraform` (3.4.8) has no global skips: `.checkov.yaml` contains no `skip-check`.
Every accepted finding is skipped inline next to its resource with a reason (`grep -rn
checkov:skip infra/terraform/modules`). They fall into: for_each false positives (S3 and DynamoDB
settings live in separate for_each resources checkov cannot follow; verified in the plan JSON),
checks that do not apply (no replication, no event notifications, no X-Ray, secret rotation by a
human) and accepted trade-offs (no WAF, 30-day logs, KMS root statement, HTTP from ALB to task,
dev-only HTTP listener). Accepted DynamoDB trade-off: `entity_link_cache`, `graph_read_model` and
`researcher_metric_cache` have PITR disabled because they are regenerable from pipeline outputs.
Without any skips the scan reports 559 passed and 59 failed; with the inline skips it reports
552 passed, 0 failed, 66 skipped (repair-01 run).

## Cleanup

`scripts/cleanup.sh --env dev` is a human-only dry run that writes a destroy plan and lists every
target. Running it with `CONFIRM_CLEANUP=<env>-<account-id>` (environment name, hyphen, the 12-digit
account) executes that plan. The script compares your value with the account it resolves from
STS but never prints the resolved value (it would put the account ID into logs): read the account
yourself with `aws sts get-caller-identity`. Cleanup
policy: state is preserved (the script never deletes or edits it and saves a copy to
`build/cleanup/<env>-<sha>/state-before.json` first); log groups are deleted only when the human
sets `CLEANUP_LOGS_PRESERVED=yes` after exporting them; inventories and the target list go to
`build/cleanup/<env>-<sha>/` (a failed before-inventory stops the script; a failed after-inventory
is written as an explicit `UNAVAILABLE` marker). Evidence gate: a confirmed run requires a clean
tree and an evidence archive from the archiving script (`scripts/archive-evidence.sh`) that it
checks as follows: `SHA256SUMS` exists and verifies the archived files, `MISSING.txt` exists and
has no `tests:` entry (no test reports at all), and `release/evidence-location.txt` is an `s3://`
location whose `SHA256SUMS` object exists, or `local-only` together with the explicit
`CLEANUP_LOCAL_EVIDENCE_ACK=yes`. The script verifies integrity and location only; whether the
evidence is sufficient is the human's judgment. Before it:

- Empty the buckets you intend to lose, deliberately (buckets are `force_destroy = false`).
- The vector indexes, DynamoDB tables and secret hold data; the secret has a 7-day recovery window.
- `protect_stateful` defaults to `true`. Cleanup requires a reviewed apply with
  `protect_stateful=false` first (removes Neptune, DynamoDB and ALB deletion protection and lets
  Neptune skip its final snapshot); only then does the destroy plan succeed.
- `cleanup.sh` refuses to run unless stdin and stdout are TTYs. The dry run saves the destroy plan
  and prints its sha256; the confirmed run needs `CONFIRM_CLEANUP=<env>-<account-id>` and
  `APPROVED_DESTROY_SHA256=<sha256>` and executes only that saved plan, with no re-plan.
- Region for every live script: `--region`, else `AWS_REGION`, `AWS_DEFAULT_REGION`, or the Region
  configured for the profile. There is no built-in default; the script fails if none resolves.
- After cleanup list the account for leftovers: ECR images, CloudWatch log groups, the KMS key
  (30-day deletion window), Neptune snapshots, S3 object versions.
