# Phase 02 Review Report 01

Reviewer: general-purpose subagent loaded with .claude/agents/code-reviewer.md and its
report contract. The real code-reviewer was not registered because the orchestrator
session started in /home/olmos instead of the repo root. Read-only: read/search tools
only, no shell, no sbt, no edits.
Scope: build.sbt, project/build.properties, project/plugins.sbt, project/Dependencies.scala,
.scalafmt.conf, .gitignore vs docs/agent-runs/phase-02/prompt.md and the Phase 2 gate.

VERDICT: REQUEST_CHANGES

## Findings
1. MAJOR | agent-report.md:53-62 vs prompt.md:45,65 | owner: build-engineer
   The work order requires raw output tails of sbt clean, Test/compile, check, packageJobs
   (from clean) and jar tf output. The report gives summaries and a count table only. The
   only raw sbt output (repair-02.md) is a non-clean `sbt -batch check`.
   Impact: clean compile, jar contents and jar-hash determinism are unbacked by evidence.
2. MINOR | .gitignore:16-24 | owner: build-engineer
   .claude/settings.local.json, SSH keys (id_ed25519), *.p12, *.jks, credentials*, and
   secret tfvars are not ignored; `git add -A` could commit them.
3. MINOR | project/Dependencies.scala:40 | owner: build-engineer
   javaRelease sits in Versions, which dependency-updater may edit. A routine bump to "21"
   would break the "Java 17 everywhere" decision (prompt.md:8). Move it out of Versions,
   like projectVersion.

## Gate results
1. No application logic: PASS (no src/ under any module).
2. sbt Test/compile succeeds: PASS on recorded output only; reviewer did not run sbt.
   (Human reran sbt clean Test/compile and sbt check at 00:38: both pass.)
3. sbt check stably defined: PASS (alias matches prompt.md:53; markTestRun root-only,
   depends on all Test/test; failure case leaves marker untouched).
4. Module graph acyclic and matches CLAUDE.md: PASS.
5. One version ledger: PASS except finding 3.

## Risks (not findings)
- check runs tests twice (alias fixed by work order).
- Job jars exclude slf4j but compile against slf4j 2; Hadoop's slf4j 1.7 could break 2.x-only calls (Phase 4).
- dockerLocal never run.
- entityResolution AWS SDK may clash with EMR's SDK if ever assembled for the cluster.
- Merge strategy fails on unhandled duplicates (LICENSE/NOTICE) when deps are added.

Routed to: build-engineer (repair-03).
