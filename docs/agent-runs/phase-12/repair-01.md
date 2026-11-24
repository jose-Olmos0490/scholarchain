# Phase 12 repair-01 — infra-engineer report (saved verbatim by human; agent write correctly blocked by guard: repair-NN.md is human-owned)

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12 repair-01, review findings 1-6 (findings 7-12 and the guard-hook part of 6 deferred, not touched)

FILES CREATED: none
FILES MODIFIED (all under infra/terraform/** and scripts/**):
- infra/terraform/environments/dev/variables.tf (alb_ingress_cidrs required plus validation; new protect_stateful)
- infra/terraform/environments/dev/main.tf (local.deletion_protection = var.protect_stateful)
- infra/terraform/environments/dev/terraform.tfvars.example
- infra/terraform/modules/network/variables.tf (alb_ingress_cidrs: no default plus validation)
- infra/terraform/.checkov.yaml (all skip-check entries removed)
- inline #checkov:skip comments with reasons in modules/{compute,network,security,datalake,logging,pipeline,emr,dynamodb}/main.tf
- infra/terraform/README.md
- scripts/plan.sh, scripts/deploy.sh, scripts/cleanup.sh (cleanup.sh was rewritten with the Write tool)

COMMANDS RUN (output observed):
- terraform fmt -check -recursive
- terraform init -backend=false and terraform validate in environments/dev
- scripts/plan.sh --env dev --offline --allow-dirty --alb-ingress-cidr 203.0.113.10/32
- terraform plan -var ... -var 'alb_ingress_cidrs=...' in environments/dev (four negative cases and one Neptune case)
- bash -n and shellcheck on scripts
- checkov -d infra/terraform, with and without skips
- No apply, destroy or live script was run. cleanup.sh, deploy.sh and the other live scripts were only syntax-checked and shellchecked.
- One guard block: my own python heredoc mentioned "terraform ... apply" as text. I rewrote cleanup.sh with the Write tool and did not bypass the guard. I also tried one recursive delete in the scratchpad, which the guard blocked. I used a fresh directory instead.

TEST RESULTS: no application tests apply (infra and scripts only). The infra checks are below.

VALIDATION RESULTS:
- fmt: terraform fmt -check -recursive passes, no output.
- validate: "Success! The configuration is valid."
- plan: `scripts/plan.sh --env dev --offline --allow-dirty --alb-ingress-cidr 203.0.113.10/32` gives add=196 change=0 destroy=0. The summary line reads `offline=true dirty=true`. The plan file is /tmp/scholarchain-plans/dev-86fbf3a.DIRTY.tfplan. This is the default plan with enable_neptune=false, so it contains no Neptune resources.
- Neptune (separate plan with enable_neptune=true, not saved): 203 to add, 0 to change, 0 to destroy. It shows `deletion_protection = true`, `skip_final_snapshot = false` and `final_snapshot_identifier = "scholarchain-dev-graph-final"`.
- DynamoDB: `deletion_protection_enabled = true` appears on all 7 tables in the saved plan log, and the ALB has `enable_deletion_protection = true`. PITR is unchanged on run_ledger, query_session, evidence_bundle and artifact_registry.
- alb_ingress_cidrs checks (terraform plan in environments/dev):
  - Omitting the variable gives "Error: No value for required variable" on variables.tf line 113.
  - `["0.0.0.0/0"]`, `["::/0"]`, `["203.0.113.10/32","0.0.0.0/0"]` and `[]` each give "Invalid value for variable" on alb_ingress_cidrs.
  - The saved plan above, with 203.0.113.10/32, succeeds.
- bash -n: ok on all 7 scripts, including lib/common.sh.
- shellcheck: `shellcheck -x --source-path=SCRIPTDIR scripts/*.sh scripts/lib/*.sh` is clean. Plain `shellcheck` without -x reports SC1091 info and SC2034 warnings for SC_PROFILE and SC_REGION being unused. These come only from the unfollowed `source`. I did not check whether they predate this repair.
- checkov 3.4.8: .checkov.yaml now has no skip-check.
  - Skips removed: all global skips are gone, including the CKV_AWS_5, CKV_AWS_7 and CKV_AWS_260 entries from the earlier file. Those three now pass on their own.
  - Without any skips (a copy of the tree with no config): 559 passed, 59 failed.
  - With the inline skips: 552 passed, 0 failed, 0 parsing errors, 66 skipped.
  - Remaining inline skips by check (each has a reason in the source, found with `grep -rn checkov:skip infra/terraform/modules`). The 66 skipped count is per resource instance, so the for_each S3 and log-group skips count several times each.
    - Checks checkov cannot evaluate through for_each or module boundaries: CKV_AWS_19, 145, 21, 18, 300 (S3 buckets and lifecycle, verified in the plan JSON), CKV_AWS_28 (DynamoDB PITR, dynamic value), CKV2_AWS_5 (7 security groups, attached across modules or at runtime), CKV2_AWS_7 (EMR).
    - Does not apply: CKV_AWS_144 (single Region), CKV2_AWS_62 (no event consumer), CKV_AWS_284 (no X-Ray), CKV2_AWS_57 (secret rotated by a human).
    - Accepted trade-offs: CKV_AWS_338 (30-day logs, two resources), CKV2_AWS_28 (no WAF), CKV_AWS_378 (HTTP from ALB to task), CKV_AWS_103 (dev-only plaintext listener), CKV2_AWS_20 (redirect listener exists whenever a certificate is set).
    - Key policy: CKV_AWS_109, 111 and 356, skipped inline in the KMS key policy document; the reason is that it is the account-root statement.
  - The accepted DynamoDB trade-off (PITR off on entity_link_cache, graph_read_model and researcher_metric_cache) is in the CKV_AWS_28 skip reason and in the README.
  - CKV_AWS_260 now passes with the restricted ingress.

PLAN CHECKSUM: 205dd3b142a7e37ad1b0d1cf205129284f37e8f23e2e1c190e36cfb33c651713 (dev-86fbf3a.DIRTY.tfplan). It is a dirty, offline plan, so it cannot be approved or deployed.

STATEFUL REPLACEMENTS AND IAM CHANGES: plan is 196 add, 0 change, 0 destroy, with 0 stateful replacements or destroys. It is all new resources, including the IAM roles and policies, the KMS key and alias, and the SQS queue policies. Compared with the earlier plan, the changes are:
- ALB ingress is now limited to 203.0.113.10/32 (placeholder).
- DynamoDB tables and the ALB have deletion protection on in dev.
- Neptune has protection and a final snapshot (visible when enable_neptune=true).

ESTIMATED HIGH-COST RESOURCES: unchanged. They are the NAT gateway (about 33 USD per month), Neptune Serverless at the minimum 1 NCU (about 117 USD per month, 470 at the maximum), the ALB (about 16 USD per month) and Fargate. EMR is off by default. See the README cost table.

CHANGES BY FINDING:
1. alb_ingress_cidrs has no default in the environment and network variables. The validation block requires a non-empty list of valid IPv4 CIDRs and rejects 0.0.0.0/0 and ::/0. `can(cidrnetmask())` means IPv6 CIDRs other than ::/0 also fail, so IPv6 ingress is not supported. tfvars.example uses 203.0.113.10/32 with the comment "set to your own public IP /32". README updated. plan.sh gained a repeatable `--alb-ingress-cidr` option, so an offline plan works without a tfvars file.
2. Inline skips replace the global ones, as listed above.
3. A plan.sh `--allow-dirty` plan is written as `<env>-<sha>.DIRTY.tfplan` and the summary has `dirty=true`. A dirty plan exits 0 with a warning instead of printing the approval hint. deploy.sh now refuses in four cases: a .DIRTY plan file, an approved checksum equal to the sibling DIRTY plan's, a summary containing dirty=true, and a plan whose owner variable is CHANGE_ME.
4. cleanup.sh's dry run saves the destroy plan and a .sha256 file, then prints the checksum. The confirmed run requires CONFIRM_CLEANUP=<env>-<account-id> and APPROVED_DESTROY_SHA256. It verifies the saved file's checksum and applies only that file, with no re-plan.
5. New variable `protect_stateful` (default true, independent of environment) feeds Neptune, DynamoDB and the ALB. README and the cleanup.sh header say cleanup needs a reviewed apply with `protect_stateful=false` first.
6. cleanup.sh dies with an actionable message unless stdin and stdout are both TTYs. The TTY check runs right after argument parsing, before any AWS call. I could not test the confirmed-run path or the TTY refusal in the agent shell.

ASSUMPTIONS:
- protect_stateful also controls ALB deletion protection, because it shares the existing local. The work order named only Neptune and DynamoDB.
- cleanup.sh's destroy plan needs alb_ingress_cidrs from the human's terraform.tfvars, since it has no `--alb-ingress-cidr` option.
- Inline checkov skips on shared for_each resources (S3 buckets, DynamoDB tables, log groups) apply to every instance. The logs bucket's SSE-S3 and suspended versioning, and the three no-PITR tables, are explained in the reasons.

RISKS:
- Checkov's per-instance skip count (66) hides which instance each skip covers.
- The Neptune engine version and the other items listed as unverified in the README are still unverified, because no read-only AWS profile was available.
- A 203.0.113.10/32 placeholder left in a real tfvars would lock out the caller.
- The README's cost, Neptune and engine statements were not re-verified beyond the edits above.

MANUAL ACTION REQUIRED: human plan review and human-only apply. The human must set alb_ingress_cidrs to their own /32 and owner (not CHANGE_ME) in terraform.tfvars, then produce a clean, online plan with plan.sh. No clean plan exists yet.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer and human plan sign-off (the DEFERRED guard-hook part of finding 6 and MINOR findings 7-12 remain open)
