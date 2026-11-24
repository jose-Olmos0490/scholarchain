STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Repair two Terraform defects from the Phase 14 apply: (1) service discovery replacement loop, (2) the DynamoDB throttles dashboard widget. No apply or destroy.
FILES CREATED: none
FILES MODIFIED:
- /home/olmos/scholarchain/infra/terraform/modules/compute/main.tf
- /home/olmos/scholarchain/infra/terraform/modules/observability/main.tf
Both edits are in my territory.
Fix 1 (compute): I kept `health_check_custom_config {}` and added `lifecycle { ignore_changes = [health_check_custom_config] }` to aws_service_discovery_service. I did not remove the block. Removing it would also have been a diff against state. With the ignore, the existing services should not be replaced. I did not confirm this with a plan.
Fix 2 (observability): `local.ddb_throttle_metrics` used `flatten([...])`, which flattens recursively into one flat list. It is now `concat([for ... : [[...],[...]]]...)`. Each metric is its own array: [namespace, metric, "TableName", name, {options}].
COMMANDS RUN:
- A Python in-place string edit of the two files.
- `terraform fmt -check -recursive` on /home/olmos/scholarchain/infra/terraform.
- `terraform validate` in /home/olmos/scholarchain/infra/terraform/environments/dev.
- `git diff --stat`, which showed 2 files changed, 8 insertions and 2 deletions.
TEST RESULTS: Not applicable. There are no unit tests for Terraform here.
VALIDATION RESULTS:
- fmt: pass (exit 0).
- validate: "Success! The configuration is valid."
- plan: NOT RUN. A plan needs remote state and credentials, so I skipped it.
- bash -n and shellcheck: not applicable, since no scripts changed.
- security scan: not run. The diff has no IAM, encryption or network changes.
ASSUMPTIONS:
- The empty block differs from the state AWS stores (a computed failure_threshold), which forced replacement. ignore_changes suppresses that diff for existing services, and new services still get the block.
- I did not inspect the dashboard JSON output. The shape follows from the concat expression.
RISKS:
- The service discovery fix is not confirmed by a plan. If the plan still shows replacement, tell me the exact diff.
- The dashboard will show as an in-place update (JSON body change), which is expected.
- Plan checksum: none, because no plan was produced.
MANUAL ACTION REQUIRED: The human runs `scripts/plan.sh` or `terraform plan` in infra/terraform/environments/dev. They should check that aws_service_discovery_service shows no replacement and that the dashboard shows an in-place change. Only then the human-only apply.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer, then human plan sign-off.
