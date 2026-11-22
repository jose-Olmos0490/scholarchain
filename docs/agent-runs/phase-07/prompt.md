Use the storage-engineer agent to implement ports, adapters, loaders, and contract tests for the ScholarChain storage layer in modules/storage/**.

Context (read, do not modify):
- Phase 3 contracts in modules/core: StableIds, GraphNode, GraphEdge, CoauthorEdge, ResearcherMetrics, EvidenceBundle, Provenance, the load-manifest type (golden example: modules/core/src/test/resources/golden/v1/load-manifest.json), and the config loader.
- Phase 6 Spark code: modules/spark/src/main/scala/scholarchain/spark/run/LoadManifests.scala. Spark emits Parquet only, plus per-target load manifests (neptune, dynamodb, s3, s3vectors-input) listing files with size and SHA-256. Converting Parquet into store-specific formats is this phase's job.
- docs/agent-runs/phase-06/agent-report.md for table schemas and schema versions.
- No AWS resources exist yet; Terraform is Phase 12.
- The SQS embedding work queue is Phase 8a. Do not implement it here.

Design rules:
- One Scala trait (port) per store, expressed only in core domain types.
- Two implementations per port: an in-memory fake used by sbt check, and an AWS SDK v2 adapter (dependencies already in project/Dependencies.scala).
- One reusable contract-test suite per port. It runs against the fake by default. It runs against the AWS adapter only when SCHOLARCHAIN_AWS_IT=true and an explicit AWS profile is set; otherwise those tests are reported as SKIPPED, never as passed.
- No hardcoded bucket, table, index, endpoint, Region, or batch-size values. Read them through the Phase 3 config loader. If a config field is missing, return BLOCKED-ON domain-engineer.
- Consume load manifests through the core load-manifest type. Verify every input file's SHA-256 against its manifest entry before loading; a mismatch fails the load.
- Read Parquet with a streaming reader (row by row, never the whole table in memory). Do not depend on the sparkAnalytics module or Spark. If no suitable Parquet reader is on the storage classpath, return BLOCKED-ON build-engineer naming the exact artifact and version you need.

1. Ordinary S3 (ObjectStore port)
- Prefixes: raw, curated, graph, rag (semantic context), reports, logs, consistent with the Phase 6 layout.
- Versioned keys under run_id=<runId>/. Never overwrite an existing run prefix.
- A JSON run manifest per load: object key, sha256, byte size, schema version, source run ID.
- Same key + same hash = skip. Same key + different hash = fail loudly.

2. Neptune (NeptuneCsvExporter + GraphLoader + GraphReader ports)
- Convert the Parquet files listed in the neptune load manifest into Neptune openCypher bulk-load CSV (loader format=opencypher, RFC 4180, UTF-8): separate node and relationship files; node headers :ID, :LABEL plus typed property headers (name:Type); relationship headers :ID, :START_ID, :END_ID, :TYPE plus typed properties.
- Use Phase 3 stable node and edge IDs as :ID so reloading the same run is idempotent.
- Labels: Researcher, Work, Venue, Institution, Topic, plus context nodes Phase 6 produces.
- Relationship types as produced by Phase 6: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY. List any type Phase 6 does not produce.
- COAUTHORED_WITH: one stored edge per canonical pair (Phase 3 ordering rule) with jointPublicationCount, firstCollaborationYear, lastCollaborationYear, windowStartYear, windowEndYear, evidencePointer (resolves to supporting work IDs), runId, policyVersion. Document that reads must use the undirected pattern -[:COAUTHORED_WITH]-.
- Researcher node properties: P_r, C_r, T_r, A_r, M_r, W_r, componentId, communityId, bridgeScore, coreness, window.
- Emit a CSV export manifest (file, row count, sha256, source Parquet file) next to the CSVs.
- Pre-load validator: fail on any relationship endpoint missing from node files, any duplicate :ID, and any COAUTHORED_WITH edge without an evidence pointer.
- GraphReader: named, parameterized openCypher templates only (getResearcher, directCoauthors, coauthorEdge, shortestPath). Depth and limit bounded by config. No string concatenation of caller values into queries.
- Starting a live Neptune load job is an adapter method; it is not exercised by sbt check.

3. DynamoDB (one port per table). Derive PK/SK from these access patterns and report the mapping:
- RunLedger: record each stage attempt of a run; list all stages of a run in order.
- EntityLinkCache: get an adjudication by (inputHash, promptVersion, modelVersion, policyVersion).
- QuerySession: create once per queryId; get by queryId; detect a repeated Idempotency-Key.
- EvidenceBundle: get one item by evidenceId; list all evidence for a queryId.
- ArtifactRegistry: get latest and a specific version of a named artifact.
- ResearcherMetricCache only if justified: get metrics by researcherId and time window.
- Load items from the Parquet files in the dynamodb load manifest.
- Conditional writes (attribute_not_exists) on every create. Audit records are never overwritten.
- TTL only on genuinely disposable records; state which and why.

4. S3 Vectors (VectorIndex port)
- Abstractions for vector bucket, index (name, dimension, distance metric, non-filterable keys), put, query with metadata filter, get.
- Indexes: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1.
- Vector key = Phase 3 chunk/profile ID.
- Filterable metadata: researcherId or communityId, windowStartYear, windowEndYear, topicId, institutionId, documentType, sourceDataset.
- Non-filterable metadata: textS3Uri, textHash, embeddingModel, runId. Long text lives only in ordinary S3.
- Enforce limits client-side before any AWS call, using current AWS documentation via the aws-docs MCP (record URL and retrieval date): filterable metadata per vector, total metadata per vector, non-filterable keys per index, vectors per PutVectors call, top-K per query. Oversized records go to a rejected-vectors output.
- Same key + different textHash = reject and record; never silently overwrite.
- Batched writes; retry only throttling/transient errors with bounded exponential backoff and jitter; record retry counts.
- No embedding generation (Phase 8). Tests use small fixed float vectors.

5. OpenSearch: not implemented this phase. State it in the report.

Required tests (fakes, run by sbt check):
- Tests must not read modules/spark/local-run (gitignored). Use a small hand-checkable fixture under modules/storage/src/test/resources: a few Parquet files plus a load manifest in the golden v1 format, with IDs produced by the Phase 3 StableIds functions.
- Round-trip serialization for every stored record and CSV row.
- Parquet-to-Neptune-CSV: exact expected CSV for the fixture, including header types and canonical pair order.
- Manifest checksum mismatch fails the load.
- Idempotent reload: loading the same run twice gives identical store state.
- Missing and malformed metadata rejected with a clear reason.
- Duplicate vector key: same hash = no-op; different hash = rejected.
- Neptune validator: dangling endpoint, duplicate :ID, and missing evidence pointer each fail.
- DynamoDB: second conditional create with the same key fails with a typed error.
- Retry: transient failure then success is retried; permanent failure is not.
- AWS integration suite exists, is tagged, and is skipped unless SCHOLARCHAIN_AWS_IT=true.

Run sbt check.
Report: ports, schemas, Parquet reader choice, Neptune CSV headers per label and type, DynamoDB key design per access pattern, index definitions and metadata fields, batch sizes, retry policy, idempotency strategy, service limits with doc links, cleanup procedure per store, skipped AWS tests, and any BLOCKED-ON items, using the standard report contract.

Additional constraints:
- Do not commit; the human architect commits after ratification.
- If the aws-docs MCP is unavailable, do not fill service limits from memory; mark them UNVERIFIED and return BLOCKED-ON with evidence.
- Report only commands whose output you observed.
