# Phase 04 agent reports

## Work order A: domain-engineer

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 04, Work order A. Additive Phase 4 ingestion contracts and the `scholarchain.hadoop` configuration section, inside modules/core/** only. No Phase 3 wire format, golden file or schema version was changed. The old SHA256SUMS lines are byte-identical; four lines were added.

FILES CREATED:
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/model/Ingestion.scala
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/author-paper-record.json
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/match-candidate.json
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/exact-identifier-outcome.json
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/reject-record.json
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/IngestionContractSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/HadoopConfigSpec.scala

FILES MODIFIED (all under /home/olmos/scholarchain/modules/core/):
- src/main/resources/reference.conf
- src/main/resources/scholarchain/env-overrides.conf
- src/main/scala/scholarchain/core/config/ConfigCursor.scala (added `has`, `probe`)
- src/main/scala/scholarchain/core/config/ScholarChainConfig.scala (added HadoopConfig, the `ScholarChainConfig.hadoop` field and `DatasetConfig.readSubsetRecordTypes`)
- src/main/scala/scholarchain/core/ids/ExternalIds.scala (added `normalizeExactId`)
- src/main/scala/scholarchain/core/ids/IdKind.scala (added 2 kinds)
- src/main/scala/scholarchain/core/ids/StableIds.scala (added `matchCandidateId`, `matchCandidateRefKinds`, `exactIdOutcomeId`)
- src/main/scala/scholarchain/core/model/Enums.scala (added 3 enums)
- src/main/scala/scholarchain/core/schema/SchemaVersions.scala
- src/main/scala/scholarchain/core/validation/RecordContract.scala (added 4 contracts)
- src/test/resources/golden/v1/SHA256SUMS (4 lines added)
- src/test/resources/scholarchain/conf/contract-test.conf (test fixture: hadoop.numReducers, hadoop.dblpDtdPath)
- src/test/scala/scholarchain/core/{ConfigLoaderSpec,Fixtures,GoldenCases,SchemaVersionSpec,WireContractSpec}.scala

COMMANDS RUN:
- `sbt -batch core/compile`: success.
- `sbt -batch "core/testOnly scholarchain.core.TmpGoldenWriterSpec"`: a temporary test that wrote the 4 new goldens with the frozen encoder (2-space ujson plus newline). It passed and I deleted it afterwards.
- `sha256sum -c SHA256SUMS`: all 25 files OK. Diffing against the original showed only the 4 added lines.
- `sbt -batch core/scalafmtAll core/test`: 447 passed, 1 failed. The failure was my own test fixture: uppercasing a DOI with no letters changes nothing. I fixed the fixture.
- `sbt -batch check`: success.

TEST RESULTS: `sbt check` (scalafmtCheckAll, scalafmtSbtCheck, Test/compile, test, markTestRun) passed. Tests run: 448, succeeded 448, failed 0, 9 suites. The output ended with "markTestRun: tests passed; touched .claude/.last-test-run".

VALIDATION RESULTS:

New schema versions (all v1, for new record types only):
- AuthorPaperRecord: "author-paper-v1"
- MatchCandidate: "match-candidate-v1"
- ExactIdentifierOutcome: "exact-identifier-outcome-v1"
- RejectRecord: "reject-v1"

They are registered in `SchemaVersions.supported`. The decoder rejects an unsupported version before decoding any field (the existing SchemaVersionSpec loops now cover the 4 new goldens). The config file version is unchanged ("config-v1").

Types and wire field names, in order. All types are in package `scholarchain.core.model`, file model/Ingestion.scala. Codecs are uPickle `derives ReadWriter`. Use `WireCodec.decode`/`encodeValidated` with `RecordContract.given`.

1. AuthorPaperRecord: schemaVersion, mentionId, workId, position, teamSize, year (Int|null), normalizedName, sourceAuthorKey (String|null), orcid (String|null), provenance.
   - Keyed by (mentionId, workId). There is no new ID kind: mentionId must equal `StableIds.mentionId(workId, position)`.
   - Rules: position >= 1; teamSize >= 1; teamSize is the de-duplicated team size and equals PublicationTeam.teamSize, so position may exceed teamSize; orcid must already be normalized.
   - Why it was added: AuthorshipRecord does NOT cover mention-level authorship. It needs a resolved researcherId and a matchConfidence. AuthorMention has no teamSize or year.

2. MatchCandidate: schemaVersion, candidateId, entityType ("RESEARCHER"|"WORK"), leftRef, rightRef, blockKey, features (object name -> number), featureVersion, provenance. There is no decision, threshold or confidence field, and a test asserts the exact key list.
   - candidateId = `StableIds.matchCandidateId(entityType, refX, refY, featureVersion)` = `match-candidate:sha256(entityType, a, b, featureVersion)`, where (a, b) is the canonical pair.
   - Input order does not matter. blockKey is NOT an input, so a pair reached through both a primary and a fallback block has one ID and duplicates can be collapsed by candidateId.
   - Refs must be canonical (leftRef < rightRef). Self-pairs are rejected.
   - Ref kinds (`StableIds.matchCandidateRefKinds`): RESEARCHER takes `mention:` or `researcher:` IDs; WORK takes `work:` IDs.
   - Feature values must be finite and feature keys non-blank. blockKey and featureVersion must be non-blank.

3. ExactIdentifierOutcome: schemaVersion, outcomeId, idType, normalizedId, recordRefs, outcome ("MATCH"|"DUPLICATE"|"CONFLICT"), provenance.
   - idType enum `ExactIdType`: DOI, ARXIV, DBLP_KEY, OPENALEX_WORK, ORCID, DBLP_PID, OPENALEX_AUTHOR.
   - normalizedId must equal `ExternalIds.normalizeExactId(idType, normalizedId)`.
   - outcomeId = `StableIds.exactIdOutcomeId(idType, rawOrNormalizedId)` = `exact-id:sha256(idType, normalized)`. The function normalizes its input. The outcome and the records are not inputs.
   - recordRefs: at least 2, distinct, sorted ascending. Kinds: `work:` for DOI, ARXIV, DBLP_KEY and OPENALEX_WORK; `mention:` or `researcher:` for ORCID, DBLP_PID and OPENALEX_AUTHOR.
   - Core fixes only the names of the outcomes. The criteria that choose between MATCH, DUPLICATE and CONFLICT belong to the producer (documented on the enum).

4. RejectRecord: schemaVersion, source (SourceType), sourceRecordRef, stage, reasonCode, rawExcerpt, runId, provenance.
   - I added `provenance` because law 1 requires it on every record.
   - Validation requires runId == provenance.runId, source == provenance.sourceDataset and sourceRecordRef == provenance.sourceRecordId.
   - reasonCode must match `[A-Z][A-Z0-9_]*`. It is a free code, not an enum, so hadoop owns its codes.
   - rawExcerpt must be non-null (it may be empty) and at most `RejectRecord.MaxExcerptCodePoints` = 1024 Unicode code points. Use `RejectRecord.truncateExcerpt(s)`, which never splits a surrogate pair and turns null into "".
   - stage and sourceRecordRef must be non-blank. runId must be a `run:` ID.

New enums (in model/Enums.scala, written as bare strings): MatchEntityType, ExactIdType, ExactIdOutcome.

New IdKinds: `match-candidate` and `exact-id`, neither with a namespace.

Sample serialized record (golden match-candidate.json, abridged):
{"schemaVersion":"match-candidate-v1","candidateId":"match-candidate:dc1df0b6...eb2a","entityType":"WORK","leftRef":"work:dblp:c0529...81f8","rightRef":"work:openalex:6e92d...30da","blockKey":"illustrative-block:embedded systems design|2000","features":{"doiEqual":1,"titleTokenJaccard":1,"yearDelta":0},"featureVersion":"illustrative-features-v1","provenance":{...}}
uPickle writes integral doubles as `1`, not `1.0`. The 4 goldens are at modules/core/src/test/resources/golden/v1/.

Config section `scholarchain.hadoop` (class `scholarchain.core.config.HadoopConfig`; load it with `ConfigLoader.loadSection(HadoopConfig, sys.env)` or use `ConfigLoader.load(...).hadoop`):

| Key | Env var | Default | Rule |
|---|---|---|---|
| numReducers | SCHOLARCHAIN_HADOOP_NUM_REDUCERS | none (required) | >= 1 |
| maxBlockSize | SCHOLARCHAIN_HADOOP_MAX_BLOCK_SIZE | 200 | >= 2 |
| largeTeamThreshold | SCHOLARCHAIN_HADOOP_LARGE_TEAM_THRESHOLD | 50 | >= 1 |
| maxPairExpansionTeamSize | SCHOLARCHAIN_HADOOP_MAX_PAIR_EXPANSION_TEAM_SIZE | 2000 | >= 2 and >= largeTeamThreshold |
| intermediateCodec | SCHOLARCHAIN_HADOOP_INTERMEDIATE_CODEC | org.apache.hadoop.io.compress.DefaultCodec | in `HadoopConfig.SupportedCodecs` |
| outputCodec | SCHOLARCHAIN_HADOOP_OUTPUT_CODEC | org.apache.hadoop.io.compress.GzipCodec | in `HadoopConfig.SupportedCodecs` |
| dblpRecordTypes | SCHOLARCHAIN_HADOOP_DBLP_RECORD_TYPES | value of datasets.dblpSubset.recordTypes | DBLP DTD elements, no blanks or duplicates, a subset of the subset types |
| yearFrom, yearTo | SCHOLARCHAIN_HADOOP_YEAR_FROM, SCHOLARCHAIN_HADOOP_YEAR_TO | values of datasets.dblpSubset.startYear/endYear | yearFrom <= yearTo, inside the subset years |
| dblpDtdPath | SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH | none (required) | non-blank, ends in .dtd, not an http(s) URL |

- `SupportedCodecs` is the six classes DefaultCodec, GzipCodec, BZip2Codec, Lz4Codec, SnappyCodec and ZStandardCodec under org.apache.hadoop.io.compress.
- The record-type and year fallbacks give article,inproceedings and 2000-2025 under SCHOLARCHAIN_ENV=dev, which is the manifest subset rule. If a fallback source key is missing or invalid, the error is reported once, at its `datasets.dblpSubset.*` path.
- `HadoopConfig.years` returns a YearWindow.
- Precedence is unchanged: environment variable > scholarchain/conf/<env>.conf > reference.conf. Only variables bound in env-overrides.conf are read.

Existing Phase 3 contracts that cover the other needs:
- `PublicationTeam` (publication-team-v1) for teams.
- `CoauthorPairCandidate` (coauthor-pair-candidate-v1): candidateId = `StableIds.pairCandidateId(workId, mentionX, mentionY)`; it carries workId, year and teamSize.
- Canonical order: `CanonicalPair.of(x, y)`, ordinal String.compareTo, self-pairs rejected.
- `StableIds.mentionId(workId, position)` and `StableIds.workId(WorkNamespace.Dblp, key)`.
- `StableIds.runId(RunManifest)`: run:sha256 of the canonical manifest JSON, independent of timestamps.
- `WorkRecord` and `AuthorMention` for normalized works.
- `ExternalIds` normalizers for DOI, ORCID checksum, arXiv, OpenAlex, dblp key and dblp pid.

ASSUMPTIONS:
- The defaults 200, 50 and 2000 and the codecs are placed in reference.conf because the human work order fixed them. This is the only documented exception to the "no defaults in reference.conf" rule, and ConfigLoaderSpec now pins exactly these entries.
- numReducers and dblpDtdPath are environment-specific (local paths vs S3, cluster size), so they have no shipped value. The local driver needs `SCHOLARCHAIN_ENV=dev SCHOLARCHAIN_HADOOP_NUM_REDUCERS=<n> SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH=datasets/samples/dblp/dblp-2023-06-28.dtd` in the environment. A forked runMain inherits it.
- Added fields beyond the work-order list: schemaVersion and outcomeId on ExactIdentifierOutcome (an evidence-citable stable ID), and provenance on RejectRecord (law 1).
- Lower bounds I chose: maxBlockSize >= 2 and maxPairExpansionTeamSize >= 2.

RISKS:
- `Map[String, Double]` key order in JSON follows the Scala Map. For byte-identical determinism, hadoop-engineer should build feature maps the same way every run, or compare sorted output. MatchDecision from Phase 3 has the same property.
- Adding the `hadoop` field to `ScholarChainConfig` changes its constructor arity. No module outside core constructs it today.
- ConfigLoaderSpec's frozen required-key list now includes hadoop.numReducers and hadoop.dblpDtdPath, so a full `ConfigLoader.load` needs them.

MANUAL ACTION REQUIRED:
- The human should confirm that the hadoop defaults in reference.conf (an exception to the "no defaults" rule) are acceptable.
- The human should decide whether dev.conf should ship numReducers and dblpDtdPath values. Its allowlist test currently forbids them.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, code-reviewer
```

## Work order B: hadoop-engineer, attempt 1 (BLOCKED-ON)

````text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 04, Work order B (docs/agent-runs/phase-04/prompt.md, "Work order B: hadoop-engineer"). I stopped before writing any jobs. The required local driver cannot load Hadoop classes under `hadoop/runMain`, which is the "Known build risk", and the shared pair contract has no field for author positions. No job code has been written, so there are no counters, outputs or determinism results to report.

FILES CREATED: none. A temporary probe file, /home/olmos/scholarchain/modules/hadoop/src/main/scala/scholarchain/hadoop/ProbeMain.scala, was created to reproduce the build risk and then deleted. modules/hadoop contains only `target/` again, and `git status --short modules/hadoop` is empty.
FILES MODIFIED: none

COMMANDS RUN:
1. `sbt -batch "hadoop/runMain scholarchain.hadoop.ProbeMain"`. The probe only does `new org.apache.hadoop.conf.Configuration()`. It failed with this exact output:
```
[info] running (fork) scholarchain.hadoop.ProbeMain
[error] Exception in thread "main" java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration
[error] 	at scholarchain.hadoop.ProbeMain$.main(ProbeMain.scala:5)
[error] 	at scholarchain.hadoop.ProbeMain.main(ProbeMain.scala)
[error] Caused by: java.lang.ClassNotFoundException: org.apache.hadoop.conf.Configuration
[error] 	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:641)
[error] 	at java.base/jdk.internal.loader.ClassLoaders$AppClassLoader.loadClass(ClassLoaders.java:188)
[error] 	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:525)
[error] 	... 2 more
[error] nonzero exit code returned from runner: 1
[error] (hadoop / Compile / runMain) nonzero exit code returned from runner: 1
```
2. `sbt -batch "hadoop/run"` with the same probe. It succeeded and printed `PROBE cwd=/home/olmos/scholarchain/modules/hadoop fs=file:///`. So the existing `Compile / run` override, which uses fullClasspath, works. Only `runMain` is missing the Provided Hadoop jars. The output also shows the forked JVM runs in `modules/hadoop`, not the repository root.
3. `sbt -batch check` after deleting the probe: success. "Tests: succeeded 448, failed 0", "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run".

TEST RESULTS: `sbt check` passes on the unchanged tree (448 tests, 0 failed). The hadoop module has no tests yet.
VALIDATION RESULTS: None of the Work order B verification items exist yet: job graph, partition and reducer counts, counters, rejects, output paths, maximum team size, largest pair expansion, largest block, determinism, and run-a/run-b. The driver could not be made runnable through the mandated `sbt "hadoop/runMain <MainClass> ..."` command.

ASSUMPTIONS:
- I am stopping at the first build blocker, as the work order requires. I am reporting the contract gap below in the same pass so that both repairs can be done before I am rerun. I found no other contract gaps after reviewing all core contracts against items 1-15 of Work order B:
  - Normalizer side data fits existing records: Institution, Topic and Venue records, GraphEdge for HAS_TOPIC, AFFILIATED_WITH and PUBLISHED_IN, and GraphNode properties for extra source IDs.
  - Large and oversize teams fit PublicationTeam.
  - Oversize blocks fit RejectRecord-shaped side output.
- The pair contract requires `year: Int`, so works without a year will be rejected in J01. The subset rule excludes them anyway.

RISKS:
- The forked run's working directory is `modules/hadoop`, as observed in command 2. The mandated relative arguments `--input-root datasets/samples --output-root target/phase04/<name>` would therefore resolve to `modules/hadoop/datasets/samples`, which does not exist. The fix should run the forked `runMain` (and `run`) from the repository root, for example with `run / baseDirectory` or the fork working directory set to the build root. That is build-engineer's decision; I will not work around it in the driver by guessing paths.
- `datasets.dblpSubset.maxWorks` (100000) is not in HadoopConfig. The deterministic cap rule is assigned to hadoop-engineer (datasets/schemas/dblp.md) and would need a global ordering step. This is not a blocker, but it will be reported when the jobs are built.
- The OpenAlex api-linked, Crossref and ORCID sample file names contain no snapshot date. The driver will need a `--snapshot dataset=label` option or another deterministic default. This is not a blocker.

MANUAL ACTION REQUIRED: The orchestrator should route the two repairs below, record them as repair-NN.md, and rerun hadoop-engineer in a fresh context.

BLOCKED-ON OWNER: build-engineer (primary); domain-engineer (contract gap)

BLOCKED-ON EVIDENCE:
1. build-engineer: `hadoop/runMain` does not get the full Compile classpath. The exact error is in command 1: `java.lang.NoClassDefFoundError: org/apache/hadoop/conf/Configuration ... Caused by: java.lang.ClassNotFoundException: org.apache.hadoop.conf.Configuration ... (hadoop / Compile / runMain) nonzero exit code returned from runner: 1`.
   - build.sbt `clusterJobSettings` overrides only `Compile / run` with `Defaults.runTask(Compile / fullClasspath, ...)`. `Compile / runMain` keeps the default runtime classpath, which leaves out the Provided hadoop-client.
   - Requested fix, per the prompt's "Known build risk": make `hadoop/runMain` use the forked full Compile classpath with the existing `java17ClusterOpens`, without changing the hadoop-client scope or assembly.
   - Also requested: make the forked working directory the repository root, so the mandated relative paths resolve.
   - Then run `sbt check`.
2. domain-engineer: Work order B item 8 says "Each pair carries workId, year, both author positions, and source provenance", and my role's law 6 says the same. `CoauthorPairCandidate` (coauthor-pair-candidate-v1, modules/core/src/main/scala/scholarchain/core/model/Bibliographic.scala) has these fields: schemaVersion, candidateId, workId, memberA, memberB, year, teamSize, provenance. It has no author-position fields. Positions cannot be recovered from `memberA`/`memberB`, because `mentionId` is a SHA-256 hash. `Provenance.derivedFrom` takes only stable IDs.
   - Requested: an additive contract with two fields, for example `positionA` and `positionB`. Each is the 1-based source author position of memberA/memberB, and must be >= 1 and distinct. If memberA/memberB are reordered canonically, the positions must be reordered with them. The validation rule should check `memberX == StableIds.mentionId(workId, positionX)`.
   - This needs a new schema version (`coauthor-pair-candidate-v2`) or whatever versioning decision domain-engineer makes, with goldens, since v1 is frozen.

NEXT GATE: After both repairs, rerun hadoop-engineer in a fresh context for the full Work order B: bounded run, deterministic output checks, then code-reviewer.
````

Repairs between attempt 1 and attempt 2: repair-01 (build-engineer, runMain classpath and working directory) and repair-02 (domain-engineer, coauthor-pair-candidate-v2 with author positions). See repair-01.md and repair-02.md.

## Work order B: hadoop-engineer, attempt 2 (COMPLETE; resumed once after an API rate-limit interruption)

````text
I finished Work order B and both bounded local runs are complete. All nine jobs ran, `sbt check` passes, and run-a and run-b produced identical output. Two things need a human decision (see MANUAL ACTION REQUIRED below): the default snapshot labels for the three sample files with no date in their name, and the fact that blocking keys and match features overlap entity-resolution territory.

STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 04, Work order B (attempt 2, resumed after an API rate-limit interruption). Implement the Phase 4 MapReduce jobs J01-J09: DBLP record reader, source normalizers, exact-identifier join, blocking, candidate features, publication teams and pairs, Spark export. Then the driver, JOBS.md, tests, `sbt check`, and two local runs into target/phase04/run-a and run-b.

FILES CREATED (all under /home/olmos/scholarchain/modules/hadoop/):
- JOBS.md
- src/main/scala/scholarchain/hadoop/HadoopCounter.scala
- src/main/scala/scholarchain/hadoop/io/{Layout,PipelineConf,Records,JobSupport,Dedup}.scala
- src/main/scala/scholarchain/hadoop/text/Normalize.scala
- src/main/scala/scholarchain/hadoop/dblp/{DblpInputFormat,DtdEntities,DblpRecordParser,DblpNormalizer}.scala
- src/main/scala/scholarchain/hadoop/sources/{Emitted,OpenAlexNormalizer,CrossrefNormalizer,OrcidNormalizer}.scala
- src/main/scala/scholarchain/hadoop/blocking/Blocking.scala
- src/main/scala/scholarchain/hadoop/pairs/TeamExpansion.scala
- src/main/scala/scholarchain/hadoop/jobs/{DblpExtractJob,WorkCapJob,SourceNormalizeJob,ExactIdentifierJob,BlockingJobs,PublicationTeamJob,GraphCandidateExportJob}.scala
- src/main/scala/scholarchain/hadoop/driver/PipelineDriver.scala
- src/test/resources/fixtures/dblp/dblp-fixture.xml (ISO-8859-1, 17 hand-checkable records)
- src/test/scala/scholarchain/hadoop/{HadoopTestSupport,DblpRecordParserSpec,DblpInputFormatSpec,TeamExpansionSpec,KeysAndSourcesSpec,PipelineIntegrationSpec}.scala

FILES MODIFIED: none outside modules/hadoop/** (nothing in build.sbt, project/, modules/core/, datasets/ or scripts/).

COMMANDS RUN:
- `sbt -batch hadoop/compile`: success.
- A probe run into target/phase04/probe: success.
- `sbt -batch hadoop/scalafmtAll hadoop/test`: 44 passed, 1 failed. The failure was a bug in my own cap test: it compared whole records, but the run ID differs when maxWorks changes. I fixed it to compare work IDs, and `testOnly ... -z cap` then passed.
- `sbt -batch check`: exit 0. "Tests: succeeded 466" (core) and "Tests: succeeded 45" (hadoop), then "markTestRun: tests passed; touched .claude/.last-test-run".
- Exact driver command, run twice (once with run-a, once with run-b); both exited 0:
  `SCHOLARCHAIN_ENV=dev SCHOLARCHAIN_HADOOP_NUM_REDUCERS=2 SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH=datasets/samples/dblp/dblp-2023-06-28.dtd sbt -batch "hadoop/runMain scholarchain.hadoop.driver.PipelineDriver --input-root datasets/samples --output-root target/phase04/run-a"`
- A Python comparison of run-a and run-b.

TEST RESULTS: `sbt check` passed with 511 tests, 0 failed (466 core, 45 hadoop). The hadoop tests cover:
- Parser:
  - multiline title with inline markup
  - DTD entities in text and attributes, with the entity table read from the DTD itself
  - unknown entity (reason UNKNOWN_ENTITY)
  - malformed XML
  - Unicode: raw ISO-8859-1 bytes, numeric references and CJK names
  - missing optional fields
  - repeated author
  - the subset rule
  - DOI, arXiv and ORCID taken from `ee` links and attributes
- Split boundaries:
  - The fixture gives the same 16 records as a single-split read at about 50 forced split sizes from 1 to 1000 bytes.
  - The committed DBLP excerpt read with 64-byte splits (over 300 splits) gives the same 35 records as one split.
  - bzip2 with about nine blocks gives the same 6000 records at five split sizes, with no record read twice.
  - gzip gives one split.
  - A truncated final record is passed to the parser (and rejected there), not dropped.
- Pairs:
  - 0, 1, 2, 3 and 5 authors give 0, 0, 1, 3 and 10 pairs.
  - Hand-checked position sets for 3 authors.
  - Canonical member order, with each position staying with its member.
  - Duplicate author removed.
  - Routing of large and oversize teams.
- Normalization and keys:
  - DOI forms.
  - ORCID checksum, including an X check digit.
  - Old and new arXiv IDs.
  - Name folding.
  - Primary and fallback blocking keys.
  - Features are finite and symmetric.
  - OpenAlex, Crossref and ORCID normalizers.
  - ORCID privacy allowlist: an injected email, biography, address and work title are all dropped.
- LocalJobRunner integration:
  - J01 counters on the fixture.
  - J07/J08 on the fixture with large threshold 3 and max expansion 5: 26 pairs, 3 large teams, 1 oversize team.
  - Provenance on every record.
  - counters.json and `_SUCCESS` next to every table.
  - Idempotent rerun: every job is skipped and nothing changes.
  - A partial state fails with an actionable message and deletes nothing.
  - The maxWorks cap.
  - Oversize blocks: 11 members re-keyed, 2 oversize blocks with 8 members routed.
  - J01 and J07 on the committed samples.
  - Determinism: two output roots give identical sorted output.

VALIDATION RESULTS (run-a; run-b is identical):

Job graph and key/value contracts. All jobs use the default `HashPartitioner` on stable Text keys, which hashes the UTF-8 bytes and is deterministic.

| Job | Input | Mapper output key -> value | Combiner | Reducer output |
|---|---|---|---|---|
| J01 DblpExtractJob | `DblpInputFormat`: (offset, record bytes) | workId -> DblpWork JSON | none | `dblp/work_extracted`; per-reducer cap histogram |
| J01C WorkCapJob | `dblp/work_extracted` | "tableDir SEP stableId" -> counted JSON | DedupCombiner | DedupReducer -> `dblp/{work, author_mention, venue, work_node}` |
| J02 SourceNormalizeJob | `SourceInputFormat`: JSONL by line, XML whole-file | same composite key | DedupCombiner | DedupReducer -> openalex/crossref/orcid tables |
| J03+J04 ExactIdentifierJob | works and researcher nodes | "IdType SEP normalizedId" -> IdRef | none | `ExactIdentifierOutcome` |
| J05 BlockingKeyJob | works | primaryKey -> BlockMember | none | bounded block, or re-keyed with fallback key |
| J05b FallbackBlockingJob | re-keyed members | fallbackKey -> member | none | block, or `oversize_block` rows as RejectRecord |
| J06 CandidateJoinJob | blocks | candidateId -> counted MatchCandidate | min combiner | one candidate per ID |
| J07+J08 PublicationTeamJob | `dblp/work` | workId -> WorkRecord | none | `publication_team`, `author_paper`, `coauthor_pair` (v2), `large_team`, `oversize_team`, `stats_j07` |
| J09 GraphCandidateExportJob | every primary table | "exportDir[/year=y] SEP id" -> counted JSON | DedupCombiner | DedupReducer -> `scholarchain/spark_*` |

Per-job input format, counters, reject conditions, prefixes, codec, idempotency rule and test fixture are documented in /home/olmos/scholarchain/modules/hadoop/JOBS.md.

Partition and reducer counts. Every job used 2 reducers (`numReducers`). Map tasks:

| Job | J01 | J01C | J02 | J03 | J05 | J05b | J06 | J07 | J09 |
|---|---|---|---|---|---|---|---|---|---|
| Map tasks | 1 | 2 | 5 | 11 | 8 | 0 (empty input, job still ran) | 2 | 2 | 59 |

Map-output codec is DefaultCodec (pure Java). Final output is GzipCodec.

Counters (HadoopCounter). Every counter not listed here is 0 in that job. All 22 counters, including zeros, are printed and written to counters.json.

| Job | Non-zero counters |
|---|---|
| J01 | INPUT_RECORDS 35, ACCEPTED 24, OUT_OF_SUBSET 11, SIDE_OUTPUT_RECORDS 24 (REJECTED 0, UNKNOWN_ENTITY 0) |
| J01C | INPUT_RECORDS 24, ACCEPTED 139 |
| J02 | INPUT_RECORDS 72, ACCEPTED 608, REJECTED 5 |
| J03 | INPUT_RECORDS 91, ACCEPTED 20, EXACT_ID_OUTCOMES 20, EXACT_ID_SINGLETONS 251 |
| J05 | INPUT_RECORDS 69, BLOCKS_EMITTED 51, ACCEPTED 51, SIDE_OUTPUT_RECORDS 2 |
| J05b | all 0 |
| J06 | INPUT_RECORDS 51, EMITTED_MATCH_CANDIDATES 91, ACCEPTED 91 |
| J07 | INPUT_RECORDS 24, ACCEPTED 24, EMITTED_PAIRS 167 |
| J09 | INPUT_RECORDS 1138, ACCEPTED 1138 |

DUPLICATE, CONFLICT, OVERSIZE_BLOCK, LARGE_AUTHOR_TEAM, OVERSIZE_AUTHOR_TEAM, MISSING_KEY, SCHEMA_FAILURE and DUPLICATE_AUTHOR_IN_TEAM are all 0 on the samples. The non-zero paths for these are exercised by the fixture tests.

The 20 exact-ID outcomes are all MATCH: 10 ORCID groups of 2, 6 DOI groups of 2, and 4 DOI groups of 3 (DBLP + OpenAlex + Crossref).

Rejects.
- Total: 5, all MISSING_TITLE in `openalex/rejects_j02` (snapshot 2026-10-14). Examples: W4417138738, W4417174150, W4417174123, W4417174519 and W4417174096; each has `"title":null` and `"display_name":null`.
- No DBLP rejects on the excerpt.
- On the fixture:
  - J01 rejects 3 records: UNKNOWN_ENTITY (`&bogus;` in journals/test/Unknown09), MISSING_KEY, MALFORMED_XML.
  - J05b routes 8 members of the 2 oversize blocks to `oversize_block` as OVERSIZE_BLOCK records.

Output paths.
- Pattern: `target/phase04/run-a/<dataset>/<table>/snapshot=<label>/run_id=356700d6f0cf4f8416eeb92e5aeca822fc3a8c28b9712a1432982b1cee41ef4e/part-r-NNNNN.gz`, with counters.json and `_SUCCESS` next to each table.
- Run ID: run:356700d6f0cf4f8416eeb92e5aeca822fc3a8c28b9712a1432982b1cee41ef4e.
- The run manifest is at `scholarchain/run_manifest/.../run-manifest.json`.
- 134 table directories in total. Key tables:
  - dblp: work 24, author_mention 89, publication_team 24, author_paper 89, coauthor_pair 167, venue 2
  - scholarchain: exact_identifier_outcome 20, match_candidate 91
  - scholarchain spark export: spark_work 69, spark_coauthor_pair 167

Maximum observed team size: 8, workId work:dblp:372df4658fb939c4c66446d58792681a390753a7e1ea99189790a89121f9fd2d (conf/dac/ZhouAZYUGUJ15, 2015).

Largest pair expansion: 28 pairs, from the same work.

Largest blocking group: 3 members, key `r|brown|t`, in J05. No group exceeded maxBlockSize 200 on the samples.

Determinism: run-a and run-b have the same 134 directories and identical sorted rows and JSON files (2386 rows). All 187 part files are also byte-identical (0 differ).

For infra-engineer (EMR):
`hadoop jar scholarchain-hadoop-assembly-0.1.0.jar scholarchain.hadoop.driver.PipelineDriver --input-root s3://<raw>/ --output-root s3://<curated>/hadoop/ --code-version <git sha> [--snapshot <dataset-or-path>=<label>]`
- Generic `-D` options go before these arguments.
- The driver environment needs `SCHOLARCHAIN_ENV`, `SCHOLARCHAIN_HADOOP_NUM_REDUCERS`, and `SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH` set to the s3:// path of the DTD.
- Do not pass `--local` on EMR.

Gzip: yes, gzip input gives exactly one split per file (tested). The full `dblp.xml.gz` would therefore be read by a single mapper. Uncompressed or bzip2 input is splittable.

ASSUMPTIONS:
- Snapshot labels: explicit `--snapshot` first, then a `snapshot=` path segment, then the first YYYY-MM-DD in the file name, otherwise `undated-<first 12 hex of the file SHA-256>`. On the samples, the OpenAlex and Crossref API-linked files and the ORCID record get undated labels. Tables built from several datasets use the DBLP (seed) snapshot, and a run must contain exactly one DBLP snapshot.
- maxWorks cap: I used a work-ID bucket histogram (first 4 hex digits, 65,536 buckets). The rule keeps at most maxWorks works and may keep up to one bucket fewer; the kept set depends only on the set of work IDs. On the samples, 24 of 24 works are kept.
- Job structure: I added J01C (the cap filter) and split J05 into J05 and J05b. Blocking runs over all works, not only works without an exact match. Exact-ID outcome rules are mine and documented in JOBS.md.
- Version strings: the feature version `hadoop-blocking-features-v1` and edge policy version `hadoop-source-asserted-edges-v1` are code-level version constants, not config values. The default code version is `scholarchain-hadoop-0.1.0-phase04`.
- Path naming: the run_id directory holds the hex digest without the `run:` prefix, because Hadoop paths reject `:`.

RISKS:
- Blocking keys and match features are listed under entity-resolution-engineer in docs/agents.md, but Work order B assigned them to me. Phase 5 may want to own or replace them.
- OpenAlex author profiles are written as GraphNode Researcher records with `researcher:openalex:` IDs. These are source profiles, not resolved identities.
- Driver SHA-256 hashing of inputs will be slow on full S3 inputs.
- Very large exact-ID groups are held in memory in one reducer call.
- One run at a time per output root (shared `_temporary`).
- Hadoop scratch space is under ./target/hadoop-tmp in local mode.
- The spark export duplicates data.
- All of the sample's 24 works fall well below the large-team threshold, so large and oversize team paths are shown only by the fixture tests.

MANUAL ACTION REQUIRED:
- Decide whether to pass `--snapshot` labels (for example `api-2026-11-20`) for the three undated sample files. Doing so changes the run ID.
- Decide who owns blocking keys and features (hadoop-engineer per this work order, or entity-resolution-engineer per docs/agents.md).
- target/phase04/probe is an extra exploratory output directory left in place; output is never deleted by agents.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: bounded run, deterministic output checks, test-engineer (Work order C), code-reviewer
````
