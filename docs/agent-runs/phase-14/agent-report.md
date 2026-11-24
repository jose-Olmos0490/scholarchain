STATUS: FAILED (rollback not executed, BLOCKED-ON human and infra-engineer)
OWNER: deploy-engineer
WORK ORDER: Phase 14b. Deploy commit a75b607 and approved plan to dev, then run smoke test. Report written inline only. The work order forbids writing files in the repo, so I wrote no reports/deployments file.

FILES CREATED: none
FILES MODIFIED: none

COMMIT SHA / PLAN CHECKSUM / ACCOUNT / REGION / ENVIRONMENT:
- Commit: a75b60702eba9f5fd8c64f01cade789db36ef60f (git rev-parse HEAD matches)
- Tree: clean (`git status --porcelain` was empty)
- Plan SHA-256: f6257dc8d7b4349c2a8e9a42a95c61af9bfbd414fd306b473567f949c859f07b. It matches the work order.
- Account: <ACCOUNT_ID>. This is taken from the deploy output ARNs. `sts get-caller-identity` through the MCP failed because the profile `scholarchain-readonly` does not exist.
- Region: us-east-2
- Environment: dev. The session's SCHOLARCHAIN_APPROVED_ENVS is `dev`.

COMMANDS RUN:
1. git status --porcelain, git rev-parse HEAD and sha256sum on the plan. All passed.
2. The exact work-order command, with the inline `SCHOLARCHAIN_APPROVED_ENVS=dev` prefix. The guard BLOCKED it because inline setting of that variable is forbidden.
3. `APPROVED_PLAN_SHA256=f6257dc8... scripts/deploy.sh --env dev`. This is the same command without the prefix. The variable was already `dev` in the session. Exit was non-zero.
4. Read-only AWS CLI calls: ecs list-clusters, list-services, describe-services, describe-task-definition, ecr describe-images, cloudwatch describe-alarms.
5. `scripts/smoke-test.sh --env dev`. It exited immediately with `SCHOLARCHAIN_API_TOKEN is required`.
6. I tried `scripts/rollback.sh`, but only as a read-only look at its contents. The guard blocked it because the command had no `--env`. I did not run rollback.

DEPLOYED IMAGE DIGESTS AND TASK REVISIONS (ECR tag a75b607):
- graph-evidence-api: sha256:09e0130929b997c11ab3438688e712ac7d64e1d4ba4c501487f7ad922073dfd7, pushed 07:58. New task definition revision :2. Service state is rollout FAILED. Running count is 0 of 1.
- query-api: sha256:b6b2eaa7d01cbbd0a16ddb0d33f307231aedf60b580b21db0b903dacb81e4fec, ECR push at 07:23. The service is on task definition :1 with rollout IN_PROGRESS. The reason given is "circuit breaker: rolling back". Running count is 0 of 1. The deploy output said "query-api is stable", but the live state shows many failed task starts after that. I can't reconcile the two.
- embedding-worker: sha256:703b287d897ec6cb330df4948150b4f884a418334644084f16cf78a8deca1281. Task definition :2. Rollout COMPLETED, but desired count is 0, so no tasks run.
- Task definition :1 for graph-evidence-api and query-api already references the `:a75b607` tag. There is no earlier image SHA.

TEST RESULTS:
- deploy.sh: failed. "graph-evidence-api did not become stable within 900s".
- ECS events for graph-evidence-api: "CannotPullContainerError ... graph-evidence-api:a75b607: not found". Its task starts had failed before it was visible in ECR. The image is visible in ECR now.
- smoke-test.sh: NOT RUN meaningfully. It aborted because SCHOLARCHAIN_API_TOKEN is not set. I must not supply or create that token. No smoke result was observed, so I make no health claim.

VALIDATION RESULTS:
- Health: graph-evidence-api and query-api have 0 running tasks. Neither is serving.
- Alarms in ALARM: scholarchain-dev-graph-evidence-api-no-healthy-targets and scholarchain-dev-query-api-no-healthy-targets. All other scholarchain-dev alarms are OK.
- Observability: module.observability.aws_cloudwatch_dashboard.main failed to apply (widget 5 metrics body). This is the known infra defect routed separately. I did not touch it.

ROLLBACK ACTION: none executed.
- The policy is `scripts/rollback.sh --env dev --to-sha <previous sha>`. This is the first deploy, so there is no previous deployed SHA and no earlier image in ECR. Task definition :1 points at the same a75b607 tag.
- A rollback would target nothing meaningful, and I won't guess a SHA.
- No data was touched and no repair was attempted.

ASSUMPTIONS:
- The inline-env omission in step 3 is equivalent to the approved command. The session already has `dev` approved.
- The graph-evidence-api pull failure is most likely a timing or ordering problem. The task definition and service were registered, or tasks started, before the ECR tag was queryable. I did not confirm this.

RISKS:
- dev has no healthy application services and two no-healthy-targets alarms firing.
- The query-api failure cause is unknown. Its tasks are failing repeatedly, and I haven't seen the stopped-task reason.
- The deploy.sh step ordering may be defective: it moves the service before the image is resolvable, and its stability wait differs from the live state. This is for infra-engineer.

MANUAL ACTION REQUIRED:
- The human should supply SCHOLARCHAIN_API_TOKEN to the session environment if the smoke test is to run. Separately, the aws-api MCP profile `scholarchain-readonly` is missing.
- The human should decide the next step: re-run deploy.sh (the image now exists), or a forced new ECS deployment. A redeploy needs a new work order. Incident-responder should investigate the query-api task failures (stopped reasons and logs).

BLOCKED-ON OWNER: human architect (rollback target and re-deploy decision, smoke token). infra-engineer (deploy.sh ordering and stability check). incident-responder (post-deploy failure diagnosis of query-api).

BLOCKED-ON EVIDENCE: deploy.sh output "graph-evidence-api did not become stable within 900s"; ECS event "CannotPullContainerError ... graph-evidence-api:a75b607: not found"; service rollout states above; smoke-test.sh "SCHOLARCHAIN_API_TOKEN is required"; no prior SHA exists.

NEXT GATE: human decision on the redeploy or rollback path. This is not a success report, so there is nothing to accept as a successful deployment.

Note: the aws-api MCP server is entering end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
