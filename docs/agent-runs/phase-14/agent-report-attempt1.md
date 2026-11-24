STATUS: FAILED (deploy.sh exited 1 at the task-definition/service step). Routing: BLOCKED-ON human architect, with infra-engineer consulted.
OWNER: deploy-engineer
WORK ORDER: Phase 14b. Deploy commit a75b607 with the approved plan checksum to dev, then run smoke-test.

FILES CREATED: none. I did not write reports/deployments/..., per the orchestrator note.
FILES MODIFIED: none. The tree was clean before the run. I did not re-check it afterward because git status was not re-run.

COMMANDS RUN:
1. git status --porcelain. Output was empty, so the tree is clean.
2. git rev-parse HEAD returned a75b60702eba9f5fd8c64f01cade789db36ef60f.
3. sha256sum /tmp/scholarchain-plans/dev-a75b607.tfplan returned 5c1d4d1e7a8e8de3c724ac2b2d214d7850683aaf7d5ed5aee771d5cab22f6089. This matches the approved value.
4. echo of SCHOLARCHAIN_APPROVED_ENVS showed it is already "dev" in the human's environment.
5. aws sts get-caller-identity via the MCP tool failed because the profile scholarchain-readonly does not exist. Via Bash it returned account <ACCOUNT_ID>.
6. APPROVED_PLAN_SHA256=5c1d4d1e... scripts/deploy.sh --env dev. I did NOT set SCHOLARCHAIN_APPROVED_ENVS inline, as the work order's command did. I must not set that variable, and it was already "dev" in the environment.
7. aws ecs list-services --cluster scholarchain-dev --region us-east-2 (read-only).
8. aws ecs list-clusters --region us-east-2 (read-only).

COMMIT SHA / PLAN CHECKSUM / ACCOUNT / REGION / ENVIRONMENT:
a75b60702eba9f5fd8c64f01cade789db36ef60f / 5c1d4d1e7a8e8de3c724ac2b2d214d7850683aaf7d5ed5aee771d5cab22f6089 (verified) / <ACCOUNT_ID> (verified via STS) / us-east-2 / dev

DEPLOYED IMAGE DIGESTS AND TASK REVISIONS:
- query-api was pushed to <ACCOUNT_ID>.dkr.ecr.us-east-2.amazonaws.com/scholarchain/dev/query-api:a75b607. The digest is sha256:b6b2eaa7d01cbbd0a16ddb0d33f307231aedf60b580b21db0b903dacb81e4fec.
- The embedding-worker image built successfully (the build output was visible). I did not see its push or digest, because I only saw the tail of the output.
- Any other images that deploy.sh builds are unverified.
- No task definition revision was registered or verified. No service was updated. Nothing is running at this commit.

TEST RESULTS: smoke-test.sh was NOT run. There is no service to test, and a run would not give a meaningful result.

VALIDATION RESULTS:
- deploy.sh printed "ERROR: service query-api not found in scholarchain-dev" and exited with code 1.
- The ECS cluster scholarchain-dev exists. aws ecs list-services returned an empty list, so the cluster has no services.
- Health, readiness and alarms were not assessed, because there are no running services.

ROLLBACK ACTION: None. No service was changed, so there was nothing to roll back to. scripts/rollback.sh was not run. No data was touched.

ASSUMPTIONS:
- The ECS services were expected to be created by the Terraform apply (Phase 14a). Their absence suggests the apply has not been run, or was only partial, or the services are defined elsewhere. I cannot tell which from read-only checks.
- I used the pre-set SCHOLARCHAIN_APPROVED_ENVS=dev instead of the inline form in the work order.

RISKS:
- ECR now holds the immutable tag a75b607 for query-api, and possibly for other images. A re-run of deploy.sh may fail on the push of an existing immutable tag. I have not checked how deploy.sh handles this.
- The deploy was partial, since the images were pushed and the services do not exist. Docker credentials were stored unencrypted in /home/olmos/.docker/config.json, as shown by a docker warning.
- The aws-api MCP read-only profile scholarchain-readonly is missing. MCP-based verification is unavailable until the human configures it. Bash read-only AWS calls do work.

MANUAL ACTION REQUIRED:
- The human architect should confirm whether the Terraform apply (Phase 14a) was run for this plan, and whether it created the ECS services (query-api and the others). If it did not, the human applies it. I cannot.
- If the apply was done and the services are still missing, route to infra-engineer. The cause could be a plan or Terraform defect, or a service-name mismatch between deploy.sh and Terraform.
- After that, the human re-approves the work order and I re-run deploy.sh and smoke-test.sh.
- Optionally, fix the scholarchain-readonly AWS profile for the MCP server.

BLOCKED-ON OWNER: human architect (Terraform apply status). infra-engineer, if the services were applied but are not found, or if deploy.sh handling of re-pushed immutable tags needs a fix.

BLOCKED-ON EVIDENCE:
- deploy.sh output: "ERROR: service query-api not found in scholarchain-dev", exit 1.
- aws ecs list-services --cluster scholarchain-dev --region us-east-2 returned {"serviceArns": []}.
- aws ecs list-clusters returned only arn:aws:ecs:us-east-2:<ACCOUNT_ID>:cluster/scholarchain-dev.

NEXT GATE: human decision on the Terraform apply state (Phase 14a). Then a re-approved deploy work order. Human acceptance of the deployment report is not yet appropriate, because the deployment did not complete.

Note: the aws-api MCP server is deprecated. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
