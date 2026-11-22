# Phase 07 human gate

Reviewed:
- prompt.md, agent-report-attempt1.md (BLOCKED-ON build-engineer and domain-engineer), repair-01.md, repair-02.md, agent-report.md, review-report.md
- Neptune CSV headers and COAUTHORED_WITH evidence pointer
- DynamoDB key design against each stated access pattern
- S3 Vectors, DynamoDB, and Neptune limits sourced from aws-docs with URLs and dates
- My own sbt check: not rerun by the human; storage-engineer observed sbt check exit 0 (core 664, storage 147, entity-resolution 212, hadoop 75, spark 66, adversarial 352; 0 failed). Next phase sbt check will re-verify.

Decision: ACCEPTED WITH DEFERRED FINDINGS. review-report.md returned REQUEST_CHANGES (3 major, 12 minor); all findings deferred to the Phase 11 repair loop due to deadline and 35-minute sbt check.

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
- Deferred to Phase 11: major #1 (Neptune LIMIT must be literal; affects Phase 9 PathFinder), #2 (metric cache rejects second Spark run), #3 (putBundle not atomic), #6 (evidencePointer resolver missing; Phase 7 gate item "COAUTHORED_WITH traceable to evidence" NOT yet met), and minor #4-#15.

Accepted commit: see commit.txt

## Commit sequence (disclosed)
- 71fcf46 was committed and pushed by the human before the review findings were resolved. This was a human process decision, not an agent action. History was not rewritten.
- repair-01.md and repair-02.md were missing from 71fcf46 and are added in the follow-up commit.
- Open findings are tracked for the Phase 11 repair loop.
