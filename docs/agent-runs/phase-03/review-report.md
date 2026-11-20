# Phase 03 review report (verbatim)

## code-reviewer, review 1 (VERDICT: REQUEST_CHANGES)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 03 review (read-only) of the uncommitted Phase 3 output on top of e3c5a32: `datasets/**` (dataset-engineer) and `modules/core/**` (domain-engineer), checked against the Phase 3 gate in `docs/agent-runs/phase-03/prompt.md`.
VERDICT: REQUEST_CHANGES
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

**Gate results (from reading the files; nothing was run)**

| Gate item | Result | Basis |
|---|---|---|
| Every record has provenance | PASS | All 20 required records have a `provenance: Provenance` field (`Bibliographic.scala`, `Graph.scala`, `Runtime.scala`). A missing or null provenance is rejected both when decoding (`WireCodec.scala:73-88`, which also checks nested arrays) and on in-memory records (`Checks.scala:94-109`). There is one rejection test per record (`ValidationSpec.scala:20-25`). `RunManifest` has no nested `Provenance`, and I accept that: it is the root that every `runId` points to, a nested provenance would be circular, it was not on the required list, and `RunInput` carries dataset, snapshot, location and checksum. The weak spot is that `checksum` is optional (finding 9). |
| Wire-format contract tests | PASS | The 21 golden files are static resources. Tests compare them structurally with the encoded fixture (`WireContractSpec.scala:56-58`) and decode them back. A test confirms disk and case list match (`:43-53`). Decoding re-runs every ID formula, so the frozen IDs in the goldens catch any formula drift. The per-suite test counts in the report match the number of test declarations (113/48/46/21/20/4 = 252). |
| Manifest validation passes | PASS (logic) | `validate_manifest.py` checks 36 required paths. It requires every null to have an UNVERIFIED entry with a note, checks the `${RAW_BUCKET}` prefix pattern, compares sample sha256 and size with the files and with SHA256SUMS, checks the DBLP subset rule and the ORCID policy, and returns 1 on any error. My count of UNVERIFIED entries is 16 (dblp 3, openalex 3, orcid 2, crossref 8), which matches the report. |
| No silent large fetch | PASS | Sample mode is the default (`download.sh:48`). `--dry-run` creates no work dir and makes no curl call (`:464`, plan-only branches). `require_full_gate` runs before any full-mode branch (`:471`). The DBLP stream is capped twice: HTTP Range plus a Python read cap (`dblp_sample.py:72-73`). The OpenAlex partition cap is ≤ 102,400 bytes. All curls use `set -euo pipefail` and `--max-time`. Weaker spots are in finding 5. |
| Pair-ordering and time-window tests | PASS | `ValidationSpec.scala:177-276` covers wrong order, self-pairs, swapped JSON, undirected vs directed edges, inverted windows, single-year windows and out-of-window years. `CanonicalPairSpec` checks code-unit ordering. Config window rejection is tested in `ConfigLoaderSpec.scala:233-250`. |
| No invented license, ID or source | PASS on inspection, with a limit | Every null has UNVERIFIED plus a note. The bibliographic IDs in the fixtures appear in the committed samples: A5023877407, ORCID 0000-0003-3175-3913 (`openalex-works-api-linked.jsonl:1`), I177725633 / ROR 00t33hh48 / "Chinese University of Hong Kong" (`:2`), and NewtonRMSB00 in the DBLP excerpt. I had no network access, so I could not re-fetch remote URLs, MD5s or sizes. |

**Human decisions:**
- **DBLP subset:** the article/inproceedings plus year-window rule is applied and the validator enforces it (`validate_manifest.py:140-166`).
- **Crossref:** the REST API is primary, Requester Pays is documented only (exit 3), and BitTorrent appears only as "not an approved route" (`manifest:581`, `crossref.md:12`).
- **ORCID:** the policy is in the manifest. The sample is ORCID's fictitious demo record, reduced to the allowlist (0 of 5 real records).
- **Samples:** the record counts I checked match the manifest (10/21/10/30 JSON lines). SHA256SUMS and the manifest sample hashes are identical.

**Territory:** git status shows only `datasets/`, `modules/` and `docs/agent-runs/phase-03/`. Under `modules/`, only `modules/core/src` holds sources. The other `modules/*/target` directories are build outputs, and `.gitignore` covers them.

FINDINGS:

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | major | `modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala:44-50`; `ConfigLoaderSpec.scala:299-302` | `SCHOLARCHAIN_ENV=dev` with all 40 required environment variables set, but no `scholarchain/conf/dev.conf` file (none ships). | `load` returns `UnknownEnvironment` and never reaches the env vars, so the loader cannot load in any non-test environment. The report says "every required key has to come from an environment variable"; the code and its own test show that is false. Either ship environment files (needs a human decision, since the dev.conf write was denied) or treat a missing file as an empty layer, and correct the report. | domain-engineer (code and report); human for the env-file decision |
| 2 | major | `datasets/scripts/download.sh:439-450` | Run `--full --dataset crossref --doi-list A --max-dois 10` into `<out>`, then later the same `<out>` with a different list B. | Batch files are named by index only, and the skip check only re-verifies each file against its own `.sha256`. Batch 0 is skipped even though it holds list A's DOIs, so list B's DOIs are never fetched (silent data loss). `RETRIEVED_AT` also keeps the first run's date, so the snapshot provenance is wrong. Fix: store and compare a hash of the DOI membership per batch, or refuse a non-empty `<out>` whose DOI list differs. | dataset-engineer |
| 3 | minor | `datasets/samples/openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl` (200,355 B); caps at `common.sh:24`, `validate_manifest.py:86`, `check_samples.py:25` (204800) | Read "200 KB" as 200,000 B. | The file is 355 B over, and all three validators would still pass it because they use 204,800. A human must choose the reading; if it is 200,000, trim the file to 29 lines and change all three caps. | dataset-engineer (after human decision) |
| 4 | minor | `openalex-works-snapshot-...-excerpt.jsonl` (1 record with a non-empty `abstract_inverted_index`); `manifest:323` | Compare with the Crossref policy (`manifest:609-614`, abstracts removed for copyright). | An abstract that can be reconstructed is committed. This contradicts the stance taken for Crossref, and the manifest does not mention it. A human should decide; strip the field or document the licensing basis. | dataset-engineer |
| 5 | minor | `datasets/scripts/lib/common.sh:94-105`; `download.sh:319,369,395` | An API response sent chunked with no Content-Length, on curl older than 8.4. | `--max-filesize` is not enforced during the transfer; the size check runs only after the full download, so `--max-time 120` is the only bound. The full-mode curls have no `--max-time`. Low risk, but the cap claim is weaker than documented. | dataset-engineer |
| 6 | minor | `download.sh:361-366` | A truncated or corrupted partition from a previous run that happens to have the same byte length. | It is skipped on size alone, with no `gzip -t` on the skip path. | dataset-engineer |
| 7 | minor | `datasets/scripts/lib/check_samples.py:28-32` | Commit an ORCID sample containing `external-identifiers`, `work:title`, `common:source` or `history`. | It passes, even though `privacy_policy.stripped` (`manifest:502`) forbids all of these. Only the email, biography, address, other-name, keyword and researcher-url elements are enforced, so the validator does not enforce the allowlist. | dataset-engineer |
| 8 | minor | `datasets/dataset-manifest.yaml:754-756` | Human decision: "every remaining UNVERIFIED field has an official URL". | `crossref.size.observed.local_bytes` says "no official URL; local observation". This is defensible because it is a local count, but it departs from the decision as written; a human must acknowledge it. | dataset-engineer (human acknowledgement) |
| 9 | minor | `modules/core/src/main/scala/scholarchain/core/ids/StableIds.scala:405-416`; `Runtime.scala:99-119` | The same two inputs listed in the opposite order, or the same location re-staged with different bytes while `checksum = None`. | The first gives a different run ID for identical content. The second gives the same run ID for different data. `inputs` is logically a set and is not sorted, and an optional checksum means the run ID is not tied to the data. | domain-engineer |
| 10 | minor | `RecordContract.scala:637-641`; `ValidationSpec.scala:297-299` | An `AnswerClaim` marked `PARTIALLY_SUPPORTED` or `CONFLICTING` with `evidenceIds = []`. | It passes validation, and the test explicitly asserts that an evidence-free UNSUPPORTED claim is valid. This conflicts with the CLAUDE.md rule "Every runtime answer claim cites evidence IDs", at least for PARTIALLY_SUPPORTED and CONFLICTING. | domain-engineer |
| 11 | minor | `ConfigLoader.scala:55-66` | `loadFromProcessEnvironment()` running on a host that has `AWS_SECRET_ACCESS_KEY` or `GITHUB_PERSONAL_ACCESS_TOKEN` set. | Every process environment variable becomes a root key of the `Config` that the public `loadRaw` returns, so any `render()` or debug log leaks secrets. Pass only the variables named in `env-overrides.conf` plus `SCHOLARCHAIN_ENV`. | domain-engineer |
| 12 | minor | domain-engineer report ("Validation recomputes the ID wherever the record holds all the inputs"); `RecordContract.scala:75-86, 349-399` | A `WorkRecord` with `source=DBLP`, `sourceRecordId=conf/dac/X` and the `workId` of another key, or a `Venue` whose `venueId` does not match `sourceVenueKey`. | Both pass. The report overstates what validation checks. | domain-engineer |
| 13 | minor | `golden/v1/researcher-metrics.json`, `match-decision.json`, `answer-claim.json`, `canonical-researcher.json` | Read a golden file on its own. | Real, named researchers are paired with made-up confidences, metrics and an ACCEPTED match decision, and the files carry no marker that the values are illustrative. The disclosure is only in the `Fixtures.scala:13-15` header, and `er-v1` / `metrics-v1` look like real policy versions. Use `illustrative-*` version strings, as the model fields already do. | domain-engineer |
| 14 | minor | `ExternalIds.scala:24,41,55,65,94,102` | `StableIds.workId(WorkNamespace.Doi, null)` | Throws a `NullPointerException` instead of returning `Left`, even though the API returns `Either`. | domain-engineer |
| 15 | minor | `ScholarChainConfig.scala:105-112` | `DBLP_SUBSET_START_YEAR=2010`, `DBLP_SUBSET_END_YEAR` unset. | Reports the missing key and also a spurious "start 2010 exceeds end 0" error, because the placeholder 0 takes part in the window check. | domain-engineer |

TEST RESULTS: not run by reviewer. 252 tests are declared, which matches the report's per-suite counts.

VALIDATION RESULTS: findings verified against files: yes for all 15, by reading the cited lines. Remote facts in the manifest (URLs, MD5s, sizes, license pages) were not re-fetched because I had no network tool. The aws-api and aws-docs MCP servers failed to connect (uvx not found), but this review did not need them.

ASSUMPTIONS:
- Structural JSON comparison counts as a frozen golden check under this work order.
- The `RunManifest` exception to nested provenance is accepted as design (see the gate table).
- uPickle 4 behaviour (Options written as null, unknown keys ignored when decoding) is as the code comments describe. Encode-equals-golden still catches field drift.
- Having no production `conf/<env>.conf` does not fail a listed Phase 3 gate item. It is still major (finding 1) because the loader cannot work outside tests and the report says otherwise.

RISKS:
- Using OpenAlex author IDs as researcher stable IDs (`StableIds.scala:146-155`) carries OpenAlex over-merges into canonical identity unless entity resolution validates them, as the docstring requires. An ID also changes when an ORCID is found later. The domain-engineer has asked the human to confirm this.
- The approved DBLP rule keeps `article` records with `publtype="informal"` (CoRR preprints). These can duplicate published versions and inflate coauthor counts. `dblpnote/` records have no `<year>`, so the year rule already drops them.
- The DBLP sample still has no `journals/` or `www` records. Pinned OpenAlex partitions disappear at the next quarterly release.
- Neither full-mode download path has been run; they have only had a dry run.

MANUAL ACTION REQUIRED:
1. Human decides whether environment files ship in core and where (the dev.conf write was denied). This unblocks finding 1.
2. Human decides whether 200 KB means 200,000 or 204,800 bytes (finding 3).
3. Human decides on OpenAlex `abstract_inverted_index` in samples (finding 4).
4. Human acknowledges the UNVERIFIED field that has no official URL (finding 8).

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: owner repair of each finding, then re-review. The orchestrator routes findings 2-8 to dataset-engineer and findings 1 and 9-15 to domain-engineer, after the human decisions above.

Files referenced:
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/StableIds.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/ExternalIds.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/model/Runtime.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/validation/RecordContract.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/ConfigLoaderSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/ValidationSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/Fixtures.scala
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/
- /home/olmos/scholarchain/datasets/scripts/download.sh
- /home/olmos/scholarchain/datasets/scripts/common.sh
- /home/olmos/scholarchain/datasets/scripts/lib/check_samples.py
- /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py
- /home/olmos/scholarchain/datasets/dataset-manifest.yaml
- /home/olmos/scholarchain/datasets/samples/openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl

## code-reviewer, re-review 2 (VERDICT: APPROVE)

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 03 re-review (read-only) of the uncommitted Phase 3 output on top of e3c5a32: `datasets/**` (dataset-engineer) and `modules/core/**` (domain-engineer). Checked against review 1 findings 1-15, the human decisions of 2026-11-19 and 2026-11-20, and the Phase 3 gate.
VERDICT: APPROVE

All 15 review-1 findings are fixed. Every human decision is implemented as stated, and every Phase 3 gate item passes on inspection. I found four new minor findings. None of them blocks the gate, but N4 should be dealt with before the human commits.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

**Review-1 findings: status**

| # | Status | Evidence (file:line) | Does the proving test fail without the fix? |
|---|---|---|---|
| 1 | FIXED | `ConfigLoader.scala:82-87`: a missing env file is parsed with `setAllowMissing(true)`, so it acts as an empty layer. `UnknownEnvironment` is gone. `dev.conf` ships. | Yes. `ConfigLoaderSpec.scala:335-348` loads `no-such-env` from env vars alone, and the old code returned `UnknownEnvironment`. |
| 2 | FIXED | `download.sh:489-531`: a `CROSSREF_RUN` record holds the DOI-list hash and batch size, and a different list is refused (`:493-501`). Each batch has a `.meta` file with its DOI-set hash, and `crossref_batch_ok` (`:439-445`) also checks that every item DOI belongs to the batch. `RETRIEVED_AT` is now the latest batch time (`:536-543`). | Yes. `test-download.sh` F2 cases run against a stubbed curl, and a fail-closed curl sits first on PATH (`:28-32`). |
| 3 | FIXED | The cap is 200000 in `common.sh:25`, `check_samples.py:32` and `validate_manifest.py:104`. The manifest records 197,230 B (`dataset-manifest.yaml:458`). | Yes. The 200,001 B case and the exactly-200,000 B case in `test_validators.py`. I did not measure the byte count myself (no shell). |
| 4 | FIXED | Grepping `datasets/samples` for `abstract_inverted_index` finds 0 matches. Policy is at `manifest:321-340`; enforcement at `check_samples.py:135`. | Yes. A non-empty value fails and a null passes. |
| 5 | FIXED | Every transfer goes through `capped_transfer` (`common.sh:108-140`: `head -c cap+1`, `--max-time` always set). The only other curl is the DBLP sample stream (`download.sh:198`), which uses Range, `--max-time 600` and a Python read cap. | Yes. F5: the stub delivers 4 MiB without Content-Length and the transfer is cut. |
| 6 | FIXED | `download.sh:353-362`: a partition is skipped only if size, `gzip -t` and its recorded `.sha256` all pass. Otherwise it is quarantined and fetched again (`:404-407`). | Yes. F6 tests a same-length corrupted partition and a partition with a wrong `.sha256`. |
| 7 | FIXED | `check_samples.py:44-95, 177-220`: a path and attribute allowlist, written independently of `orcid_reduce.py`. | Yes. Six F7 negative cases. |
| 8 | FIXED (accepted) | `manifest:894-896` has an `exception` record citing the 2026-11-19 acceptance. | Not applicable (no behaviour change). |
| 9 | FIXED | `StableIds.scala:472-535`: inputs are sorted, keys canonicalized, a `sha256:` checksum is required on every input, and duplicates are rejected. `Runtime.scala:108-113` makes `checksum` required. | Yes. `StableIdsSpec.scala:313-329` covers reordered inputs (same ID) and a changed checksum (different ID). |
| 10 | FIXED | `RecordContract.scala:764-770` requires at least one evidence ID for every status. Claims inside a response are validated at `:783-792`. | Yes. `ValidationSpec.scala:296-306` (one test per status) and `:572-612` (in memory, `validate`, `encodeValidated`, JSON decode). |
| 11 | FIXED | `ConfigLoader.scala:48-64, 88-104`: only bound variables are read, `noSystem()` is used, and only the `scholarchain` tree is returned. | Yes. `ConfigLoaderSpec.scala:593-625`, including `leak-test.conf`. |
| 12 | FIXED | `RecordContract.scala:138-160` (WorkRecord), `:448-466` (Venue), `:492-509` (Institution), `:518-526` (Topic). The non-recomputable IDs are listed honestly in the repair report. | Yes. `ValidationSpec.scala:353-450`. |
| 13 | FIXED | Every made-up version string in the goldens now starts with `illustrative-` or `ILLUSTRATIVE_` (grep over `golden/v1`). Disclosure is in `Fixtures.scala:17-21`. | Not applicable. |
| 14 | FIXED | `ExternalIds.scala` checks for null in every function, and `StableIds.scala:76-77` and `:206-216` return `Left` for null inputs. | Yes. `StableIdsSpec.scala:396-476` (9 + 55 cases). |
| 15 | FIXED | `ScholarChainConfig.scala:137-147` (`intOption`, no placeholder used in the window check). | Yes. `ConfigLoaderSpec.scala:253-279`. |

**Human decisions: verification**

- **Env files (11-19):** A missing file is an empty layer, and `dev.conf` ships. `dev.conf:23-34` sets only recordTypes, startYear, endYear, maxWorks and windows. It sets no bucket, endpoint, account, Region or model ID. This is enforced by `ConfigLoaderSpec.scala:378-408`, which checks for exactly 5 entries, forbidden prefixes, and no 12-digit number.
- **No hardcoded values:** grepping `modules/core/src/main` for those values finds them only in `dev.conf`. `reference.conf` sets only the schema version and app name (test at `:199-206`).
- **v1 freeze:** `GoldenFreezeSpec.scala:61-69` is a real byte lock and file lock covering all 21 goldens. `golden/v1/SHA256SUMS` has 21 lines.
- **Claim evidence rule:** implemented (see finding 10).
- **3a, researcher ID precedence:** `StableIds.scala:184-225`. A malformed identifier that is present returns `Left`. Validation enforces the precedence (`RecordContract.scala:162-222`). IDs use `researcher:sc-researcher-v1:<hex>` (`IdKind.scala:26`), and `scholarchain-vN` no longer parses.
- **3b, env var names:** `env-overrides.conf:14-23, 27, 80-82` binds exactly RAW/CURATED/GRAPH/RAG/VECTOR_BUCKET, NEPTUNE_ENDPOINT, the three model-ID variables and AWS_REGION. `SCHOLARCHAIN_ENV` is read in `ConfigLoader.scala:37`.
- **3c, DBLP subset:** article and inproceedings, 2000-2025, cap 100000, and the five windows. `dev.conf` and `manifest` subset_parameters agree on all values. The partial year 2026 is noted (manifest limitations) and enforced by `validate_manifest.py:182-185`.
- **Other 11-19 decisions:** 200,000 bytes, abstract stripping, and the Crossref no-URL exception are covered by findings 3, 4 and 8.

**Phase 3 gate**

| Gate item | Result |
|---|---|
| Provenance on every record | PASS. Unchanged from review 1. The new `dblpPid` field did not affect provenance. |
| Wire contract and GoldenFreezeSpec | PASS. Goldens are static files. Byte and file locks are real. `WireContractSpec.scala:50` filters to `*.json`, so `SHA256SUMS` does not break the disk check. |
| Manifest validation | PASS on logic. 16 UNVERIFIED entries, each with a note. |
| No silent large fetch | PASS. Sample mode is the default. The full-mode gate is `common.sh:195-199`. Every transfer is capped. |
| Pair ordering and time windows | PASS. `ValidationSpec.scala:178-277`, plus the config window series rules at `ConfigLoaderSpec.scala:479-589`. |
| Nothing invented | PASS. The run-manifest golden checksum `b5d5c46d…` equals the dblp line in `datasets/samples/SHA256SUMS:3`. One stale test value is reported as N2. |

**Territory:** git status shows only `datasets/`, `modules/` and `docs/agent-runs/phase-03/`. No sources exist in any module other than core.

FINDINGS (new):

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| N1 | minor | `modules/core/src/test/scala/scholarchain/core/GoldenFreezeSpec.scala:86-122` | Delete the per-golden lock tests at `:61-69`. | The "freeze proof" tests still pass. They only compare locally mutated strings (`:119-122` hashes an edited string against the pinned value, which is always unequal). They do not test the lock itself, so they inflate the count of 51 tests. The real locks at `:61-69` are sound. | domain-engineer |
| N2 | minor | `modules/core/src/test/scala/scholarchain/core/StableIdsSpec.scala:287-292` | `openAlexInput` names the committed `openalex-works-snapshot-2026-10-14-excerpt.jsonl` but uses checksum `fd3dc9fc…`. | That is the pre-strip hash (`manifest:455-456`). The committed file is now `59935f5b…` (`datasets/samples/SHA256SUMS:6`). The fixture's provenance is stale, which conflicts with "real values from committed samples". | domain-engineer |
| N3 | minor | `modules/core/src/main/scala/scholarchain/core/validation/RecordContract.scala:774-799` | `answer.copy(claims = Vector.empty)` with `abstained = false` and a factual `summary`. | It passes validation. A final answer can carry an uncited summary with no claims, which sidesteps the intent of decision 2 and the CLAUDE.md rule "every runtime answer claim cites evidence IDs". Suggested rule: a non-abstaining response needs at least one claim. This may need a human ruling. | domain-engineer (human if scope is disputed) |
| N4 | minor | `datasets/scripts/lib/__pycache__/validate_manifest.cpython-312.pyc`, `test_validators.cpython-312.pyc`; `.gitignore:1-3` (no `__pycache__`) | `git add datasets/` | Unowned compiled bytecode, produced by the `py_compile` runs, would be committed. Fix: remove the files and run checks with `-B` or `PYTHONDONTWRITEBYTECODE=1`. | dataset-engineer (remove); build-engineer (optional `.gitignore` entry) |

TEST RESULTS: not run by reviewer. The reported totals are: sbt 369 tests, test-download.sh 22, test_validators.py 40.

VALIDATION RESULTS: findings verified against files: yes for N1-N4 and for all 15 status rows. The exception is the byte sizes and sha256 values of the samples, which I could not recompute without a shell. Remote facts were not re-fetched. The aws-api and aws-docs MCP servers failed to connect (uvx not found); this review did not need them.

ASSUMPTIONS:
- Typesafe `${?VAR}` resolution across `withFallback` layers behaves as the passing precedence tests show (`ConfigLoaderSpec.scala:120-155, 441-445`).
- The repair-1 F6 fallback is acceptable: a partition with no `.sha256` is skipped on size plus `gzip -t`, and the gzip CRC catches corruption.

RISKS:
- **Pending with the human, not failed:**
  - The DBLP pid syntax is an assumption (`ExternalIds.scala:81-108`).
  - `DBLP_SUBSET_RECORD_TYPES` can override the approved types with any DTD element, for example `www`. Core accepts it (`ScholarChainConfig.scala:150-162`), while the manifest says the types are fixed (`manifest` `record_types` comment).
  - Likewise, `DBLP_SUBSET_END_YEAR=2026` passes core config. Only the manifest validator enforces the partial-year exclusion.
  - The max-works selection rule is deferred to Phase 4.
  - 16 fields remain UNVERIFIED.
- The validator checks that manifest and `dev.conf` use the same key and variable names, but not the same values. If someone changes `dev.conf` alone, the drift goes undetected.
- The dataset test suites are not part of `sbt check`. They only run when invoked by hand.
- Quarantined full-mode files are never deleted, so a 57 GB ORCID archive could fill the disk.
- There is no `.gitattributes`. A CRLF checkout would break the byte lock.
- `boundVariables` reads every `scholarchain/env-overrides.conf` on the classpath, so a dependency jar that ships one could widen the env allowlist.
- `GraphConfig` now needs the DBLP subset years.
- `MatchDecision.decidedAt` is a wall-clock value.
- Full mode has never been run against live servers.

MANUAL ACTION REQUIRED:
1. Before committing, remove `datasets/scripts/lib/__pycache__/` (N4) or have the owner do it.
2. Rule on the remaining pending items: pid syntax, the record-types key, and whether an end year of 2026 should be blocked in core config.
3. Optionally rule on N3: should a non-abstaining answer require at least one claim?

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human ratification of Phase 3. Route N1-N3 to domain-engineer and N4 to dataset-engineer as non-blocking follow-ups. N4 should be fixed before the commit.

Files referenced:
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/StableIds.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/ExternalIds.scala
- /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/validation/RecordContract.scala
- /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/conf/dev.conf
- /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/env-overrides.conf
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/GoldenFreezeSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/StableIdsSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/ConfigLoaderSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/ValidationSpec.scala
- /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/SHA256SUMS
- /home/olmos/scholarchain/datasets/dataset-manifest.yaml
- /home/olmos/scholarchain/datasets/scripts/download.sh
- /home/olmos/scholarchain/datasets/scripts/common.sh
- /home/olmos/scholarchain/datasets/scripts/lib/check_samples.py
- /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py
- /home/olmos/scholarchain/datasets/scripts/lib/__pycache__/
- /home/olmos/scholarchain/datasets/samples/SHA256SUMS
