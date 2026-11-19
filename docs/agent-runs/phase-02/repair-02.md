# Phase 02 Repair 02

Trigger: human review of build-engineer's rev-2 output. entityResolution had no
dependencies, but Phase 5 requires LLM adjudication inside that module and it may
depend only on core.

Decision: add Aws.bedrockruntime to the entityResolution dependency set in
project/Dependencies.scala. No other changes.
Owner: build-engineer (fresh context).

## Diff

- val entityResolution: Seq[ModuleID] = Seq.empty
+ // Model invocation for LLM match adjudication.
+ val entityResolution: Seq[ModuleID] = Seq(Aws.bedrockruntime)

## Rerun output (sbt -batch check, from build-engineer)

[info] loading project definition from /home/olmos/scholarchain/project
[info] compiling 1 Scala source to /home/olmos/scholarchain/project/target/scala-2.12/sbt-1.0/classes ...
[info] done compiling
[info] loading settings for project root from build.sbt...
[info] set current project to scholarchain (in build file:/home/olmos/scholarchain/)
[success] Total time: 0 s, completed Nov 19, 2026, 12:25:24 AM
[info] scalafmt: Checking 1 Scala sources (/home/olmos/scholarchain)...
[info] scalafmt: Checking 2 Scala sources (/home/olmos/scholarchain)...
[success] Total time: 3 s, completed Nov 19, 2026, 12:25:27 AM
[success] Total time: 2 s, completed Nov 19, 2026, 12:25:29 AM
[success] Total time: 0 s, completed Nov 19, 2026, 12:25:29 AM
[info] markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run
[success] Total time: 0 s, completed Nov 19, 2026, 12:25:30 AM

Result: COMPLETE. No tests ran (modules have no source files yet); the pass shows
the build loads, formatting is clean, and markTestRun touches the marker.

## Risks noted by the agent

- entityResolution now pulls in AWS SDK core libraries and HTTP clients. If the module
  is later packaged to run on EMR, these could clash with the cluster's own AWS SDK.
  Nothing breaks until then; revisit if entityResolution is ever assembled into an EMR jar.

## Also noted, not repaired

- check runs the test suite twice.
- dockerLocal was not executed in this phase.
