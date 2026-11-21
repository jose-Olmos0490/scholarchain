# ScholarChain Hadoop MapReduce jobs (Phase 4)

Owner: `hadoop-engineer`. Main class: `scholarchain.hadoop.driver.PipelineDriver`.
All jobs use `org.apache.hadoop.mapreduce`; mappers, combiners and reducers are
top-level classes with no-arg constructors. Per-task state lives only in
`setup`/`cleanup` (MultipleOutputs sink, histogram array, max trackers); there
are no global mutable objects.

## Running

Local (LocalJobRunner, `file:///`, scratch under `./target/hadoop-tmp`):

```text
SCHOLARCHAIN_ENV=dev SCHOLARCHAIN_HADOOP_NUM_REDUCERS=2 \
SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH=datasets/samples/dblp/dblp-2023-06-28.dtd \
sbt "hadoop/runMain scholarchain.hadoop.driver.PipelineDriver --input-root datasets/samples --output-root target/phase04/<name>"
```

EMR (for `infra-engineer`; the cluster config selects YARN; do not pass `--local`):

```text
hadoop jar scholarchain-hadoop-assembly-0.1.0.jar scholarchain.hadoop.driver.PipelineDriver \
  [-D mapreduce.input.fileinputformat.split.maxsize=<bytes>] \
  --input-root s3://${RAW_BUCKET}/ --output-root s3://${CURATED_BUCKET}/hadoop/ \
  --code-version <git sha> [--snapshot <dataset-or-relative-path>=<label>]...
```

with `SCHOLARCHAIN_ENV`, `SCHOLARCHAIN_HADOOP_NUM_REDUCERS` and
`SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH` (an `s3://` path to the DTD) in the driver
environment. Input layout under the root: `<dataset>/.../<file>` with dataset
`dblp`, `openalex`, `crossref` or `orcid`.

## Configuration (all from `scholarchain.hadoop`, core `HadoopConfig`)

`numReducers` (every job), `maxBlockSize`, `largeTeamThreshold`,
`maxPairExpansionTeamSize`, `intermediateCodec` (map-output compression;
default `DefaultCodec`, pure Java), `outputCodec` (final output; default
`GzipCodec`), `dblpRecordTypes`, `yearFrom`/`yearTo`, `dblpDtdPath`, plus
`datasets.dblpSubset.maxWorks`. The driver puts them in the job
`Configuration`; tasks never read the environment.

## Output layout and idempotency

Every table: `<outputRoot>/<dataset>/<table>/snapshot=<label>/run_id=<digest>/part-[mr]-NNNNN.gz`
plus `counters.json` and `_SUCCESS`. `<digest>` is the hex of the stable run ID
`run:<digest>` (`StableIds.runId` over the run manifest: input locations
relative to the input root and their SHA-256, the DTD and its SHA-256, every
Hadoop/subset parameter, feature and edge-policy versions, code version). The
output root is not part of the run ID, so two roots get the same paths.

Idempotency rule, per job, checked before the job runs:

- every declared table directory has `_SUCCESS`: the job is skipped (logged,
  stored counters printed);
- none exists: the job runs; afterwards the driver writes `counters.json` and
  then `_SUCCESS` into every declared directory (also empty ones);
- anything else, or a leftover `<outputRoot>/_temporary`: the run fails with a
  message naming the directories. Nothing is ever deleted; removal is a human
  data operation.

The committer's own `_SUCCESS` is off; jobs write through MultipleOutputs base
paths below the output root (`TableOutputFormat` skips the "output exists"
check that the driver replaces). One run at a time per output root.

## Snapshot labels (deterministic)

1. `--snapshot <relative path>=<label>`, then `--snapshot <dataset>=<label>`;
2. a `snapshot=<label>` directory segment of the input path;
3. the first calendar date `YYYY-MM-DD` in the file name;
4. otherwise `undated-<first 12 hex of the file SHA-256>`.

Every record keeps its own input's label. Tables derived from several datasets
(`scholarchain/*`) use the seed (DBLP) snapshot; a run must have exactly one
DBLP snapshot. On the committed samples: dblp `2026-11-01`, OpenAlex snapshot
excerpts `2026-10-14`, the API-linked OpenAlex and Crossref files and the ORCID
record `undated-<hash>` (their names carry no date; pass `--snapshot` to label
them, which changes the run ID).

## maxWorks cap (deterministic)

J01 counts distinct subset works per work-ID bucket (first 4 hex digits of the
work-ID digest, 65,536 buckets) in a reducer-local histogram written in
`cleanup` (`dblp/cap_histogram`). The driver sums it: if the total is at most
`maxWorks`, the bucket limit is 65,536 (keep all); otherwise the limit is the
largest `b` with `sum(count[0..b-1]) <= maxWorks`. J01C keeps a work iff its
bucket is below the limit. At most `maxWorks` works are kept (it may keep up to
one bucket fewer); the choice depends only on the set of work IDs.

## Counters (`scholarchain.hadoop.HadoopCounter`)

Required: `INPUT_RECORDS`, `ACCEPTED`, `REJECTED`, `DUPLICATE`, `CONFLICT`,
`OVERSIZE_BLOCK`, `LARGE_AUTHOR_TEAM`, `OVERSIZE_AUTHOR_TEAM`, `EMITTED_PAIRS`,
`MISSING_KEY`, `SCHEMA_FAILURE`, `UNKNOWN_ENTITY`, `DUPLICATE_AUTHOR_IN_TEAM`.
Additional: `OUT_OF_SUBSET`, `CAPPED_WORKS`, `BLOCKS_EMITTED`,
`REKEYED_MEMBERS`, `OVERSIZE_BLOCK_MEMBERS`, `EMITTED_MATCH_CANDIDATES`,
`EXACT_ID_OUTCOMES`, `EXACT_ID_SINGLETONS`, `SIDE_OUTPUT_RECORDS`. The driver
prints all of them (zeros included) plus the data-dependent task counters after
each job and writes them to `counters.json`.

## Rejects

MultipleOutputs named output `rejects`, `RejectRecord` (`reject-v1`) rows in
`<dataset>/rejects_<job>/...` (J01, J01C, J02, J07) or
`scholarchain/rejects_<job>/...` (J03, J05, J05b, J06, J09). Reason codes:
`UNKNOWN_ENTITY`, `MALFORMED_XML`, `MALFORMED_JSON`, `MISSING_KEY`,
`INVALID_KEY`, `MISSING_TITLE`, `MISSING_AUTHOR_NAME`, `MISSING_YEAR`,
`SCHEMA_FAILURE`, `CONFLICTING_RECORD`. The excerpt is the raw input
(<= 1024 code points), always untrusted data. A truncated DBLP record's
excerpt holds only that record (see the J01 truncated-record rule).

## Partitioning

Every shuffle key is a `Text` and the partitioner is Hadoop's `HashPartitioner`
(`Text.hashCode` is a hash of the UTF-8 bytes, so it is stable across JVMs and
runs). Reducer count = `numReducers` for every job. Stable keys per job are
listed below.

## Job graph

```text
DBLP XML ──J01──> dblp/work_extracted, dblp/cap_histogram
                     └─J01C (cap)──> dblp/{work,author_mention,venue,work_node}
OpenAlex/Crossref/ORCID ──J02──> <ds>/{work,author_mention,work_node,mention_node,
                                       researcher_node,institution,topic,venue,graph_edge}
works + researcher nodes ──J03/J04──> scholarchain/exact_identifier_outcome
works ──J05──> scholarchain/{blocking_block, blocking_rekeyed}
blocking_rekeyed ──J05b──> scholarchain/{blocking_block_fallback, oversize_block}
blocks ──J06──> scholarchain/match_candidate
dblp/work ──J07/J08──> dblp/{publication_team,author_paper,coauthor_pair,large_team,oversize_team}
all primary tables ──J09──> scholarchain/spark_<table>[/year=<y>]
```

Merges used: J03+J04, J07+J08. J05 is split into J05 (primary key) and J05b
(fallback key) because the fallback needs the primary group size.

## Per-job contracts

### J01 DblpExtractJob
- Input format: `DblpInputFormat` (custom). Syncs on `<type` start tags of the
  configured record types; a record belongs to the split holding its `<`;
  read to its end tag past the split end; leading partial record skipped.
  Uncompressed and bzip2 (BYBLOCK) are splittable; gzip gives one split per file.
- Truncated-record rule: a value ends at its matching end tag `</type>`
  (whitespace allowed before `>`) or the `/>` of an empty element. If a start
  tag of any configured record type (`<t` followed by whitespace, `>` or `/`)
  appears first, the current record was cut off: its value ends just before
  that `<` and is emitted (J01 rejects it as one `MALFORMED_XML`), and the next
  record starts at that `<`, owned by the split holding it like any other
  start. A `<` inside an unfinished start tag ends the start tag the same way.
  A record cut off by end of file is emitted as-is (also rejected). Because
  the cut point is the next start tag, which every split finds identically,
  output is independent of split size even with truncated records mid-file.
  A configured start tag inside CDATA or a comment would also cut (DBLP has
  neither).
- Mapper in: `(LongWritable recordOffset, BytesWritable recordBytes)`. Record
  bytes are decoded strictly in the charset of the file's XML declaration: a
  byte sequence that is malformed or unmappable in that charset is a fatal
  error (XML 1.0 §4.3.3) and gives a `MALFORMED_XML` reject naming the byte
  offset; it is never replaced by U+FFFD (the reject excerpt alone is decoded
  leniently). Entities
  resolved with the DTD's own entity table (DTD shipped through the distributed
  cache as `#scholarchain-dblp.dtd`, parsed by the JDK SAX parser with external
  access disabled); entity references are recognized with the full XML 1.0
  `Name` production, so an undeclared non-ASCII name (`&café;`) is an
  `UNKNOWN_ENTITY` reject, not `MALFORMED_XML`. Records parsed with StAX with
  DTD, external entities and network off.
- Mapper out: `(Text workId, Text DblpWork-json)`. Combiner: none.
- Reducer in/out: `(workId, [DblpWork])` -> one `dblp/work_extracted` row;
  identical repeats DUPLICATE; differing CONFLICT (smallest JSON kept, others
  rejected `CONFLICTING_RECORD`). `cleanup` writes `dblp/cap_histogram`.
- Counters: INPUT_RECORDS, ACCEPTED, REJECTED, UNKNOWN_ENTITY, MISSING_KEY,
  SCHEMA_FAILURE, OUT_OF_SUBSET, DUPLICATE, CONFLICT, SIDE_OUTPUT_RECORDS.
- Rejects: UNKNOWN_ENTITY, MALFORMED_XML, MISSING_KEY, INVALID_KEY,
  MISSING_TITLE, MISSING_AUTHOR_NAME, SCHEMA_FAILURE, CONFLICTING_RECORD.
  Not rejects: other record types, no integer year, year outside the window
  (OUT_OF_SUBSET).
- Prefixes: in `<root>/dblp/`; out `dblp/work_extracted`, `dblp/cap_histogram`,
  `dblp/rejects_j01`. Codec: map output `intermediateCodec`, final `outputCodec`.
- Fixture: `src/test/resources/fixtures/dblp/dblp-fixture.xml`,
  `DblpRecordParserSpec`, `DblpInputFormatSpec`, `PipelineIntegrationSpec`.

### J01C WorkCapJob
- Input: `TextInputFormat` over `dblp/work_extracted` part files.
- Mapper: `(offset, DblpWork line) -> (Text "<tableDir>\u0001<stableId>", Text "<n>\t<json>")`
  for work, each author mention, the dblp venue and the work node, after the cap.
- Combiner `DedupCombiner` (sums counts per distinct value); reducer
  `DedupReducer` (one row per key; venue repeats are expected and silent).
- Counters: INPUT_RECORDS, CAPPED_WORKS, ACCEPTED, DUPLICATE, CONFLICT, SCHEMA_FAILURE.
- Out: `dblp/{work,author_mention,venue,work_node,rejects_j01c}`.
- Fixture: cap test in `PipelineIntegrationSpec`.

### J02 SourceNormalizeJob
- Input: `SourceInputFormat`: JSON Lines by line (`LineRecordReader`), `*.xml`
  whole-file (ORCID), never split.
- Mapper: dispatches on the split's input kind to `OpenAlexNormalizer.works`,
  `.authors`, `CrossrefNormalizer.works`, `OrcidNormalizer.record`; out
  `(Text "<tableDir>\u0001<stableId>", Text counted-json)`.
- Combiner `DedupCombiner`; reducer `DedupReducer`.
- Keeps every source ID (OpenAlex `ids.*`, author/institution/topic/source IDs,
  DOI, ISSN, MAG...), affiliations (Institution + AFFILIATED_WITH, raw strings on
  mention nodes), topics (Topic + HAS_TOPIC), abstract references
  (`abstractTextRef`, text never copied). ORCID: allowlist only (iD, names,
  affiliations, work external IDs).
- Counters: INPUT_RECORDS, ACCEPTED, REJECTED, MISSING_KEY, SCHEMA_FAILURE,
  DUPLICATE, CONFLICT. Rejects: MALFORMED_JSON, MALFORMED_XML, MISSING_KEY,
  MISSING_TITLE, MISSING_AUTHOR_NAME, SCHEMA_FAILURE, CONFLICTING_RECORD.
- Out: per (dataset, snapshot) the tables in the job graph, plus `rejects_j02`.
- Fixture: `KeysAndSourcesSpec`, samples run in `PipelineIntegrationSpec`.

### J03+J04 ExactIdentifierJob
- Input: `TextInputFormat` over every `work` and `researcher_node` table.
- Mapper (J03 index): `(offset, row) -> (Text "<ExactIdType>\u0001<normalizedId>", Text IdRef-json)`
  for DOI, ARXIV, DBLP_KEY, OPENALEX_WORK (work refs) and ORCID, OPENALEX_AUTHOR
  (mention or researcher refs). No combiner.
- Reducer (J04 join): distinct refs sorted; < 2 -> EXACT_ID_SINGLETONS; else an
  `ExactIdentifierOutcome`: work IDs from one source -> DUPLICATE, several
  sources with >1 distinct year -> CONFLICT, else MATCH; researcher IDs with two
  mentions on one work -> CONFLICT, else MATCH. Memory: refs of one identifier.
- Counters: INPUT_RECORDS, EXACT_ID_OUTCOMES, EXACT_ID_SINGLETONS, ACCEPTED,
  DUPLICATE, CONFLICT, SCHEMA_FAILURE.
- Out: `scholarchain/exact_identifier_outcome`, `scholarchain/rejects_j03`.

### J05 BlockingKeyJob / J05b FallbackBlockingJob
- J05 mapper: `(offset, WorkRecord) -> (Text primaryKey, Text BlockMember-json)`,
  one WORK member per work and one RESEARCHER member per mention. Keys:
  researcher `r|<surname>|<first initial>`; work
  `w|<first 3 title tokens>|<5-year bucket>`. No key -> MISSING_KEY.
- Reducers buffer at most `maxBlockSize` members. A group within the bound with
  >= 2 members is written as a block (members sorted by ref). A larger group is
  streamed member by member (bounded memory):
  - J05: re-keyed once with the fallback key to `blocking_rekeyed`
    (researcher `+|co:<smallest coauthor surname>` or `+|y:<year bucket>`; work
    `+|fa:<first-author surname>` or `+|y:<year>`);
  - J05b: still too large -> every member to `scholarchain/oversize_block` as a
    `RejectRecord` (`OVERSIZE_BLOCK`), counted, never truncated or dropped.
  A bigger reducer is never used.
- `cleanup` writes the largest group seen (`stats_j05`, `stats_j05b`).
- Counters: INPUT_RECORDS, MISSING_KEY, BLOCKS_EMITTED, REKEYED_MEMBERS,
  OVERSIZE_BLOCK, OVERSIZE_BLOCK_MEMBERS, ACCEPTED, SCHEMA_FAILURE.
- Blocking runs over all normalized works (not only those without an exact
  match); exact outcomes are separate evidence for Phase 5.
- Fixture: blocking test in `PipelineIntegrationSpec`, keys in `KeysAndSourcesSpec`.

### J06 CandidateJoinJob
- Input: blocks from `blocking_block` and `blocking_block_fallback`.
- Mapper: every member pair `i < j` of a block -> `(Text candidateId, Text
  counted MatchCandidate-json)`; two mentions of the same work are skipped.
  Features (`hadoop-blocking-features-v2`, sorted keys, finite): see
  `MatchFeatures`. No thresholds, no decisions. v2 (repair 08):
  `sourceAuthorKeyEqual` compares `Normalize.identityKey` of both keys instead
  of the raw strings, so Cf-only or spacing-only differences do not count as
  different keys. Candidate IDs include the feature version, so every
  candidate ID differs from v1 runs.
- Combiner `CandidateMinCombiner` (min JSON, summed count); reducer keeps one
  row per candidate ID (smallest JSON, i.e. smallest block key); repeats DUPLICATE.
- Counters: INPUT_RECORDS, EMITTED_MATCH_CANDIDATES, ACCEPTED, DUPLICATE, SCHEMA_FAILURE.
- Out: `scholarchain/match_candidate`, `scholarchain/rejects_j06`.

### J07+J08 PublicationTeamJob
- Input: `dblp/work`. Mapper `(offset, WorkRecord) -> (Text workId, Text json)`. No combiner.
- Reducer per work: repeated mentions (same identity key of the source author
  key, `Normalize.identityKey`, else normalized name) removed, first position
  kept (DUPLICATE_AUTHOR_IN_TEAM). The identity key drops Cf characters and
  treats any run of invisible spacing as one space (the same rule as `fold`),
  then applies Unicode NFC, which merges canonically equivalent spellings only
  (precomposed vs combining `é`); case, compatibility forms and the homonym
  suffix are not folded, since they may be distinct dblp persons. The key is
  used only for de-duplication; mention IDs keep the
  frozen core formula (work ID + position) and `sourceAuthorKey` is stored
  unchanged. Emits
  `PublicationTeam`, one `AuthorPaperRecord` per kept mention, and, for
  `teamSize <= maxPairExpansionTeamSize`, all n(n-1)/2 `coauthor-pair-candidate-v2`
  rows via `CoauthorPairCandidate.fromPositions` (canonical order, positions,
  work ID, year, source provenance). `teamSize > largeTeamThreshold` ->
  LARGE_AUTHOR_TEAM and `large_team`; `> maxPairExpansionTeamSize` ->
  OVERSIZE_AUTHOR_TEAM and `oversize_team` (team and author-paper rows still
  written, no pairs). Missing year -> MISSING_KEY, reject MISSING_YEAR.
- `cleanup` writes `stats_j07` (max team size, largest pair expansion).
- Counters: INPUT_RECORDS, ACCEPTED, EMITTED_PAIRS, LARGE_AUTHOR_TEAM,
  OVERSIZE_AUTHOR_TEAM, DUPLICATE_AUTHOR_IN_TEAM, DUPLICATE, CONFLICT,
  MISSING_KEY, SCHEMA_FAILURE.
- Fixture: `TeamExpansionSpec` (0/1/2/3/5 authors), fixture run in `PipelineIntegrationSpec`.

### J09 GraphCandidateExportJob
- Input: every primary table of the run. Mapper re-validates each row with its
  core contract and emits `(Text "<scholarchain/spark_<table>/...>[/year=<y>]\u0001<id>", counted json)`.
- Combiner `DedupCombiner`; reducer `DedupReducer`.
- Year-partitioned: `spark_work`, `spark_publication_team`, `spark_author_paper`,
  `spark_coauthor_pair` (`year=unknown` when absent). Node tables merge into
  `spark_graph_node`.
- Counters: INPUT_RECORDS, ACCEPTED, SCHEMA_FAILURE, DUPLICATE, CONFLICT.

## Text folding (`text/Normalize.fold`)

NFKD, combining marks removed, invisible format characters (Unicode category
Cf: soft hyphen U+00AD, zero-width space U+200B, ZWNJ/ZWJ, word joiner, BOM)
removed without separating tokens, lower-cased with `Locale.ROOT`, every other
run of non-letter/non-digit characters replaced by one space. Used for
normalized titles and names, blocking keys and features; never for stable IDs.

Blankness (`Normalize.isBlank`): a value made only of whitespace, Unicode space
separators (including no-break spaces), ISO controls or Cf characters is blank.
This is the single blankness rule for required text, so an invisible value is
treated exactly like an empty one:
- DBLP (J01): a blank `<author>` is `MISSING_AUTHOR_NAME`; a blank `<title>` is
  `MISSING_TITLE`. Editors are not authors; they stay in `properties` only.
- OpenAlex and Crossref (J02): `Json.str`/`Json.strs` treat a blank string as
  absent, so a blank author name is `MISSING_AUTHOR_NAME`, a blank title is
  `MISSING_TITLE`, and a blank Crossref `given` falls back to `family` alone.
- ORCID (J02): a blank name or affiliation element is absent (the label falls
  back to given + family names, then to the iD).
- Every JSON-to-property path (repair 08): `Json.scalar` (used for `type`,
  `language`, `ids.*`, `publisher`, `event.*`, `score`, ...) returns nothing
  for a blank string, and `Json.nonEmpty` (abstract presence) uses
  `Json.present`: a non-blank string, a number or boolean, an array with a
  present element, or an object with a non-blank key and a present value. An
  invisible-only Crossref `abstract` or an OpenAlex inverted index whose only
  words are invisible gives no `abstractTextRef`. So a work node never holds an
  invisible-only property and agrees with `WorkRecord` (`recordType` "unknown").
- DBLP properties (J01): a blank field text or attribute value is not stored;
  `<field>.<i>` keeps the source position among same-named fields.
  `DblpRawRecord.first` returns the first non-blank field of a name, so a blank
  first `<journal>` does not hide a later one.

Fallback candidates (repair 08): where a value has several sources, the first
candidate that normalizes successfully wins (`Json.firstValid`), not the first
one present. OpenAlex DOI: `doi`, then `ids.doi`. OpenAlex mention ORCID:
`author.orcid`, then `raw_orcid`. DBLP DOI and arXiv ID already scan every
`<ee>` in order.

Identity keys (`Normalize.identityKey`, repair 08): `spacingNormalized` (Cf
removed without splitting tokens, every run of other invisible code points
replaced by one ASCII space, trimmed) then NFC. No case folding, no NFKC. Used
for team de-duplication (J07) and `sourceAuthorKeyEqual` (J06); never stored,
never part of a stable ID.

Homonym suffix: `Normalize.stripHomonymSuffix` removes ` [0-9]{4}$` from the
`spacingNormalized` form, so a Cf character beside the number
(`Wang 0001` + ZWSP, `Wang 00` + soft hyphen + `01`) or a no-break or
ideographic space before it still marks the suffix, and the number never
becomes the blocking surname.

Fallback: when a non-blank title or name folds to "" (punctuation only), the
normalized form is the raw value with Cf characters removed and invisible
characters stripped from both ends (`Normalize.stripInvisible`; titles also
lower-cased with `Locale.ROOT`). Raw values are stored unchanged.
Fixtures: `InvisibleTextSpec`, `InvisibleIdentitySpec` (repair 08).

## Known limits

- Entity replacement text is inserted as character data (DBLP entities are
  single characters). `&name;` inside a CDATA section or comment would be
  resolved too; DBLP has neither.
- Large exact-identifier groups are held in one reducer call (one ref per
  record, about 100 bytes each).
- Blocking-key and feature definitions overlap the entity-resolution
  territory; they are implemented here per the Phase 4 work order.
