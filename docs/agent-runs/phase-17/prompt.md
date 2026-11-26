# Phase 17 work order, part 1 (docs that do not depend on pending fixes)

Owner: documentation-engineer. Write only README.md and docs/** except docs/agents.md, docs/agent-runs/** and docs/incidents/**. No code, Terraform, script or constitutional changes. Do not invent results; cite the file behind every claim.

Create from verified repository state:
- docs/architecture.md: build-time (agent factory) and runtime diagrams.
- docs/data-lineage.md: source dataset -> Hadoop -> entity resolution -> Spark -> Neptune/DynamoDB/S3/S3 Vectors -> evidence -> answer, with real paths and schema versions.
- docs/threat-model.md: every threat in the course spec's security section, including the Phase 15 credential-in-DEBUG-logs finding and plaintext HTTP, with no secret values.
- docs/service-limits.md: limits and Region availability the implementation relies on, each with its source and date checked.
- docs/api-reference.md: routes, request and response examples, error model, matching the contract tests.
- README.md: project summary, student identity (Jose Olmos, jolmo5@uic.edu), agent catalog (one row per .claude/agents file: purpose, owned paths, tool fence, example orchestrator instruction, verification command, report fields), the "Reproducing the agentic workflow" subsection, one bounded replay exercise a grader can run without the cloud, and a tool-version record. Mark deploy, smoke, experiment and results sections PENDING.
- docs/demo-script.md: draft covering the 22 required demo items, marking which are pending.

Part 2 (after fixes and Phase 16 Part B): runbook, final report, evaluation results, release manifest, final review.
Replay every local command before documenting it. Report the files created, commands replayed and every PENDING marker.
