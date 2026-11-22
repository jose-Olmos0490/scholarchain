STATUS: BLOCKED-ON
OWNER: storage-engineer
WORK ORDER: Phase 07: storage ports, adapters, loaders and contract tests (modules/storage/**). Text is in docs/agent-runs/phase-07/prompt.md.

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (output observed):
- `sbt -batch "export storage/Test/dependencyClasspath"`: 79 entries. None matches parquet, hadoop, avro, arrow, snappy or thrift.
- `sbt -batch "export sparkAnalytics/Test/dependencyClasspath"`: Spark 4.1.1 resolves parquet-hadoop, parquet-column, parquet-common, parquet-encoding, parquet-format-structures and parquet-jackson at 1.16.0, plus hadoop-client-api 3.4.2, hadoop-client-runtime 3.4.2, snappy-java 1.1.10.8 and zstd-jni 1.5.7-6.
- grep of parquet-hadoop-1.16.0.pom in the coursier cache: hadoop-client and hadoop-common are `provided` (3.3.0). snappy-java 1.1.10.7, aircompressor 2.0.2, commons-pool 1.6 and zstd-jni 1.5.7-3 are compile-scope.
- grep of modules/spark: no Parquet compression codec is set, so Spark's default (snappy) applies to the Phase 6 output.
- Read of the core config (reference.conf, ScholarChainConfig.scala): StorageConfig, NeptuneConfig, VectorIndexConfig and RetrievalConfig contents are listed under BLOCKED-ON EVIDENCE.
- aws-docs MCP: search_documentation and read_documentation on the S3 Vectors limits page. Both worked; the limits are recorded below.
- `sbt check`: not run, because no source was changed.

TEST RESULTS: none run. No tests were written.
VALIDATION RESULTS: The work order's own condition applies: "If no suitable Parquet reader is on the storage classpath, return BLOCKED-ON build-engineer naming the exact artifact and version you need." That is confirmed.

BLOCKED-ON OWNER: build-engineer (main blocker). domain-engineer (second blocker; both can proceed in parallel).

BLOCKED-ON EVIDENCE:

1. build-engineer: needed in project/Dependencies.scala `storage`, Compile scope, versions matched to what Spark 4.1.1 already resolves in this build:
   - `org.apache.parquet:parquet-hadoop:1.16.0`. This is the streaming row reader (ParquetReader / ParquetFileReader with GroupReadSupport). It also has a writer, so tests can build small Parquet fixtures without Spark. It brings in parquet-column, parquet-common, parquet-format-structures, snappy-java, aircompressor, zstd-jni and commons-pool.
   - `org.apache.hadoop:hadoop-client-api:3.4.2` and `org.apache.hadoop:hadoop-client-runtime:3.4.2` at Compile scope in storage only. parquet-hadoop marks Hadoop as `provided`, and the storage loaders do not run inside EMR, so Hadoop must ship with storage. The existing `Dependencies.hadoopClient` is `Provided`, so it can't be reused here.
   - Snappy is needed because Phase 6 writes Spark-default snappy Parquet. parquet-hadoop brings snappy-java 1.1.10.7 while Spark resolves 1.1.10.8. Build-engineer should decide whether to pin 1.1.10.8 for consistency.
   - Ask build-engineer to confirm the storage Test classpath gets no scala-xml or cross-version conflicts. Storage's internal dependencies (core only) do not change.

2. domain-engineer: these config fields are missing from the core config. The rule is "no hardcoded bucket/table/index/endpoint/Region/batch-size values; if a config field is missing, return BLOCKED-ON domain-engineer". The config has storage.{raw,curated,graph,rag,vector}Bucket, neptune.{endpoint,port,queryTimeoutMs}, the five vectorIndexes.* names, retrieval.{graphLimit,maxPathDepth,vectorLimit} and models.embeddingModelId. It is missing:
   - DynamoDB table names: runLedger, entityLinkCache, querySession, evidenceBundle and artifactRegistry. Add researcherMetricCache only if the cache is accepted.
   - The location for the reports and logs prefixes. Either name a bucket for each, or state that they live under an existing bucket.
   - Batch sizes: S3 Vectors PutVectors batch (documented maximum 500), S3 Vectors GetVectors batch (maximum 100), DynamoDB batch-write size, and the Neptune bulk-load parallelism setting.
   - Retry policy: maxAttempts, baseDelayMs, maxDelayMs.
   - Per vector index: dimension, distance metric, and index type (CLASSIC or ENHANCED). The limits depend on the index type, as shown below.
   - An embedding model version field, if models.embeddingModelId is not meant to serve as the version.
   - Neptune bulk loader: IAM role ARN and the S3 source prefix for the CSV export.
   - The QuerySession TTL duration (the one proposed disposable record).
   - Optionally, per-service endpoint overrides (local and integration test) and the name of the explicit AWS profile variable for the integration suite. Today build.sbt only reads SCHOLARCHAIN_AWS_IT.

SERVICE LIMITS (verified, not from memory):
Source: https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html, retrieved 2026-11-21 through the aws-docs MCP.
- Dimension: 1 to 4,096.
- Total metadata per vector: 100 KB on ENHANCED indexes, 40 KB on CLASSIC.
- Total metadata keys per vector: up to 50.
- Filterable metadata per vector: 40 KB on ENHANCED, 2 KB on CLASSIC.
- Filterable string value: up to 2 KB each, including each element of a list.
- Non-filterable metadata keys per index: up to 10.
- Single non-filterable field: 100 KB on ENHANCED, 40 KB on CLASSIC.
- Vectors per PutVectors call: up to 500. Per DeleteVectors call: up to 500. Per GetVectors call: up to 100.
- Top-K per QueryVectors call: up to 10,000. Results per page: up to 100. Filter constraints per query on ENHANCED: up to 100.
- Request payload: up to 20 MiB.
- Per index: up to 1,000 combined Put/Delete requests per second, up to 2,500 vectors inserted or deleted per second, up to 140 MBps.

DynamoDB, Neptune bulk-load and S3 limits were not fetched this run. They will be fetched through aws-docs when implementation resumes.

ASSUMPTIONS:
- Writing a Parquet decoder (Thrift footer plus snappy) inside storage is not "a suitable reader", and the work order rules out using Spark or the sparkAnalytics module.
- Partial delivery (ports, fakes, DynamoDB and vectors without the Parquet loaders) was not authorized. Parquet fixtures are also a required test input. So I stopped instead of leaving a half-built tree.
- The ownership notes (docs/agents.md) do not put the OpenSearch decision in this phase; the work order says it is not implemented this phase.

RISKS:
- The S3 Vectors limits differ a lot between CLASSIC and ENHANCED indexes, for example 2 KB versus 40 KB of filterable metadata. Index type has to come from config and match the Phase 12 Terraform. Otherwise the client-side limit checks will be wrong.
- hadoop-client-runtime is a large shaded jar and will make any storage or services image noticeably bigger. Build-engineer and the human should accept that cost.

MANUAL ACTION REQUIRED: The human or orchestrator routes item 1 to build-engineer and item 2 to domain-engineer. After both land, re-dispatch the Phase 07 work order to storage-engineer unchanged.

NEXT GATE: build-engineer adds the dependencies (`sbt check`) and domain-engineer adds the config fields (contract tests, `sbt check`). Then storage-engineer resumes Phase 07 (contract tests, bounded load test, code-reviewer).
