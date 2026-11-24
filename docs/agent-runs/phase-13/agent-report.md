STATUS: COMPLETE for authoring and for the gates I was permitted to run. Three items are BLOCKED-ON, see BLOCKED-ON EVIDENCE: a live plan, the --help of seven scripts, and any execution of the other new scripts.
OWNER: infra-engineer
WORK ORDER: Phase 13, finalize scripts/ automation (no workflows, no commit, no apply)

FILES CREATED (all under /home/olmos/scholarchain)
- scripts/check.sh
- scripts/package.sh
- scripts/run-experiment.sh
- scripts/archive-evidence.sh

FILES MODIFIED
- scripts/lib/common.sh. It already had the uncommitted edit (step marker, print_help, usage_error, utc_now, bucket_name, newest_file, clean-tree tolerance for build/ and evidence/); I kept it. I added `retry` (idempotent calls only) and `validate_region`.
- scripts/plan.sh, scripts/deploy.sh, scripts/rollback.sh, scripts/smoke-test.sh, scripts/run-bounded-pipeline.sh, scripts/cleanup.sh
- infra/terraform/README.md (Cleanup section: new CONFIRM_CLEANUP value and the preservation policy)
- Side effect: `sbt check` touched .claude/.last-test-run (its designed behavior).
- Untracked generated outputs now in the tree: build/plan-summary.json (from the offline plan).
- I did not touch docs/agent-runs/phase-13/.

COMMANDS RUN (observed)
- bash -n on every scripts/*.sh and scripts/lib/common.sh: no syntax errors.
- shellcheck -x -P SCRIPTDIR scripts/*.sh scripts/lib/common.sh: rc=0, no findings. The first attempt had SC2034, SC2015, SC2016, SC2094 and SC1073 findings; I fixed them.
- ./scripts/check.sh --help, ./scripts/package.sh --help, ./scripts/plan.sh --help: each printed usage and the required/optional environment variable names, exit 0.
- ./scripts/check.sh, two full runs:
  - Run 1 (unfiltered): shellcheck gate FAIL, because it also linted the factory hooks under .claude/hooks (not mine, only info and warning findings). Every other gate passed. The sbt output was 3.6 GB of repeated Spark/Hadoop test stack traces.
  - Fix: the shellcheck gate now lints scripts/ only, and sbt output is filtered to [info], [warn], [error] and [success] lines (SC_SBT_FULL_LOG=1 disables the filter).
  - Run 2: CHECK RESULT: PASS, EXIT=0, described below.
- ./scripts/plan.sh --env dev --offline --allow-dirty --alb-ingress-cidr 203.0.113.10/32: EXIT=0.
- aws sts get-caller-identity (read-only): see BLOCKED-ON EVIDENCE.
- aws configure list-profiles: only `cs441`.
- Read-only git status and jq on build/plan-summary.json.
- No deploy, cleanup, rollback, smoke-test, pipeline, experiment or archive script was executed. The guard blocks that for this agent. No apply or destroy was run.

TEST RESULTS (sbt check inside check.sh, run 2, EXIT=0)
- Test counts per sbt project: 695, 212, 140, 242, 312, 75, 66 and 579 (24 ignored), all `failed 0`, "All tests passed". The count lines are not labelled by project in my filtered output.
- `markTestRun: tests passed; touched .claude/.last-test-run`; `[success] Total time: 2224 s`.
- scalafmtCheckAll, scalafmtSbtCheck and Test/compile ran inside `sbt check` (the `check` alias) and succeeded.

VALIDATION RESULTS
- check.sh run 2, full output:
  - File: /tmp/claude-1000/-home-olmos-scholarchain/82e97af7-a345-46cc-9825-12bb82dd2ca4/scratchpad/check2.out, 3078 lines, 226 KB. The summary block below is verbatim.
  - The agent-files gate prints about 420 lines, the sbt gate about 2600, and the contracts gate about 40.
  - Terraform gate: `Reusing version 6.74.0 of hashicorp/aws`, `Success! The configuration is valid.`

```
==> [check.sh] STEP: summary
GATE agent-files: PASS
GATE json: PASS
GATE bash-syntax: PASS
GATE shellcheck: PASS
GATE sbt: PASS
GATE contracts: PASS
GATE schemas: PASS
GATE terraform: PASS
CHECK RESULT: PASS
EXIT=0
```

- fmt: `terraform fmt -check -recursive infra/terraform` PASS inside check.sh. plan.sh also runs it.
- validate: PASS (check.sh uses init -backend=false; plan.sh also validates).
- plan: offline and dirty review plan only (see BLOCKED-ON EVIDENCE for the live plan).
  - Plan: add 196, change 0, destroy 0. The code defaults are enable_emr=false and enable_neptune=false; I did not read the variable defaults in this session, so I did not state them.
  - IAM and policy changes: 29, all additions: 8 aws_iam_role, 8 aws_iam_role_policy, 1 aws_iam_role_policy_attachment, 1 aws_iam_instance_profile, 1 aws_kms_key, 1 aws_kms_alias, 6 aws_s3_bucket_policy, 1 aws_sns_topic_policy, 2 aws_sqs_queue_policy.
  - Stateful resources created: 33, no replacements or destroys. They are 6 S3 buckets, 7 DynamoDB tables, 5 S3 Vectors indexes plus the vector bucket, 3 ECR repositories, 2 SQS queues, 1 SNS topic, 1 KMS key, 1 secret and 6 log groups.
  - Offline plans use a placeholder account of 000000000000 and the owner tag CHANGE_ME, so this plan can never be approved or applied.
- bash -n: PASS. shellcheck: PASS. Security scan: no separate scanner was run (none is configured in this repo); the checks done were shellcheck, a grep-level review for secrets, and no secret values printed.

PLAN CHECKSUM
- Offline dirty review plan only, not approvable: 5f56508f34bfed4b0f5be0ad041067146059aa77aad68ae75cb272c7db380832 (/tmp/scholarchain-plans/dev-1c42d5c.DIRTY.tfplan). There is no approvable checksum yet.

STATEFUL REPLACEMENTS AND IAM CHANGES: see VALIDATION RESULTS above. This is a first-apply plan with no stateful replacements.

ESTIMATED HIGH-COST RESOURCES
- The plan creates 1 NAT gateway and 1 ALB (both appear in the plan output).
- Neptune and EMR are off by default, and the earlier terraform/README says Neptune is gated by `enable_neptune`.
- No cost figure was computed.

PER-SCRIPT COMMAND LINE, ENVIRONMENT VARIABLES, APPROVAL POINTS, EVIDENCE, ROLLBACK
- Every script uses strict mode and `==> [script] STEP: ...` markers. Cloud calls go through aws_call (connect/read timeouts plus an overall `timeout`). Only describe, list, get, s3 cp to the same key, and checks are retried. Starts, pushes, updates and execution starts are never retried. Exit codes: 0 ok, 1 failed or refused, 2 usage, 3 cleanup dry run.
- check.sh
  - Command line: `scripts/check.sh [--skip-sbt] [--skip-terraform] [--skip-shellcheck]`.
  - Environment variables: none required; optional SC_SBT_TIMEOUT, SC_TF_TIMEOUT, SC_SBT_FULL_LOG.
  - Approval point: none.
  - Evidence: the GATE lines.
  - Rollback: none.
  - Behavior: every gate runs; any failure gives exit 1.
- package.sh
  - Command line: `scripts/package.sh [--skip-images] [--skip-jars]`.
  - Environment variables: none required; optional SC_SBT_TIMEOUT, SC_BUILD_DIR.
  - Approval point: none.
  - Behavior: refuses a dirty tree. It runs packageJobs and dockerLocal, tags `scholarchain-<svc>:<short-sha>` only, drops the sbt version tag, and never pushes. It does not tag latest.
  - Evidence: build/package-manifest.json with JAR SHA-256 and sizes, image tags and local image IDs, and the commit.
  - Rollback: none (local images only).
- plan.sh
  - Command line: `scripts/plan.sh --env <env> [--profile P] [--region R] [--alb-ingress-cidr C]... [--offline] [--allow-dirty]`.
  - Environment variables: none required; AWS read credentials unless --offline; optional TF_VAR_aws_account_id, SC_PLAN_DIR, SC_BUILD_DIR.
  - Approval point: human plan review, then the human applies the saved file by hand. The command is printed at the end of the output.
  - Evidence: /tmp/scholarchain-plans/<env>-<sha>.tfplan, .sha256, .plan.log, .summary.txt, and build/plan-summary.json (plan file, SHA-256, commit, account from sts get-caller-identity, region, counts, IAM changes, stateful actions).
  - Rollback: none. Never applies.
- deploy.sh
  - Command line: `APPROVED_PLAN_SHA256=<sha> scripts/deploy.sh --env <env> [--git-sha S] [--profile P] [--region R]`.
  - Environment variables: APPROVED_PLAN_SHA256, SCHOLARCHAIN_APPROVED_ENVS (optional SC_STABLE_TIMEOUT).
  - Approval points: plan sign-off checksum, approved environment, and the /deploy work order.
  - Behavior: refuses a dirty tree. It requires the plan checksum to match the plan file (exit 1 otherwise), and checks account, commit, environment, not dirty, not offline, and not the CHANGE_ME owner tag. Rollout only, with no Terraform apply.
  - Evidence: /tmp/scholarchain-plans/deploy-<env>-<sha>.json and a copy at build/deploy-record-<env>-<sha>.json, with registry image digests and new and previous task-definition ARNs and revision numbers.
  - Rollback behavior: if a service is not stable in time, it exits 1 and names the rollback script with the previous SHA. The previous task definitions are saved in /tmp/scholarchain-plans/previous-<env>-<sha>.txt. Nothing rolls back automatically.
- rollback.sh
  - Command line: `scripts/rollback.sh --env <env> --to-sha <sha>`.
  - Environment variables: SCHOLARCHAIN_APPROVED_ENVS.
  - Behavior: it finds the ACTIVE task definition whose image ends in `:<sha>` for all three services, changes nothing if any is missing, then moves the services and waits for stability.
- smoke-test.sh
  - Command line: `scripts/smoke-test.sh --env <env> [--base-url U] [--window YYYY-YYYY]`.
  - Environment variables: SCHOLARCHAIN_API_TOKEN, SMOKE_RESEARCHER_ID, SCHOLARCHAIN_APPROVED_ENVS.
  - Checks: /healthz, /readyz, one bounded query, an evidence fetch for a returned evidence ID, a repeat POST with the same Idempotency-Key, and the error paths (401 without a token, 422 for an unsupported operation).
  - Evidence: stdout PASS/FAIL lines and build/smoke-test-<env>.txt.
  - I added a `require_approved_env` check.
- run-bounded-pipeline.sh
  - Command line: `scripts/run-bounded-pipeline.sh --local [--name N] [--stages hadoop[,spark]]` or `--env <env> [--git-sha S] [--wait-timeout SEC]`.
  - Environment variables: none for --local; cloud needs SCHOLARCHAIN_APPROVED_ENVS (optional SC_ARTIFACTS_BUCKET, SC_RAW_BUCKET, SC_CURATED_BUCKET).
  - Approval point: EMR cost and bounds.
  - Behavior: the Step Functions state machine runs the Hadoop step then the Spark step. It stops the execution at the wait timeout.
  - Evidence: build/pipeline-run-*.json with dataset snapshots (from datasets/dataset-manifest.yaml), commit, execution ARN and name, configuration, output S3 prefixes, start and end times, and status.
- run-experiment.sh
  - Command line: `scripts/run-experiment.sh --env <env> --manifest experiments/<...>.yaml`.
  - Environment variables: SCHOLARCHAIN_APPROVED_ENVS (a load-test runner may need its own).
  - Behavior: the manifest is resolved with realpath -e, so symlinks and `..` are followed, and it must lie under experiments/ and be tracked by Git. A refused manifest exits 1. Runners are only the bounded pipeline script or a tracked executable under load-tests/. The run has a hard timeout of max_duration_seconds.
  - Evidence: build/experiment-<name>-<sha>-<ts>.json with commit, resource limits, start and end UTC, region, runner exit code, raw-result directory and metric locations.
- archive-evidence.sh
  - Command line: `scripts/archive-evidence.sh --env <env> [--git-sha S] [--hours N] [--metric ns:metric:Dim=v]...`.
  - Environment variables: optional EVIDENCE_S3_PREFIX; AWS read credentials (and write access to the prefix when EVIDENCE_S3_PREFIX is set); SCHOLARCHAIN_APPROVED_ENVS when AWS is used.
  - Behavior: gathers test reports, the plan summary and checksum, package manifest, deploy records (image digests), run records, experiment outputs, CloudWatch exports and release metadata into evidence/<sha>/ or the S3 prefix, plus SHA256SUMS. Missing items go to MISSING.txt. An existing non-empty archive is refused (immutable). The plan file itself is never copied.
  - Rollback: none; nothing is deleted or overwritten.
- cleanup.sh (human-only; the TTY check still stops agents)
  - Command line: `CONFIRM_CLEANUP=<env> APPROVED_DESTROY_SHA256=<sha> [CLEANUP_LOGS_PRESERVED=yes] scripts/cleanup.sh --env <env>`.
  - Dry run lists targets and exits 3.
  - Execute needs a clean tree, evidence/<sha>/SHA256SUMS, and CLEANUP_LOGS_PRESERVED=yes if log groups are targeted. It saves the state copy to build/cleanup/<env>-<sha>/state-before.json (mode 600) first. It executes only the saved destroy plan and writes inventories to build/cleanup/<env>-<sha>/. It never deletes state, empties buckets or lifts deletion protection.

ASSUMPTIONS
- build/ and evidence/ are the untracked output directories. require_clean_tree ignores untracked files there only.
- Bucket names come from the Terraform naming convention via bucket_name, with SC_*_BUCKET overrides. Cluster and service names (`scholarchain-<env>`) are likewise derived from the convention. The Step Functions and ALB names are derived the same way. Nothing else is hardcoded: no account IDs, endpoints or model IDs.
- The run-experiment.sh manifest schema (name, runner, limits, args, target, metrics) is my own definition. experiments/ and load-tests/ do not exist yet, so experiment-engineer must author manifests to match it.
- python3 with PyYAML is a prerequisite (already used by the dataset validators).

RISKS
- CONSTITUTION CONFLICT, flagged: the work order asked deploy.sh to "apply only that exact saved plan file". docs/agents.md forbids deploy.sh from running Terraform apply, and the guard blocks apply and destroy wrappers. I did not add an apply path. deploy.sh verifies the saved plan's checksum, account, commit and environment, and does the application rollout only. plan.sh prints the exact `terraform ... apply <plan file>` command for the human. A human-only apply script is a possible follow-up if you want one, but it needs your ruling.
- CONFLICT ON CLEANUP: the work order says CONFIRM_CLEANUP=<environment-name>. Phase 12 required <env>-<account-id>. I followed the work order, which is a weaker confirmation. The destroy-plan checksum, the TTY requirement and the saved-plan-only execution remain.
- .gitignore (build-engineer territory) does not list build/ or evidence/, so the generated outputs show as untracked in `git status`. Route this to build-engineer.
- sbt output volume: the Spark/Hadoop tests emit gigabytes of repeated stack traces (3.6 GB in run 1). This is worth routing to the owning module engineers; check.sh now filters it.
- The shellcheck gate covers scripts/ only. The factory hooks under .claude/hooks have info and warning findings; that is for factory-engineer to decide.
- The scripts I could not execute were checked only with bash -n and shellcheck, so the embedded Python and the AWS call sequences are untested at runtime: deploy, rollback, smoke-test, run-bounded-pipeline, run-experiment, archive-evidence, cleanup. check.sh, package.sh (help only) and plan.sh were run.
- The first plan will leave desired count 0 and fire the no-healthy-targets alarm once, as the existing README states.

MANUAL ACTION REQUIRED: human plan review and human-only apply
- Create infra/terraform/environments/dev/terraform.tfvars (owner, alb_ingress_cidrs) and obtain the designated read-only AWS profile.
- Commit the work (tree must be clean), then run `scripts/plan.sh --env dev --profile <readonly>`.
- Verify account, region, cost and checksum, then apply the saved plan file by hand.
- Decide the two conflicts under RISKS.

BLOCKED-ON OWNER
- Live ./scripts/plan.sh, any other script execution, and --help of seven scripts: human architect (credentials, tfvars, clean tree) and the executing agents.
- .gitignore update: build-engineer.
- run-experiment.sh manifests: experiment-engineer.

BLOCKED-ON EVIDENCE
- Live plan:
  - `aws sts get-caller-identity` returned an IAM user ARN `arn:aws:iam::<12-digit account>:user/CS441_Dev`.
  - Only the profile `cs441` is configured. The designated read-only profile named in the project docs does not exist.
  - The default-chain identity is not shown to be read-only, and terraform.tfvars is absent. The tree was also dirty, which plan.sh refuses without --allow-dirty.
  - I therefore did not run a live plan with those credentials, and ran the offline plan above.
- The guard blocked executing the scripts below, including `--help`, for infra-engineer, so I have no observed `--help` output for them. They share print_help, which was observed working on the other three scripts: deploy, rollback, smoke-test, run-bounded-pipeline, run-experiment, archive-evidence, cleanup.

NEXT GATE: code-reviewer and human plan sign-off
