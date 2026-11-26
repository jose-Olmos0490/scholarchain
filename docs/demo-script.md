# ScholarChain demonstration script (DRAFT)

Covers the 22 items of the course specification's "Demonstration requirements".
The specification asks for live demonstration, not only screenshots.

Status of each item: **READY** (can be shown now; local or archived),
**ARCHIVED** (only recorded evidence exists), **PENDING** (cannot be shown yet).
No video has been recorded: PENDING. Cloud steps need the human's approval and
credentials; none was rehearsed for this draft.

Before recording: start in the repository root; run `sbt clean check` and the
[bounded local run](../README.md#bounded-local-data-run-no-cloud) in advance
(about 40 minutes); hide account IDs, tokens, endpoints and log content; do not
open the incident's log groups on screen; do not send a token over HTTP.

| # | Item | Status | Show | Say |
|---|---|---|---|---|
| 1 | Private repository structure | READY | `ls`, GitHub page | modules, infra, scripts, experiments, docs |
| 2 | Agent ownership map | READY | [docs/agents.md](agents.md) | one writer per artifact; one shared file |
| 3 | An agent role file | READY | `.claude/agents/code-reviewer.md` | no write tools, no shell |
| 4 | Hook blocks a probe | READY | the manual probe in the [README](../README.md#reproducing-the-agentic-workflow), then `.claude/hooks/validate-agent-files.sh --all`; show two more probes from its list | exit 2, nothing executed; at least three probes |
| 5 | Test-stop gate | READY (stale case not rehearsed) | README replay exercise steps 3 and 4 | Stop is refused while the marker is stale |
| 6 | Phase report and repair record | READY | `docs/agent-runs/phase-14b/agent-report.md`, `repair-01.md`, review reports | REQUEST_CHANGES, repair, APPROVE |
| 7 | Clean `sbt check` | READY | tail of a pre-run `sbt clean check` | 2391 passed, 0 failed, 24 ignored and why |
| 8 | Manifest and real samples | READY | `datasets/dataset-manifest.yaml`, `datasets/samples/`, `sha256sum -c` | four datasets, licenses, snapshots |
| 9 | Hadoop execution and counters | READY (local only). EMR: PENDING | Hadoop run output, `counters.json` | **not run on EMR** |
| 10 | Spark execution and graph outputs | READY (local only). EMR: PENDING | Spark `RUN` and `OUTPUT` lines | **not run on EMR**; small graph |
| 11 | Neptune graph query | PENDING | cluster only | no load or query recorded |
| 12 | DynamoDB run or query record | PENDING (live read not rehearsed). ARCHIVED: `docs/agent-runs/phase-14b/load-live.log` shows the run ledger entry | one read-only item from the run ledger | `load-dynamo` SUCCEEDED, `load-s3` first attempt FAILED |
| 13 | S3 Vectors query with source pointers | PENDING | nothing to query | 0 vectors; Bedrock account block |
| 14 | Complete user query through the API | PENDING | smoke log only (200, no evidence) | not a complete answer |
| 15 | Evidence and explain endpoints | PENDING (live). Fixture shapes: [api-reference.md](api-reference.md) | label fixtures as illustrative | no real evidence ID exists |
| 16 | Claim verification, one unsupported claim | READY (test with fake model). Live: PENDING | `sbt rag/test` (claim-verification specs in `modules/rag/src/test`) | verifier removes unsupported claims; fake model |
| 17 | Scripts, replay instructions, clean-checkout bounded run | READY (local) | README sections; the local run in a fresh clone | cloud scripts not rerun |
| 18 | Terraform plan or deployed resources | ARCHIVED. Live view: PENDING | `docs/agent-runs/phase-14/plan.sha256`, apply logs; `terraform validate` live | applied without EMR |
| 19 | CloudWatch elasticity dashboard | PENDING (not verified to render experiment C) | dashboard, or `experiments/derived/C-embedding-backlog/` plots | one run only |
| 20 | Scale-out and scale-in evidence | ARCHIVED | `experiments/derived/C-embedding-backlog/.../queue-timeline.svg`, `ecs-tasks.svg`; [evaluation.md 3](evaluation.md#3-performance-and-elasticity) | 0 to 2 to 0, but the workload **failed** |
| 21 | One failure and recovery | ARCHIVED | [incident report](incidents/INC-20261125-debug-credential-logging.md) | detection, cause, fix `3c3e1a1`; **contained, not resolved** |
| 22 | Cleanup or inventory evidence | PENDING | none yet | planned after the demo |

## Suggested order and timing (illustrative)

1. Problem and architecture (2 min): [architecture.md](architecture.md).
2. Agent factory: items 1-6 (5 min).
3. Build and data: items 7-10, 17 (5 min).
4. Cloud and runtime: items 11-16, 18 (5 min). State plainly what is pending.
5. Operations: items 19-22 (3 min).
6. Limitations (1 min).

## Questions to be ready for

- Difference between distinct coauthors, weighted degree, team size and bridge
  measures.
- Why an UNRESOLVED pair counts as a true negative, and why precision 1.000 is
  weak ([evaluation.md 1](evaluation.md#1-entity-resolution)).
- How an edge traces to a DBLP record.
- Why the model cannot state a number that is not in the evidence.
- What went wrong: Bedrock block, credential logging, plain HTTP, ack-on-reject.
