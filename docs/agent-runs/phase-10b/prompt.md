Phase 10b: runtime wiring follow-ups from docs/agent-runs/phase-10/agent-report.md (BLOCKED-ON OWNER section). Delegate each part as a separate work order, in this order. Pass earlier agents' final reports to later agents where noted. No agent edits another agent's files.

1. rag-engineer
- Add typed Timeout and Overloaded variants to StageFailure; keep existing callers compiling.
- Implement the live Bedrock LlmClient adapter: model ID from configuration, bounded retries, token usage reported. Test with a fake Bedrock client; no live AWS in tests.
- Provide a composition entry that builds AnswerWorkflow from injected ports.

2. storage-engineer
- Implement a DynamoDB-backed JobStore adapter (jobs, results, stage trace) behind the existing JobStore port. Use conditional writes; TTL only on disposable items.
- Contract tests using the existing fake pattern; AWS integration tests only behind the existing AwsIntegration gate.

3. service-engineer (give it reports 1 and 2)
- Replace message-text 504/503 mapping with the typed StageFailure variants.
- Add production composition roots that wire the real adapters (Neptune runtime adapter, DynamoDB stores including the new JobStore, S3 Vectors, S3 object store, Bedrock LlmClient and embedder) from configuration only. No hardcoded endpoints, buckets, or model IDs.
- Provide three independently deployable entrypoints, each with /healthz and /readyz:
  a. query-api: POST /v1/query, jobs, explain
  b. graph-evidence-api: graph and evidence read endpoints
  c. embedding-worker: long-running worker for the Phase 8 embedding path, consuming the embedding queue so it can scale on queue depth. If Phase 8 has no queue consumer, report BLOCKED-ON storage-engineer instead of writing one.
- Rate-limit failed authentication attempts per source.
- Test that each composition root builds with fakes and serves /healthz over a real local socket.

4. build-engineer (give it report 3)
- One container image per entrypoint (or one image with a per-service main class), pinned Java runtime, non-root user, percentage-based memory limits.

Each agent iterates with module-scoped tests, then runs sbt check once before reporting. Save each final report verbatim to docs/agent-runs/phase-10b/<agent-name>-report.md. Stop for human review.
