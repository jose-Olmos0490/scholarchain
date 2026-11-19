## Phase 1 factory report: ScholarChain agent control plane

The control plane is built and every check passes: the final validation run shows 382 PASS, 0 FAIL, 0 SKIP. Nothing is committed. One part of the request is not done: I did not write `docs/agent-runs/README.md`. It is human-owned and outside the paths I may write, so the proposed text is in section 9 for you to place yourself.

Two things need your attention before anything else:
- **The new configuration is already live in this session.** Claude Code reloaded `.claude/settings.json` while I worked. The guard and validator ran on my own tool calls; the guard blocked my first smoke-test command because its text contained `terraform apply`. If you reject this proposal, remove the new files (or stash them with untracked files included) and restart, so unratified hooks do not stay in force.
- **The full diff is not pasted inline.** It is 3,965 lines (188 KB), so it is on disk instead (section 8). This departs from your instruction.

### 1. Request
Build the whole ScholarChain agent system from the spec's matrix and required-artifact sections:
- `CLAUDE.md`, `docs/agents.md`, 17 new agent files
- `.claude/settings.json` and 4 hooks
- `.mcp.json` and 5 commands
- `docs/agent-runs/README.md`

Then validate everything, report, and stop for ratification without committing.

### 2. Matrix basis
`spec/scholarchain-spec.md` sections: "Agent authority and ownership matrix", "Required factory artifacts", "Required hook and permission behavior", "Required MCP policy", plus the Phase 1 work order.
- `spec/` is **untracked** in git (it appeared after the session's snapshot). I treated it as authoritative because you told me to.
- Recommendation: commit `spec/` as human-owned input before ratifying, so the matrix this proposal relies on is itself recorded.

### 3. Changes (all new files; nothing tracked was modified)

| File | Authority | Reason |
|---|---|---|
| `/home/olmos/scholarchain/CLAUDE.md` | Required factory artifacts; Phase 1 item 1 | Project identity, module boundaries, `sbt check` as done, one-owner rule, tests, provenance, secrets, Terraform-only, no console mutation, `@docs/agents.md` import |
| `/home/olmos/scholarchain/docs/agents.md` | Matrix ("ownership map is authoritative"); Phase 1 item 2; MCP policy | Ownership map with exact paths, `spec/**` as human-owned, routing table, escalation rules, report contract, evidence format, MCP trust boundaries |
| `.claude/agents/*.md` (17 new) | Matrix rows; Phase 1 item 3 | One file per required agent: description, narrow tools, rules, procedure, refusals, report contract |
| `.claude/hooks/guard-dangerous.sh` | Required factory artifacts; hook behavior; floors 1, 4, 5, 6, 7 | PreToolUse guard: dangerous commands, ownership fences, script authority, read-only AWS |
| `.claude/hooks/format-scala.sh` | Required factory artifacts; floor 3 | Scala formatter after edits |
| `.claude/hooks/verify-tests-ran.sh` | Required factory artifacts; floor 2 | Stop and SubagentStop test-marker gate |
| `.claude/hooks/validate-agent-files.sh` | Required factory artifacts; Phase 1 validation | Validation script, also wired as a PostToolUse hook |
| `.claude/settings.json` | Required factory artifacts; hook behavior | Allow/ask/deny lists, hook wiring, MCP enablement, bypass mode disabled |
| `.mcp.json` | MCP policy; Phase 1 item 5 | github, aws-api, terraform, aws-docs; all read-only; env-var references only |
| `.claude/commands/{deploy,rollback,incident,run-pipeline,experiment}.md` | Required factory artifacts; Phase 1 item 6 | Each names its responsible agent and gates |

`.claude/agents/factory-engineer.md` is unchanged (its diff is 0 lines). The matrix does not require changing it.

### 4. Scope deltas
Everything below is **widened from nothing**, since these are new grants.

**Tools per agent:**
- Read, Grep, Glob, Write, Edit, Bash: build, domain, dataset, hadoop, entity-resolution, spark, rag, service, test, experiment.
- storage-engineer and documentation-engineer: those six plus aws-docs.
- infra-engineer: those six plus aws-api (read-only), terraform, aws-docs.
- deploy-engineer: Read, Grep, Glob, Write, Bash, aws-api. No Edit.
- incident-responder: Read, Grep, Glob, Write, Bash, aws-api, github read. No Edit.
- dependency-updater: Read, Grep, Glob, Edit, Bash. No Write.
- code-reviewer: Read, Grep, Glob, github read only. No Write, Edit, or Bash.
- No agent has the Agent/Task tool, so subagents cannot invoke one another.

**Hooks:** 1 PreToolUse, 2 PostToolUse, plus Stop and SubagentStop.

**Permissions:** 33 allow, 19 ask, 101 deny rules.

**MCP:** 4 servers, all read-only.

**Also narrowed:**
- Agents never commit, push, or rewrite git history.
- Every agent's direct AWS CLI use is read-only.
- The orchestrator may write only `docs/agent-runs/phase-*/{agent,review,test}-report.md`.

### 5. Floor invariants (all present and enforced)

| # | Invariant | Enforced by |
|---|---|---|
| 1 | Dangerous-command guard | `guard-dangerous.sh` on PreToolUse Bash, plus deny rules |
| 2 | Test-stop hook | `verify-tests-ran.sh` on Stop and SubagentStop |
| 3 | Scala formatter | `format-scala.sh` on PostToolUse; exits 2 when scalafmt fails |
| 4 | Secret reads denied | Read deny rules (`.env*`, `secrets/**`, `*.pem`, `*.key`, `id_*`, credential files, tfstate, `~/.aws/**`, …), plus shell patterns in the guard |
| 5 | Terraform apply/destroy denied | Deny rules plus guard (including `-auto-approve`, `TF_CLI_ARGS`, `run-all`, `make`, `*apply*.sh`/`*destroy*.sh` wrappers, `-chdir`) |
| 6 | Destructive AWS denied | Deny rules plus guard (delete/terminate/remove/purge, `s3 rm/rb/mv/sync --delete`, DynamoDB, Neptune, S3 Vectors, ECR, KMS, EMR, large scale-outs) |
| 7 | Force push denied | Deny rules plus guard (`--force`, `-f`, `--force-with-lease`, `--mirror`, `--delete`, `+refspec`) |

### 6. Audits
- **One writer per artifact:** checked by running the guard as every agent and the orchestrator against 44 sample paths. Every path has exactly one writer, with one exception: `project/Dependencies.scala` has two (build-engineer for structure, dependency-updater for version values). The matrix assigns it that way, and the split is documented in `docs/agents.md`. Human-owned paths have zero agent writers.
- **Collisions:**
  - Each territory word (for example `modules/spark`, `tests/`, `infra/terraform`) appears only in its owner's description, outside the "Do not use" part.
  - Routing stages are unique, with one primary agent each.
  - Command names are unique.
  - The two PostToolUse hooks share a matcher but act on separate file sets (Scala files vs. constitutional files). Both scopes are probe-checked.
- **Orphans:** Phases 0–17 each have a routing row, every agent is primary somewhere, every hook is wired and exists, every command and MCP server is listed in `docs/agents.md`, and there are no extra agents.
- **Tool fences:**
  - code-reviewer has no write tools or shell.
  - test-engineer can write only under `tests/**`; probes show writes to production code are blocked, by tool and by shell.
  - infra-engineer cannot apply Terraform, run live scripts, or make mutating AWS calls.
  - deploy-engineer may run only the approved scripts plus read-only commands, with `--env` taken from the human-set `SCHOLARCHAIN_APPROVED_ENVS`. Setting that variable inline is blocked.
- **Mutation tests:** I broke a copy of the system on purpose to confirm the validator catches each fault. It failed correctly for:
  - reviewer given Write
  - a missing tools field
  - a duplicate agent name
  - a missing executable bit
  - broken JSON
  - an extra `feature-implementer` agent
  - a routing collision

  This testing found and fixed two bugs in the validator itself. Missing executable bits on the Stop hooks were going unnoticed. The python3 fallback path never matched.

### 7. Validation (raw output, condensed)
```
validate-agent-files.sh --all   -> validate-agent-files: PASS=382 FAIL=0 SKIP=0   EXIT=0
python3-only fallback (no jq)   -> PASS=382 FAIL=0 SKIP=0
jq OK / json.tool OK: .claude/settings.json, .mcp.json
bash -n OK, test -x OK: all 4 hooks;  shellcheck -S warning: clean
Guard smoke (piped hook JSON):
exit=2 terraform apply          exit=2 terraform destroy -auto-approve   exit=2 echo 'terraform destroy'
exit=2 git push --force         exit=2 aws s3 rm s3://scholarchain-raw --recursive
exit=2 aws dynamodb delete-table          exit=2 aws emr modify-instance-groups ...=200
exit=2 cat .env                 exit=0 sbt test (orchestrator and spark-engineer)
exit=2 code-reviewer Write      exit=2 test-engineer Write modules/core/...
exit=2 deploy-engineer scripts/deploy.sh --env prod (approved=dev)
Stop hook:  no sources ->0; marker missing ->2; marker fresh ->0; stale ->2; stale + "STATUS: COMPLETE" ->2;
            stale + "STATUS: BLOCKED-ON"/"FAILED" ->0; SubagentStop code-reviewer ->0; domain-engineer stale ->2
Formatter:  non-Scala ->0; no .scalafmt.conf ->0 + notice; scalafmt absent ->0 + notice; scalafmt fails ->2; ok ->0
```
The full output is in `/tmp/claude-1000/-home-olmos-scholarchain/2b5a1f93-5b31-40ce-b858-6b527a572413/scratchpad/validation.txt`.

### 8. Diff
- **Patch file:** `/tmp/claude-1000/-home-olmos-scholarchain/2b5a1f93-5b31-40ce-b858-6b527a572413/scratchpad/phase-01-constitution.diff`
  - 30 files, 3,965 lines
  - sha256 `3586de17dafc93b200e5885c703cd4f74cf13e493827823471119d502f891726`
- **Why not pasted:** every file is new, so the diff is exactly the file contents in the working tree. Scratch can be cleared, so the working tree is the durable copy.
- **Regenerate it:** `git diff` (empty) plus `git diff --no-index -- /dev/null <file>` for each file listed by `git status --short`, excluding `spec/`.
- **Why not `git add -N`/`git reset`:** the new guard blocks `git reset` for subagents, so I generated the diff without touching the index.

### 9. Refusals, interpretations, open questions
1. **Refused: `docs/agent-runs/README.md`.** The matrix says `docs/agent-runs/**` is human-owned, and my seed limits me to 7 paths. The format is defined in `docs/agents.md` ("Phase evidence format"). Proposed text for you:
   > Each `phase-NN/` holds `prompt.md` (human), `agent-report.md`, `review-report.md`, `test-report.md` (orchestrator, verbatim agent reports), `human-gate.md` (reviewed, decision, manual changes, remaining risk, accepted hash), `repair-NN.md`, and `commit.txt` (human). Command output is stored deterministically. Hidden model reasoning is never stored.
2. **MCP documentation** is in `docs/agents.md`, not `docs/mcp.md`, because `docs/**` belongs to documentation-engineer.
3. **Matrix vs. seed:** the matrix gives factory-engineer `.claude/**`, but the seed lists 7 paths. I kept the narrower seed. `.claude/settings.local.json` is human-owned. `.claude/.last-test-run` is written only by `sbt markTestRun`.
4. **Terraform apply in Phase 14 is a human step.** Floor 5 bans wrappers, so `scripts/deploy.sh` must do only the application rollout. infra-engineer is bound to this.
5. **Dependency ledger:** I assumed it is named `project/Dependencies.scala`, split by field between build-engineer and dependency-updater.
6. **Test territory:** test-engineer's only test territory is `tests/**`. Each module's own feature tests belong to that module's owner.
7. **Reports:** deployment records go to `reports/deployments/**` (deploy-engineer). The release manifest and the rest of `reports/**` belong to documentation-engineer.
8. **Rollback:** `/rollback` goes to incident-responder. Rollback inside an active `/deploy` stays with deploy-engineer.
9. **Pipeline runs:** `/run-pipeline` goes to experiment-engineer, following spec Phase 15.
10. **Spec probe 1** names `feature-implementer`, which is not in the matrix. I did not create it; probe P2 below substitutes service-engineer.
11. **Module edges:** I chose the edges `rag → core, storage` and `services → core, rag, storage`. build-engineer should confirm them in Phase 2.
12. **Stop hook design:** it checks the test marker rather than running sbt. An agent can still stop if its final message honestly says `STATUS: FAILED` or `BLOCKED-ON`. Agents with no source territory are exempt from SubagentStop.
13. **Agent identity:** fences rely on the hook input's `agent_type`. This was observed working in this session: my writes as factory-engineer were allowed by the territory check.
14. **MCP specifics:** the MCP packages and images are not version-pinned, and the exact tool names (especially github) are best-effort. Check them with `/mcp` and pin versions.
15. **`id_*` deny rule:** this floor rule also stops agents reading any source file whose name starts with `id_`.
16. **code-reviewer has no Bash,** so the orchestrator must paste diffs into its work orders.

### 10. Post-ratification probes
Run these after committing and restarting `claude` from the repo root. For P8, start with `SCHOLARCHAIN_APPROVED_ENVS` unset.

| # | Probe | Expected result |
|---|---|---|
| P1 | Run `.claude/hooks/validate-agent-files.sh --all` and `/agents` | FAIL=0; 18 agents listed |
| P2 | "Use the service-engineer agent to add a library to build.sbt." | Refuses with `BLOCKED-ON build-engineer`; any write attempt shows the guard's owner message |
| P3 | "Use the code-reviewer agent to fix the defect in <file>." | Reports the finding and its owner; no edit (no write tools) |
| P4 | Ask the orchestrator to run `echo 'terraform destroy'`, then `git push --force`, then `aws s3 rm s3://scholarchain-raw --recursive` | All three blocked by `guard-dangerous.sh` |
| P5 | Create a dummy `.env` yourself, then ask "Read .env" | Denied by permission policy |
| P6 | Add a harmless `modules/core/src/main/scala/Probe.scala` in your editor, then let the orchestrator finish a turn | Stop hook refuses ("tests are stale … BLOCKED-ON build-engineer" until a build exists). Delete the file afterwards. |
| P7 | "Use the test-engineer agent to create modules/core/src/main/scala/X.scala." | Blocked; message names test-engineer (confirms `agent_type` works) |
| P8 | "Use the deploy-engineer agent to run scripts/deploy.sh --env dev." | Blocked: no approved environment |
| P9 | `/mcp` | Four read-only servers; amend agent tool names if they differ |
| P10 | Read the routing table in `docs/agents.md` | Every lifecycle stage has one owner |

**Ratification required.** This is a proposal awaiting your ratification. Nothing is committed. It takes effect only after you ratify it, commit it, and restart Claude Code.
