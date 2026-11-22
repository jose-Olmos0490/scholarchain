# Phase 06 repair 01: human decisions

Human decisions for Phase 6 repair round 1. Record them verbatim in docs/agent-runs/phase-06/repair-01.md as human decisions, then route:

1. APPROVED: domain-engineer adds the config keys and shared record schemas for BOTH Part A and Part B in one pass (all items 1-11 of the work order), with contract tests, and runs sbt check.
2. APPROVED in parallel: build-engineer wires adversarialTests to dependsOn sparkAnalytics and adds the Provided Spark libraries to that test project only, then runs sbt check. Its files do not overlap with domain-engineer's.
3. RATIFIED identity rule with these mandatory constraints, which spark-engineer implements in addition to its own safeguards:
   - Identity clusters are built only from ACCEPTED researcher links.
   - Two mentions that appear on the same work never end up in the same cluster.
   - A cluster may contain at most one distinct ORCID and at most one distinct OpenAlex author ID.
   - A cluster larger than a configured maximum size is not merged.
   - Any cluster that violates a constraint is not merged: each mention keeps its own fallback researcher ID, and the violation is written as a CONFLICT record with reason codes and counted in the report.
   - Fixture tests must include a chain case (X links to A1 and Y links to A1, where X and Y share a paper) that stays split.
   domain-engineer includes the max-cluster-size config key in step 1.
4. After steps 1 and 2 pass, rerun spark-engineer on Part A in a fresh delegation with the ratified identity rule, then Part B in another fresh delegation. Before packageJobs in Part B, run sbt clean so the jar checksum comes from a clean build.
5. Then test-engineer writes the adversarial Spark cases, then code-reviewer does a fresh review of the full Phase 6 diff.

Stop and report after step 4's Part A run so the human can check the fixture before Part B.
