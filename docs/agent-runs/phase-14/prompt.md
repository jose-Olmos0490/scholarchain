Use the deploy-engineer agent to deploy the exact approved ScholarChain commit and Terraform plan to the approved course environment.

Approved inputs:
- Commit SHA: a75b60702eba9f5fd8c64f01cade789db36ef60f
- Plan file: /tmp/scholarchain-plans/dev-a75b607.tfplan
- Plan SHA-256: f6257dc8d7b4349c2a8e9a42a95c61af9bfbd414fd306b473567f949c859f07b
- AWS account: <ACCOUNT_ID>
- Region: us-east-2
- Environment: dev
- Exact command: APPROVED_PLAN_SHA256=f6257dc8d7b4349c2a8e9a42a95c61af9bfbd414fd306b473567f949c859f07b SCHOLARCHAIN_APPROVED_ENVS=dev scripts/deploy.sh --env dev
- Then: scripts/smoke-test.sh --env dev

Requirements:
- Refuse a dirty working tree.
- Record commit SHA, plan checksum, target account, Region, and environment.
- Execute only approved source-controlled deployment scripts (scripts/deploy.sh, scripts/smoke-test.sh, scripts/rollback.sh).
- Do not edit Terraform, scripts, or application source. Do not write any file inside the repository.
- Wait for service stabilization with a bounded timeout.
- Verify deployed image digests and task revisions.
- Run smoke-test.sh.
- On failure, collect evidence and follow the approved rollback policy (scripts/rollback.sh --env dev --to-sha <previous sha>).
- Do not perform destructive data repair.
- Return a deployment report with outputs, health status, smoke results, revisions, alarms, and any rollback action, using the fields STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
