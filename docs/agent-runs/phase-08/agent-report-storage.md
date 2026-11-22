# storage-engineer, Phase 8 work order 1 (final report, verbatim from session)

STATUS: COMPLETE
OWNER: storage-engineer (executed inline by the orchestrator session)
WORK ORDER: Phase 8, work order 1 of 2 — S3 Vectors ingestion path
FILES CREATED (modules/storage/src/main/scala/scholarchain/storage/vectors/):
  VectorKeys.scala, VectorGuards.scala (moved out of VectorWriter), MetadataSchema.scala,
  IngestionSettings.scala, IngestionDocuments.scala (DocumentStore, ManifestStore, manifest types),
  IngestionSupport.scala (RateLimiter, Backoff), VectorIngestor.scala
  modules/storage/src/test/scala/scholarchain/storage/VectorIngestionSpec.scala (26 tests)
FILES MODIFIED: VectorTypes.scala (new metadata schema, 6 non-filterable keys),
  VectorContracts.scala and VectorSpec.scala (test helpers moved to the new schema)
FILES DELETED: VectorWriter.scala (its TEXT_HASH_CONFLICT rule contradicts the new UPDATED rule)
COMMANDS RUN: sbt storage/compile, storage/Test/compile, storage/testOnly (vector specs), sbt fmt, sbt check
TEST RESULTS: sbt check passed. Storage: 352 passed, 0 failed, 9 ignored (the AWS-tagged tests).
  The other modules also passed (664 / 160 / 212 / 75 / 66).
VALIDATION RESULTS: all 7 required local tests are in VectorIngestionSpec and pass. No live AWS tests were run.

Deviations and gaps
- Agent routing: storage-engineer isn't offered as an Agent type in this session, so I followed .claude/agents/storage-engineer.md inline.
- Service limits not re-fetched: I don't have the aws-docs tools here. The limits below come from the Phase 7 constants, documented as retrieved 2026-11-21. Please have them re-verified before the bounded live test.
- Rate-limit value not verified: the PutVectors requests-per-second quota was not checked against the docs. The rate is config-only, with no default.
- Config: I can't edit modules/core, so the new settings are read from env vars. They are listed under Rate limit and in the follow-ups.
- Staged deletion reverted: I ran git rm --cached once by mistake and reverted it immediately. The index is clean and only the working tree has changes.

Metadata schema vector-metadata-v1 (identical on all 5 indexes, each with its own MetadataSchema value)
- Filterable: entityId, documentType, windowStartYear, windowEndYear, topicIds (capped), institutionIds (capped), sourceDataset, communityId (when applicable). Empty lists are omitted.
- Non-filterable (6, declared at index creation, limit 10): textS3Uri, textHash, embeddingModel, embeddingModelVersion, runId, schemaVersion.
- Checked before every PutVectors:
  - Filterable bytes <= 2000 on every index mode, including ENHANCED, as a project rule.
  - Total key count, per-field and total byte limits per index mode, and list caps.
  - An oversize record gets a reason code (FILTERABLE_METADATA_TOO_LARGE, TOO_MANY_TOPIC_IDS, ...). Nothing is truncated.

Key formula (vector-key-v1): lowerHex(SHA-256(utf8("entityId|documentType|windowStartYear|windowEndYear|ordinal"))). A component containing | or a control character is refused, which keeps the encoding unambiguous. Text is not part of the key. A known-answer test pins it.

Document store: <ragBucket>/<indexName>/<documentType>/<entityId>/<hex(textHash)>.json.
- I used the hex without the sha256: prefix in the filename.
- The object holds the full text and provenance.
- A later run that re-renders identical text keeps the first object, so its provenance stays the first writer's.

Duplicate rule
- Same hash and same model id and version gives SKIPPED_UNCHANGED.
- A different hash or model gives UPDATED. The old and new hashes and model versions are written to RunLedger.
- Two different texts under one key in a run give REJECTED_KEY_COLLISION, and neither is written.

Batch size: batch.putVectors, at most 500; the 1,201-item test splits into 500/500/201. GetVectors lookups use batch.getVectors, at most 100.

Rate limit: a per-index limiter that spaces PutVectors requests evenly, 1/rate seconds apart. It is configured by env: SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_PER_SECOND as the default, with optional ..._RESEARCHERS|COMMUNITIES|ABSTRACTS|PATHS|TOPIC_INSTITUTIONS overrides.

Retry policy
- Only throttling, timeouts and 5xx are retried. Everything else fails at once.
- It uses the configured retry.* values: bounded exponential backoff with full jitter, delay uniform in [0, min(max, base*2^(n-1))].
- Each put attempt re-acquires the rate limiter.
- Each put batch writes a RunLedger entry with attempts, updated hashes and any failure. A give-up is recorded as FAILED.
- Failed items go to the rejected-vectors output with reason codes: the RejectSink plus a content-addressed S3 copy.
- Wire the S3 Vectors adapter with retry.copy(maxAttempts = 1), or attempts multiply.

Idempotency
- Documents are content-addressed.
- The manifest is immutable per run ID, at ingestion-manifests/<runHex>.json, and is registered once in ArtifactRegistry.
- Lookups use GetVectors, so skipped items do no embedding, no puts, no ledger entries and no rejects.
- The test checks vectors, objects, ledger and registry are unchanged on rerun.

Service limits relied on (https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html)
- PutVectors <= 500 vectors per call.
- GetVectors <= 100 keys per call.
- Dimension <= 4096.
- Filterable metadata 2 KB on CLASSIC (40 KB on ENHANCED, but the project caps at 2 KB).
- Total metadata 40 KB on CLASSIC.
- At most 10 non-filterable keys per index and 50 metadata keys per vector.
- Request payload <= 20 MiB. The worst case is about 8.5 MB, so I did not add payload splitting.

ASSUMPTIONS
- "Object whose sha256 equals textHash" is read as the text inside the JSON object, not the whole file.
- The manifest has an added ordinal field, because the key needs it.
- The existing-record lookup uses GetVectors, not ArtifactRegistry.

RISKS
- PutVectors is an unconditional upsert, so run one writer per index.
- A vector in the old Phase 7 metadata format makes its lookup chunk fail with LOOKUP_FAILED. None should exist, since Phase 7 didn't populate anything.
- Spark's semantic_fact to RenderedDocument mapping is not built, because rendering is out of scope.

MANUAL ACTION REQUIRED
- domain-engineer: move the ingestion env vars into core config, covering rate limit, topic/institution caps, EMBEDDING_MODEL_VERSION and ledger sequence.
- infra-engineer: indexes must declare the 6 non-filterable keys from IndexDefinition.NonFilterableMetadataKeys.
- A human decides whether to re-verify the limits from live docs and run the bounded live test.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, bounded load test, code-reviewer, then work order 2 (rag-engineer: rendering and embedder)
