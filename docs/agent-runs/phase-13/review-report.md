# Phase 13 review report

## Review round 1

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only adversarial review of the full Phase 13 diff (scripts/*, scripts/lib/common.sh, .gitignore, infra/terraform/README.md) against the Phase 13 prompt.md and the two human rulings (deploy.sh is rollout-only; cleanup.sh needs CONFIRM_CLEANUP=<env>-<account-id>).
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

No critical finding. Three major findings block approval. Both human rulings are honored.

Confirmed clean:
- deploy.sh never runs terraform apply. Its only terraform calls are `show -json` through plan_var.
- cleanup.sh is the only apply, and it applies only the saved plan file after an exact sha256 match.
- Every script has `set -euo pipefail` and no `set -x`.
- Every script's `--help` is handled before any cloud call or TTY check.
- Step markers use the form "==> [script] STEP:".
- The deploy.sh checksum gate is not bypassable through empty, unset, uppercase or whitespace values. The regex is `^[0-9a-f]{64}$`, and the check compares the sha256 of the plan file at the commit-derived path.
- The run-experiment.sh allowlist is correct. `realpath -e` resolves symlinks and `..`. The `"$EXP_ROOT"/*` case pattern needs a trailing slash, so `/x/experiments-evil` is refused. The manifest must be Git-tracked and the tree clean. `yaml.safe_load` is used. Runner values are an allowlist, and load-test args go to an argv, not a shell.
- In check.sh the sbt gate uses pipefail, so sbt's own failure propagates through the grep filter.

FINDINGS:
| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | scripts/run-experiment.sh:139,157 with scripts/run-bounded-pipeline.sh:691-745 | A manifest with `runner: pipeline` and `max_duration_seconds: N`. The outer `timeout N` and the inner `--wait-timeout N` use the same value, and the outer clock starts before sbt packageJobs and the jar upload. The outer timeout always fires first. | It SIGTERMs only the bash script (`--foreground`, no `--kill-after`). run-bounded-pipeline.sh has no signal trap, so its `stop-execution` is never reached. The record says `timedOut: true`, but the Step Functions execution and EMR steps keep running. This is a cost and cleanup risk, and the "hard timeout" claim is false. For `load-test` runners, `--foreground` also leaves spawned child load generators running, and a SIGTERM-ignoring runner is never killed. Fix: add a TERM/INT trap in run-bounded-pipeline.sh that stops the execution. Also pass the inner wait-timeout as `DURATION` minus a margin, or give the outer timeout a margin. Add `--kill-after`. | infra-engineer |
| 2 | major | scripts/check.sh:158-169,173-177 | `scripts/check.sh --skip-sbt --skip-terraform --skip-shellcheck`, or any host without shellcheck. | The gate is skipped and the script still prints "CHECK RESULT: PASS" and exits 0. Skipped gates are listed only as "GATE x: SKIP". A caller or log grep for the final line, or for the exit code, sees PASS with no sbt, Terraform or shellcheck evidence. Without flags, a missing shellcheck is skipped silently by default. CLAUDE.md says `sbt check` is the definition of done. Fix: exit nonzero, or print "CHECK RESULT: INCOMPLETE (n skipped)", when any gate is skipped. Make shellcheck absence a FAIL unless explicitly skipped. | infra-engineer |
| 3 | major | scripts/smoke-test.sh:110,125,133,140,155 | `AUTH=(--header "Authorization: Bearer $SCHOLARCHAIN_API_TOKEN")` is passed to curl on its argv. | The bearer token is visible in `ps` and `/proc/<pid>/cmdline` to every local user for the whole call. The script's own message says "never put it on the command line", and the Phase 13 rule is "Print no secrets". Fix: pass the header from a 0600 file with `--header @file` (curl 7.55 or later) or `--config -` on stdin. | infra-engineer |
| 4 | minor | scripts/lib/common.sh:90-91 | `git status` fails, for example with dubious ownership or an index.lock error. | The failure is masked by `\|\| true` after the grep pipeline. `dirty` is empty and the tree is treated as CLEAN. This is a fail-open in the gate used by deploy.sh, cleanup.sh, package.sh and run-experiment.sh. Fix: capture the status separately and die if it fails, or use `\|\| [[ $? -eq 1 ]]` for the grep only. | infra-engineer |
| 5 | minor | scripts/cleanup.sh:106,181 | `terraform state list` fails (backend auth or lock). | `\|\| true` masks it. An empty `inventory-before.txt` is written as if the state were empty, and the confirmed path continues. The "inventory before/after" evidence can be false. The apply itself would still fail on a bad backend. Fix: fail on the pre-destroy inventory, and record an explicit "UNAVAILABLE" marker for the after-inventory. | infra-engineer |
| 6 | minor | scripts/cleanup.sh:108-112 and infra/terraform/README.md:169-173 | `evidence/<sha>/SHA256SUMS` exists but holds nothing useful (every item in MISSING.txt), or only on a local disk. | The "final reports preserved" gate checks file existence only. `/evidence/` is gitignored, so nothing makes the archive durable unless EVIDENCE_S3_PREFIX was used. The README claim "final reports are preserved by requiring ... SHA256SUMS" is stronger than the code. Fix: require that MISSING.txt has no test-report entry, require an S3 location or a human flag, and soften the README wording. | infra-engineer |
| 7 | minor | scripts/cleanup.sh:159-161 | The human runs `cleanup.sh 2>log` (stdout and stdin are still TTYs, so the check passes). | The dry run prints the resolved value `CONFIRM_CLEANUP=<env>-<account-id>` on stderr, so it lands in the log. The account ID is exposed. Printing the exact confirmation string also weakens the "deliberate typing" intent, because the human can copy and paste it. Fix: do not print the resolved value. Print only the form, and have the human read the account from sts. | infra-engineer |
| 8 | minor | scripts/archive-evidence.sh:127-129,9-10 | A stale `target/test-reports/*.xml` from another commit, or a huge XML. | Test reports are copied whatever their commit. The header claims "belongs to the commit". JUnit XML carries system-out (the project has seen 3.6 GB of output), so the archive and the S3 upload are unbounded in size and cost, and could carry sensitive test output. Fix: filter by mtime or commit, cap the size, or exclude system-out. | infra-engineer |
| 9 | minor | scripts/archive-evidence.sh:176,210-214 | `rawResultDirectory` of `$BUILD/experiments/../../x`. Also two metric specs that sanitize to the same `$safe` name. | The `== "$BUILD"/experiments/*` glob matches `..` traversal, so an arbitrary directory is copied into the evidence archive. The second metric silently overwrites the first, which contradicts the header "overwrites nothing". Fix: check with `realpath`, and disambiguate the file name with an index or hash. | infra-engineer |
| 10 | minor | scripts/run-bounded-pipeline.sh:629 and the cloud path at 737-745 | PyYAML is missing, or `stop-execution` fails. | `import yaml` is not checked by `require_cmd`. In the cloud path the record step runs after the execution, so the script exits 1 with no run record although the run completed. A failed `stop-execution` under `set -e` also skips the record. Fix: preflight `python3 -c 'import yaml'`, and write the record in a trap or before failing. | infra-engineer |
| 11 | minor | scripts/package.sh:54,69,89-103 | `--skip-jars --skip-images`, or either flag alone. | The script exits 0 and writes `build/package-manifest.json` with empty jars or images and no marker. archive-evidence.sh then treats it as a valid package manifest for the commit. Fix: record `skipped` in the manifest, or refuse to write it when anything is skipped. | infra-engineer |
| 12 | minor | scripts/rollback.sh:78-81 and :61-62 | `update-service` fails on the second or third service. | The first service has already been moved, and the error gives no recovery instruction (mixed-version state). Also, the `mapfile < <(aws ...)` process substitution swallows an AWS error and reports a misleading "no ACTIVE task definition... nothing was changed". Fix: report the services already moved and the exact command to re-run. | infra-engineer |
| 13 | minor | scripts/deploy.sh:80-91,126-127; scripts/lib/common.sh:12 | `SC_PLAN_DIR` is overridden, or `/tmp/scholarchain-plans` is pre-created by another user. | The plan is read and its sha checked before any ownership or mode check (chmod comes later, at lines 126-127). The plan file is re-read by about six `terraform show` calls after the checksum, which is a TOCTOU window. The impact is limited, because those calls are only used as account, commit and environment checks and the checksum must still match. Fix: assert that the directory is owned by the invoker and is mode 700 before reading. Also consider that nothing verifies the plan was actually applied: only the ECS cluster ACTIVE check at lines 112-114 is used. | infra-engineer |
| 14 | minor | scripts/*.sh (`SC_REGION="us-east-1"` default in 9 scripts); lib/common.sh:131 | Any region other than us-east-1. | The default region is hardcoded, and the bucket and ALB naming convention `scholarchain-<env>-...` is hardcoded. Phase 13 says no hardcoded endpoints, account IDs, bucket names or model IDs. Account IDs and buckets are derived or overridable, so this is only the region default. Fix: default from AWS_REGION or AWS_DEFAULT_REGION. | infra-engineer |
| 15 | minor | .gitignore:36-43 (diff) | n/a | The file is build-engineer territory. The agent report says it was routed to build-engineer. The diff is sane: `/build/` and `/evidence/` are anchored and `!*.tfvars.example` is kept, and `.terraform.lock.hcl` is not hidden. Side effect: evidence/ is never tracked, which feeds finding 6. The orchestrator must confirm that build-engineer, not infra-engineer, made this edit. | build-engineer (confirm) |

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: yes for all 15. Each was confirmed by reading the cited lines in the current files, not only the diff. Findings 1, 3, 4 and 5 are the most reliably reproducible by reading.

ASSUMPTIONS:
- The human-gate.md in phase-13 already accepted an earlier iteration. This review is an independent re-review of the working tree.
- The `-t 0 && -t 1` TTY check is sufficient against agents, since the guard hook also blocks cleanup.sh. A pty-allocating caller could still pass it, but that is outside the script's control.
- Bash is 4.4 or later (empty-array expansion under `set -u`, `mapfile`).

RISKS:
- 7 operational scripts have been tested only with bash -n and shellcheck, not at runtime (human-gate.md records this). Finding 1 is a cost risk when it is first run in Phase 15.
- Embedded Python was not executed by me.
- Terraform `init -backend=false` in check.sh gate g_terraform writes `.terraform/` into the environments. This is ignored by Git and was not flagged.

MANUAL ACTION REQUIRED: Human decides whether findings 1-3 must be repaired before the Phase 13 commit, or recorded as repair-NN. The orchestrator confirms the .gitignore authorship (finding 15).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none. The deploy.sh, cleanup.sh and run-experiment.sh paths cannot be executed by me, so the findings come from reading only.
NEXT GATE: owner repair of each finding (infra-engineer for 1-14), then re-review. Findings 1-3 are blocking.

Routing after round 1 (orchestrator): findings 1-14 went to infra-engineer (repair-02). Finding 15: the .gitignore edit was made by build-engineer, invoked by the orchestrator in repair-01 step 3.

## Review round 2

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Round 2 read-only adversarial review of the Phase 13 working tree (scripts/*, scripts/lib/common.sh, .gitignore, infra/terraform/README.md cleanup section) against the round-1 report. Human rulings (deploy.sh rollout-only; cleanup.sh needs CONFIRM_CLEANUP=<env>-<account-id>) were treated as fixed.
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

SUMMARY
- All 14 round-1 findings are FIXED in the current code. Finding 15 (.gitignore) is fine, and its authorship is for the orchestrator to confirm.
- I found no critical or major defect. I found four new minor issues (N1-N4), none blocking.
- I did not execute anything. Every verdict below comes from reading the files.

ROUND-1 FINDINGS
| # | Status | Evidence (file:line) |
|---|---|---|
| 1 | FIXED | run-bounded-pipeline.sh:312-314 installs `trap 'cloud_exit $?' EXIT`, `trap 'exit 143' TERM` and `trap 'exit 130' INT`. `cloud_exit` (230-237) clears the traps, sets `set +e`, removes the input temp file, calls `cloud_finalize`, and exits with the original rc. rc is raised to 1 only when it was 0 and the record write failed. `cloud_finalize` (195-227) is idempotent through `C_FINALIZED`. It stops the execution only when `describe-execution` says RUNNING, with a 30 s timeout per call. If the stop call fails, the record says STOP_FAILED_STILL_RUNNING and the ARN is warned. `C_EXEC_ARN` is derived from the execution name (302) before the traps go in, so a signal during `start-execution` can still find the execution. run-experiment.sh:147-153 sets inner wait = duration minus a margin of duration/10, clamped to 30..300 s. run-experiment.sh:177 uses `timeout --signal=TERM --kill-after=60` with no `--foreground`, so the whole process group is signalled. The `--help` text claims exit codes 130/143 and the signal behavior, and the code supports both. |
| 2 | FIXED | check.sh:186-194 gives FAIL (exit 1) precedence, then INCOMPLETE (exit 4) when any gate is skipped, then PASS only if every gate ran. A missing shellcheck without `--skip-shellcheck` is a FAIL (171-175). sbt filter pipeline: `timeout ... sbt -batch check 2>&1 \| grep -a ...` (132-133) runs under inherited pipefail inside `( set -e; "$fn" )` (72), so an sbt failure propagates. See N4 for a small gap. |
| 3 | FIXED | smoke-test.sh:122-125 writes `Authorization: Bearer ...` with the `printf` builtin under `umask 077` into `$WORK/authorization.hdr`. `$WORK` comes from `mktemp -d` (mode 700, line 49), and the file is chmod 600. curl gets `--header "@$AUTH_FILE"`, so the token is never on argv. There is a curl >= 7.55 check (117-121). The EXIT trap (line 50) is the only trap in the script and removes the directory. The token appears in no error message: the `:?` message at line 70 names the variable only, and `--dump-header` records response headers only. The saved smoke-test file holds only PASS/FAIL. |
| 4 | FIXED | common.sh:93-94 captures `git status` and dies if git fails. 96-99 runs grep and treats only exit codes above 1 as errors. |
| 5 | FIXED | cleanup.sh:119-121 dies when `state list` fails before the destroy. 223-230 writes an explicit UNAVAILABLE marker when the after-inventory fails. |
| 6 | FIXED | cleanup.sh:127-155. It requires `sha256sum --check` of SHA256SUMS, MISSING.txt present with no `^tests:` line, and evidence-location.txt that is either `s3://` (with a `head-object` of its SHA256SUMS object) or `local-only` with CLEANUP_LOCAL_EVIDENCE_ACK=yes. This matches archive-evidence.sh. `missing "tests: ..."` is written only when zero test reports were copied (archive-evidence.sh:200). The "tests-skipped:" entries do not match `^tests:`, which is consistent. The README (infra/terraform/README.md:174-180) states the limit honestly: integrity and location only. |
| 7 | FIXED, with N1 | The script no longer prints the resolved CONFIRM value (cleanup.sh:94, 202-203). Dry run prints only the sha and the form. |
| 8 | FIXED | archive-evidence.sh:140-201. XML is filtered by mtime against the parent commit's time. There are per-file and total byte caps, and system-out/system-err are stripped by the python code. Markdown reports are copied only if they exist in the commit and are unmodified (151-152). A whole-archive cap is checked before checksum and upload (345-347). |
| 9 | FIXED | archive-evidence.sh:259-269 uses `realpath -e`. It matches `"$raw_real/" == "$exp_root/"?*`, a trailing-slash prefix match that also refuses the root itself. It copies regular files only (`find -type f`). Metric file names carry an index and a hash of the spec (301-306), with an existence check. |
| 10 | FIXED | run-bounded-pipeline.sh:83 has the PyYAML preflight. The record is written from the trap on failure, signal, or wait timeout. |
| 11 | FIXED | package.sh:92-114 writes `jarsSkipped`, `imagesSkipped`, and `complete`. archive-evidence.sh:218-226 archives only a complete manifest and lists a partial one in MISSING.txt. |
| 12 | FIXED, with N3 | rollback.sh:66-70 captures the AWS error and exit code separately. 89-102 reports the services already moved and the exact re-run command. |
| 13 | FIXED | deploy.sh:93 `assert_private_dir` (common.sh:189-196: not a symlink, owned by the invoker, mode exactly 700). The plan is copied once into a private mktemp directory (97-100). The sha256 and every `plan_var` call use that copy (101, 119-125). Trap cleanup is at line 98 and does not change the exit status. The `^[0-9a-f]{64}$` gate (79) and the comparison (102) are not weakened. deploy.sh has no `terraform apply`. |
| 14 | FIXED | `resolve_region` (common.sh:174-185) takes `--region`, then AWS_REGION, then AWS_DEFAULT_REGION, then the profile's region (`aws configure get region`, 20 s timeout). It dies with a clear message if none resolves, and the result is validated. It is called in every AWS-using script: deploy:74, rollback:50, plan:71, cleanup:82, archive-evidence:80, run-experiment:69, run-bounded-pipeline:80 (cloud only), smoke-test:67 (only without `--base-url`). check.sh and package.sh make no AWS calls. A grep for `us-east`, `us-west`, `eu-west` and 12-digit literals finds only plan.sh:86, the offline placeholder `000000000000`, which is documented and cannot be applied. `--help` is handled in the argument loops before `resolve_region` in every script. |
| 15 | OK | .gitignore:36-43 keeps `!*.tfvars.example` and anchors `/build/` and `/evidence/`. The orchestrator confirms build-engineer authorship. |

NEW FINDINGS (all minor, none blocking)
| # | Severity | File:line | Concrete failure input | Impact | Owning agent |
|---|---|---|---|---|---|
| N1 | minor | cleanup.sh:94,189-195; infra/terraform/README.md:167-169 | Any dry run or confirmed run. | The script prints `bucket scholarchain-<env>-<area>-<12-digit account>-<region> is ...` for six buckets. The account ID therefore reaches the log, and a human can copy it into CONFIRM_CLEANUP. Also, `terraform plan -destroy` output (cleanup.sh:167-168) contains ARNs with the account ID. The claims "account resolved ... (not printed)" and README "never prints the resolved value (it would put the account ID into logs)" are therefore inaccurate. This is documentation-claim drift, not a gate failure: the exact-match gate, the TTY requirement, and the sha-matched saved plan all still hold. | infra-engineer |
| N2 | minor | run-experiment.sh:177 (no trap anywhere in the script) | A human presses Ctrl-C in the terminal, or sends `kill -TERM` to run-experiment.sh. | Without `--foreground`, `timeout` and the runner live in their own process group. Ctrl-C reaches only run-experiment.sh's group, and bash waits for its child. TERM to the script kills the bash script with no record and leaves timeout and the runner running. The run stays bounded by max_duration_seconds (at most 86400 s) and by the pipeline runner's own trap. But an operator abort is not honored and no record is written. Suggested fix: add a TERM/INT trap that forwards the signal to the timeout child and then writes the record. | infra-engineer |
| N3 | minor | rollback.sh:67-71 | `aws ecs list-task-definitions` exits 0 but prints a warning on stderr. | `2>&1` merges stderr into the captured output, which is parsed as ARNs. The warning text is then passed to `describe-task-definition`, which fails and dies with "nothing was changed". It fails closed, but it can block a rollback during an incident. Suggested fix: capture stdout only and keep stderr separate for the error message. | infra-engineer |
| N4 | minor | check.sh:97-114 | `.claude` or the hooks directory is missing or empty, or `find` fails inside `< <(...)`. | g_json and g_bash_syntax do not require n>0, and a failure inside the process substitution is swallowed, so the gate prints "parsed 0 file(s)" and PASSes. g_schemas does require n>0. Very low likelihood, since the script cds to the repo root. | infra-engineer |

Traps and process groups that I checked and found sound:
- Double finalize: a no-op through `C_FINALIZED`.
- A SUCCEEDED run is never stopped. The status is taken from `describe-execution` and `stop-execution` is called only on RUNNING.
- Exit-code masking: `cloud_exit` exits with the original rc and changes it only when it was 0 and the record write failed. TERM gives 143 and INT gives 130 through `exit`, which fires the EXIT trap with `$?` set to that code.
- set -e inside the trap: `set +e` is set first, and `|| rc=$?` is used on the record write.
- Unset variables on an early signal: all C_* variables are globals initialised at lines 178-190. Before line 312 no trap is installed, and no execution exists yet.
- A signal arriving between `start-execution` and the stored ARN is covered by the derived ARN.
- Kill semantics: `timeout` without `--foreground` signals its own group, not run-experiment.sh's. At the kill-after point the group dies, and the shell sees 137. The script records `timedOut` for both 124 and 137.
- Inner wait versus outer limit: the inner wait counts only the polling loop. The packaging and upload time before it can still let the outer limit fire first. The trap makes that safe (a signal before the traps are installed finds no execution to stop).

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: yes for all (14 round-1 findings re-checked at the cited lines, plus N1-N4). Embedded Python and bash traps were not executed.

ASSUMPTIONS:
- Bash 4.4 or later (empty-array expansion under `set -u`, `mapfile`).
- coreutils `timeout` behaves as documented for group signalling and `--kill-after`.
- curl 7.55 or later supports `--header @file`.
- The `-t 0 && -t 1` TTY check, together with the guard hook, is accepted as sufficient against agent invocation of cleanup.sh.

RISKS:
- All of the operational scripts remain verified by reading and by bash -n / shellcheck only. The trap and timeout behavior of run-bounded-pipeline.sh and run-experiment.sh has never been exercised against a real Step Functions execution. Watch the first Phase 15 run closely.
- A narrow race remains: a signal during an in-flight `start-execution` call may leave an execution that finalize cannot see yet.
- JUnit XML `<properties>` (JVM system properties such as user.name and user.home) is not stripped from the archived test reports. This is low sensitivity.
- Evidence SHA256SUMS proves the archive's self-consistency only. Anyone with write access to the directory can regenerate it. The README states this limit.

MANUAL ACTION REQUIRED: The orchestrator decides whether N1-N4 are repaired before the commit or recorded as repair notes. The orchestrator also confirms that build-engineer made the .gitignore edit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human gate for Phase 13. If the human wants N1-N4 closed, route them to infra-engineer and re-review only those lines.
