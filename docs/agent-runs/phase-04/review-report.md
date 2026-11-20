# Phase 04 review report

Assembled by the orchestrator from code-reviewer's final report, verbatim.

## Work order D: code-reviewer

_Orchestrator note: the review ran after test-engineer attempt 5 (COMPLETE) and repairs 01–08, under human escalation 01. Only verified CRITICAL or MAJOR findings get one repair round. MINOR findings go to the human gate as known risks._

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 04, Work order D. A read-only review of the Phase 4 diff against the Phase 4 gate. The diff covers build.sbt, modules/core, modules/hadoop, the 12 files under tests/src/test/scala/scholarchain/adversarial, and repairs 01 to 08. Severities follow human escalation 01.
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

GATE ASSESSMENT (each item checked against the code, not only against the run outputs)
- At least two real MapReduce jobs, no disguised local loops: met. PipelineDriver.scala:445-625 chains nine `org.apache.hadoop.mapreduce` jobs. They use top-level Mapper, Combiner and Reducer classes. Every job calls `setNumReduceTasks(numReducers)` and `waitForCompletion`. The driver reads only bounded tables on its side: the 65,536-bucket cap histogram and the per-reducer stats rows. Nothing collects the full graph on the driver.
- DBLP reader handles record and split boundaries: met. In DblpInputFormat.scala:163-193, a record belongs to the split that holds its `<` (`tagPos >= end` stops the split). Lines 235-285 read each record past the split end. A truncated record is cut at the next configured start tag, and lines 298-308 hand that tag to the split that owns it. Gzip gives one split per file and bzip2 is read BYBLOCK. Tests cover a split boundary at every byte of a start tag and of an end tag, about 50 forced split sizes, 64-byte splits on the real excerpt, and bzip2 at 5 split sizes.
- Pair counts for 1, 2, 3 and larger teams, and large teams counted rather than dropped: met. TeamExpansion.scala:46 and :99-112 expand all i < j pairs, and fromPositions orders each pair canonically. PublicationTeamJob.scala:151-176: above largeTeamThreshold, a team counts LARGE_AUTHOR_TEAM, goes to `large_team`, and is still expanded. Above maxPairExpansionTeamSize, it counts OVERSIZE_AUTHOR_TEAM, goes to `oversize_team`, and its team and author-paper rows are still written. The config validation in core requires maxPairExpansionTeamSize >= largeTeamThreshold.
- Counters and reject outputs are visible: met. HadoopCounter.scala holds all 13 required counters. PipelineDriver.scala:279-313 prints them and writes counters.json. JobSupport.scala:105-113 writes `RejectRecord` rows to the MultipleOutputs `rejects` output, and still writes a reject that fails validation.
- Repeated runs give equivalent output: met. The run ID covers the input checksums, every parameter and the code version. Shuffle keys are hashed with HashPartitioner on Text. Every conflict picks the smallest JSON from a TreeMap. Feature maps are TreeMaps. The idempotency check is at PipelineDriver.scala:225-244. Mutable state is per-instance only: mappers and reducers keep it in fields set up in setup/cleanup, and no Scala `object` holds a `var`.
- Every output record carries provenance: met for every domain record (Work, AuthorMention, Venue, GraphNode, PublicationTeam, AuthorPaper, CoauthorPair v2, MatchCandidate, ExactIdentifierOutcome, RejectRecord). Three statistics and intermediate row types lack a Provenance object (finding F2).
- Adversarial tests present and `sbt check` passes: met. There are 12 files under tests/. Test report attempt 5 shows `sbt check` exit 0 (core 483, hadoop 75, adversarialTests 114). I did not rerun it.

Focus areas, all clean:
- Frozen contracts: the v1 SHA256SUMS lines are byte-identical and 4 lines were added (tracked.diff:1105-1132). CoauthorPairCandidate v1 bytes are kept: the positions default to None and are omitted. v2 is registered alongside v1 (SchemaVersions diff :835). No stable-ID formula changed. Finding F6 covers a normalizer input-domain change.
- Security: XXE is off.
  - The DTD is loaded by SAX with FEATURE_SECURE_PROCESSING, external-general-entities false, and ACCESS_EXTERNAL_DTD/SCHEMA "". The EntityResolver serves only the in-memory DTD and throws for any other system ID (DtdEntities.scala:30-61).
  - StAX runs with SUPPORT_DTD false and external entities false (DblpRecordParser.scala:45-51, OrcidNormalizer.scala:38-44).
  - Entity values are re-emitted as numeric character references, not expanded recursively, so there is no entity-expansion blow-up.
  - The DTD travels through the distributed cache (`addCacheFile(uri#scholarchain-dblp.dtd)`, PipelineDriver.scala:456; readCacheFile in DblpExtractJob.scala:39-55).
  - No secrets, and no hardcoded buckets or endpoints. JOBS.md uses `${RAW_BUCKET}` placeholders.
- Ownership (paths checked against docs/agents.md and the repair records):
  - modules/hadoop/** is hadoop-engineer's.
  - modules/core/** is domain-engineer's (work order A, repairs 02, 04 and 06).
  - build.sbt is build-engineer's (repairs 01 and 03).
  - tests/** is test-engineer's.
  - project/Dependencies.scala was not modified, so there is no ledger split to check.
- Tests that cannot fail: none found. The split, bzip2, pair-count, routing, counter, idempotency and determinism tests assert exact sets and counts. The provenance loop (PipelineIntegrationSpec.scala:108-131) would pass vacuously on empty tables, but other tests in the same suite pin those tables' row counts.

FINDINGS:
| id | severity | file:line | failing input | impact | owner | verified evidence |
|---|---|---|---|---|---|---|
| F1 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/sources/OrcidNormalizer.scala:130 and :167 | An ORCID XML file with no long namespace header, an `<email>` element early, and a `<path>` with a bad check digit, e.g. `<record><person><emails><email>a@b.org</email></emails></person><orcid-identifier><path>0000-0002-1825-0098</path></orcid-identifier></record>`. The same happens with any malformed ORCID XML. | The MISSING_KEY (or MALFORMED_XML) reject stores the raw XML as `rawExcerpt`, up to 1024 code points, in `orcid/rejects_j02`. That bypasses the privacy allowlist. The manifest says "fields outside the four kept categories are not stored in derived outputs" (dataset-manifest.yaml:627). Real exposure is low: the inputs are pre-reduced by orcid_reduce.py, and real v3.0 records open with about 1 KB of namespace declarations. | hadoop-engineer | `out.reject("MISSING_KEY", ctx.inputLocation, xml)` and `out.reject("MALFORMED_XML", ..., s"${e.getMessage} \| $xml")`. Records.reject only truncates. |
| F2 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/DblpExtractJob.scala:215; jobs/BlockingJobs.scala:18; blocking/Blocking.scala:36 | Any run. | Rows in `dblp/cap_histogram`, `scholarchain/stats_j05`, `stats_j05b` and `stats_j07`, and the `blocking_block(_fallback)` rows, have no `Provenance` object. CapBucket has runId and snapshot, StatRow has runId only, and Block carries provenance per member only. These are internal aggregates, not exported by J09. The gate says "every output record", so the human should accept this interpretation explicitly. | hadoop-engineer | `final case class CapBucket(bucket, works, runId, snapshot)`; `final case class StatRow(job, metric, value, key, runId)`; `final case class Block(blockKey, entityType, members)` |
| F3 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/PublicationTeamJob.scala:140-142 | An in-subset DBLP record with no `<author>`, e.g. `<article key="journals/x/Ed20"><title>Editorial</title><year>2020</year></article>` | The work is written to `dblp/work`, but there is no publication_team row, no counter and no side output. Teams count less than works with no recorded reason. Gate item 3 is not affected. | hadoop-engineer | `if team.size == 0 then ()` with no counter increment |
| F4 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/blocking/Blocking.scala:55-56, :92 | Any run | The blocking parameters are hardcoded: TitlePrefixTokens = 3, YearBucketWidth = 5, and a 64-token title cap. CLAUDE.md says algorithm parameters come from configuration. They are not in the run-manifest parameters either, so they are covered only by the code version. | hadoop-engineer (domain-engineer if config keys are added to HadoopConfig) | `val TitlePrefixTokens = 3`, `val YearBucketWidth = 5`, `.take(64)` |
| F5 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/driver/PipelineDriver.scala:79 (used at :382, skip at :322) | Change any job logic, then rerun `hadoop/runMain ... --output-root target/phase04/run-a4` without `--code-version`. | The run ID is unchanged, so every job is SKIPPED and output from the old code is reported as the current result. Today the version string is bumped by hand (now 0.1.3). EMR is protected only if the infra script always passes `--code-version <git sha>`. | hadoop-engineer (infra-engineer must make `--code-version` mandatory in the Phase 13 script) | `val DefaultCodeVersion = "scholarchain-hadoop-0.1.3-phase04"` and `codeVersion = args.codeVersion` |
| F6 | MINOR | modules/core/src/main/scala/scholarchain/core/ids/ExternalIds.scala:87 (normalizeDoi, repairs 04 and 06) used by StableIds.scala:140 | `StableIds.workId(WorkNamespace.Doi, "https://doi.org/10.1000%2Fabc")`. Phase 3 hashed `10.1000%2fabc`; it now hashes `10.1000/abc`. Also, a DOI containing an internal U+00A0 was accepted before and is rejected now. | The input domain of a frozen stable-ID formula changed. The formula itself, all goldens, and every previously valid plain DOI are unchanged, and no persisted data exists yet, so nothing has to be migrated. The human should record this as an accepted contract change. Carried-forward known risk: a DOI with a leading or trailing Cf character (`10.1000/abc`+U+200B) is still accepted as a separate DOI. | domain-engineer | tracked.diff:507-520 (decode then non-graphic check) versus the removed lines 496-506 |
| F7 | MINOR | modules/hadoop/src/main/scala/scholarchain/hadoop/blocking/Blocking.scala (whole file) versus docs/agents.md "entity-resolution-engineer ... blocking keys, match features" | n/a | The ownership map is ambiguous. The path belongs to hadoop-engineer (modules/hadoop/**), the work order assigned these functions to it, and module rules forbid `hadoop` from depending on `entityResolution`. But the description for entity-resolution-engineer also names blocking keys and match features. Phase 5 needs a ratified rule, either a map clarification or moving the definitions into core contracts. | factory-engineer (map amendment, human ratification) | agent-report.md:361, :372; JOBS.md:356-357 |
| F8 | MINOR | Process: repair-06.md:33 (DoiNonGraphicSpec.scala), repair-07.md:59 (Emitted.scala), repair-08.md:82 (Blocking.scala) | n/a | These edits were made through Bash or python instead of Write or Edit. Each edit stayed inside the agent's own territory, so there is no ownership violation, and the content passed scalafmtCheck and `sbt check`. But the edits bypassed the Write/Edit guard and the format hook. | hadoop-engineer and domain-engineer (process); factory-engineer (guard hardening) | Self-reported in the cited repair records |
| F9 | MINOR | build.sbt:252 (alias `...; test; markTestRun`) with :241 (markTestRun depends on `Test/test`) | `sbt check` | Every test suite runs twice, which takes about 32 minutes now that adversarialTests is aggregated (test-report attempt 5). This is a cost and turnaround risk, not a correctness problem. The alias was written before this phase. | build-engineer | build.sbt lines 241 and 250-253 |

No CRITICAL or MAJOR findings. Under escalation 01, no repair round is triggered. F1 to F9 go to human-gate.md as known risks.

TEST RESULTS: not run by reviewer. I relied on test-report attempt 5: `sbt check` exit 0, 672 passed and 0 failed.
VALIDATION RESULTS: findings verified against files: F1 yes, F2 yes, F3 yes, F4 yes, F5 yes, F6 yes, F7 yes, F8 yes (against the repair records), F9 yes.

ASSUMPTIONS:
- The scratchpad repair drafts and tracked.diff match the working tree. I read the untracked hadoop and tests files directly.
- I did not reproduce the bzip2 BYBLOCK ownership behaviour by execution. It is accepted on the basis of the code reading plus DblpInputFormatSpec.scala:100-120 and TruncationSplitSpec.scala:181-211.

RISKS:
- bzip2 split ownership is tested at a limited set of split sizes (about 5 plus the truncation suite), not exhaustively. Hadoop's own bzip2 split handling has had boundary bugs before (cf. LineRecordReader's `needAdditionalRecordAfterSplit`). An EMR run on the full dblp.xml.bz2 should compare its record count with a single-split count.
- Known risks from test-engineer: a Cf character at a DOI edge is accepted as a distinct DOI. A Crossref DOI with a trailing NBSP rejects the whole record (visible as MISSING_KEY).
- Each exact-identifier group is held in memory within one reducer call. On full data, a prolific ORCID could create a large group.
- J09 duplicates every primary table into `scholarchain/spark_*` (storage cost). The driver computes SHA-256 over every input, which will be slow on full S3 inputs.
- Records.xmlEncoding (Records.scala:70-75) silently falls back to UTF-8 when it does not recognise the declared charset. The strict decoding would then reject non-ASCII records, visibly, as MALFORMED_XML.

MANUAL ACTION REQUIRED:
- Human: record F1 to F9 in docs/agent-runs/phase-04/human-gate.md as known risks, and explicitly accept the provenance interpretation in F2 and the DOI normalizer change in F6.
- Human: decide the ownership of blocking keys and features before Phase 5 (F7). Any change to the map goes through factory-engineer and needs ratification.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human gate for Phase 04. No owner repair is required for this verdict. The MINOR findings may be routed later at the human's discretion: F1-F5 to hadoop-engineer, F6 to domain-engineer, F7 and F8 to factory-engineer, F9 to build-engineer.
```
