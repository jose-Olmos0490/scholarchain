Use the infra-engineer agent to create or finalize the source-controlled automation under scripts/ for ScholarChain. Phase 12 already created plan.sh, deploy.sh, rollback.sh, smoke-test.sh, run-bounded-pipeline.sh, and cleanup.sh. Keep their existing interfaces, finalize them against the requirements below, and add the missing scripts. Do not create GitHub Actions workflows.

Required scripts:

1. scripts/check.sh
- Run .claude/hooks/validate-agent-files.sh and parse every JSON file under .claude/ and .mcp.json with jq.
- Run bash -n on every script in .claude/hooks/ and scripts/.
- Run sbt scalafmtCheckAll, sbt Test/compile, sbt test (or sbt check if it covers these), contract validation, and schema validation.
- Run terraform fmt -check -recursive infra/terraform, then terraform init -backend=false and terraform validate for each environment.
- Exit nonzero on any failed gate. Needs no AWS credentials.

2. scripts/package.sh
- Build the Hadoop and Spark job JARs (sbt packageJobs) and the service images.
- Tag images with the short Git commit SHA only; never use latest.
- Write SHA-256 checksums for JARs and the image tags to build/package-manifest.json.

3. scripts/plan.sh (finalize)
- Run terraform plan -out for the selected environment.
- Record the plan file, its SHA-256 checksum, the commit SHA, the target account (from aws sts get-caller-identity) and Region, and add/change/destroy counts, IAM changes, and stateful-resource actions taken from terraform show -json. Write all of this to build/plan-summary.json.
- Never apply.

4. scripts/deploy.sh (finalize)
- Refuse a dirty tree.
- Require APPROVED_PLAN_SHA256 to match the checksum of the plan file, or exit nonzero.
- Apply only that exact saved plan file.
- Record deployed image digests and ECS task-definition revisions.

5. scripts/smoke-test.sh (finalize)
- Check /healthz and /readyz, run one bounded researcher query, fetch evidence for one returned claim, repeat a POST with the same Idempotency-Key, and test one expected error path (for example a 400 or 422).

6. scripts/run-bounded-pipeline.sh (finalize)
- Submit the approved bounded Hadoop and then Spark steps.
- Record the dataset snapshot, run IDs, configuration, and output S3 prefixes.

7. scripts/run-experiment.sh
- Accept only a manifest path under experiments/ and refuse anything else.
- Record the commit SHA, resource limits, start and end UTC times, Region, and metric locations.

8. scripts/archive-evidence.sh
- Gather test reports, the plan checksum, image digests, run manifests, selected CloudWatch metric exports, experiment outputs, and release metadata into evidence/<commit-sha>/, or into an S3 prefix when EVIDENCE_S3_PREFIX is set.

9. scripts/cleanup.sh (finalize)
- List the targeted resources first.
- Require CONFIRM_CLEANUP=<environment-name> before any destructive action.
- Preserve Terraform state, logs, and final reports according to the documented cleanup policy.

Requirements for every script:
- Use set -euo pipefail.
- Support --help, which prints usage and the names of the required environment variables.
- Print no secrets.
- Put timeouts on cloud operations, and retry only idempotent actions.
- Print greppable step markers of the form "==> [script] STEP: ...", and use explicit exit codes.
- Work from a clean checkout once the documented prerequisites are installed.
- Contain no hardcoded bucket names, account IDs, endpoints, or model IDs.

Verification (run, do not merely describe):
- bash -n scripts/*.sh
- shellcheck scripts/*.sh if it is installed
- the --help output of every script
- ./scripts/check.sh with the full output and exit code
- ./scripts/plan.sh only if AWS read credentials are available; otherwise report BLOCKED-ON with evidence

Do not run deploy.sh, cleanup.sh, terraform apply, or anything that creates or destroys AWS resources.

Report using the standard contract (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE). Also include the command line for each script, the required environment variables by name, the human approval points, the evidence each script generates, and the rollback behavior.
