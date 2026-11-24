STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Part 2, read-only review of infra/terraform and scripts/ against the infra-engineer Attempt 2 report, including checks (a) to (e)
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

FINDINGS (severity scale: CRITICAL / MAJOR / MINOR):
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | MAJOR | infra/terraform/environments/dev/variables.tf:113-117; modules/network/variables.tf:29-32; modules/network/main.tf:225-243; .checkov.yaml:33 | A tfvars file that omits `alb_ingress_cidrs`. The default is `["0.0.0.0/0"]`, the ALB is internet-facing (modules/compute/main.tf:116, `internal = false`), and ports 80 and 443 open to the world. | The query API is exposed to the whole internet by default. Checkov CKV_AWS_260 is skipped as "port 80 from the allowed CIDR". That reason is false when the default applies, so the skip hides a real exposure (check a). With `allow_plaintext_http=true`, bearer tokens also cross the internet in cleartext. | infra-engineer |
| 2 | MAJOR | .checkov.yaml:15-22, 31-32 | The skips are global, not per resource. CKV_AWS_109, 111 and 356 are justified only by the KMS key policy (modules/security/main.tf:12-13), but they switch off wildcard-resource and permissions-management checks for every IAM policy. The same applies to CKV_AWS_19, 145, 21, 18, 300 and 28 for every bucket and table. | A later regression gets through the scan unnoticed. Examples: a `*` resource in a task role, or an unencrypted, unversioned or PITR-less bucket or table. The report's "0 failed / 0 skipped" is misleading. CKV_AWS_28 is not purely a false positive: entity_link_cache, graph_read_model and researcher_metric_cache have `pitr=false` (modules/dynamodb/main.tf:19-57). That is an accepted trade-off, not a checkov error. This answers check (a). Skips for 144, 62, 284, 57, 338, 378, 103, 20 and 28 (WAF) are justified. The skips for 5, 7, 19, 145, 21, 18, 300, 28, 109, 111, 356 and 260 are not acceptable as global skips. | infra-engineer |
| 3 | MAJOR | scripts/plan.sh:59-63, 86; scripts/deploy.sh:57-72 | `plan.sh --env dev --allow-dirty`, run online with real credentials, writes `<env>-<sha>.tfplan`. That is the same file name a clean plan would have. The offline and owner-tag checks are tolerated under `--allow-dirty` (plan.sh:204-205). deploy.sh rejects only `offline_plan`. It cannot tell that the plan came from a dirty tree. | A plan built from uncommitted content, with a CHANGE_ME owner tag, can be checksummed, applied and "approved" against a commit it does not represent. This weakens check (c): deploy.sh has no dirty flag of its own, but the plan-to-commit binding can be defeated through plan.sh. | infra-engineer |
| 4 | MAJOR | scripts/cleanup.sh:94-101 | The dry run and the confirmed run are separate invocations. The confirmed run re-plans and overwrites `$DESTROY_PLAN` (lines 68-69), then applies it at once with no pause. | The human confirms against a destroy list from an earlier run, but the plan that executes is newly generated. Resources added in between are destroyed unreviewed. Check (b) result: cleanup.sh destroys only when CONFIRM_CLEANUP equals `<env>-<account-id>` exactly (line 94), and it also requires an approved env (line 41). | infra-engineer |
| 5 | MAJOR | infra/terraform/environments/dev/main.tf:3; modules/dynamodb/main.tf:68; modules/neptune/main.tf:104-106 | `deletion_protection = (environment != "dev")`, and Neptune `skip_final_snapshot = !deletion_protection`. In dev, a destroy plan or a replacement takes the PITR audit tables (run_ledger, query_session, evidence_bundle, artifact_registry) and Neptune with no deletion protection and no final snapshot. | A destroy or replacement in dev permanently loses the answer-audit trail and the graph. The guard treats this as data destruction. The only fences are human-only cleanup.sh and the `force_destroy=false` S3 buckets, so the tables and Neptune are unfenced. | infra-engineer |
| 6 | MAJOR | scripts/cleanup.sh (whole script); .claude/hooks/guard-dangerous.sh:629-646 | Check (b), "never runs from an agent context". The script has no agent-context or TTY check. Agent exclusion rests entirely on the guard hook, which blocks execution by script name. `CONFIRM_CLEANUP` is an ordinary environment variable. | If the hook is bypassed or misses a wrapper such as `bash -c`, a symlink, or `env CONFIRM_CLEANUP=... bash scripts/cleanup.sh`, the script runs a real `terraform apply` of a destroy plan. This contradicts the report's "no apply/destroy wrapper" posture. A human-ratified exception is needed, plus a script-level interactive check, for example refusing to run when stdin is not a TTY. The guard probe for these wrappers was not run here. | infra-engineer, with factory-engineer for the guard |
| 7 | MINOR | modules/iam/main.tf:596-609 | The state-machine statement grants `logs:PutResourcePolicy`, `logs:DescribeResourcePolicies`, `logs:DescribeLogGroups` and the log-delivery actions on `*`. The report lists only "log-delivery actions on *". | `PutResourcePolicy` on `*` can rewrite account-level CloudWatch Logs resource policies. It is the AWS-documented Step Functions pattern and is created only when EMR is enabled. It goes beyond what the report discloses for check (d). | infra-engineer |
| 8 | MINOR | modules/iam/main.tf:696-706 | `ecs:ListTasks` and `ecs:DescribeTasks` on `*`, narrowed by an `ecs:cluster` condition. This is not in the report's wildcard list. | Low impact, because the condition is correct and it is read-only. The wildcard list in check (d) is still incomplete. | infra-engineer |
| 9 | MINOR | scripts/lib/common.sh:10; scripts/plan.sh:84-86 | `SC_PLAN_DIR` defaults to `/tmp/scholarchain-plans` and can be overridden from the environment. The directory is created with `mkdir -p` and `chmod 700`, but nothing checks its ownership or that it is not a symlink. | On a shared host, another user could pre-create the directory or swap the plan file. deploy.sh's checksum check would still catch a tampered plan, but only if `APPROVED_PLAN_SHA256` came from a trusted source. | infra-engineer |
| 10 | MINOR | scripts/deploy.sh:78-80 | The script checks only that the ECS cluster is ACTIVE. It does not check that the applied state matches the approved plan. | A cluster created from a different plan passes. This is acceptable under the no-apply rule, but "plan is evidence only" is weaker than the documentation suggests. | infra-engineer |
| 11 | MINOR | infra/terraform/environments/dev/main.tf:227 and the report's mismatch 3 | `NEPTUNE_LOADER_ROLE_ARN` is bound in env-overrides.conf but unused. The loader role, its security group and its IAM are unused until a loader entrypoint exists. | An unused IAM role and security group stay provisioned: `iam:PassRole` to `rds.amazonaws.com`, a Neptune loader role, and DynamoDB write access on all tables. This is a least-privilege surplus until the entrypoint exists. The report's mismatches 1, 3 and 8 are valid and route to storage-engineer, rag-engineer and service-engineer, not infra. | storage-engineer / service-engineer (design), infra-engineer (remove or gate the role) |
| 12 | MINOR | Cost: modules/neptune (min 1 NCU), modules/network (single NAT) | Defaults leave Neptune at 1 NCU continuously, with ALB and NAT adding to it. | The report's estimate of about 230 USD/month with Neptune against a 250 USD budget leaves about 8% headroom. A budget alert exists, but there is no hard cap. The report calls these prices "unverified". Capacity is otherwise bounded: Neptune max 4 NCU, Fargate max 3+2+2, EMR a 4-node ceiling with 1 h idle termination. | infra-engineer |

CHECK RESULTS (a) to (e):
(a) NOT satisfied. Two skips hide real exposures or controls: CKV_AWS_260 (world-open ALB default, finding 1) and the global scope of the IAM and storage skips (finding 2). The remaining skips are justified.
(b) PARTIAL. The exact `CONFIRM_CLEANUP=<env>-<account-id>` value is required and the env must be approved. The script has no agent-context defense of its own, and the confirmed run applies a freshly generated plan (findings 4 and 6).
(c) PARTIAL. deploy.sh has no `--allow-dirty` path. It calls `require_clean_tree` (line 49) and `: "${APPROVED_PLAN_SHA256:?...}"` (line 45), and it checks the checksum, account, commit, environment and `offline_plan` (lines 57-72). The weakness is upstream: plan.sh `--allow-dirty` can produce an approvable plan (finding 3).
(d) PARTIAL. Wildcards I found: ecr:GetAuthorizationToken, ecs:RegisterTaskDefinition and DescribeTaskDefinition, log-delivery actions, `kms:*` in the key policy (account root only, which is the standard statement), and the S3 and SQS `Deny` statements for non-TLS (deny-only, fine). Beyond the report's list: `logs:PutResourcePolicy` and `logs:DescribeLogGroups` on `*` (finding 7), and `ecs:ListTasks` and `ecs:DescribeTasks` on `*` (finding 8). I did not read every iam statement, including the EMR instance and Neptune loader policies.
(e) PASS for what I checked. These match the code:
- DynamoDB tables: pk/sk string keys, no GSI or LSI, names `<prefix>-<env>-<suffix>`, TTL attribute `expiresAt` on query_session only.
- Env vars: SCHOLARCHAIN_DDB_TABLE_*, VECTOR_INDEX_*, SCHOLARCHAIN_VECTOR_INDEX_<NAME>_{DIMENSION,DISTANCE_METRIC,TYPE}, VECTOR_BUCKET, NEPTUNE_*, EMBEDDING_MODEL_*, SYNTHESIS_MODEL_ID, SCHOLARCHAIN_SVC_{KEY_SECRET_NAME,RUN_ID,EMBEDDING_QUEUE_URL,EMBEDDING_DLQ_URL}.
Not verified by me: the S3 Vectors non-filterable key list and index names against the storage module, and the SCHOLARCHAIN_SVC_SNAPSHOT binding.

SECRETS AND EXPOSURE:
- Secrets: no secret values found in source. The Secrets Manager secret is declared by name only (modules/security/main.tf:79-84), with a recovery window.
- Network: Neptune is `publicly_accessible=false`, encrypted and IAM-authenticated. Fargate has `assign_public_ip=false`. S3 buckets block public access (datalake/main.tf:37-38 and presumably the rest). ECR tags are IMMUTABLE and `force_delete=false`. The provider sets `allowed_account_ids`, but it is disabled by `offline_plan=true`. deploy.sh refuses offline plans.
- Only the ALB is deliberately public (finding 1). I did not inspect the TLS listener or the plaintext-HTTP listener.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: yes for all 12. Not independently verified: the report's plan counts, the checkov counts, the fmt and validate output, and the AWS service claims (Neptune 1.4.0.0, EMR label, S3 Vectors index mode).

ASSUMPTIONS:
- cleanup.sh and the other scripts are executed only by humans, as documented.
- `.claude/hooks/guard-dangerous.sh:629-646` blocks `scripts/cleanup.sh` execution by name for agents. I read only those lines and did not run the guard probes.

RISKS:
- The ALB defaults to open to the internet.
- Checkov's clean result depends on global skips.
- The dev environment has no stateful-resource deletion safeguards beyond a human typing the confirmation.
- Services will not be ready without Neptune, and the plaintext HTTP option exists.

MANUAL ACTION REQUIRED: human decision on findings 1 and 5 (the exposure default and dev deletion posture), and on a script-level TTY guard for finding 6. A human must also set a real `alb_ingress_cidrs` before any plan is approved.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: infra-engineer repairs findings 1 to 6 (7 to 12 are advisory), then re-review. Human plan sign-off follows.

## Re-review after repair-01

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only re-review of Phase 12 repair-01, findings 1-6 only. Findings 7-12 and the guard-hook part of F6 are out of scope.
VERDICT: APPROVE

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

NOTE: docs/agent-runs/phase-12/repair-01.md does not exist. The phase-12 directory holds only prompt.md, agent-report.md and review-report.md. I reviewed the changed files against the human decisions in the work order. I could not read the repair's own claims, and I could not run checkov, terraform or shellcheck.

PER-FINDING RESULT:
| # | Result | Evidence |
|---|---|---|
| F1 ALB ingress | FIXED | infra/terraform/environments/dev/variables.tf:113-121 and modules/network/variables.tf:29-37. Both variables have no default. Both validations require a non-empty list where every entry passes cidrnetmask() and is not "0.0.0.0/0" or "::/0". environments/dev/main.tf:43 passes the value through. modules/network/main.tf:230-248 builds the ingress rules from it. terraform.tfvars.example:25-26 has 203.0.113.10/32 with the comment "set to your own public IP /32". README.md:81 documents it. plan.sh:16-17 and :104-107 accept --alb-ingress-cidr. |
| F5 protect_stateful | FIXED | dev/variables.tf:123-127 (bool, default true, no environment dependence). dev/main.tf:3 sets local.deletion_protection = var.protect_stateful, and it feeds the dynamodb (:60), neptune (:101) and compute (:325) modules. neptune/main.tf:104-106 sets deletion_protection, skip_final_snapshot = !dp, and final_snapshot_identifier when dp. dynamodb/main.tf:69 sets deletion_protection_enabled on the single for_each resource, so it covers every table. PITR is true on run_ledger, query_session, evidence_bundle and artifact_registry (dynamodb/main.tf:15-42). The ALB also gets enable_deletion_protection (compute/main.tf:122). README.md:80 and :170-172 and cleanup.sh:16-21 and :126 document the reviewed apply with protect_stateful=false first. |
| F6 cleanup TTY (script part) | FIXED | cleanup.sh:44-45 is `[[ -t 0 && -t 1 ]] \|\| die "..."`, with an actionable message. It runs after argument parsing and before any AWS or terraform call. |
| F2 checkov | FIXED, with a caveat | infra/terraform/.checkov.yaml has no skip-check. Skips are inline per resource, each with a reason (for example compute/main.tf:114-115, :133 and :196; datalake/main.tf:27-32 and :85; dynamodb/main.tf:62; security/main.tf:7-9 and :83). I spot-checked the "checkov cannot follow for_each" claims. They hold: datalake/main.tf:59-82 shows SSE, versioning and the lifecycle abort rule on every bucket, and the logs bucket is intentionally SSE-S3 and Suspended. README.md:152-161 reports 559 passed / 59 failed without skips and 552 passed / 0 failed / 66 skipped with skips. I could not re-run checkov, so I could not confirm these counts. |
| F3 dirty plans | FIXED | plan.sh:92-94 renames the plan to *.DIRTY.tfplan, so it cannot overwrite a clean plan. plan.sh:137-182 writes dirty=<bool> into the summary. deploy.sh:49 requires a clean tree. :64 refuses *.DIRTY.tfplan. :65-68 refuses an approved sha256 that matches the DIRTY file. :70-72 refuses dirty=true in the summary. :82-83 refuses an owner tag of CHANGE_ME. plan_var fails closed: a missing owner raises KeyError and the script exits under set -e. plan.sh:215-221 also rejects CHANGE_ME for a non-offline, non-dirty plan. |
| F4 cleanup destroy plan | FIXED | The dry run (cleanup.sh:89-97) saves the destroy plan and writes a .sha256 file. It then lists targets and exits 3 (:121-128). The confirmed run (:80-88) needs CONFIRM_CLEANUP == "<env>-<account>" (:74). It then enforces APPROVED_DESTROY_SHA256 through the ${VAR:?} check at :82. It requires 64 lowercase hex characters (:83), requires the saved plan file (:84) and compares the checksum (:85-87). The only apply is at :131 and applies the saved file with no re-plan. A confirmed run never reaches the plan branch. Without an exact CONFIRM_CLEANUP the script takes the plan-only branch. I found no way to reach the apply without both variables. |

NEW ISSUES INTRODUCED (no CRITICAL or MAJOR found):
| # | Severity | File:line | Concrete input | Impact | Owner |
|---|---|---|---|---|---|
| N1 | MINOR | dev/variables.tf:118 and modules/network/variables.tf:34 | alb_ingress_cidrs = ["0.0.0.0/1","128.0.0.0/1"] passes validation. | The check matches only the literal 0.0.0.0/0 and ::/0, so near-open ranges get through. This meets the human decision as written. Consider a minimum prefix length (for example /16) or a split-range check. IPv6 is rejected anyway because cidrnetmask is IPv4-only. | infra-engineer |
| N2 | MINOR | deploy.sh:64-72 | A human copies a *.DIRTY.tfplan over the clean plan name. | The sha256 and the git_sha variable would match, and the clean-name summary would not say dirty=true. This needs deliberate human tampering with /tmp files. Optionally record dirty in the plan variables or embed a marker. | infra-engineer |
| N3 | MINOR | cleanup.sh:61-62 and :74-87 | The destroy plan is named by HEAD sha only, and the script does not require a clean tree. | A plan made on a dirty tree is accepted for the confirmed run. The human-supplied sha256 still binds the exact file, and terraform rejects a stale plan against changed state. | infra-engineer |
| N4 | MINOR | dynamodb/main.tf:62 | The CKV_AWS_28 skip applies to the whole table resource. | If a PITR-needed table later gets pitr=false in the local map, checkov will no longer flag it. This is a regression-detection gap, not a current exposure. | infra-engineer |

VALIDATION RESULTS: All findings were verified against the files by reading them: yes, for every row above. No tests, terraform, shellcheck or checkov were run, and the 552/0/66 and 559/59 counts are unverified.

ASSUMPTIONS: The orchestrator-supplied human decisions are the acceptance criteria. The human, not an agent, runs cleanup.sh and applies any Terraform.

RISKS:
- repair-01.md is missing, so the repair's own claims are unchecked.
- The checkov counts are unverified.
- With protect_stateful=false, Neptune is destroyed without a final snapshot. This is by design and documented in README.md:170-172.

MANUAL ACTION REQUIRED: The human may want to re-run `checkov -d infra/terraform`, `terraform fmt -check -recursive` and `shellcheck scripts/*.sh` to confirm the reported counts. The guard-hook part of F6 remains deferred, as the human decided.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 12. The MINOR items N1-N4 are optional follow-ups.
