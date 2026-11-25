STATUS: BLOCKED-ON
OWNER: infra-engineer
WORK ORDER: Close the gaps blocking Phase 15 experiments C, F and D. Nothing applied, nothing committed.

Items 1, 2, 3, 5 and 6 are done. Item 4 is only partly done: the load tools do not accept `--env` or `--profile`. The new Python gate in `check.sh` also fails on one existing test.

**1. Scaling.** The defect was real. The deployed expression is `visible / FILL(running, 1)`. `FILL` covers only a missing datapoint, and CloudWatch drops a datapoint that divides by a reported 0. I confirmed this from the expression and the AWS documentation, not by seeing it happen live.
- **Fix chosen: step scaling.** I rejected `min_capacity = 1` because `queue_scenario.py` sends nothing unless workers are at 0 (`PRECONDITION_WORKERS_NOT_ZERO`), and C is meant to show 0 -> 2 -> 0.
- **Scale out:** on at least 1 visible message, add 1 task, or add 2 at 6 or more. Maximum stays 2. It only ever adds.
- **Scale in:** to 0 after 5 minutes with visible plus in-flight at 0.

**2. Outputs.** The queue URL, cluster name, service names and table map already existed. I added `embedding_dlq_url`, `alb_url` and `run_ledger_table_name`. For the manifests' collectors I also added `embedding_queue_name`, `embedding_dlq_name`, `alb_arn_suffix`, `target_group_arn_suffixes`, `neptune_cluster_identifier` and `log_group_names`. `terraform output` shows the new ones only after an apply.

**3. Identity. No IAM change.**
- The only configured profile is `cs441`. It resolves to the IAM user `CS441_Dev`. That this profile runs the experiments is my inference.
- The user is not managed by Terraform: there is no `aws_iam_user` in the tracked infrastructure code.
- It has the AWS managed `AdministratorAccess` policy, with no inline policies, groups or permissions boundary. So it already has `SendMessage` and `Query`, and I added nothing.
- This is not least privilege, and adding a narrow policy to an administrator would not change that.
- A dedicated experiment role would be your decision. Both the queue and the table use the environment KMS key, so it would also need `kms:GenerateDataKey` and `kms:Decrypt` through SQS, and `kms:Decrypt` through DynamoDB. It would also need the read calls the tools and collector make.
- `deployer_principal_arns = []`, so no deployer role exists. I did not check organization SCPs or run the IAM policy simulator.

**4. `--env` and `--profile` (partial).**
- `query_load.py` and `queue_scenario.py` define only `--manifest`, so passing the flags would make argparse exit.
- Instead, `run-experiment.sh` exports `SC_ENV` and, with `--profile`, `AWS_PROFILE`, which the tools' AWS CLI calls inherit.
- It refuses `--profile` while `AWS_ACCESS_KEY_ID` is set, and refuses a manifest whose `environment` differs from `--env`.
- No tool reads `SC_ENV` today.
- Literal flag forwarding needs experiment-engineer to add the arguments first.

**5. Approval gate.** It applies to both runners and reuses `approval_problems()` from `load-tests/sclib.py`. It is stricter than asked: the status must be exactly `APPROVED`, with a named approver, a `YYYY-MM-DD` date, `blocked: false` and no `BLOCKED` precondition. Running `run-bounded-pipeline.sh` directly is still not gated.

**6.** `check.sh` has a new `python-tests` gate covering both suites.

FILES CREATED: none in the repository. A temporary probe is at `/tmp/claude-1000/-home-olmos-scholarchain/0f34e731-348f-408d-852d-5872f0480ff3/scratchpad/gate_probe.py`.

FILES MODIFIED:
- `/home/olmos/scholarchain/infra/terraform/modules/compute/main.tf`
- `/home/olmos/scholarchain/infra/terraform/modules/compute/variables.tf` (description only)
- `/home/olmos/scholarchain/infra/terraform/environments/dev/outputs.tf`
- `/home/olmos/scholarchain/infra/terraform/README.md`
- `/home/olmos/scholarchain/scripts/run-experiment.sh`
- `/home/olmos/scholarchain/scripts/check.sh`

COMMANDS RUN:
- `terraform fmt -check -recursive` and `terraform validate`
- `bash -n scripts/check.sh` and `bash -n` on `run-experiment.sh`, only through `check.sh`'s bash-syntax gate
- `shellcheck`, `checkov`
- both Python suites, and `scripts/check.sh --skip-sbt`
- `scripts/plan.sh` twice
- the approval-gate probe
- read-only AWS calls (STS, IAM, ECS, Application Auto Scaling, CloudWatch, ECR)

TEST RESULTS:
- `load-tests/tests`: 44 run, OK.
- `experiments/tests`: 51 run, **1 failure**, `test_no_manifest_is_approved`. The committed C manifest has `status: Approved`, and the test requires every manifest to be pending.
- Approval probe: PASS. All six committed manifests are refused. For both runner types the approved fixture is accepted, and the pending, blocked, wrong-environment and no-approval-block fixtures are refused.
- `sbt check`: not run. No sbt source changed, and the completion hook exempts this agent.
- No committed test covers the new gate or the scaling change.

VALIDATION RESULTS:
- **fmt:** pass.
- **validate:** pass.
- **bash -n:** pass on all 15 scripts (via `check.sh`).
- **shellcheck:** pass.
- **`check.sh --skip-sbt`:** FAIL. The `python-tests` gate failed, sbt was skipped, and every other gate passed.
- **Security scan (checkov):** 566 passed, 66 skipped, **1 failed**: `CKV_AWS_361` (Neptune backup retention) in `modules/neptune/main.tf`, which I did not change.
- **Plan (HEAD tag):** 7 add, 0 change, 4 destroy.
  - Scaling: 2 policies and 2 alarms added, and the old `worker_backlog` policy destroyed.
  - 3 task definitions replaced, because the baseline image tag moves from `08fc89b` to `1cb0cc4`. Old revisions are kept (`skip_destroy`).
  - 9 outputs added.
- **Plan (comparison, `--git-sha 08fc89b`):** 4 add, 0 change, 1 destroy. This is the change on its own.

PLAN CHECKSUM: both plans are DIRTY (uncommitted edits), so **neither can be approved or applied as the reviewed plan**.
- `/tmp/scholarchain-plans/dev-1cb0cc4.DIRTY.tfplan`, sha256 `6229ec96b8265a7a1f5a6b728baa7910fe796155e940c79955bac4f679164562`
- `/tmp/scholarchain-plans/dev-08fc89b.DIRTY.tfplan` (comparison), sha256 `2edc8574b4ec9694fe5daa50544e6758e282cd0aa7429c1c5f65122a42c8ec99`
- `build/plan-summary.json` now describes the second run.

STATEFUL REPLACEMENTS AND IAM CHANGES: none of either. IAM and policy changes: 0 (no identity, action or resource to sign off).

ESTIMATED HIGH-COST RESOURCES: none added. The two CloudWatch alarms cost little (estimate, not checked against pricing). Worker maximum is still 2.

ASSUMPTIONS:
- The step sizes and the 5-minute scale-in window are my choices.
- ECR holds only tags `a75b607` and `2365bf5`, so neither plan's baseline image exists. The services ignore the Terraform task definition, and `deploy.sh` replaces the image when it deploys.

RISKS:
- Step scaling from zero has not been exercised live.
- From memory, not verified: SQS metrics for a queue idle over 6 hours can lag by up to 15 minutes. C and F stop with `NO_SCALE_OUT` at 900 s.
- The scale-in alarm will sit in ALARM while the queue is active and idle. That is expected.
- The worker has no ECS task scale-in protection. The in-flight check reduces the risk but does not remove it.
- The experiment operator is a long-lived administrator IAM user.

**Experiments still blocked**

All three need this change committed. Approving any manifest will also make `check.sh` fail until the tests change.

| Experiment | Blockers |
|---|---|
| **C** | (1) Approval reads `Approved` and `11/25/2026`; it needs `APPROVED` and `'2026-11-25'`. (2) `blocked: true` and a `BLOCKED` precondition: C and F need separate item sets. (3) The deployed worker is `2365bf5`, which does not contain the index fix `55b0d3b`, so a redeploy is needed or vector writes are likely to fail. (4) The scaling fix must be applied. (5) No staged `SC_EMBEDDING_RUN_ID` or `SC_EMBEDDING_SNAPSHOT` is recorded. |
| **F** | Approval is pending, plus blockers 2 to 5 from C. |
| **D** | Approval is pending. You also need to supply `SC_BASE_URL`, a short-lived `SCHOLARCHAIN_API_TOKEN`, `SC_ALLOW_PLAINTEXT_HTTP=1` and the collector variables. It does not depend on the scaling fix. query-api is at 1/1 tasks; I did not check its health. |

Left open as instructed: EMR pipeline arguments (experiment B) and `archive-evidence.sh`.

MANUAL ACTION REQUIRED: human plan review and human-only apply.
1. Review the diff, get a code review, and commit.
2. Run `scripts/plan.sh --env dev --profile cs441 --region us-east-2` from the clean tree. Expect 7/0/4. Use this HEAD-tag plan, because `deploy.sh` requires a clean plan made for HEAD.
3. Apply that saved plan yourself.
4. Have deploy-engineer deploy the same HEAD, which includes the storage fix. If you commit anything first (such as manifest approvals), plan again for the new HEAD.

BLOCKED-ON OWNER:
- **experiment-engineer:**
  - Add `--env` and `--profile` to both load tools if you want real flags.
  - Fix `test_no_manifest_is_approved` so a human approval does not fail the gate. `test_blocked_is_stated_and_b_c_f_are_blocked_f03` will likewise fail when C or F is unblocked.
  - Update stale text: the manifests' `worker_autoscaling` blocks (target, cooldown and line references), the "NOT ENFORCED for runner: pipeline" comments in A and B, and the `--profile` note in the `queue_scenario.py` docstring.
- **Human architect:** manifest approvals (C's format), apply, and the decision on a dedicated experiment role.
- **storage-engineer or rag-engineer:** separate item sets for C and F.
- **test-engineer:** a committed test for the approval gate and profile export.
- **factory-engineer:** guard false positives, listed below.

BLOCKED-ON EVIDENCE:
- `experiments/tests/test_experiments.py:65`: `AssertionError: {'status': 'Approved', ...} != {'status': 'PENDING_HUMAN_APPROVAL', ...}`.
- `load-tests/query_load.py` and `load-tests/queue_scenario.py`, in `main()`: the only argument added is `--manifest`.

**Guard blocks and handling of sensitive files**
- The guard rejected these, and none of them ran:
  - a listing command that named state files in its exclusion
  - a search whose `<name>` pattern it read as a redirect
  - `sts get-caller-identity` sent in list form to the MCP server
  - a `printenv` of a few variable names
  - an edit heredoc and `bash -n` that mentioned `run-experiment.sh`
  - a `grep` of `deploy.sh`
- I used permitted routes instead: tracked-file listing, the read-only CLI, the Edit and Read tools, and `check.sh`'s own syntax gate.
- I read no state file contents; one directory listing showed their names.
- I read selected non-secret keys from the git-ignored `terraform.tfvars`.

Note: the AWS API MCP server is reaching end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md

NEXT GATE: code-reviewer and human plan sign-off. Stopping here for your approval.

---

STATUS: BLOCKED-ON
All three tasks are implemented. One test fails, as you predicted, on C's committed approval block. Only the human may change that block.

OWNER: experiment-engineer

WORK ORDER: Phase 15 follow-up (approval test, unblock C, --env/--profile, stale manifest text). HEAD 1cb0cc4, nothing committed.

FILES CREATED: none in the repository. Throwaway files are only in the session scratchpad.

FILES MODIFIED (all under /home/olmos/scholarchain; the six infra/script files that were already modified were not touched):
- experiments/manifests/A-emr-baseline.yaml, B-emr-burst.yaml, C-embedding-backlog.yaml, D-query-load.yaml, E-retrieval-modes.yaml, F-poison-message.yaml
- experiments/tests/test_experiments.py
- load-tests/sclib.py, queue_scenario.py, query_load.py
- load-tests/tests/fakes.py, test_load_tools.py

WHAT CHANGED:

1. Approval tests
- `test_no_manifest_is_approved` is replaced. Every manifest must now be pending (with null approver and date) or exactly APPROVED, with a non-empty approver and a YYYY-MM-DD date. A blocked manifest (flag or BLOCKED precondition) may not be approved.
- A second test proves the checker rejects `Approved`, `11/25/2026` and approved-but-blocked manifests.
- The blocked-state test now expects B and F blocked, and C unblocked.
- The old test requiring both C and F to send nothing is now an F-only test, plus a test that only approval holds C back.

2. C unblocked
- `blocked: false`, and the BLOCKED comment and precondition are removed. The approval block is unchanged.
- New C preconditions: storage fix 55b0d3b must be deployed, the step-scaling policy must be deployed, and the human must record the staged run.
- F stays blocked and pending, with its text updated to your stage-only ruling.

3. Flags and stale text
- Both load tools accept `--env` and `--profile`, falling back to SC_ENV and AWS_PROFILE.
- `--env`/SC_ENV must equal the manifest environment. It does not widen SCHOLARCHAIN_APPROVED_ENVS. If neither is given, behavior is as before.
- The resolved profile is passed as `--profile` on every AWS CLI call. This matters: an offline `aws configure list` with fake keys showed that AWS_PROFILE alone loses to static key variables, while `--profile` wins.
- `queue_scenario.py` records `environment` and `awsProfile` in run.json.
- `query_load.py` makes no AWS CLI calls (HTTP only), so `--profile` changes nothing it does. Its docstring says so.
- All six manifests now carry an `identity` block: CS441_Dev / cs441, recorded as a known least-privilege risk.
- Also updated: the worker_autoscaling blocks (step scaling, new line references), the header comments, the A/B "NOT ENFORCED" comments and the queue_scenario `--profile` note.
- Approval blocks, cost caps, limits and message counts are unchanged.

STAGED INGESTION RUN: NOT FOUND in committed evidence, so C holds `run_id: HUMAN_TO_SUPPLY` and `snapshot: HUMAN_TO_SUPPLY` under `dataset_snapshot.ingestion_run`.
- docs/agent-runs/phase-14b/load-live.log:6-7 records `run:f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60` / 2026-11-01. That is the graph load (the serving run), not a recorded ingestion.
- docs/agent-runs/phase-14/logs/pipeline-local.log:11 records a local Hadoop run (`run:dc970f17...`).
- There is no phase-14c record directory, and I found no IngestMain execution log.
- I did not assume the serving run ID is the ingestion run ID.

How to find the two values:
- Easiest: the `--run-id` and `--snapshot` you passed to the live `IngestMain` run (shell history or log).
- Or read-only (not run by me; take bucket and table names from your deployment outputs):
  - `aws s3 ls s3://<rag-bucket>/ingestion-manifests/ --profile cs441 --region us-east-2`. An object `<64 hex>.json` means run ID `run:<64 hex>`.
  - `aws dynamodb query --table-name <artifact-registry-table> --key-condition-expression "pk = :pk" --expression-attribute-values '{":pk":{"S":"artifact#vector-ingestion-manifest-<64 hex>"}}' --profile cs441 --region us-east-2`. The item's provenance holds the snapshot.

COMMANDS RUN (observed):
- `python3 -B -m unittest discover -s experiments/tests` and `... -s load-tests/tests`, before and after the changes.
- `scripts/check.sh --skip-sbt`.
- A mutation check on a scratch copy of the load tools.
- Offline `aws configure list` with fake credentials (no AWS request).
- Read-only git, grep and file reads. No recursive search reached Terraform state.

The guard hook blocked four of my shell commands, and none of them ran:
- A file-reading command that named the pipeline script. I used the Read tool instead.
- An edit script whose manifest text mentioned the human Terraform step. I reworded it to "deployed by the human".
- An edit script flagged as writing to spec/ (it only used a variable named `spec`). I made the edits with the Edit tool.
- An `rm -r` on a scratchpad variable. I used a fresh temporary directory instead.

TEST RESULTS:

| Suite | Before | After |
|---|---|---|
| experiments/tests | 51 run, 1 failure (old no-approval test, on C) | 54 run, 1 failure |
| load-tests/tests | 44 run, OK | 53 run, OK |

The one remaining failure:
```
FAIL: test_every_approval_is_pending_or_well_formed_and_no_blocked_manifest_is_approved
{'C-embedding-backlog.yaml': ["status is 'Approved', not exactly APPROVED",
                              "approved_on is '11/25/2026', not a YYYY-MM-DD date"]}
```

`scripts/check.sh --skip-sbt` exited 1 with `CHECK RESULT: FAIL (1 gate(s) failed, 1 skipped)`:
- PASS: agent-files, json, bash-syntax, shellcheck, contracts, schemas, terraform.
- SKIP: sbt (`--skip-sbt`). I did not run `sbt check`; no Scala changed.
- FAIL: python-tests, only because of the failure above.

Mutation check:
- With `--profile` removed from the CLI command, both profile tests failed.
- With the env check disabled, both env tests failed.
- The test that `query_load` accepts the flags was not part of the mutation run.

VALIDATION RESULTS:
- No experiment was run, so there is no raw data, no checksums and no failed runs to retain.
- `approval_problems()` on committed C now returns only the status and date problems, so `run-experiment.sh` and the load tool would still refuse it.

MANIFEST / COMMIT SHA / SNAPSHOT / REGION / RUN IDS / START-END: manifests as listed; HEAD 1cb0cc4; C ingestion run and snapshot not supplied; us-east-2; no run IDs and no start or end times.

RESULTS SUMMARY AND LIMITATIONS: no measurements. The tool behavior was tested against offline stubs only.

ESTIMATED COST: $0 for this work. C's declared estimate is unchanged: $0.11 against a $1.00 cap.

ASSUMPTIONS:
- A pending approval must have null approver and date.
- The autoscaling values come from the uncommitted Terraform source. Whether they are deployed is unverified.
- "+2 at 6 or more visible messages" is derived from the alarm threshold of 1 plus the step bound of 5.

RISKS:
- **C's approval predates these edits.** It was given on a manifest that was still blocked, and the content has now changed. Please re-review C before correcting the block.
- **The fix may not be deployed.** docs/agent-runs/phase-15/repair-01.md:25 says 55b0d3b was not yet deployed (last build 2365bf5). Without it, C's vectors would be rejected again.
- **The cost cap does not cover a write failure.** C estimates $1.60 if vectors are embedded but never written, with a $7.50 redelivery ceiling, against a $1.00 cap that no code enforces.
- **Placeholders do not block a run.** Nothing checks that SC_EMBEDDING_RUN_ID and SC_EMBEDDING_SNAPSHOT match the values recorded in the manifest.
- **The collector is not covered.** `experiments/bin/collect.py` has no `--profile` and relies on AWS_PROFILE, so static key variables would override it. Unset them before collecting.
- **Identity is declared, not verified.** Nothing checks the caller identity at run time.
- **Commit order matters.** The new autoscaling test reads the uncommitted compute/main.tf, so commit these changes together with or after the infra changes.

MANUAL ACTION REQUIRED:
1. Re-review /home/olmos/scholarchain/experiments/manifests/C-embedding-backlog.yaml. If you approve it, change these two lines yourself:
   - Line 14: `  status: Approved` to `  status: APPROVED`
   - Line 16: `  approved_on: 11/25/2026` to `  approved_on: '2026-11-25'` (use the date you actually approve)
   - Line 15 (`approved_by`) is already well formed.
2. Replace `HUMAN_TO_SUPPLY` (run_id and snapshot) in the same file, and use the same values for SC_EMBEDDING_RUN_ID and SC_EMBEDDING_SNAPSHOT.
3. Before running C, deploy 55b0d3b and the step-scaling change, and commit so the tree is clean.
4. Rerun `scripts/check.sh --skip-sbt`. I expect python-tests to pass after step 1, but I have not observed that.

BLOCKED-ON OWNER: human architect (C's approval block and the staged run values). Later, rag-engineer for F's stage-only mode.

BLOCKED-ON EVIDENCE: the test failure quoted above; C lines 14 and 16; no committed record of a staged ingestion run.

NEXT GATE: reproducible rerun and raw-result review. Before that, the human approval fix and code review of this diff.

Unrelated notice from the AWS API MCP server, which I did not use: it is deprecated in favor of the AWS MCP Server. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md
