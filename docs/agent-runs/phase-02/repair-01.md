# Phase 02 Repair 01

Trigger: build-engineer returned BLOCKED-ON factory-engineer on work order revision 1
(docs/agent-runs/phase-02/prompt-v1-blocked.md). It only read files, wrote nothing,
and ran no sbt commands.

## Agent-reported conflicts (as relayed by the orchestrator)

1. Ledger file name: the order named project/Versions.scala; the constitution and guard
   use project/Dependencies.scala. Under the current guard, dependency-updater could not
   edit a Versions.scala ledger.
   Citation: CLAUDE.md:50-51, docs/agents.md:83, guard-dangerous.sh:98
2. The order added a services -> entityResolution dependency, which the module table
   does not allow.
   Citation: CLAUDE.md:39
3. repoTests is not in the module table, and only test-engineer may write under tests/.
   Citation: CLAUDE.md:31-39, docs/agents.md:64, guard:133
4. Empty .gitkeep source folders under modules/*/src/ belong to each module's owner,
   not build-engineer.
   Citation: docs/agents.md:50,57-63, guard:116-121
5. New top-level folders (datasets/, prompts/, infra/terraform/, scripts/, load-tests/,
   experiments/, reports/) each belong to another agent.
   Citation: docs/agents.md:56,62,66,68,69

Additional notes from the agent: rag -> core only is narrower than the constitution
allows (acceptable). The test marker path .claude/.last-test-run matches
verify-tests-ran.sh:75.

## Human decision

Option B selected: amend the work order to conform to the ratified constitution rather
than amend the constitution.
- Ledger renamed to project/Dependencies.scala.
- services -> entityResolution edge removed (services -> core, rag, storage).
- repoTests project removed; test-engineer will use module test trees or request a
  build amendment later.
- No .gitkeep or source directories created by build-engineer.
- No top-level directories created by build-engineer; owning agents create them in
  their own phases.

Rationale: no constitutional change needed; avoids a re-ratification cycle.
Deviation from the course spec: the spec asks Phase 2 to create empty module source roots
and top-level directories; the ratified one-writer rules assign those to their owners.

## Related control event

The orchestrator attempted to write this file and was blocked by
.claude/hooks/guard-dangerous.sh (human-owned path rule). This record was written by the
human architect.

## Rerun

Amended order: docs/agent-runs/phase-02/prompt.md (revision 2).
Rerun owner: build-engineer, fresh context.
Rerun result: COMPLETE (build-engineer rev 2; see agent-report.md)
