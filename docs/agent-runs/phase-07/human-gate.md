# Phase 07 human gate

Reviewed:
- prompt.md, agent-report-attempt1.md (BLOCKED-ON build-engineer and domain-engineer), repair-01.md, repair-02.md, agent-report.md, review-report.md
- Neptune CSV headers and COAUTHORED_WITH evidence pointer
- DynamoDB key design against each stated access pattern
- S3 Vectors, DynamoDB, and Neptune limits sourced from aws-docs with URLs and dates
- My own sbt check: <paste the test summary lines from /tmp/phase07-check.log>

Decision: <ACCEPTED, or REJECTED + reason>

Manual changes: none to generated artifacts.

Human decisions:
- reports/, logs/, and neptune-export/ prefixes live in the graph bucket.
- Vector index type CLASSIC. New vector buckets default to ENHANCED, so Phase 12 Terraform must set CLASSIC explicitly; if the provider cannot, switch the config to ENHANCED.
- Accepted hadoop-client-runtime image-size cost in storage and downstream images.
- PUBLISHED_IN, AFFILIATED_WITH, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY not exported: Phase 6 produces no source tables. Collaboration queries rely on AUTHORED and COAUTHORED_WITH.
- Only researcher_collaboration loads into DynamoDB; other Spark tables stay in S3/Neptune.

Deferred:
- test-engineer adversarial storage tests moved to Phase 11 (same categories required there) because of the ~35-minute sbt check and the deadline; storage-engineer's 147 storage tests cover this gate.

Environment interventions:
- First session was launched from ~/ instead of the repo root; the orchestrator refused to substitute a general-purpose agent. Restarted from ~/scholarchain.
- Installed uv/uvx 0.13.9 so the aws-docs MCP could start; it was not runnable in earlier phases.

Remaining risks:
- AWS adapters and 36 tagged integration tests have never run against live AWS (no infrastructure until Phase 12).
- Neptune path query uses a bounded variable-length template (no shortestPath() in Neptune openCypher); verify in the Phase 14 smoke test.
- DynamoDB ports share one conditional item-store seam; the Neptune CSV exporter has no fake.
- Config overrides with a mistyped variable name silently fall back to defaults.
- Guard hook false positives on harmless text such as run_id=; route to factory-engineer.
- aws-docs MCP package version unpinned in .mcp.json; route to factory-engineer.
- <any minor review findings accepted without fixing, or delete this line>

Accepted commit: see commit.txt
