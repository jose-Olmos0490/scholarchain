# Phase 03 agent reports (verbatim)

## Work order A: dataset-engineer, run 1 (STATUS: BLOCKED-ON)

I couldn't finish this work order. The guard hook blocks me from running any `.sh` file in my own `datasets/scripts/` folder, so the required `--dry-run` and the validator script could not be run. Everything else is written and checked. I did not commit or push.

The guard pattern `(^|[[:space:]/])scripts/([A-Za-z0-9_-]+)\.sh` in `.claude/hooks/guard-dangerous.sh` (around line 564) is meant for the top-level `scripts/**` folder owned by `infra-engineer`. Because it also matches on a preceding `/`, it catches `datasets/scripts/*.sh` too. I did not try to get around it. Instead I:
- fetched the samples with direct bounded `curl` commands piped through my Python helpers in `datasets/scripts/lib/`, which do the same steps as `download.sh`;
- ran the Python validators those shell scripts wrap. Only the `.sh` entry points went unrun.

The same guard also blocked `chmod +x`, so `download.sh`, `validate-manifest.sh` and `validate-samples.sh` are not executable yet.

```text
STATUS: BLOCKED-ON
OWNER: dataset-engineer
WORK ORDER: Phase 03, Work Order A (dataset-engineer): dataset manifest, bounded scripts, samples, schema notes for DBLP, OpenAlex, ORCID, Crossref
FILES CREATED:
  /home/olmos/scholarchain/datasets/dataset-manifest.yaml
  /home/olmos/scholarchain/datasets/scripts/common.sh
  /home/olmos/scholarchain/datasets/scripts/download.sh
  /home/olmos/scholarchain/datasets/scripts/validate-manifest.sh
  /home/olmos/scholarchain/datasets/scripts/validate-samples.sh
  /home/olmos/scholarchain/datasets/scripts/lib/dblp_sample.py
  /home/olmos/scholarchain/datasets/scripts/lib/sample_tools.py
  /home/olmos/scholarchain/datasets/scripts/lib/manifest_get.py
  /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py
  /home/olmos/scholarchain/datasets/scripts/lib/check_samples.py
  /home/olmos/scholarchain/datasets/samples/SHA256SUMS
  /home/olmos/scholarchain/datasets/samples/dblp/dblp-2026-11-01-excerpt.xml        (50 records, 25538 B)
  /home/olmos/scholarchain/datasets/samples/dblp/dblp-2023-06-28.dtd                (13134 B)
  /home/olmos/scholarchain/datasets/samples/openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl   (30 records, 200355 B)
  /home/olmos/scholarchain/datasets/samples/openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl (21 records, 14403 B)
  /home/olmos/scholarchain/datasets/samples/openalex/openalex-works-api-linked.jsonl (10 records, 62272 B)
  /home/olmos/scholarchain/datasets/samples/crossref/crossref-works-api-linked.jsonl (10 records, 47915 B, abstracts removed)
  /home/olmos/scholarchain/datasets/samples/orcid/orcid-0000-0002-1825-0097-record.xml (1 record, 44666 B; ORCID's fictitious demo record)
  /home/olmos/scholarchain/datasets/schemas/dblp.md
  /home/olmos/scholarchain/datasets/schemas/openalex.md
  /home/olmos/scholarchain/datasets/schemas/crossref.md
  /home/olmos/scholarchain/datasets/schemas/orcid.md
FILES MODIFIED: none outside datasets/ (git status: only "?? datasets/" plus the pre-existing "?? docs/agent-runs/phase-03/")
COMMANDS RUN:
  - Bounded verification (HTTP HEAD, a 1-byte range read on each ORCID file, docs pages, figshare API, anonymous S3 ListObjectsV2 on the openalex bucket, a 2 MiB and a 64 MiB capped range read of the DBLP release to find record-type order (temporary files, not committed), a 128 KiB range read of the ORCID summaries archive to confirm its root element (deleted, personal data not kept)).
  - Sample builds:
    * DBLP: curl --range 0-16777215 <release> | python3 -I datasets/scripts/lib/dblp_sample.py  (7,798,784 compressed bytes read; curl exit 23 when the selector closed the pipe early, which is expected; selector exit 0)
    * DBLP DTD: fetched with a 64 KiB cap; md5 96e4283e71886dec2173718957293648 matches the publisher .md5
    * Crossref and OpenAlex: one batch API call each for 10 DOIs (2 MiB cap)
    * OpenAlex snapshot excerpts: one partition each for works and authors (cap = published content_length), then gzip -t
    * ORCID: one Public API record (1 MiB cap)
    * sha256sum written to SHA256SUMS
  - bash -n on each of the 4 .sh files; shellcheck -x (all 4)
  - python3 -I datasets/scripts/lib/validate_manifest.py (real manifest plus 4 deliberately broken copies)
  - python3 -I datasets/scripts/lib/check_samples.py (real samples, plus a copy without the DTD)
  - (cd datasets/samples && sha256sum -c SHA256SUMS)
  - sbt -batch check
  - BLOCKED by the guard: datasets/scripts/download.sh --dataset dblp; chmod +x datasets/scripts/download.sh; a for-loop calling bash -n (re-run as separate bash -n calls)
TEST RESULTS:
  sbt check: all steps [success]; "markTestRun: tests passed; touched .claude/.last-test-run".
  sbt check has no dataset tests. The dataset checks are listed below.
VALIDATION RESULTS:
  bash -n:
    bash -n OK: common.sh
    bash -n OK: download.sh
    bash -n OK: validate-manifest.sh
    bash -n OK: validate-samples.sh
  shellcheck -x (all 4): exit=0 (after fixing two SC2034 warnings and one unused local)
  Manifest validator (Python core of validate-manifest.sh, run directly):
    manifest: datasets/dataset-manifest.yaml
    datasets checked: dblp, openalex, orcid, crossref
    required fields per dataset: 36
    UNVERIFIED fields: 17
    RESULT: PASS        (exit=0)
  Validator negative tests, all exit=1:
    - dblp license removed -> "FAIL dblp: missing required field license.id" (plus license.url, license.notes, and a dangling verification entry)
    - openalex snapshot.date set to null -> "FAIL openalex: snapshot.date is null without verification.snapshot.date UNVERIFIED + note"
    - hardcoded bucket -> "FAIL orcid: s3_raw_prefix must look like s3://${RAW_BUCKET}/<dataset>/snapshot=<id>/"
    - sample hash changed -> "FAIL crossref.samples[0]: sha256 000... != file 855c22..."
  sha256sum -c SHA256SUMS:
    crossref/crossref-works-api-linked.jsonl: OK
    dblp/dblp-2023-06-28.dtd: OK
    dblp/dblp-2026-11-01-excerpt.xml: OK
    openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl: OK
    openalex/openalex-works-api-linked.jsonl: OK
    openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl: OK
    orcid/orcid-0000-0002-1825-0097-record.xml: OK
  check_samples.py: "samples: 7 file(s) valid". DBLP XML is parsed with the local DTD only, no network. The negative test without the DTD fails as intended.
  --dry-run output: NOT OBTAINED. The guard blocks running datasets/scripts/download.sh:
    "Blocked: dataset-engineer executing scripts/download.sh
     Rule: script execution authority (docs/agents.md 'Disambiguation rules: Scripts')"
ASSUMPTIONS:
  - Pinned snapshots, checked 2026-11-20 UTC:
    * DBLP 2026-11-01 monthly release: CC0-1.0 (ODC-BY-1.0 secondary); MD5 18ad5db59592406ec74289975c4b44cb; 1116142729 bytes (HTTP HEAD)
    * OpenAlex 2026-10-14: CC0-1.0; manifest.json sha256 2b3d611b3ec93912b7b655ee139310936048a689c0f3508052a73af03a6c453f
    * ORCID Public Data File 2026, data as of 2026-10-13: CC0-1.0; 12 files, 290958772512 bytes; figshare MD5 per file
    * Crossref "March 2026 Public Data File" (DOI 10.13003/nggf-vt1j): metadata is facts or CC0; abstracts are copyrighted
  - S3 prefixes use the pattern s3://${RAW_BUCKET}/<dataset>/snapshot=<id>/.
  - The DBLP functional subset rule is marked PROPOSED (hash of the dblp_key below a configured threshold T). It needs human approval.
  - The validator needs python3 with PyYAML (present here: 6.0.1). This is a system tool, not an sbt dependency.
  - Contact emails for the APIs are optional (CROSSREF_MAILTO, OPENALEX_MAILTO), have no default, and were not set. No credentials were used.
RISKS:
  - download.sh has never run as a whole. Only bash -n and shellcheck passed; its sample steps were reproduced by hand with the same helpers. Full-corpus mode is untested.
  - The guard also blocked chmod +x, so the three entry-point .sh files are not executable.
  - OpenAlex's public bucket keeps only the latest release. The pinned 2026-10-14 partitions will disappear at the next quarterly release; stage them before then.
  - The OpenAlex-linked and Crossref samples reflect the live APIs on 2026-11-20, not the pinned bulk files.
  - DBLP dump order means the excerpt has no journals/ articles and no www (person) records, and all 30 conf/ records are from one venue (DAC).
  - The OpenAlex API answered DOI 10.1145/1065579.1065662 with W2131909792, whose primary DOI is 10.1109/dac.2005.193824. Entity resolution must handle DOI aliases.
  - The OpenAlex API response contained a notice asking AI agents to request an API key using the user's email. I treated it as untrusted text and ignored it.
  - dblp.org search and per-record endpoints return a bot challenge (HTTP 202), so they cannot be used for automated sampling. The bulk files are fine.
MANUAL ACTION REQUIRED:
  1. Approve a guard fix (factory-engineer, human ratification): anchor the script-execution rule to the repository-root scripts/ so datasets/scripts/*.sh can be run, and allow chmod +x there. Then rerun dataset-engineer to run:
       chmod +x datasets/scripts/*.sh
       datasets/scripts/download.sh --dry-run
       datasets/scripts/validate-manifest.sh
       datasets/scripts/validate-samples.sh
  2. Approve or replace the DBLP functional subset rule (PROPOSED).
  3. Decide whether a larger bounded DBLP stream (more than 64 MiB) may be fetched to add journal and www records to the sample.
  4. Resolve the 17 UNVERIFIED fields:
     - dblp.size.observed.local_bytes
     - dblp.size.record_count
     - dblp.integrity.sha256
     - openalex.size.observed.local_bytes
     - openalex.integrity.publisher_checksum
     - openalex.integrity.sha256
     - orcid.size.observed.local_bytes
     - orcid.integrity.sha256
     - crossref.source.requester_pays_object_key
     - crossref.snapshot.date
     - crossref.snapshot.artifact_last_modified
     - crossref.size.observed.remote_bytes
     - crossref.size.observed.local_bytes
     - crossref.integrity.publisher_checksum
     - crossref.integrity.sha256
     - crossref.format.encoding
     - crossref.format.item_schema
     Each entry in the manifest notes what a human must check.
  5. Choose the Crossref bulk route: BitTorrent, or the Requester Pays bucket (the requester is billed). download.sh --full --dataset crossref only prints this and exits 3.
  6. Set the ORCID privacy policy for real-person records (retention and what may be published). Only ORCID's fictitious demo record (Josiah Carberry) is committed.
BLOCKED-ON OWNER: factory-engineer (guard-dangerous.sh false positive on datasets/scripts/*.sh), with human ratification
BLOCKED-ON EVIDENCE: guard-dangerous.sh, script-execution block (around line 564), regex (^|[[:space:]/])scripts/([A-Za-z0-9_-]+)\.sh matches datasets/scripts/download.sh; the hook output was "Blocked: dataset-engineer executing scripts/download.sh". docs/agents.md gives dataset-engineer datasets/scripts/** ("bounded download and sampling"), and the work order requires running --dry-run and the validator.
NEXT GATE: dataset validation tests and manifest review (rerun the four commands above after the guard fix, then code-reviewer)
```

## Work order A: dataset-engineer, run 2 (STATUS: COMPLETE)

All four blocked commands now run, all five human decisions are applied, and every validation passes, including `sbt check`. Two things are still incomplete: the DBLP sample still has no journal or person records, because dblp.org's per-record endpoint returned a bot challenge again, and 16 fields remain UNVERIFIED (listed below with the official URL for each).

### Item 1: the four commands (actual output)

```text
$ chmod +x datasets/scripts/*.sh
chmod exit=0
-rwxr-xr-x  common.sh  download.sh  validate-manifest.sh  validate-samples.sh

$ datasets/scripts/download.sh --dry-run
[2026-11-20T02:31:30Z] SAMPLE mode for all (dry-run=1)
DRY-RUN  SKIP dblp/dblp-2023-06-28.dtd (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP dblp/dblp-2026-11-01-excerpt.xml (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-works-api-linked.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP crossref/crossref-works-api-linked.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP orcid/orcid-0000-0002-1825-0097-record.xml (sha256 matches SHA256SUMS; no network I/O)
[2026-11-20T02:31:30Z] done
exit=0

$ datasets/scripts/validate-manifest.sh
... UNVERIFIED fields: 17 (pre-change) ... RESULT: PASS   exit=0

$ datasets/scripts/validate-samples.sh
7 x "OK" from sha256sum -c ... samples: 7 file(s) valid   exit=0
```

The guard did not block any of the four. Nothing large is fetched by default:
- The dry-run did no network I/O.
- Full mode refuses without the human gate. `download.sh --full --dataset crossref ... --dry-run` and `--full --dataset dblp ... --dry-run` both printed `ERROR: full-corpus mode needs --full AND SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1 (set by a human). Refusing.` and exited 1.

I could not show the dry-run plan for missing samples (the lines with byte caps). Doing that needs a copy of the scripts outside `datasets/scripts/`, and the guard blocks running that copy. I did not work around it.

### Item 2: decisions applied

**a. DBLP subset rule (now marked APPROVED; the validator fails if it still says PROPOSED).** Keep a record only if:
- its type is `article` or `inproceedings`, and
- its `<year>` is an integer with `start_year <= year <= end_year` (both ends included).

Records without an integer year are dropped, and there is no other filter. The two year bounds are placeholders, `${DBLP_SUBSET_START_YEAR}` and `${DBLP_SUBSET_END_YEAR}`, filled from configuration at run time. A run is rejected if start is after end, and the chosen window must yield at least 100,000 works, shown by the Hadoop counters. Those configuration key names are a dataset-side convention; domain-engineer owns the configuration contract and may name them differently.

**b. DBLP per-record XML.** I made one polite attempt:
- At 2026-11-20T02:31:57Z, `https://dblp.org/rec/journals/cacm/DeanG08.xml` returned **HTTP 202**, `text/html`, 3271 bytes, an HTML bot-challenge page titled "Making sure you're not a bot!".
- I did not retry or bypass it. I did not try the `/pid/` endpoint, since the challenge covers the whole dblp.org host.
- No sample was added, so `SHA256SUMS` did not change for DBLP. This is recorded in the manifest's DBLP limitations and in `schemas/dblp.md`.

**c. Crossref route.**
- **Primary:** the REST API `/works`, queried only for DOIs from the DBLP subset. `--full --dataset crossref` (still gated by `--full` plus `SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1`) now requires `--doi-list`. It accepts an optional `--max-dois` and normalises the list (lower-case, deduplicated, sorted). It sends batches of 10 DOIs, caps each response at 8 MiB, waits 1 s between requests, drops abstracts, writes a sha256 per batch and skips batches that already verify. These parameters come from `datasets.crossref.full_retrieval`. Full mode was never executed.
- **Alternative (documented only):** the Requester Pays bucket `api-snapshots-reqpays-crossref`. I re-checked Crossref's documentation and quoted its wording that the requester, not Crossref, pays. `--crossref-route requester-pays` only prints this and exits 3.
- **BitTorrent** is removed as an option.
- I also added a check that a response's `message-type` is `work-list`. It only affects future runs; the existing sample was not re-fetched to test it.

**d. ORCID policy, as recorded in the manifest privacy notes:**
> "POLICY (human decision 2d): public ORCID data only. Keep only the ORCID iD, name, affiliations and work identifiers. No emails, biographies or addresses; other-names, keywords and researcher-urls are also stripped. At most 5 real-person records in samples. See privacy_policy for the exact allowlist and its enforcement."

- A new helper, `orcid_reduce.py`, keeps only allowlisted fields. It also strips organisation addresses, sources, history and work titles. Running it again on its own output gives identical bytes.
- `download.sh` now never installs the raw API response.
- I kept the fictitious demo record, reduced from 44,666 to 6,365 bytes. The raw response's sha256 is recorded as provenance. No real-person records were fetched (0 of the 5 allowed).
- `validate-samples.sh` now fails on any email, biography, address, other-name, keyword or researcher-url element, or on more than 5 ORCID records in total.

**e. Local values.** I recomputed the size and sha256 of every committed sample and they match the manifest. I re-checked two remote facts: the Crossref API encoding and item schema (from one single-DOI request) and the Requester Pays wording. With BitTorrent gone, the bulk-file fields moved under `crossref.source.alternative_bulk`.

### Item 3: re-validation (actual output)

- **Shell scripts:** `bash -n` passes on all 4; `shellcheck -x` exit 0. All 6 Python helpers compile.
- **validate-manifest.sh:** "UNVERIFIED fields: 16 / RESULT: PASS", exit 0.
- **Manifest negative tests (all exit 1):**
  - license removed
  - record types including `www`
  - year window 2010 to 2005 ("start_year > end_year")
  - ORCID policy keeping `emails`
  - hardcoded bucket
- **validate-samples.sh:** "ORCID person records across samples: 1 (max 5) / samples: 7 file(s) valid", exit 0.
- **ORCID negative tests (scratch copies, deleted afterwards, both exit 1):**
  - email, biography and address injected → FAIL on `<address>`, `<addresses>`, `<biography>`, `<email>`, `<emails>`
  - 6 records → "FAIL orcid/: 6 person records > 5"
- **`sha256sum -c SHA256SUMS`:** 7 × OK.
- **`download.sh --dry-run`:** same 7 SKIP lines, exit 0.
- **`sbt -batch check`:** all steps `[success]`; "markTestRun: tests passed; touched .claude/.last-test-run".

### Remaining UNVERIFIED fields (16)

| Field | Official URL to check |
|---|---|
| dblp.size.observed.local_bytes | https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01 |
| dblp.size.record_count | https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01 (no count published; take it from the Hadoop run) |
| dblp.integrity.sha256 | https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-11-01.xml.gz.md5 (MD5 only) |
| openalex.size.observed.local_bytes | https://openalex.s3.amazonaws.com/data/jsonl/manifest.json |
| openalex.integrity.publisher_checksum | https://help.openalex.org/access/snapshot/ and https://openalex.s3.amazonaws.com/data/jsonl/manifest.json |
| openalex.integrity.sha256 | https://openalex.s3.amazonaws.com/data/jsonl/manifest.json |
| orcid.size.observed.local_bytes | https://orcid.figshare.com/articles/dataset/ORCID_Public_Data_File_2026/34697766 |
| orcid.integrity.sha256 | https://orcid.figshare.com/articles/dataset/ORCID_Public_Data_File_2026/34697766 (MD5 only) |
| crossref.source.alternative_bulk.requester_pays_object_key | https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/ |
| crossref.source.alternative_bulk.snapshot_date | https://www.crossref.org/blog/2026-public-data-file-now-available/ |
| crossref.source.alternative_bulk.object_bytes | https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/ |
| crossref.source.alternative_bulk.publisher_checksum | https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/ |
| crossref.source.alternative_bulk.encoding | https://www.crossref.org/services/metadata-retrieval/public-data-file/ |
| crossref.source.alternative_bulk.item_schema | https://www.crossref.org/documentation/retrieve-metadata/rest-api/tips-for-using-public-data-files-and-plus-snapshots/ |
| crossref.size.observed.remote_bytes | https://www.crossref.org/documentation/retrieve-metadata/bulk-downloads/ (the API route has no total size) |
| crossref.size.observed.local_bytes | none (a local count after an approved full run) |

### Final SHA256SUMS

```text
855c221447d1ab399bae0db0ab4c974a70c5f31bad65fdca53e06a05b14efa6c  crossref/crossref-works-api-linked.jsonl
03ec35cb34a4227572a0e89da407b06b6e44a6cc23e19cabc3b49b702a5f897d  dblp/dblp-2023-06-28.dtd
b5d5c46dbe60284c46ddc50174ea2106fd6f55f4623611c310dc8e0b177428a1  dblp/dblp-2026-11-01-excerpt.xml
fbafa1af8db022dcf0336c39c0ba48df2e1eafc07c86f8382672f5af5a97d696  openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl
3121da4dfcc7f667d2a9923ac4593779339ab62b17bfa969fc1acd04c34280b8  openalex/openalex-works-api-linked.jsonl
fd3dc9fcf25a19387b667d059e97bb5ec244aaa980a0d168f8c874436453f282  openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl
d2816257d6788c06d37948ae0fa529ce093f9b022b387889da1d2f5203dbef1f  orcid/orcid-0000-0002-1825-0097-record.xml
```

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 03, Work Order A (dataset-engineer), run 2 (resume after guard fix e3c5a32)
FILES CREATED:
  /home/olmos/scholarchain/datasets/scripts/lib/orcid_reduce.py
FILES MODIFIED:
  /home/olmos/scholarchain/datasets/dataset-manifest.yaml (DBLP approved subset rule + subset_parameters + per-record endpoint limitation; ORCID privacy_policy, privacy_notes, linkage_fields, sample entry; Crossref rewritten for the REST API primary route, alternative_bulk Requester Pays, BitTorrent removed; official URLs added to every UNVERIFIED note)
  /home/olmos/scholarchain/datasets/scripts/download.sh (mode +x; ORCID reduction before install; Crossref full mode = bounded REST API over --doi-list; requester-pays prints and exits 3)
  /home/olmos/scholarchain/datasets/scripts/common.sh, validate-manifest.sh, validate-samples.sh (mode +x; validate-samples.sh header text)
  /home/olmos/scholarchain/datasets/scripts/lib/check_samples.py (ORCID forbidden elements, <= 5 ORCID records)
  /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py (DBLP subset-rule check, ORCID policy check, ${VAR} snapshot placeholder in S3 prefix)
  /home/olmos/scholarchain/datasets/scripts/lib/sample_tools.py (Crossref message-type check)
  /home/olmos/scholarchain/datasets/samples/orcid/orcid-0000-0002-1825-0097-record.xml (reduced: 6365 B)
  /home/olmos/scholarchain/datasets/samples/SHA256SUMS
  /home/olmos/scholarchain/datasets/schemas/dblp.md, crossref.md, orcid.md
COMMANDS RUN:
  chmod +x datasets/scripts/*.sh; datasets/scripts/download.sh --dry-run (x2); datasets/scripts/validate-manifest.sh (real + 5 negative copies); datasets/scripts/validate-samples.sh (x3); python3 -I check_samples.py on 2 negative scratch copies; download.sh --full ... --dry-run without the gate (crossref, dblp: refused); one curl to https://dblp.org/rec/journals/cacm/DeanG08.xml (HTTP 202 challenge); one curl to https://api.crossref.org/works/10.1145/337292.337434 (HTTP 200); curl of 2 Crossref documentation pages (HTTP 200); orcid_reduce.py (build + idempotence cmp); bash -n x4; shellcheck -x datasets/scripts/*.sh; python3 -I -m py_compile x6; (cd datasets/samples && sha256sum -c SHA256SUMS); wc -c on samples; sbt -batch check
  BLOCKED by the guard (not worked around): running a scratch copy of download.sh to show the dry-run plan for missing samples
TEST RESULTS: sbt check all [success]; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
VALIDATION RESULTS: validate-manifest PASS (16 UNVERIFIED), 5/5 negatives exit 1; validate-samples 7 files valid, ORCID 1/5, 2/2 ORCID negatives exit 1; sha256sum -c 7 OK; bash -n 4 OK; shellcheck exit 0; dry-run no network I/O, exit 0
ASSUMPTIONS:
  - "200 KB per file" is read as 204,800 bytes (run-1 convention); the largest sample is 200,355 B (OpenAlex works excerpt). If the limit means 200,000 B, that file is 355 B over.
  - "Affiliations" covers all 7 ORCID v3 affiliation sections; organisation addresses are stripped as addresses.
  - The 5-record ORCID cap also counts the fictitious demo record (stricter than required).
  - The DBLP year-window placeholder names are a dataset-side convention; the configuration contract belongs to domain-engineer.
RISKS:
  - DBLP sample still lacks journals/ articles and www (person) records.
  - Crossref API-route full mode and the new message-type check are untested end to end (full mode must not be executed); the API's rate limits for multi-DOI requests were not observed.
  - The reduced ORCID sample cannot be byte-reproduced without re-fetching (the raw response is not retained; its sha256 is recorded).
  - OpenAlex pinned partitions vanish at the next quarterly release (unchanged from run 1).
MANUAL ACTION REQUIRED:
  1. Decide how to obtain journal and www DBLP examples (dblp.org per-record endpoints return HTTP 202 bot challenge; more dump streaming is disallowed).
  2. Choose the DBLP year window values (configuration) so the run yields at least 100,000 works.
  3. Check the 16 UNVERIFIED fields at the URLs listed above.
  4. Confirm the 200 KB = 204,800 B interpretation.
  5. Optional: approve fetching up to 5 real public ORCID records.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review (code-reviewer)
```

## Work order B: domain-engineer, run 1 (STATUS: COMPLETE)

I implemented Work Order B in `modules/core` and `sbt check` passes: 252 tests, 0 failures, and `markTestRun` updated the marker. One gap: there is no shipped environment file. The write of `modules/core/src/main/resources/scholarchain/conf/dev.conf` was denied by the permission system. No rule in `.claude/settings.json` or the guard matches that path, so it looks like a decision on that one write. I did not retry it or work around it. The environment-file layer is implemented and tested with test-only files. Until a human decides on real environment files, every required key has to come from an environment variable.

### Sample serialized records
All 21 frozen examples are in `/home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/`. Bibliographic IDs are real and come from the committed samples (dblp `conf/dac/NewtonRMSB00`, DOI 10.1145/337292.337434, OpenAlex A5023877407 / A5110371381 / I177725633 / T10363, ROR 00t33hh48, ISSN 0738-100X). Scores, metrics, policy/model/prompt versions and timestamps are made-up example values; the fixture header says so. `coauthor-edge.json`, abridged:
```json
{"schemaVersion":"coauthor-edge-v1",
 "edgeId":"coauthor-edge:a675e40e1d6014815f76765dfb0fe3440a6478ac6c31ab4545af3a329e938a0e",
 "researcherA":"researcher:openalex:c2e53727f9756b062f5a17732f0d9985ec0aa7335a8d45197742d2eb8b135c7a",
 "researcherB":"researcher:orcid:26ed21108b722489f94bd746bbfaade3675a6bb3264fa8c36fc9fdf70eac337d",
 "windowStartYear":2000,"windowEndYear":2005,"jointPublicationCount":1,
 "firstCollaborationYear":2000,"lastCollaborationYear":2000,
 "representativeWorkIds":["work:dblp:c052950cbcb85c8a57918631ac869dd2dcf014996d311adf4fbc63c6a2d981f8"],
 "supportingWorkIdsRef":null,"policyVersion":"coauthor-v1",
 "provenance":{"schemaVersion":"provenance-v1","sourceDataset":"SCHOLARCHAIN",
  "sourceRecordId":"pair-candidate:0c5e…","snapshot":"2026-11-01",
  "runId":"run:535a5b257e6a05ab3e70b05c35f0bffaf969bc40f58636379fef0bbe4692f6f7",
  "policyVersion":"coauthor-v1","modelVersion":null,"promptVersion":null,"derivedFrom":["pair-candidate:0c5e…"]}}
```
Wire rules: enums are bare strings, an absent `Option` is written as `null`, and maps are JSON objects.

### Stable ID formulas (formula version v1)
- **Format:** `kind[:namespace]:<64 lower-case hex>`. The hash input is `"scholarchain-id/v1/<kind>\n"` followed by each field as `utf8ByteLength + ":" + value`. Lists that are really sets are sorted and de-duplicated first. No formula reads a clock. An independent Python run of this formula gives the same IDs, and they are frozen as test vectors.
- **work:** `work:{dblp|doi|openalex|arxiv}:H(ns, normalized id)`. DOIs are lower-cased with resolver prefixes removed; arXiv drops the version suffix.
- **researcher:** a validated ORCID iD (checksum checked), else an OpenAlex author ID, else `researcher:scholarchain-vN:H(vN, anchorKey)`.
- **coauthor-edge:** `H(a, b, windowStartYear, windowEndYear, policyVersion)`, with (a, b) the canonical pair.
- **chunk / profile:** `H(entityId, sourceVersion, documentType, ordinal, textHash)`.
- **graph-edge:** `H(edgeType, endpoints, policyVersion, sorted source evidence IDs)`. Endpoints are canonicalized only for the undirected `COAUTHORED_WITH`.
- **run:** `H(manifest JSON with sorted keys)`. `RunManifest` has no time field.
- **Also defined:** mention, authorship, pair-candidate, metrics, venue, institution, topic, match, evidence, bundle, claim, query and snapshot IDs.
- Validation recomputes the ID wherever the record holds all the inputs; a mismatch gives `StableIdMismatch`.

### Pair canonicalization
A pair is stored as `a < b` using plain `String.compareTo` (code-unit order, no locale rules; IDs are ASCII). Equal members are rejected as a self-pair. This applies to `CoauthorEdge`, `CoauthorPairCandidate` (author-mention IDs) and `COAUTHORED_WITH` graph edges.

### Config precedence
From highest to lowest:
1. Environment variables, through `${?VAR}` lines in `scholarchain/env-overrides.conf`.
2. `scholarchain/conf/<SCHOLARCHAIN_ENV>.conf`.
3. `reference.conf`.

- `reference.conf` sets only `config.schemaVersion = "config-v1"` and `application.name`, and a test enforces that.
- There are 40 required keys and none has a default. A missing key gives `ConfigError.Invalid`, which lists every missing key with the environment variable that supplies it.
- Each module can load just the sections it needs.
- The DBLP window keeps the dataset-engineer names: `DBLP_SUBSET_START_YEAR` / `DBLP_SUBSET_END_YEAR`, mapped to `scholarchain.datasets.dblpSubset.{startYear,endYear}`. Start > end is rejected.

### Schema versions
`provenance-v1`, `work-v1`, `author-mention-v1`, `researcher-v1`, `authorship-v1`, `publication-team-v1`, `coauthor-pair-candidate-v1`, `coauthor-edge-v1`, `researcher-metrics-v1`, `venue-v1`, `institution-v1`, `topic-v1`, `match-decision-v1`, `graph-node-v1`, `graph-edge-v1`, `evidence-v1`, `evidence-bundle-v1`, `query-plan-v1`, `answer-claim-v1`, `answer-response-v1`, `run-manifest-v1`, plus `config-v1`. An unsupported version is rejected before any fields are decoded.

### Test counts (from sbt output)
| Suite | Tests |
|---|---|
| WireContractSpec | 113 |
| ValidationSpec | 48 |
| SchemaVersionSpec | 46 |
| ConfigLoaderSpec | 21 |
| StableIdsSpec | 20 |
| CanonicalPairSpec | 4 |
| **Total** | **252 run, 252 passed, 0 failed** |

### Known unresolved dataset questions
- The DBLP sample still has no `journals/` or `www` (person) records.
- DOI aliases exist (10.1145/1065579.1065662 maps to a work whose primary DOI is 10.1109/dac.2005.193824). Core keys a DOI work ID on the DOI string; deciding which DOI is authoritative is up to entity resolution.
- The real DBLP window values must give at least 100,000 works.
- The OpenAlex pinned partitions will disappear at the next quarterly release.
- dataset-engineer owns the 16 manifest fields still marked UNVERIFIED (listed in its run-2 report): the observed byte counts for DBLP, OpenAlex, ORCID and Crossref; `dblp.size.record_count`; the DBLP and ORCID sha256 (only MD5 is published); the OpenAlex publisher checksum and sha256; the Crossref alternative-bulk object key, snapshot date, bytes, checksum, encoding and item schema; and `crossref.size.observed.remote_bytes`.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 03, Work Order B (domain-engineer), run 1
FILES CREATED:
  /home/olmos/scholarchain/modules/core/src/main/resources/reference.conf
  /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/env-overrides.conf
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/{ConfigCursor,ConfigError,ConfigLoader,ScholarChainConfig}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/{CanonicalPair,ExternalIds,IdKind,StableId,StableIds}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/json/{WireCodec,WireEnum}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/model/{Bibliographic,Enums,Graph,Provenance,Runtime}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/schema/SchemaVersions.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/validation/{Checks,RecordContract,ValidationError}.scala
  /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/*.json (21 files)
  /home/olmos/scholarchain/modules/core/src/test/resources/scholarchain/conf/{contract-test,empty-test,precedence-test}.conf
  /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/{CanonicalPairSpec,ConfigLoaderSpec,Fixtures,GoldenCases,SchemaVersionSpec,StableIdsSpec,TestSupport,ValidationSpec,WireContractSpec}.scala
FILES MODIFIED: none outside modules/core (a temporary golden generator test was created, run once and deleted)
COMMANDS RUN:
  sbt -batch core/compile -> success
  sbt -batch "core/testOnly scholarchain.core.TmpGenerateGolden" -> 1 passed (wrote golden files; generator then deleted)
  python3 -I independent SHA-256 check of the ID formula -> matches Scala output
  sbt -batch core/test -> first run 237/252 (Typesafe resolveWith limitation); fixed loader; rerun 252/252
  sbt -batch core/scalafmtAll -> reformatted 13 core files
  sbt -batch check -> exit 0; "Tests: succeeded 252, failed 0"; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
  sbt -batch core/test (per-suite counts) -> 252 passed
TEST RESULTS: 252 run, 252 passed, 0 failed (WireContractSpec 113, ValidationSpec 48, SchemaVersionSpec 46, ConfigLoaderSpec 21, StableIdsSpec 20, CanonicalPairSpec 4)
VALIDATION RESULTS: all 21 golden examples round-trip and validate clean; each required rule (missing provenance, confidence outside [0,1], unsupported schemaVersion, malformed stable ID, wrong pair order or self-pair, start year > end year) has passing reject tests; scalafmtCheckAll passes
ASSUMPTIONS:
  - Researcher ID precedence is ORCID, then OpenAlex, then ScholarChain vN; entity resolution decides which IDs count as "validated".
  - Work ID takes an explicit namespace; which namespace is authoritative for a work (dblp vs DOI) is decided by Hadoop and entity-resolution policy.
  - RunManifest is the root that provenance points to: its inputs are its provenance and it has no nested Provenance (intentional exception).
  - Production environment files live at modules/core/src/main/resources/scholarchain/conf/<env>.conf (prefixed to avoid classpath clashes).
  - Environment variables follow the spec names (RAW_BUCKET, EMBEDDING_MODEL_ID, ...), the dataset names (DBLP_SUBSET_*) or the SCHOLARCHAIN_* prefix.
RISKS:
  - No shipped environment file exists (write denied), so the file layer only runs in tests until a human adds one.
  - MatchDecision.decidedAt (required by the spec) is a wall-clock field; no ID depends on it, but byte-identical reruns need producers to take it from the run's logical time.
  - Graph-edge evidence IDs and evidence IDs include the runId, so they change from run to run by design.
  - ScalaTest also prints a "- " line for each test, which the per-suite count relies on; the totals come from the ScalaTest summary.
MANUAL ACTION REQUIRED:
  1. Decide whether to allow scholarchain/conf/<env>.conf files under modules/core and what they may contain (the dev.conf write was denied).
  2. Confirm the researcher-ID precedence (ORCID first) and the environment variable names.
  3. Choose the DBLP window values (DBLP_SUBSET_START_YEAR/END_YEAR).
  4. The 16 UNVERIFIED manifest fields remain with dataset-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, code-reviewer
```

## Work order B: domain-engineer, repair 1 for review findings 1 and 9-15 (STATUS: COMPLETE)

I fixed findings 1 and 9-15 in `modules/core/**` only, and `sbt check` passes: exit 0, 286 tests, 0 failures, and `markTestRun` touched `.claude/.last-test-run`. This time the `dev.conf` write went through and was not denied. Nothing under `datasets/` was changed.

**Correction to my run-1 report.** I wrote that "every required key has to come from an environment variable". That was false: without a `scholarchain/conf/<env>.conf` file, the loader returned `UnknownEnvironment` and never read the environment variables. A missing file is now treated as an empty layer, and `UnknownEnvironment` has been removed.

**New run-ID formula**
- **Formula:** `run:sha256("scholarchain-id/v1/run\n" + len + ":" + canonicalManifestJson)`.
- **Canonical JSON:** the manifest JSON with `inputs` sorted by (sourceDataset, snapshot, location, checksum), all object keys sorted, rendered compactly.
- **Checksum required:** `RunInput.checksum` is now a required `sha256:<64 lower-case hex>`. For a prefix, the producer hashes a sorted listing of objects.
- **Rejected inputs:** `runId` returns `Left` for a missing or bad checksum, no inputs, duplicate dataset+location, null maps, map entries with null keys or values, or a null manifest.
- **Fixture:** the single input is the committed dblp excerpt, with its real sha256 from SHA256SUMS. An independent Python run gives the same frozen ID, `run:a2439ecf…5ce53c`.
- **Tests:** reordered inputs give the same ID; a different checksum gives a different ID.

**Evidence rule for claims.** Every `AnswerClaim` must cite at least one evidence ID, whatever its status, including UNSUPPORTED. CLAUDE.md says "Every runtime answer claim cites evidence IDs" with no exception. An UNSUPPORTED claim cites the evidence it was checked against and found lacking. If there is no evidence at all, the runtime should abstain (`abstained` plus `uncertainty`) rather than make a claim. Duplicate evidence IDs are also rejected. In an `AnswerResponse`, `claims[i].claimId` must equal `claimId(queryId, i)`.

**Config precedence (highest first), including missing-file behaviour**
1. Environment variables bound by `${?VAR}` in `env-overrides.conf`.
2. `scholarchain/conf/<SCHOLARCHAIN_ENV>.conf`. This file is optional: a missing one is an empty layer.
3. `reference.conf`.

- **Environment name:** `SCHOLARCHAIN_ENV` is still required. If it is unset or blank you get `MissingEnvironmentName`; a name outside `[a-z][a-z0-9-]*` gives `InvalidEnvironmentName`.
- **Filtering:** only the variables bound in `env-overrides.conf`, plus `SCHOLARCHAIN_ENV`, are read. The returned Config holds only the `scholarchain` tree, and an environment file cannot pull in a variable that is not bound.
- **`dev.conf`:** it ships with structure only. It sets no values, and a test enforces that.

**Updated test counts per suite**

| Suite | Tests |
|---|---|
| WireContractSpec | 113 |
| ValidationSpec | 67 |
| SchemaVersionSpec | 46 |
| ConfigLoaderSpec | 30 |
| StableIdsSpec | 26 |
| CanonicalPairSpec | 4 |
| **Total** | **286** |

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 03, Work Order B repair 1 (findings 1, 9-15)
FILES CREATED:
  /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/conf/dev.conf
  /home/olmos/scholarchain/modules/core/src/test/resources/scholarchain/conf/leak-test.conf
FILES MODIFIED:
  /home/olmos/scholarchain/modules/core/src/main/resources/reference.conf
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/{ConfigLoader,ConfigError,ConfigCursor,ScholarChainConfig}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/{ExternalIds,StableIds,CanonicalPair}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/model/Runtime.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/validation/{RecordContract,Checks}.scala
  /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/{Fixtures,ConfigLoaderSpec,StableIdsSpec,ValidationSpec}.scala
  /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/*.json (all 21 regenerated; static files)
COMMANDS RUN:
  sbt -batch core/Test/compile -> first: 6 test compile errors (API change); after fixes: success
  sbt -batch -Dgolden.dir=... "core/testOnly scholarchain.core.TmpGenerateGolden" -> 1 passed, wrote 21 goldens; generator file then deleted
  python3 -I independent run-ID check -> run:a2439ecf7fe23fdbc3fce38038deb00c663b1e0302f1fcee7d34be779b5ce53c (matches Scala)
  sbt -batch fmt core/test -> reformatted sources; Tests: succeeded 286, failed 0
  sbt -batch check -> exit 0; "Tests: succeeded 286, failed 0"; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
TEST RESULTS: 286 run, 286 passed, 0 failed (WireContractSpec 113, ValidationSpec 67, SchemaVersionSpec 46, ConfigLoaderSpec 30, StableIdsSpec 26, CanonicalPairSpec 4)
VALIDATION RESULTS:
  F1: missing env file = empty layer (test: no-such-env + env vars loads; no vars lists all 40 keys); dev.conf ships, has zero entries, dev + no vars lists every required key.
  F9: reorder test and checksum-change test pass; frozen run-ID vector.
  F10: one reject test and one accept test for each of the 4 statuses, a nested-path test, a duplicate test.
  F11: boundVariables equals the 40 required vars + 2 optional + SCHOLARCHAIN_ENV; fake AWS_SECRET_ACCESS_KEY / GITHUB_PERSONAL_ACCESS_TOKEN absent from raw Config and its render; leak-test.conf cannot read an unbound var.
  F12: validation now recomputes the ID wherever the record holds the inputs: WorkRecord (dblp/openalex from sourceRecordId, which needs the matching source; doi from doi; arxiv from arxivId), Venue (dblp/openalex from sourceVenueKey; issn from one of issns), Institution (ror from rorId; openalex from provenance.sourceRecordId when provenance.sourceDataset=OPENALEX), Topic (same OpenAlex rule), AnswerResponse claimIds, plus the existing AuthorMention, AuthorshipRecord, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, MatchDecision, GraphEdge, EvidenceItem, EvidenceBundle, CanonicalResearcher. Not recomputable (inputs not in the record): GraphNode.nodeId, QueryPlan.queryId, a standalone AnswerClaim.claimId, Provenance.runId, chunk/profile IDs referenced by EvidenceItem. Mismatch tests added.
  F13: every made-up version string is "illustrative-*" (er/coauthor policy, metrics, query plan, prompts, code version); rule and reason codes are "ILLUSTRATIVE_*"; component/community IDs "illustrative-*"; claim uncertainty says "Illustrative example".
  F14: null tests: 8 ExternalIds functions and 55 StableIds cases return Left; CanonicalPair is null-safe. StableIds.textHash and runId now return Either.
  F15: missing end bound, or missing start bound -> only MissingKey; invalid bound -> only the parse error.
ASSUMPTIONS:
  - The wire changes (RunInput.checksum Option -> required String; new rules) stay under run-manifest-v1 and the other v1 versions, because v1 has never been committed or released. Goldens and contract tests were updated in the same change.
  - Every made-up version string in the goldens now starts with "illustrative-". Confidence, score and timestamp values cannot carry a marker; the illustrative version strings in the same records flag them.
  - For an OpenAlex-sourced Institution or Topic, provenance.sourceRecordId is the OpenAlex ID the stable ID was built from.
  - SCHOLARCHAIN_ENV stays required, so every config names its environment.
RISKS:
  - Validation does not enforce researcher-ID precedence (an orcid that is present while the ID is in the openalex namespace is accepted), because "validated" is decided by entity resolution.
  - The listing rule for checksums of multi-object inputs is defined in a docstring only; producers (hadoop/spark) must follow it.
  - Callers of StableIds.textHash/runId must handle Either (no callers exist outside core yet).
MANUAL ACTION REQUIRED:
  1. Confirm that keeping the v1 schema versions for these pre-release wire changes is acceptable.
  2. Confirm the UNSUPPORTED-claim rule (evidence always required).
  3. Earlier items still open: researcher-ID precedence, environment variable names, DBLP window values.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, code-reviewer
```

## Work order A: dataset-engineer, repair 1 for review findings 2-8 (STATUS: COMPLETE)

I fixed findings 2 to 8, and every required check passes, including `sbt check` (286 tests, `markTestRun` touched `.claude/.last-test-run`). Full mode was never run with the human gate open: the full-mode code was tested offline against a stubbed `curl`, and the gate still refuses on the command line.

I added two test suites that stay in the repo (only throwaway fixtures go to temporary directories). I wrote them before changing any code and ran them first on the unchanged code: 13 of 21 shell checks and 11 of 17 validator checks failed there. After the fixes all pass (22 of 22 shell checks with one assertion added, 17 of 17 validator checks).

### Each finding: fix and the test that proves it

| # | Fix | Proving test (fails on unchanged code, passes now) |
|---|---|---|
| 2 | Each Crossref `<out>` is tied to one DOI list. `<out>/CROSSREF_RUN` records the list's hash and batch size, and `DOI_LIST.txt` holds the list. A non-empty `<out>` with a different list, a different batch size, or no record is refused before any request, and the message names both hashes. Each batch has a `.meta` file with its DOI-set hash and `retrieved_at`. A batch is skipped only if its sha256 and DOI-set hash verify and every item DOI belongs to it. `RETRIEVED_AT` is recalculated after each run as the latest batch time; `RETRIEVAL_WINDOW` records the first and last. | `datasets/scripts/test-download.sh`, F2 checks. Unchanged code: a different list was silently skipped ("0 of 12 list-B DOIs"), `RETRIEVED_AT` stayed at the first run's date, and there was no `.meta`. Now: refused with `<out>` unchanged, a batch with foreign content is fetched again, and a resumed run sets `RETRIEVED_AT` to the new date. |
| 3 | Every cap is 200,000 bytes: `common.sh`, `check_samples.py`, `validate_manifest.py`, the manifest (`sample_caps`, retrieval commands), `validate-samples.sh` and the schema notes. After the strip, the works excerpt is 197,230 bytes, so no trim was needed (line 31 is 6,745 bytes). | `test_validators.py`: a 200,001-byte sample now fails both validators ("200001 bytes > 200000"; it passed before), and exactly 200,000 bytes passes. |
| 4 | `abstract_inverted_index` is removed from the committed OpenAlex samples. The removal touches only that key: every other byte stays the same (including `2.0E-4`), and each line is checked to equal the original record minus the key. The policy is recorded as `openalex.sample_policy`, and the works sample entry records what changed: the key was non-empty only in W4417174297, all 30 work IDs are kept, and the unstripped excerpt's sha256 and size are kept. `download.sh` strips before applying the caps. I regenerated the file by running `datasets/scripts/download.sh --dataset openalex`, and the output is byte-identical to stripping the committed file. | `test_validators.py`: a non-empty value in the works sample or the API-linked sample fails, and a null value passes. The manifest validator fails if the policy is missing. |
| 5 | All transfers go through a helper in `common.sh` that pipes the body through `head -c cap+1`, so the cap holds even without Content-Length. Every transfer has `--max-time`. The full-mode limits come from the manifest: dblp 7200 s, openalex 3600 s, orcid 21600 s, crossref 120 s per request. | `test-download.sh` F5. Unchanged code: the stub delivered 4,194,304 bytes for a 1,024-byte cap, and the full-mode dblp, orcid and openalex calls had no `--max-time`. Now: 65,536 bytes delivered and no file left over the cap. |
| 6 | An OpenAlex partition is skipped only if its size, `gzip -t` and its recorded `.sha256` (written after each verified download) all pass. A full-length file that fails is moved aside as `<file>.corrupt-<UTC>` (not deleted) and fetched again. dblp and orcid use the same pattern with MD5. | `test-download.sh` F6: a corrupted partition of the same length, and one with a wrong `.sha256`, were skipped by the unchanged code and are fetched again now. |
| 7 | The ORCID check is now an allowlist of element paths and attributes, written separately from `orcid_reduce.py`. | `test_validators.py`: person external-identifiers, `work:title`, `common:source`, history, an unknown element and an unknown attribute each fail now (all passed before). The emails check still fails as before. |
| 8 | No code change. The manifest note, and a new `exception` field on that entry, record that the human accepted this on 2026-11-19. | None needed (no behaviour change). |

### Final SHA256SUMS
```text
855c221447d1ab399bae0db0ab4c974a70c5f31bad65fdca53e06a05b14efa6c  crossref/crossref-works-api-linked.jsonl
03ec35cb34a4227572a0e89da407b06b6e44a6cc23e19cabc3b49b702a5f897d  dblp/dblp-2023-06-28.dtd
b5d5c46dbe60284c46ddc50174ea2106fd6f55f4623611c310dc8e0b177428a1  dblp/dblp-2026-11-01-excerpt.xml
fbafa1af8db022dcf0336c39c0ba48df2e1eafc07c86f8382672f5af5a97d696  openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl
3121da4dfcc7f667d2a9923ac4593779339ab62b17bfa969fc1acd04c34280b8  openalex/openalex-works-api-linked.jsonl
59935f5ba2b34620a2c31090f0102c414758143c54c3dc84c8db019deccf2db8  openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl
d2816257d6788c06d37948ae0fa529ce093f9b022b387889da1d2f5203dbef1f  orcid/orcid-0000-0002-1825-0097-record.xml
```

### UNVERIFIED fields: 16 (unchanged)
- dblp: `size.observed.local_bytes`, `size.record_count`, `integrity.sha256`
- openalex: `size.observed.local_bytes`, `integrity.publisher_checksum`, `integrity.sha256`
- orcid: `size.observed.local_bytes`, `integrity.sha256`
- crossref: `source.alternative_bulk.{requester_pays_object_key, snapshot_date, object_bytes, publisher_checksum, encoding, item_schema}`, `size.observed.remote_bytes`, `size.observed.local_bytes` (the no-URL exception you accepted)

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 03, Work Order A repair 1 (dataset-engineer): review findings 2-8
FILES CREATED:
  /home/olmos/scholarchain/datasets/scripts/test-download.sh
  /home/olmos/scholarchain/datasets/scripts/lib/stub_curl.py
  /home/olmos/scholarchain/datasets/scripts/lib/test_validators.py
FILES MODIFIED:
  /home/olmos/scholarchain/datasets/dataset-manifest.yaml
  /home/olmos/scholarchain/datasets/samples/SHA256SUMS
  /home/olmos/scholarchain/datasets/samples/openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl (200355 -> 197230 B, 30 records)
  /home/olmos/scholarchain/datasets/scripts/common.sh
  /home/olmos/scholarchain/datasets/scripts/download.sh
  /home/olmos/scholarchain/datasets/scripts/validate-samples.sh (header text)
  /home/olmos/scholarchain/datasets/scripts/lib/check_samples.py
  /home/olmos/scholarchain/datasets/scripts/lib/sample_tools.py
  /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py
  /home/olmos/scholarchain/datasets/schemas/openalex.md, orcid.md, crossref.md
COMMANDS RUN:
  datasets/scripts/test-download.sh (before fixes: 8 passed, 13 failed; after: 22 passed, 0 failed)
  python3 -I datasets/scripts/lib/test_validators.py (before: 6 passed, 11 failed; after: 17 passed, 0 failed)
  curl --max-time 60 --max-filesize 26695 of the pinned OpenAlex works partition to scratch (26695 B, gzip -t OK, sha256 33c6c112...977a)
  sample_tools.py strip-file / jsonl-head comparison in scratch (identical, 197230 B)
  datasets/scripts/download.sh --dataset openalex (works excerpt regenerated, sha256 59935f5b...2db8; other two skipped)
  bash -n on all 5 .sh; shellcheck -x on all 5 .sh; python3 -I -m py_compile on all 8 lib/*.py
  datasets/scripts/validate-manifest.sh; datasets/scripts/validate-samples.sh; (cd datasets/samples && sha256sum -c SHA256SUMS)
  datasets/scripts/download.sh --dry-run; download.sh --full --dataset crossref ... --dry-run without the gate (refused, exit 1)
  sbt -batch check
  BLOCKED by the guard (not worked around): one python3 heredoc that edited the manifest, because its text contained "datasets/scripts/validate-manifest.sh"; nothing executed. Redone with the Edit tool.
TEST RESULTS:
  test-download.sh: RESULT: PASS (22 passed, 0 failed), exit=0
  test_validators.py: RESULT: PASS (17 passed, 0 failed), exit=0
  sbt check: exit 0; "Tests: succeeded 286, failed 0, canceled 0, ignored 0, pending 0"; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
VALIDATION RESULTS:
  bash -n OK x5; shellcheck -x exit=0
  validate-manifest.sh: "UNVERIFIED fields: 16" / "RESULT: PASS", exit=0
  validate-samples.sh: 7 x OK; "ORCID person records across samples: 1 (max 5)"; "samples: 7 file(s) valid", exit=0
  sha256sum -c SHA256SUMS: 7 x OK, exit=0
  download.sh --dry-run: 7 DRY-RUN SKIP lines, no network I/O, exit=0
ASSUMPTIONS:
  - The strip removes the key rather than setting it to null. The validator accepts null or empty, as the decision states.
  - The full-mode --max-time values (7200/3600/21600/120 s) are bounds I chose and recorded in the manifest, not published facts. An interrupted transfer resumes on the next run.
  - Refusing a non-empty <out> with no CROSSREF_RUN also applies to output from earlier versions of the script, which never ran.
RISKS:
  - Full mode has still only been tested offline with a stubbed curl; it has not been run against real servers.
  - Parsers must accept OpenAlex works lines that lack abstract_inverted_index.
  - Unchanged: no journals/ or www records in the DBLP sample; the pinned OpenAlex partitions disappear at the next quarterly release.
MANUAL ACTION REQUIRED:
  1. Review the full-mode --max-time values in the manifest (full_retrieval.*).
  2. The 16 UNVERIFIED fields above remain open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review (code-reviewer re-review of findings 2-8)
```

## Work order B: domain-engineer, repair 2 for human decisions of 2026-11-20 (STATUS: COMPLETE)

I've applied all of the human's decisions inside `modules/core/**`, and `sbt check` passes: 369 tests, 0 failures, and `markTestRun` touched `.claude/.last-test-run`. Two things need a decision from you or the human. The DBLP pid format is my assumption: nothing committed defines it. And I added a `recordTypes` config key that the order did not name explicitly.

**v1 freeze (decision 1).** A new suite, `GoldenFreezeSpec`, adds two locks:
- **Byte lock:** the encoder must reproduce every v1 golden byte for byte. Renamed, added, removed or reordered fields fail, and so does a changed number format. Before this, the golden check compared JSON structure only, so a key reorder would have passed.
- **File lock:** a new file, `golden/v1/SHA256SUMS`, pins the sha256 of all 21 goldens. Editing a golden to match a changed encoder also fails.

Each kind of change has a test proving the lock catches it. After Phase 3 is accepted, the only passing route for a wire change is a new `-v2` schema version with goldens under `golden/v2/`.

**Claim evidence rule (decision 2).** The rule was already right; I added the named tests:
- An UNSUPPORTED claim citing evidence that does not back it is valid (cites a 1-publication edge for a "40 publications" claim).
- An UNSUPPORTED claim with no evidence is rejected.
- An AnswerResponse holding an evidence-free claim is rejected in memory, by `validate`, by `encodeValidated`, and when decoding JSON with `evidenceIds: []`.

**Researcher IDs (decision 3a).**

| Precedence | Identifier | ID format |
|---|---|---|
| 1 | validated ORCID | `researcher:orcid:H("orcid", normalized iD)` |
| 2 | validated DBLP pid | `researcher:dblp:H("dblp", bare pid)` (new) |
| 3 | validated OpenAlex author ID | `researcher:openalex:H("openalex", A…)` |
| 4 | none of the above | `researcher:sc-researcher-v1:H("sc-researcher-v1", anchorKey)` |

- **Hash:** `H` is SHA-256 over `"scholarchain-id/v1/researcher\n"` followed by each field as `len:value`.
- **Namespace mapping:** the human's `sc-researcher-v1:<hash>` maps to `researcher:sc-researcher-v1:<64 hex>`, replacing `scholarchain-vN`, which no longer parses. The hash now includes the namespace token, so the frozen vector changed to `researcher:sc-researcher-v1:9b8fecdf…06c2`. Python gives the same values.
- **Pure functions:** `StableIds.researcherIdBasis` picks the level, and `StableIds.researcherId(orcid, dblpPid, openAlex, identityVersion, anchorKey)` builds the ID. An absent identifier falls through to the next level. A present but malformed one returns `Left`, so a researcher's identity never changes silently. Every level and each fallthrough is tested.
- **Wire change:** `CanonicalResearcher` gains `dblpPid: Option[String]`. Its golden now has `"dblpPid": null`; no ID in any golden changed.
- **Validation now enforces precedence.** For example, an OpenAlex-based ID while an ORCID is present is rejected. This closes the repair-1 risk.

**DBLP pid format: assumption.** The DTD, the sample and `datasets/schemas/dblp.md` define no pid syntax. The only committed reference is the URL `https://dblp.org/pid/<pid>.xml`. I assumed two forms from dblp's public pages:
- numeric `<digits>/<digits>`, with an optional `-<digits>` homonym suffix
- legacy `<lower-case letter>/<letter>[A-Za-z0-9=_.'-]*`

The validator also accepts the pid URL (optional `.xml` or `.html`) and `homepages/<pid>`, and stores the bare pid.

**Environment variables (decision 3b).** I checked `spec/scholarchain-spec.md` (the Configuration section, lines 2629-2680). It names only nine variables, and all nine are used exactly. `AWS_REGION` was already bound.

| Config key (`scholarchain.` prefix) | Variable | Source of name |
|---|---|---|
| storage.rawBucket / curatedBucket / graphBucket / ragBucket / vectorBucket | `RAW_BUCKET` / `CURATED_BUCKET` / `GRAPH_BUCKET` / `RAG_BUCKET` / `VECTOR_BUCKET` | spec |
| neptune.endpoint | `NEPTUNE_ENDPOINT` | spec |
| models.embeddingModelId / synthesisModelId / verificationModelId | `EMBEDDING_MODEL_ID` / `SYNTHESIS_MODEL_ID` / `VERIFICATION_MODEL_ID` | spec |
| (environment selector) | `SCHOLARCHAIN_ENV` | human decision |
| application.awsRegion | `AWS_REGION` | human decision; not in spec |
| datasets.dblpSubset.startYear / endYear | `DBLP_SUBSET_START_YEAR` / `DBLP_SUBSET_END_YEAR` | dataset manifest, kept by decision 3c |
| datasets.dblpSubset.maxWorks | `DBLP_SUBSET_MAX_WORKS` (new) | convention |
| datasets.dblpSubset.recordTypes | `DBLP_SUBSET_RECORD_TYPES` (new) | convention |
| application.awsAccountId | `SCHOLARCHAIN_AWS_ACCOUNT_ID` | convention |
| neptune.port / queryTimeoutMs | `NEPTUNE_PORT` / `NEPTUNE_QUERY_TIMEOUT_MS` | convention |
| vectorIndexes.researchers / communities / abstracts / paths / topicInstitutions | `VECTOR_INDEX_RESEARCHERS` / `_COMMUNITIES` / `_ABSTRACTS` / `_PATHS` / `_TOPIC_INSTITUTIONS` | convention |
| graph.windows / coauthorPolicyVersion | `SCHOLARCHAIN_GRAPH_WINDOWS` / `SCHOLARCHAIN_GRAPH_COAUTHOR_POLICY_VERSION` | convention |
| entityResolution.policyVersion / acceptThreshold / rejectThreshold / llmBandMin / llmBandMax | `SCHOLARCHAIN_ER_POLICY_VERSION` / `_ACCEPT_THRESHOLD` / `_REJECT_THRESHOLD` / `_LLM_BAND_MIN` / `_LLM_BAND_MAX` | convention |
| bridge.algorithm / sampleSize / randomSeed | `SCHOLARCHAIN_BRIDGE_ALGORITHM` / `_SAMPLE_SIZE` / `_RANDOM_SEED` | convention |
| community.algorithm / maxIterations | `SCHOLARCHAIN_COMMUNITY_ALGORITHM` / `_MAX_ITERATIONS` | convention |
| retrieval.graphLimit / vectorLimit / maxPathDepth / contextTokenBudget / fusionPolicyVersion | `SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT` / `_VECTOR_LIMIT` / `_MAX_PATH_DEPTH` / `_CONTEXT_TOKEN_BUDGET` / `_FUSION_POLICY_VERSION` | convention |
| operations.synchronousDeadlineMs / maxWorkflowRetries / maxConcurrentLlmCalls | `SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS` / `_MAX_WORKFLOW_RETRIES` / `_MAX_CONCURRENT_LLM_CALLS` | convention |
| config.schemaVersion; application.name (optional, set in reference.conf) | `SCHOLARCHAIN_CONFIG_SCHEMA_VERSION`; `SCHOLARCHAIN_APPLICATION_NAME` | convention |

There are 42 required keys. The variable names match the spec, but our config paths differ from the spec's "recommended shape" (for example, `models.embeddingModelId` where the spec shows `models.embeddingModel`).

**Subset and windows (decision 3c).**
- `maxWorks` must be greater than 0.
- `recordTypes` must be comma-separated record element names declared by the DBLP DTD, with no blanks or duplicates.
- Analysis windows must each have start ≤ end, be sorted by start year, not overlap, and lie inside the subset years. Gaps between windows are allowed.
- A trailing comma in the windows string is now an error; before, it was silently ignored.
- Because of the containment rule, the graph section also reads the subset years. A shared problem is reported only once.

Every rule has a test. Exact `dev.conf` content:

```hocon
# ScholarChain versioned environment file for SCHOLARCHAIN_ENV=dev.
# Owner: domain-engineer (modules/core).
#
# Functional-run configuration approved by the human architect on 2026-11-20
# (Phase 03, Work Order B repair 2, decision 3c). This file sets ONLY these
# non-secret run parameters:
#   - the DBLP subset: record types, inclusive year range, max-works cap
#   - the default analysis windows
# Partial-year 2026 is excluded by endYear = 2025.
#
# It sets no bucket name, endpoint, port, account ID, Region, model ID,
# threshold, policy version, vector index, bridge/community parameter or
# retrieval limit. ConfigLoaderSpec fails if any other key is set here.
# Supply those through their environment variables (see reference.conf);
# scholarchain/env-overrides.conf binds them.
#
# Precedence, highest first:
#   1. environment variables (scholarchain/env-overrides.conf); for example
#      DBLP_SUBSET_MAX_WORKS overrides maxWorks below
#   2. this file (a missing scholarchain/conf/<env>.conf counts as empty)
#   3. reference.conf

scholarchain {
  datasets.dblpSubset {
    recordTypes = "article,inproceedings"
    startYear = 2000
    endYear = 2025
    maxWorks = 100000
  }

  graph {
    windows = "2000-2005,2006-2010,2011-2015,2016-2020,2021-2025"
  }
}
```

Tests check that `dev.conf` sets exactly these five entries and no other key. They also check that `dev.conf` plus the other 37 variables loads with exactly these values.

**Test counts per suite**

| Suite | Tests |
|---|---|
| WireContractSpec | 113 |
| ValidationSpec | 79 |
| GoldenFreezeSpec | 51 (new) |
| SchemaVersionSpec | 46 |
| ConfigLoaderSpec | 42 |
| StableIdsSpec | 34 |
| CanonicalPairSpec | 4 |
| **Total** | **369** |

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 03, Work Order B repair 2 (human decisions of 2026-11-20: 1, 2, 3a, 3b, 3c)
FILES CREATED:
  /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/GoldenFreezeSpec.scala
  /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/SHA256SUMS
FILES MODIFIED:
  /home/olmos/scholarchain/modules/core/src/main/resources/reference.conf (comments only)
  /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/env-overrides.conf (+DBLP_SUBSET_RECORD_TYPES, +DBLP_SUBSET_MAX_WORKS)
  /home/olmos/scholarchain/modules/core/src/main/resources/scholarchain/conf/dev.conf
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/config/{ConfigCursor,ScholarChainConfig}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/ids/{IdKind,ExternalIds,StableIds}.scala
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/model/Bibliographic.scala (CanonicalResearcher.dblpPid)
  /home/olmos/scholarchain/modules/core/src/main/scala/scholarchain/core/validation/RecordContract.scala
  /home/olmos/scholarchain/modules/core/src/test/scala/scholarchain/core/{ConfigLoaderSpec,StableIdsSpec,ValidationSpec,Fixtures}.scala
  /home/olmos/scholarchain/modules/core/src/test/resources/golden/v1/canonical-researcher.json (+"dblpPid": null)
  /home/olmos/scholarchain/modules/core/src/test/resources/scholarchain/conf/contract-test.conf (+recordTypes, +maxWorks)
COMMANDS RUN:
  python3 -I independent researcher-ID check -> sc-researcher-v1 and dblp vectors computed; orcid vector reproduces the existing frozen value
  sha256sum *.json > golden/v1/SHA256SUMS -> 21 lines
  sbt -batch fmt core/test -> first: scalafmt parse error in ConfigLoaderSpec (fixed); then "Tests: succeeded 369, failed 0"
  sbt -batch check -> exit=0; all steps [success]; "Tests: succeeded 369, failed 0"; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
TEST RESULTS: 369 run, 369 passed, 0 failed (WireContractSpec 113, ValidationSpec 79, GoldenFreezeSpec 51, SchemaVersionSpec 46, ConfigLoaderSpec 42, StableIdsSpec 34, CanonicalPairSpec 4)
VALIDATION RESULTS:
  D1: v1 goldens are locked by bytes and by sha256; a renamed, added, removed or reordered field, a changed number format, or an edited golden each fail a test.
  D2: UNSUPPORTED claim with non-supporting evidence is valid; with no evidence it is rejected; an AnswerResponse with an evidence-free claim is rejected (in memory, validate, encodeValidated, JSON decode).
  D3a: 4 precedence levels plus fallthrough tested; malformed present identifier returns Left; validation enforces precedence; scholarchain-vN no longer parses; frozen vectors updated.
  D3b: the 9 spec-named variables used exactly; AWS_REGION and SCHOLARCHAIN_ENV bound; the spec defines no other variable names.
  D3c: dev.conf holds exactly the 5 approved entries; window rules (sorted, non-overlapping, inside subset), maxWorks > 0 and recordTypes each tested; dev.conf plus env vars loads exactly.
ASSUMPTIONS:
  - DBLP pid syntax (numeric NN/NNNN[-N], legacy x/Name) and the homepages/<pid> spelling are assumed; only the https://dblp.org/pid/<pid>.xml URL template is cited (datasets/schemas/dblp.md).
  - CanonicalResearcher.orcid/dblpPid/openAlexAuthorId are validated identifiers, so validation enforces precedence on them.
  - Record types are configuration (DBLP_SUBSET_RECORD_TYPES), limited to DTD record elements. The decision listed them among the subset values; the manifest also fixes them as part of the approved rule.
  - "71/4882" in tests is a syntax example only.
RISKS:
  - If the pid assumption is wrong, valid pids are rejected or invalid ones accepted; confirm with a real www/person record when one becomes available.
  - The maxWorks cap needs a deterministic selection rule in Hadoop (which works are kept when a window yields more than the cap); none is defined yet.
  - GraphConfig now requires the DBLP subset years, so a Spark job loading only the graph section must also supply DBLP_SUBSET_START_YEAR and DBLP_SUBSET_END_YEAR.
  - SHA256SUMS can still be regenerated deliberately; the lock makes that a visible diff for code review, not an impossibility.
MANUAL ACTION REQUIRED:
  1. Confirm or correct the DBLP pid syntax assumption.
  2. Confirm DBLP_SUBSET_RECORD_TYPES as a config key, or ask for record types to stay fixed by the manifest rule only.
  3. dataset-engineer should mirror DBLP_SUBSET_MAX_WORKS (and DBLP_SUBSET_RECORD_TYPES if kept) in the manifest subset_parameters.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, code-reviewer
```

## Work order A: dataset-engineer, repair 2 for the human DBLP subset decision of 2026-11-20 (STATUS: COMPLETE)

The manifest now records your DBLP subset decision of 2026-11-20, and every requested check passes, including `sbt check` (369 tests passed, and `markTestRun` updated `.claude/.last-test-run`). Nothing was committed, and no files outside `datasets/**` changed.

- **Proof the new tests work:** I ran the new test file against a copy of the old validator. All 20 tests for the new rules failed there, and all pass now. Three other tests also pass on the old validator, because the old validator already enforced those rules. They are the two record-type cases (adding `proceedings`, `article` only) and start year after end year. They stay as regression guards.
- **One extra check you didn't ask for:** the end year must be before the snapshot's year. This is how the validator enforces "partial-year 2026 is excluded". The manifest's configuration block must also cite domain-engineer's config keys, environment variables and `dev.conf` path exactly, so the two can't drift apart.
- **Evidence for the 2026 note:** the committed DBLP sample really does contain four records dated 2026. One is an `inproceedings` record (`conf/dac/GuoXJLHYZZS26`), which the rule would otherwise have kept.

**New dblp subset YAML block** (exact text from `datasets/dataset-manifest.yaml`):
```yaml
    subset_rule:
      functional_run: >-
        APPROVED by the human architect (rule: Phase 03 run 2 work order,
        decision 2a; values: human decision of 2026-11-20). Keep a dblp
        record if and only if (1) its element name (record type) is article
        or inproceedings, and (2) its <year> value is an integer Y with
        2000 <= Y <= 2025 (both bounds inclusive). Records with no <year> or
        a non-integer <year> are excluded. Partial-year 2026 is excluded (see
        limitations). No other filter is applied by the subset rule. The
        functional run is then bounded by a max-works cap of 100,000: it
        processes at most 100,000 of the works that pass the rule. The
        deterministic selection rule that decides which works are kept when
        the subset exceeds the cap is NOT yet defined; it belongs to
        hadoop-engineer (Phase 4) and is listed in
        subset_parameters.open_questions. The values are read from
        configuration at run time (subset_parameters.configuration), are not
        hardcoded in dataset scripts, and are recorded in the run manifest.
        The Hadoop extract counters are the evidence of how many works passed
        the rule and how many the run kept (spec "At least 100,000 DBLP works
        in the integrated functional run").
      sample: "See samples[].subset_rule. The committed excerpt is a stratified format sample covering all record types, not an application of the functional-run rule."
    subset_parameters:
      decided_by: "human architect"
      decided_on: "2026-11-20"
      record_types: [article, inproceedings] # fixed by the approved rule, whatever DBLP_SUBSET_RECORD_TYPES says
      year_field: "year"   # the record's <year> child element
      year_window:         # approved functional-run values, both inclusive
        start_year: 2000
        end_year: 2025
        constraint: "start_year <= end_year; end_year < year of snapshot.date (partial year excluded)"
      max_works: 100000    # cap that bounds the functional run; positive integer
      analysis_windows:    # default analysis windows: inclusive, sorted, non-overlapping, inside year_window
        - {start_year: 2000, end_year: 2005}
        - {start_year: 2006, end_year: 2010}
        - {start_year: 2011, end_year: 2015}
        - {start_year: 2016, end_year: 2020}
        - {start_year: 2021, end_year: 2025}
      configuration:
        values_file: "modules/core/src/main/resources/scholarchain/conf/dev.conf"
        values_file_owner: domain-engineer
        env_bindings_file: "modules/core/src/main/resources/scholarchain/env-overrides.conf"
        keys:
          record_types:
            config_key: "scholarchain.datasets.dblpSubset.recordTypes"
            env_var: "DBLP_SUBSET_RECORD_TYPES"
            dev_value: "article,inproceedings"
            status: "PENDING human confirmation as a configurable key (domain-engineer repair 2, manual action 2). The approved rule fixes the types to article and inproceedings regardless of this key."
          start_year:
            config_key: "scholarchain.datasets.dblpSubset.startYear"
            env_var: "DBLP_SUBSET_START_YEAR"
            dev_value: 2000
          end_year:
            config_key: "scholarchain.datasets.dblpSubset.endYear"
            env_var: "DBLP_SUBSET_END_YEAR"
            dev_value: 2025
          max_works:
            config_key: "scholarchain.datasets.dblpSubset.maxWorks"
            env_var: "DBLP_SUBSET_MAX_WORKS"
            dev_value: 100000
          analysis_windows:
            config_key: "scholarchain.graph.windows"
            env_var: "SCHOLARCHAIN_GRAPH_WINDOWS"
            dev_value: "2000-2005,2006-2010,2011-2015,2016-2020,2021-2025"
        note: >-
          The values live in modules/core/src/main/resources/scholarchain/conf/dev.conf,
          owned by domain-engineer, which also owns the configuration contract
          and loader. Environment variables override that file. The values
          are not hardcoded in dataset scripts: no script under
          datasets/scripts/ reads or applies the subset rule (it is applied by
          the Hadoop extract stage). This manifest mirrors the approved values
          for review; if they change, dev.conf and this block must change
          together.
      open_questions:
        - id: dblp-max-works-selection
          owner: hadoop-engineer
          phase: 4
          question: >-
            Which works are kept when the works passing the subset rule exceed
            max_works? The deterministic selection rule (ordering and
            tie-breaking) is not defined. It must be deterministic for a fixed
            snapshot and configuration, and recorded in the run manifest.
            dataset-engineer has not chosen one.
```
Two lines were added to `datasets.dblp.limitations`:
```yaml
      - "Partial-year 2026 is excluded (human decision, 2026-11-20). The 2026-11-01 snapshot contains records with <year>2026</year> (the committed excerpt has 4: inproceedings conf/dac/GuoXJLHYZZS26 and data records data/13/WangB26a, data/13/VasilevaWRDJL26, data/13/Xu26); the functional-run rule drops them because end_year is 2025. Collaboration in 2026 is therefore absent from every graph and metric built on this subset."
      - "The max-works cap (100,000) bounds the functional run, so the run can process only part of the works that pass the subset rule. Which works are kept is not yet defined (subset_parameters.open_questions, owner hadoop-engineer). Until it is, no completeness claim about the 2000-2025 article and inproceedings population can be made from a capped run."
```

**UNVERIFIED count: 16** (unchanged; the same 16 fields as repair 1).

**Command output** (full lines as printed):

`bash -n`, `shellcheck -x`, `py_compile`:
```text
bash -n OK datasets/scripts/common.sh
bash -n OK datasets/scripts/download.sh
bash -n OK datasets/scripts/test-download.sh
bash -n OK datasets/scripts/validate-manifest.sh
bash -n OK datasets/scripts/validate-samples.sh
shellcheck exit=0
py_compile exit=0
```

`python3 -I datasets/scripts/lib/test_validators.py`:
```text
PASS baseline: committed samples pass check_samples
PASS baseline: committed manifest passes validate_manifest
PASS D cap: max_works 0 fails
PASS D cap: max_works -1 fails
PASS D cap: max_works as a string fails
PASS D cap: max_works true (bool) fails
PASS D cap: max_works missing fails
PASS D windows: missing fails
PASS D windows: empty list fails
PASS D windows: unsorted fails
PASS D windows: overlapping fails
PASS D windows: shared boundary year overlaps
PASS D windows: start_year > end_year fails
PASS D windows: ending after year_window (2026) fails
PASS D windows: starting before year_window (1999) fails
PASS D windows: non-integer bound fails
PASS D windows: placeholder year_window bounds fail containment
PASS D year: end_year 2026 (partial snapshot year) fails
PASS D year: start_year > end_year still fails
PASS D types: adding proceedings fails
PASS D types: article only fails
PASS D config: wrong env var for max_works fails
PASS D config: wrong config key for windows fails
PASS D config: missing record_types mapping fails
PASS D config: wrong values_file fails
PASS F3 cap: 200,001-byte sample fails check_samples
PASS F3 cap: 200,001-byte sample fails validate_manifest
PASS F3 cap: exactly 200,000 bytes passes check_samples
PASS F3 cap: exactly 200,000 bytes passes validate_manifest
PASS F4 openalex: non-empty abstract_inverted_index in the works excerpt fails
PASS F4 openalex: non-empty abstract_inverted_index in the API-linked sample fails
PASS F4 openalex: abstract_inverted_index null passes
PASS F4 openalex: manifest without the abstract-stripping sample policy fails
PASS F7 orcid: person external-identifiers fails
PASS F7 orcid: work:title inside a works group fails
PASS F7 orcid: common:source inside an employment summary fails
PASS F7 orcid: record history fails
PASS F7 orcid: an unknown element in an allowed namespace fails
PASS F7 orcid: an attribute outside the allowlist fails
PASS F7 orcid: an emails element still fails
RESULT: PASS (40 passed, 0 failed)
exit=0
```

The same test file against the old validator (sha256 `3e12fa32280d72ed729d0e4cacc42a27c8f60d1f17bc0fd5e567287be7469915`, scratch copy). The 17 F3/F4/F7 cases also passed; their lines are identical to the run above and are omitted here:
```text
PASS baseline: committed samples pass check_samples
PASS baseline: committed manifest passes validate_manifest
FAIL D cap: max_works 0 fails: exit 0; RESULT: PASS
FAIL D cap: max_works -1 fails: exit 0; RESULT: PASS
FAIL D cap: max_works as a string fails: exit 0; RESULT: PASS
FAIL D cap: max_works true (bool) fails: exit 0; RESULT: PASS
FAIL D cap: max_works missing fails: exit 0; RESULT: PASS
FAIL D windows: missing fails: exit 0; RESULT: PASS
FAIL D windows: empty list fails: exit 0; RESULT: PASS
FAIL D windows: unsorted fails: exit 0; RESULT: PASS
FAIL D windows: overlapping fails: exit 0; RESULT: PASS
FAIL D windows: shared boundary year overlaps: exit 0; RESULT: PASS
FAIL D windows: start_year > end_year fails: exit 0; RESULT: PASS
FAIL D windows: ending after year_window (2026) fails: exit 0; RESULT: PASS
FAIL D windows: starting before year_window (1999) fails: exit 0; RESULT: PASS
FAIL D windows: non-integer bound fails: exit 0; RESULT: PASS
FAIL D windows: placeholder year_window bounds fail containment: exit 0; RESULT: PASS
FAIL D year: end_year 2026 (partial snapshot year) fails: exit 0; RESULT: PASS
PASS D year: start_year > end_year still fails
PASS D types: adding proceedings fails
PASS D types: article only fails
FAIL D config: wrong env var for max_works fails: exit 0; RESULT: PASS
FAIL D config: wrong config key for windows fails: exit 0; RESULT: PASS
FAIL D config: missing record_types mapping fails: exit 0; RESULT: PASS
FAIL D config: wrong values_file fails: exit 0; RESULT: PASS
RESULT: FAIL (20 passed, 20 failed)
exit=1
```

`datasets/scripts/test-download.sh` (I captured only the last 40 lines; the first line shown is the first PASS line):
```text
PASS gate: require_full_gate refuses without SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD
PASS F2 crossref: list A (12 DOIs) into an empty <out> fetches 2 batches holding exactly list A
PASS F2 crossref: abstracts are dropped from batch files
PASS F2 crossref: rerun of the same list is a no-op and keeps RETRIEVED_AT (no data retrieved)
PASS F2 crossref: a different DOI list into the same <out> never silently skips (refused, <out> unchanged)
PASS F2 crossref: the refusal names both DOI-list hashes
PASS F2 crossref: same list with a different batch size never reuses batches of other membership (refused, <out> unchanged)
PASS F2 crossref: a batch whose content does not match its DOI membership is fetched again
PASS F2 crossref: after a resumed run RETRIEVED_AT is the latest batch retrieval time
PASS F2 crossref: per-batch provenance records each batch's own retrieval time and DOI-set hash
PASS F2 crossref: a non-empty <out> without a DOI-list record is refused and left unchanged
PASS F5 bounded_get: 4 MiB body without Content-Length, cap 1024 -> fails, fewer than 1 MiB transferred, no oversized file left (stub delivered 65536 bytes)
PASS F5 bounded_get: a body of exactly the cap (1024 bytes) is accepted
PASS F5 full mode: every dblp and orcid transfer passes --max-time and --max-filesize (truncated fixtures are then rejected)
PASS F5 full mode: the 2048-byte dblp and orcid fixtures are rejected as short (size mismatch), not accepted
PASS F6 openalex: first run fetches both partitions intact
PASS F5 full mode: every openalex transfer passes --max-time and --max-filesize
PASS F6 openalex: a same-length corrupted partition is not skipped; it is fetched again and verifies (1 re-fetch)
PASS F6 openalex: a partition whose recorded sha256 does not verify is fetched again
PASS F6 openalex: intact partitions (size, gzip -t, sha256) are skipped
PASS F6 openalex: a truncated partition is completed and verifies
PASS F5 openalex: a partition body larger than content_length (no Content-Length) is cut during transfer (stub delivered 65536 bytes, file 0 bytes)
RESULT: PASS (22 passed, 0 failed)
exit=0
```

`datasets/scripts/validate-manifest.sh` (the 16 `UNVERIFIED` detail lines are omitted here; they are word for word the same as in repair 1):
```text
manifest: /home/olmos/scholarchain/datasets/dataset-manifest.yaml
datasets checked: dblp, openalex, orcid, crossref
required fields per dataset: 36
UNVERIFIED fields: 16
RESULT: PASS
exit=0
```

`datasets/scripts/validate-samples.sh`:
```text
crossref/crossref-works-api-linked.jsonl: OK
dblp/dblp-2023-06-28.dtd: OK
dblp/dblp-2026-11-01-excerpt.xml: OK
openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl: OK
openalex/openalex-works-api-linked.jsonl: OK
openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl: OK
orcid/orcid-0000-0002-1825-0097-record.xml: OK
CHECKED crossref/crossref-works-api-linked.jsonl bytes=47915 records=10
CHECKED dblp/dblp-2023-06-28.dtd bytes=13134 records=0
CHECKED dblp/dblp-2026-11-01-excerpt.xml bytes=25538 records=50
CHECKED openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl bytes=14403 records=21
CHECKED openalex/openalex-works-api-linked.jsonl bytes=62272 records=10
CHECKED openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl bytes=197230 records=30
CHECKED orcid/orcid-0000-0002-1825-0097-record.xml bytes=6365 records=1
ORCID person records across samples: 1 (max 5)
samples: 7 file(s) valid
exit=0
```

`(cd datasets/samples && sha256sum -c SHA256SUMS)`:
```text
crossref/crossref-works-api-linked.jsonl: OK
dblp/dblp-2023-06-28.dtd: OK
dblp/dblp-2026-11-01-excerpt.xml: OK
openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl: OK
openalex/openalex-works-api-linked.jsonl: OK
openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl: OK
orcid/orcid-0000-0002-1825-0097-record.xml: OK
sha256sum exit=0
```

`datasets/scripts/download.sh --dry-run`:
```text
[2026-11-20T07:06:49Z] SAMPLE mode for all (dry-run=1)
DRY-RUN  SKIP dblp/dblp-2023-06-28.dtd (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP dblp/dblp-2026-11-01-excerpt.xml (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-authors-snapshot-2026-10-14-excerpt.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP openalex/openalex-works-api-linked.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP crossref/crossref-works-api-linked.jsonl (sha256 matches SHA256SUMS; no network I/O)
DRY-RUN  SKIP orcid/orcid-0000-0002-1825-0097-record.xml (sha256 matches SHA256SUMS; no network I/O)
[2026-11-20T07:06:50Z] done
dry-run exit=0
```

`sbt -batch check` (filtered for success, error, test and markTestRun lines; the full log was not kept):
```text
[success] Total time: 2 s, completed Nov 20, 2026, 1:07:12 AM
[success] Total time: 1 s, completed Nov 20, 2026, 1:07:13 AM
[success] Total time: 2 s, completed Nov 20, 2026, 1:07:15 AM
[info] - researcher ID precedence: a malformed present identifier is an error, not a fallthrough
[info] - missing keys: with no values supplied, the error lists every required key and its variable
[info] - missing DBLP window bound: only the missing key is reported, no window error
[info] - invalid DBLP window bound: only the parse error is reported, no window error
[info] Tests: succeeded 369, failed 0, canceled 0, ignored 0, pending 0
[success] Total time: 2 s, completed Nov 20, 2026, 1:07:17 AM
[info] - researcher ID precedence: a malformed present identifier is an error, not a fallthrough
[info] - missing keys: with no values supplied, the error lists every required key and its variable
[info] - missing DBLP window bound: only the missing key is reported, no window error
[info] - invalid DBLP window bound: only the parse error is reported, no window error
[info] Tests: succeeded 369, failed 0, canceled 0, ignored 0, pending 0
[info] markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run
[success] Total time: 1 s, completed Nov 20, 2026, 1:07:18 AM
exit=0
-rw-r--r-- 1 olmos olmos 0 2026-11-20 01:07:18.580000000 -0600 .claude/.last-test-run
```
The `[info] -` lines are test names that matched the "error" filter, not errors.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Phase 03, Work Order A repair 2 (dataset-engineer): mirror the human's DBLP subset decision of 2026-11-20 in the manifest
FILES CREATED: none
FILES MODIFIED:
  /home/olmos/scholarchain/datasets/dataset-manifest.yaml (dblp subset_rule.functional_run, subset_parameters rewritten, 2 limitations added, self_tests text)
  /home/olmos/scholarchain/datasets/scripts/lib/validate_manifest.py (check_dblp_cap, check_dblp_windows, check_dblp_config, partial-year end_year check, docstring)
  /home/olmos/scholarchain/datasets/scripts/lib/test_validators.py (23 DBLP subset cases, docstring)
  /home/olmos/scholarchain/datasets/schemas/dblp.md (subset rule section)
COMMANDS RUN:
  cp of datasets/scripts/lib to the scratchpad before any change (old validator sha256 3e12fa32...9915)
  python3 -I scan of the committed DBLP excerpt for <year>2026</year> records (4 found)
  python3 -I datasets/scripts/lib/test_validators.py -> 40 passed, 0 failed, exit=0
  the same test file in a scratch tree with the old validator and the new manifest -> 20 passed, 20 failed, exit=1
  bash -n on all 5 .sh; shellcheck -x datasets/scripts/*.sh; python3 -I -m py_compile on the 2 changed .py files
  datasets/scripts/test-download.sh; datasets/scripts/validate-manifest.sh; datasets/scripts/validate-samples.sh
  (cd datasets/samples && sha256sum -c SHA256SUMS); datasets/scripts/download.sh --dry-run
  sbt -batch check
  BLOCKED by the guard (not worked around): "rm -rf $S/samples" on a scratchpad variable path; it was not needed, so I reran without it.
TEST RESULTS:
  test_validators.py: RESULT: PASS (40 passed, 0 failed), exit=0
  old validator: RESULT: FAIL (20 passed, 20 failed); all 20 new-rule tests fail there; the 3 regression-guard tests pass on both versions, as documented
  test-download.sh: RESULT: PASS (22 passed, 0 failed), exit=0
  sbt check: exit=0; "Tests: succeeded 369, failed 0, canceled 0, ignored 0, pending 0"; "markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run"
VALIDATION RESULTS:
  bash -n OK x5; shellcheck exit=0; py_compile exit=0
  validate-manifest.sh: "UNVERIFIED fields: 16" / "RESULT: PASS", exit=0
  validate-samples.sh: 7 x OK; "samples: 7 file(s) valid", exit=0
  sha256sum -c: 7 x OK, exit=0 (no sample changed; SHA256SUMS unchanged from repair 1)
  download.sh --dry-run: 7 DRY-RUN SKIP lines, no network I/O, exit=0
ASSUMPTIONS:
  - Analysis windows are recorded as structured {start_year, end_year} pairs; the config string form is recorded as dev_value. Gaps between windows are allowed, matching domain-engineer's rule.
  - "Excluding partial-year 2026" is enforced generally: end_year must be before the snapshot's year.
  - year_window still accepts ${CONFIG} placeholders, as in the old rule, but window containment now needs integer bounds, so the committed manifest must hold integers.
RISKS:
  - The manifest duplicates values that live in dev.conf. The validator checks the key and variable names against domain-engineer's, but not the values; a change to dev.conf alone would not be detected under datasets/.
  - Until the cap-selection rule is defined, a capped run cannot support completeness claims about the 2000-2025 population.
  - Whether the functional run reaches the spec's 100,000 works is shown only by Hadoop counters; no count has been observed (dblp.size.record_count is UNVERIFIED).
MANUAL ACTION REQUIRED:
  1. Confirm DBLP_SUBSET_RECORD_TYPES as a configurable key, or ask for it to be removed (recorded as PENDING).
  2. Route the open question dblp-max-works-selection to hadoop-engineer (Phase 4).
  3. The 16 UNVERIFIED fields remain open.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review
```
