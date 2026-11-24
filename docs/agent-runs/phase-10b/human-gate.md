# Phase 10b Human Gate (labeled follow-up to Phase 10)

## Reviewed
- agent-report.md: rag-engineer, storage-engineer, service-engineer (BLOCKED-ON), build-engineer, storage-engineer (repair-01), service-engineer (rerun)
- repair-01.md
- entrypoints-after.txt: QueryApiMain, GraphEvidenceApiMain, EmbeddingWorkerMain
- QueryEngine maps StageFailure.Timeout to 504 and Overloaded to 503 (grep verified)
- Test marker newer than all Scala sources; no hardcoded endpoints or model IDs (grep verified)

## Decision
ACCEPTED after repair-01, for code and fakes. No live AWS behavior is claimed.

## Human architect decisions
- D1: the graph-read-model DynamoDB table (pk/sk strings, on-demand, no TTL) and its IAM (query-api read-only; loader PutItem) go into the Phase 12 Terraform work order. Owner: infra-engineer.
- D2: GraphReadModelLoader needs a runnable entrypoint (owner: storage-engineer, Phase 11 repair). It is run by scripts/run-bounded-pipeline.sh (owner: infra-engineer, Phase 13).
- D3: the Spark DynamoDB load manifest shall include cross_community_edge, because bridge analysis is a core research question. Owner: spark-engineer, Phase 11 repair.
- D4: no new support-row cap key now. SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT will be set at deploy to at least the maximum jointPublicationCount in the Spark output. This is recorded as a known limitation.

## Deferred to Phase 11
- code-reviewer and test-engineer review of Phases 10 and 10b
- LLM backoff and per-attempt timeout configuration keys (owner: domain-engineer)
- Optional cleanup of the legacy Retryable text markers (owner: rag-engineer)

## Corrections to agent reports
- service-engineer attributes the 9 ignored tests to AwsIntegration-gated suites. Phase 10 sbt-check.log shows that the 9 IGNORED tests are entity-resolution adversarial cases. Module attribution of the suite totals in both reports is inferred, not verified.

## Manual changes
None

## Remaining risk
- All live adapters (Bedrock, Neptune, DynamoDB, S3 Vectors, SQS) are tested with fakes only; first live exercise is the Phase 14 smoke test.
- A readiness probe cannot tell whether the loader ran for SCHOLARCHAIN_SVC_RUN_ID; a mismatched run ID gives empty reads with healthy probes.
- The guard hook blocked orchestrator writes to unapproved paths and a heredoc mentioning the test marker; the agents fell back to allowed tools. The hook behaved as designed.

## Accepted commit
See commit.txt
