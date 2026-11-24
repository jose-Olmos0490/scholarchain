# Phase 11 Human Gate
Reviewer: Jose | Date: 2026-11-24

## Reviewed
- test-report.md: Attempt 1 (BLOCKED-ON build-engineer), Attempt 2 (12 defects, 15 failing tests), Attempt 3 (deferral)
- blocked-on-01-build-engineer.md (saved by human: orchestrator write was blocked because the path has no owner)
- review-report.md: code-reviewer verdict REQUEST_CHANGES; 0 CRITICAL, 5 MAJOR (R1, R2, R4, R7, R11) + R13 MAJOR (human decision)
- Final sbt check (Attempt 3): PASS, exit 0, 33 min 21 s, AwsIntegration not run, markTestRun updated.
  Totals: passed=2321 failed=0 ignored=24 canceled=0 errored=0 aborted=0
  Per-project summaries: 695, 75, 212, 66, 312, 242, 140 (all 0 failed, 0 ignored); adversarialTests 579 passed, 24 ignored (9 Phase-05 D6-D10 + 15 Phase-11 deferrals)

## Decision
ACCEPTED WITH DOCUMENTED DEVIATION. The Phase 11 gate ("no critical or major verified finding remains") is NOT met:
5 MAJOR findings are deferred because of the Nov 25 submission deadline. Nothing is hidden: every failing test is kept unchanged as ignore(...) with a "// DEFERRED phase-11 defect T<N>, owner <agent>" marker pointing to this file.

## Routing and control events
- BLOCKED-ON build-engineer: adversarialTests lacked storage, rag, services on its classpath. Fixed with a one-line dependsOn change in build.sbt; no version-ledger change; module graph acyclic.
- test-engineer found 12 production defects without touching production code.
- code-reviewer stayed read-only (Read, Grep, Glob only).
- Stop hook (verify-tests-ran.sh) refused orchestrator completion while tests were stale, until the Attempt 3 sbt check passed.
- guard-dangerous.sh blocked a test-engineer shell command that referenced human-gate.md (human-owned record); nothing was written.

## Deferred tests (15, ignore(...) with DEFERRED markers)
| Defect | Owner | Severity | Tests |
|---|---|---|---|
| T1 DynamoDB TransactionConflict/Throttling classified Permanent | storage-engineer | MAJOR (R1) | 2 |
| T2 Neptune rows read with silent defaults (provenance loss) | storage-engineer | MAJOR (R2) | 2 |
| T3 directCoauthors skips window check | storage-engineer | MINOR (R3) | 1 |
| T4 JSON-null record throws NPE | storage-engineer | MAJOR (R4) | 1 |
| T5 credential-shaped SDK text in StorageError | storage-engineer | MINOR, policy pending | 1 |
| T6 embedder exception text in ingest rejects | storage-engineer | MINOR, policy pending | 1 |
| T7 async job can stay RUNNING forever | service-engineer | MAJOR (R7) | 1 |
| T8 neighbors response overstates depth (latent) | service-engineer | MINOR (R8) | 1 |
| T9 model property name echoed in rejection | rag-engineer | MINOR, policy pending | 1 |
| T10 non-UTF-8 input throws | rag-engineer | MINOR (R10) | 1 |
| T11 chunker splits on ASCII whitespace only | rag-engineer | MAJOR (R11) | 2 |
| T12 poison message coerces numeric runId | storage-engineer | MINOR (R12) | 1 |

## Other deferred items
- R13: Phase-05 D6-D10 (entity-resolution-engineer, 9 ignored tests) remain deferred.
- R14: canceled=0 in this run; the assume/cancel guards in EntrypointAdvSpec and LoggingAndQueueAdvSpec are environment-dependent (test-engineer to add a canceled==0 check later).
- R15: build-engineer to confirm a single SLF4J provider in production assemblies.
- Phase-10b items still deferred: GraphReadModelLoader entrypoint (storage-engineer), cross_community_edge manifest (spark-engineer), LLM backoff/timeout config keys (domain-engineer), Retryable cleanup (rag-engineer).

## Pending human policy decision
- T5, T6, T9: no ratified redaction contract exists for SDK, embedder or model text. Either ratify the contract or relax these tests after submission.

## Process gaps found
- docs/agent-runs/phase-11/blocked-on-*.md has no owner in docs/agents.md; factory-engineer ownership amendment needed (constitutional, post-submission).
- The reviewer had no git or shell: it did not read the HEAD~4..HEAD diff directly and did not verify T4 or T12.
- The sbt check log reached 3.6 GB because of Hadoop/Spark DEBUG logging in the tests project; per-module summaries could not be labeled.

## Manual changes
- Human saved blocked-on-01-build-engineer.md and wrote this file. No code was edited by hand.

## Remaining risk
The deferred MAJOR defects are real and unrepaired; the 15 ignored tests must be re-enabled when each owner repairs its defect. AwsIntegration was not run in this phase.

## Accepted commit
See commit.txt
