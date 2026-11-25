STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the full uncommitted diff (HEAD 1cb0cc4) in infra/, scripts/, experiments/ and load-tests/.
VERDICT: REQUEST_CHANGES

Summary
- I found no critical defect. By reading, the step-scaling logic, the new approval gate's main path, the flag/env precedence and the shell quoting are sound.
- C should not be approved as it stands. Nothing ties the run to the intended staged run, and the failure the manifest itself prices above its cost cap is guarded only by text.
- The new gate in run-experiment.sh has no test.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

FINDINGS:
Paths are under /home/olmos/scholarchain.

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | experiments/manifests/C-embedding-backlog.yaml:37-38,146-147; load-tests/queue_scenario.py:238-242; load-tests/tests/test_load_tools.py:389-394 | The human fixes only the approval block, leaving `run_id` and `snapshot` as HUMAN_TO_SUPPLY, then exports any well-formed SC_EMBEDDING_RUN_ID (F's future run, the graph-load serving_run_id, a typo) and any snapshot. | The gate passes and 300 messages are sent. A wrong run can consume F's items against the human ruling, or with no staged manifest send all messages to retry and the DLQ. The tool checks only the `run:<64 hex>` shape. The new test shows the committed C runs with the placeholders in place. Prior R8 is still open. Repair: refuse unless both env values equal the manifest's recorded values, and refuse placeholders. | experiment-engineer |
| 2 | major | experiments/manifests/C-embedding-backlog.yaml:89,119-125,143-145 | C is approved while the deployed worker is still 2365bf5 (docs/agent-runs/phase-15/repair-01.md:15), or puts fail for any other reason. | By the manifest's own figures each of the 300 messages can embed a full pass: $1.60 against a $1.00 cap that no code enforces. The tool exits on its timeouts but never purges, so workers can keep spending afterwards. If step scaling is not applied, the messages can sit queued and be processed after a later apply. The two deployment preconditions are text only. Prior R9 is still open and this diff makes it reachable. Repair: bound `messages` so the failure case fits the cap (about 180 on the stated figures), or record an explicit human acceptance. | experiment-engineer; human to accept the cost |
| 3 | major | scripts/run-experiment.sh:137-157,200-206 | Delete the whole approval-gate block, the `export SC_ENV` line or the static-credential refusal. | Every Python test still passes. The only test that reads the script extracts the first heredoc, the parser (experiments/tests/test_experiments.py:177-189), and never runs the script. I found no other test of it in tests/ or load-tests/. The pipeline path has no second line of defence, so a regression would start EMR spend for an unapproved or blocked manifest. | test-engineer (tests/**); infra-engineer to route |
| 4 | major | experiments/manifests/C-embedding-backlog.yaml:14,16 | The committed block reads `status: Approved`, `approved_on: 11/25/2026`. | Not introduced by this diff. The gate refuses C, and per the authors' report experiments/tests and the new python-tests gate fail, so check.sh cannot pass. This diff also changes C's `blocked`, preconditions and scaling bounds under that approval block, so the human should re-read C before rewriting it. No agent may edit it. | human architect |
| 5 | minor | experiments/manifests/C-embedding-backlog.yaml:143; F-poison-message.yaml:135 | A reader follows the citation `docs/agent-runs/phase-15/repair-01.md:25`. | Wrong line. Line 25 is about Terraform state output in agent searches. "Not yet deployed (last deployed build 2365bf5)" is line 15. | experiment-engineer |
| 6 | minor | experiments/tests/test_experiments.py:125-147; infra/terraform/modules/compute/main.tf:372-381,419-421,429-440 | Change the scale-in expression to `visible` only, set scale-in `scaling_adjustment` to 1, or swap the scale-out adjustments. | The test checks policy type, adjustment type, cooldown, evaluation periods, threshold and the backlog threshold only. The key safety property (do not stop a worker holding a message) and the step adjustments, comparison operators, period and statistic can break with all tests passing. | test-engineer; experiment-engineer for the manifest-match test |
| 7 | minor | scripts/run-experiment.sh:151-153 | An approved A manifest (`region: us-east-2`) run with `--region us-west-2`, or with that value in AWS_REGION. | The gate binds `environment` but not `region`. The load tools check region (sclib.py:150-155); the pipeline path does not, so an approval can be used in a Region it was not given for. | infra-engineer |
| 8 | minor | experiments/manifests/C-embedding-backlog.yaml:22-24 (same block in all six); scripts/run-experiment.sh:202-206; experiments/bin/collect.py:29 | Run without `--profile` and with AWS_PROFILE unset, or collect with static credential variables set alongside AWS_PROFILE=cs441. | Nothing reads `identity`. The run proceeds under the default credential chain, not the ruled CS441_Dev/cs441. The run-experiment record holds no profile, and only queue_scenario's run.json records one. collect.py does not call `use_profile`, so static credentials win there and it can use a different identity from the load tool. | infra-engineer (refuse or record), experiment-engineer |
| 9 | minor | experiments/manifests/C-embedding-backlog.yaml:152 | The embedding queue has been idle for more than 6 hours when C starts. | SQS can take up to 15 minutes to publish metrics for a reactivated queue (from my AWS knowledge, not checked here). That equals the 900 s scale-out timeout, so C may report NO_SCALE_OUT and leave 300 messages to be processed unobserved. Consider a longer timeout (watch caps permitting) or an activation step. | experiment-engineer |
| 10 | minor | experiments/tests/test_experiments.py:45-75,101-115 | A committed manifest with `approved_by: TBD` or `approved_on: '2999-01-01'`. | The test passes but the gate refuses, because the test re-implements the rules instead of calling `sclib.approval_problems`. The "refuses malformed approvals" test exercises only the test's own helper. | experiment-engineer |
| 11 | nit | load-tests/sclib.py:88 | `preconditions: 'BLOCKED: x'` as a scalar with `blocked: false`. | Iterated by character, so not detected. Prior N2, now also reached by the pipeline gate. The `blocked` flag still protects if it is set. | experiment-engineer |
| 12 | nit | infra/terraform/environments/dev/variables.tf:328-331 | `worker_backlog_per_task = 0` in tfvars. | Produces a step with equal bounds, which I expect AWS to reject at apply time, not at validate. Add a `> 0` validation. | infra-engineer |

Checks requested in the work order (no defect beyond the findings above)
- Metric math and bounds: with threshold 1, steps [0,5) and [5,inf) give +1 for 1-5 visible and +2 for 6 or more, matching both manifests. Scale-in uses visible + inflight <= 0 for 5 of 5 periods and one step with upper bound 0 to ExactCapacity 0.
- Missing data: `notBreaching` means a queue with no datapoints takes no action. An inactive queue therefore will not force a non-zero desired count back to 0.
- In flight: any not-visible message keeps the sum above 0, including a retried poison message waiting out the 1800 s visibility timeout.
- Interaction of the two alarms: scale-out only adds and scale-in only fires on five empty minutes. The resting ALARM state of scale-in is expected. A one-minute skew when messages first arrive would be corrected by the next evaluation. It would also reset a manually or deploy-raised worker count to 0 while the queue is active and empty.
- IAM: I see no new application IAM need. The human's apply principal needs CloudWatch alarm permissions (not verified). The worker is not covered by the CPU target-tracking policy.
- Approval gate: `|| die` is effective, the manifest must be tracked and the tree clean, and load-test `args` cannot smuggle a second approved file past the name check. Direct run-bounded-pipeline.sh is ungated and documented as such.
- Quoting and precedence: no quoting defect found. `--env` beats SC_ENV and never widens the approved list; an empty `--env` is refused. `--profile` beats AWS_PROFILE and is passed to every queue_scenario AWS call.
- Outputs: the new outputs refer to existing module outputs, and no secret values are exposed.
- Manifest text versus source: autoscaling values and source line ranges match, apart from finding 5.
- F stays blocked by both the flag and the precondition. The diff does not touch any approval block.
- Prior review-report-02: R2 is fixed by this diff. R5 is partly fixed (check.sh runs the Python suites; I did not recheck build.sbt for `sbt check`). R8, R9 and N2 remain (findings 1, 2, 11). I did not re-verify the other prior findings.

TEST RESULTS: not run by reviewer. Reported by the authors, unverified: experiments 54 run with 1 failure (C approval), load-tests 53 OK, check.sh --skip-sbt fails only on that, terraform fmt/validate pass, plan 7/0/4, checkov 1 failure in an unchanged module, `sbt check` not run.

VALIDATION RESULTS: findings verified against files: yes for 1-8, 10 and 11 (by reading). Finding 9: the manifest value is verified; the SQS delay is from my knowledge, not verified. Finding 12: the missing validation is verified; the AWS rejection is not.

ASSUMPTIONS:
- The saved diff matches the working tree. The files I opened were consistent with it.
- The cost figures and put-failure behaviour are as the manifest states; I did not re-derive the Scala ingestion path.

RISKS:
- Not verified: live AWS behaviour of the step policies, the deployed worker build, tfvars overrides, existence and index state of the staged run, and the Terraform plan.
- The reported plan came from a dirty tree and should not be applied. Re-plan after the commit; the images for the new SHA must exist for the worker task definition to be usable.
- Human-only approval is not machine-enforced. The old test requiring every manifest to be pending was replaced by a shape check.
- I opened no state, tfvars, env or credential file. Terraform searches were limited to *.tf. Two file-listing searches timed out and returned nothing.

MANUAL ACTION REQUIRED:
- Do not approve C until findings 1 and 2 are repaired or explicitly accepted. Then rewrite the approval block as `APPROVED` with a `'YYYY-MM-DD'` date.
- Supply C's run ID and snapshot, deploy the worker with 55b0d3b, and apply a freshly generated plan before running C.
- Notice from the connected `aws-api` MCP server: it is reaching end of development. Migration guide: https://github.com/awslabs/mcp/blob/main/src/aws-api-mcp-server/MIGRATION.md. I did not use it.

BLOCKED-ON OWNER: none for this review.
BLOCKED-ON EVIDENCE: none.
NEXT GATE: owner repair (experiment-engineer: 1, 2, 5, 8-11; infra-engineer: 7, 8, 12; test-engineer: 3, 6; human: 2, 4), then re-review.
