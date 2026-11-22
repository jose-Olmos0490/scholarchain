# Phase 8 human gate

## Reviewed
- agent-report-storage.md (work order 1, storage-engineer)
- agent-report-rag-partial.md (work order 2 first run, rag-engineer, BLOCKED-ON build-engineer)
- agent-report-build-repair01.md (repair 01, build-engineer)
- agent-report-rag-final.md (work order 2 rerun, rag-engineer)
- Final sbt check (rerun): exit 0. Tests passed: core 664, hadoop 75, entityResolution 212, sparkAnalytics 66, storage 160, rag 53, tests/** 352 (9 ignored, AWS-tagged). services: no tests yet (Phase 10).
- Code review: skipped for Phase 8 by human decision; covered by the Phase 11 full-repository review.

## Accepted
- S3 Vectors ingestion path in modules/storage: content-addressed document store, vector-key-v1, duplicate rule (SKIPPED_UNCHANGED / UPDATED / REJECTED_KEY_COLLISION), vector-metadata-v1 with 6 non-filterable keys, batching, rate limiting, retries, RunLedger, idempotent manifests.
- modules/rag: rendering templates, chunking, EmbeddingModelSpec and Embedder (Bedrock + fake), SemanticContextItem (authoritative = false, no metric fields), IngestMain entrypoint (--index, --limit, --dry-run; unbounded live runs refused), RetrievalSmokeSpec (20-document fixture across all 5 indexes).
- build.sbt: rag depends on core and storage (repair 01).

## Human decisions
- Routed the rag BLOCKED-ON to build-engineer instead of granting a one-time ownership exception on build.sbt.
- rag may use the Bedrock runtime SDK directly (embedder now, LLM calls in Phase 9). All S3, S3 Vectors, DynamoDB, and Neptune access goes through storage ports. The stricter "no AWS SDK in rag" line in the rerun prompt was an over-strict human instruction and is withdrawn. Import check output is below.

## Disclosures and manual changes
- Work order 1 and the first work order 2 run executed inline in the orchestrator: Claude Code was started from /home/olmos instead of the repo root, so project subagents were not loaded. Repair 01 and the rag rerun ran as real subagents from ~/scholarchain.
- storage-engineer ran `git rm --cached` by mistake and reverted it immediately; index verified clean.
- Phase 7 VectorWriter.scala deleted by storage-engineer (its TEXT_HASH_CONFLICT rule contradicted the Phase 8 UPDATED rule).
- The rag-engineer first run ended before printing a final report (its background sbt check outlived the session). agent-report-rag-partial.md records only facts visible in the session log.
- The rag rerun started before prompt.md, agent-report-storage.md and agent-report-build-repair01.md were filled in. The agent worked from the inline prompt, repair-01.md and the code. Files were filled in afterward from session logs, verbatim.
- The guard hook blocks agent greps of docs/agent-runs/ (human-owned). From Phase 9 on, prior reports are pasted inline in work orders.
- Test-count labels differ between reports (storage-engineer listed storage 352; the rerun lists storage 160 and tests/** 352). Both sbt check runs exited 0. Test suites appear to run twice inside sbt check; not investigated.
- The WSL clock jumped during the repair 01 sbt check, so the reported 24118 s runtime is wrong (about 40 minutes of wall-clock time).
- No manual code edits by the human.

## Deferred
- Bounded AWS ingestion (>= 10,000 vectors), throughput, query latency, rejected records, index counts and cost reporting. Needs Phase 12 Terraform and Phase 14 deploy; will be added as a Phase 8 repair record.
- LiveRetrievalSmokeIT not run against Bedrock + S3 Vectors; runs with the deferred ingestion.
- S3 Vectors limits and the PutVectors rate quota to be re-verified against current docs before the live run.

## Follow-ups routed
- spark-engineer: export semantic summaries as JSONL alongside Parquet (IngestMain reads JSONL; rag must not read Parquet). Needed before the live ingestion.
- domain-engineer: move the ingestion env vars plus SCHOLARCHAIN_RAG_* and SCHOLARCHAIN_EMBEDDING_NORMALIZE into core config (Phase 11 repair loop).
- infra-engineer: declare the 6 non-filterable metadata keys on every index (Phase 12 work order).

## Remaining risk
- PutVectors is an unconditional upsert: run one writer per index.
- rag's compile classpath includes storage's AWS SDK and parquet libraries; the ports-only rule is enforced by review, not by the build.

## Accepted commit
See commit.txt.

## Import check (rag main sources)
modules/rag/src/main/scala/scholarchain/rag/embed/Embedders.scala:6:import software.amazon.awssdk.core.SdkBytes
modules/rag/src/main/scala/scholarchain/rag/embed/Embedders.scala:7:import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
modules/rag/src/main/scala/scholarchain/rag/embed/Embedders.scala:8:import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
