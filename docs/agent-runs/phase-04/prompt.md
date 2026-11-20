# Phase 04 work order: raw ingestion and Hadoop MapReduce

## Orchestrator rules
- Run in this order: A domain-engineer -> B hadoop-engineer -> C test-engineer -> D code-reviewer. Each runs in a fresh context. Paste every earlier final report into the next work order.
- Do not do specialist work inline. Reading files to route work is allowed.
- BLOCKED-ON routing:
  - Missing or incompatible dependency, JVM flag, fork setting, or sbt setting -> build-engineer, which runs sbt check.
  - Missing sample file or DTD -> dataset-engineer.
  - Missing or changed shared contract or config key -> domain-engineer.
  After the repair, rerun the blocked agent in a fresh context. Record each event as repair-NN.md.
- Test-engineer failures that expose production defects go to hadoop-engineer. Then test-engineer reruns.
- Reviewer findings go to the owning agent. The reviewer never edits.
- Save final reports verbatim:
  - A and B -> docs/agent-runs/phase-04/agent-report.md
  - C -> test-report.md
  - D -> review-report.md
- Stop for human ratification. Do not commit.

## Pinned stack (from project/Dependencies.scala; only build-engineer changes it)
- JSON: uPickle. Tests: ScalaTest. Config: Typesafe Config (HOCON).
- Live-AWS tests must use awsIntegrationTag. Phase 4 needs none.
- Hadoop: 3.4.2 (hadoop-client, Provided), already pinned. Do not change it.
- Datasets: DBLP (required), OpenAlex, ORCID, Crossref. Samples are in datasets/samples/. The DBLP DTD is datasets/samples/dblp/dblp-2023-06-28.dtd.

## Work order A: domain-engineer (additive contract changes only)
Add the items below only where they are missing. Do not change any frozen wire format from Phase 3.
- MatchCandidate: candidateId, entityType (RESEARCHER | WORK), leftRef, rightRef, blockKey, features: Map[String, Double], featureVersion, provenance. No decision field: decisions belong to Phase 5.
- ExactIdentifierOutcome: idType, normalizedId, recordRefs, outcome (MATCH | DUPLICATE | CONFLICT), provenance.
- RejectRecord: schemaVersion, source, sourceRecordRef, stage, reasonCode, rawExcerpt (max 1024 chars), runId.
- An author-paper record keyed by author mention and work ID, unless AuthorshipRecord already covers mention-level authorship.
- Config section scholarchain.hadoop with these keys and validation:
  - numReducers
  - maxBlockSize (default 200)
  - largeTeamThreshold (default 50)
  - maxPairExpansionTeamSize (default 2000; must be >= largeTeamThreshold)
  - intermediateCodec, outputCodec
  - dblpRecordTypes and yearFrom/yearTo, taken from the manifest subset rule
  - dblpDtdPath
- Freeze JSON examples for each new record in contract tests. Add validation tests for invalid config.
- Run sbt check. Report the new schema versions.

## Work order B: hadoop-engineer
Implement the Phase 4 jobs from spec/scholarchain-spec.md using these pinned decisions.

Job chain (J01-J09 from the spec). These merges are allowed: J03+J04, J05+J06, J07+J08 (using MultipleOutputs). Every job must be a real MapReduce job using org.apache.hadoop.mapreduce. Mapper and reducer classes are top-level classes with no-arg constructors. No local collection loops.

1. DBLP reader: a custom InputFormat and RecordReader that synchronize on top-level start tags of the configured record types.
   - A record that starts inside a split is read to its end tag, even past the split end.
   - A record that starts before the split start is skipped.
   - Supports uncompressed and bzip2 input. Gzip is allowed but gives one split; say so in the report.
2. Entities: resolve DBLP entities using the DTD at dblpDtdPath, shipped through the distributed cache. Disable network and external-entity resolution (XXE). An unknown entity produces a reject with reason UNKNOWN_ENTITY. Never hardcode a partial entity table.
3. Normalizers: one per dataset (OpenAlex, ORCID, Crossref), reading the formats in datasets/samples.
   - Keep every source-specific ID, author ID, affiliation, topic ID and abstract reference.
   - ORCID: keep only the fields allowed by the manifest privacy policy.
4. Records are written as JSON Lines using the Phase 3 uPickle codecs. Do not create a new serialization format.
5. Output path: <outputRoot>/<dataset>/<table>/snapshot=<date>/run_id=<runId>/. The runId comes from the Phase 3 run-ID function over input checksums, config, and code version.
   - Target has _SUCCESS: skip and log.
   - Target exists without _SUCCESS: fail with an actionable message.
   - Never delete output.
6. Compression: turn on map-output compression with codec from config. The default must work without native libraries. Final output is gzip.
7. Partitioning: hash on stable keys, reducer count from config. Document both for each job.
8. Pairs come from publication-team records.
   - Remove a repeated mention within one paper (counter DUPLICATE_AUTHOR_IN_TEAM).
   - Use the Phase 3 canonical pair order.
   - n authors produce n(n-1)/2 pairs. 0 or 1 author produces none.
   - Each pair carries workId, year, both author positions, and source provenance.
   - Team size > largeTeamThreshold: counter LARGE_AUTHOR_TEAM and a large-teams side output. Still expand.
   - Team size > maxPairExpansionTeamSize: do not expand. Route to an oversize-teams output and count OVERSIZE_AUTHOR_TEAM. Still emit the team and author-paper records so maximum team size stays correct. Never drop silently.
9. Blocking:
   - Researcher key: normalized surname + first initial.
   - Work key: title-prefix tokens + year bucket.
   - A block larger than maxBlockSize is re-keyed once with a fallback key (coauthor surname or year bucket).
   - Still too large: send to an oversize-blocks output and count OVERSIZE_BLOCK. Never use a bigger reducer or silent truncation.
10. Candidate join emits MatchCandidate with deterministic features only. No thresholds or decisions.
11. No mutable shared state across tasks: no global vars or singletons holding data. Per-task state lives only in setup and cleanup.
12. Counters: one enum with INPUT_RECORDS, ACCEPTED, REJECTED, DUPLICATE, CONFLICT, OVERSIZE_BLOCK, LARGE_AUTHOR_TEAM, OVERSIZE_AUTHOR_TEAM, EMITTED_PAIRS, MISSING_KEY, SCHEMA_FAILURE, UNKNOWN_ENTITY, DUPLICATE_AUTHOR_IN_TEAM. Print them at the end of each job and write them as counters.json next to the output.
13. Rejects go to a MultipleOutputs "rejects" output as RejectRecord.
14. Driver: one main class that chains all jobs in local mode (mapreduce.framework.name=local, file:///). It must be runnable as:
    sbt "hadoop/runMain <MainClass> --input-root datasets/samples --output-root target/phase04/<name>"
    Do not create scripts under scripts/ or modules/*/scripts/. Report the EMR main class and arguments for infra-engineer.
15. Write modules/hadoop/JOBS.md. For each job, document: input format, input K/V, mapper output K/V, combiner, partitioner, reducer I/O, counters, reject conditions, reducer count, input and output prefixes, codec, idempotency rule, and test fixture.

Tests (ScalaTest, modules/hadoop/src/test):
- Parser:
  - multiline title
  - DTD entity
  - Unicode
  - missing optional fields
  - repeated author
  - records across split boundaries: force mapreduce.input.fileinputformat.split.maxsize small on an uncompressed sample, then assert the same records as a single-split run
- Pairs:
  - 0, 1, 2, 3 and 5 authors give 0, 0, 1, 3 and 10 pairs
  - canonical order
  - duplicate author
  - large-team and oversize-team routing
- Normalization and key functions: DOI forms, ORCID checksum, arXiv IDs, blocking fallback, oversize blocks.
- LocalJobRunner integration tests for J01 and J07/J08 on the samples.
- Determinism test: two runs into different output roots give identical sorted output.

Verification: run sbt check. Run the driver twice, into target/phase04/run-a and target/phase04/run-b. Report:
- job graph and K/V contracts
- partition and reducer counts
- all counters
- reject counts with example reasons
- output paths
- maximum observed team size and its workId
- largest pair expansion
- largest blocking group
- determinism comparison result
- standard report fields

## Work order C: test-engineer
Add adversarial tests in your owned test territory only. Never edit production code. Cover:
- malformed and truncated XML
- unknown entities
- Unicode and confusable characters
- missing fields
- duplicate and conflicting DOIs
- common names (block overflow)
- reordered author lists
- a giant team above maxPairExpansionTeamSize
- a record straddling a split boundary
Leave failing tests failing. Report exact pass and fail counts and the owner of each failure.

## Work order D: code-reviewer (read-only)
Review the Phase 4 diff against the gate below. Verify every finding against the files. For each finding give severity, file and line, failing input, impact, and owner. Return APPROVE or REQUEST_CHANGES.

## Phase 4 gate
- At least two real MapReduce jobs execute. No local loops disguised as MapReduce.
- The DBLP reader handles record and split boundaries correctly.
- Pair counts are correct for 1-, 2-, 3- and larger-author papers. Large teams are counted, not silently dropped.
- Counters and reject outputs are visible.
- Repeated runs give equivalent logical output.
- Every output record carries provenance.
- Adversarial tests are present. sbt check passes.

## Known build risk
hadoop-client is Provided. build.sbt already makes Compile / run use the full Compile classpath, but runMain may not be covered. If the local driver fails with ClassNotFoundException or NoClassDefFoundError for org.apache.hadoop classes, hadoop-engineer must return BLOCKED-ON build-engineer with the exact error. build-engineer then makes hadoop/runMain use the full Compile classpath (forked, with the existing Java 17 openings), without changing assembly scope, and runs sbt check. hadoop-engineer must not edit build.sbt or add hadoop-client to a different scope.
