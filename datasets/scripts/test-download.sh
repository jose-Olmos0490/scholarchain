#!/usr/bin/env bash
# datasets/scripts/test-download.sh
#
# Owner: dataset-engineer.
# Offline tests for download.sh: Crossref batch identity and provenance
# dates, byte caps enforced during transfer, --max-time on full-mode
# transfers, and the OpenAlex partition skip path.
#
# Nothing is downloaded. download.sh is sourced as a library, curl is
# replaced by an offline stub (lib/stub_curl.py), and a fail-closed curl is
# put first on PATH. SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD is never set: the
# full-mode functions are called directly with network I/O stubbed, so the
# human gate itself is not exercised except to check that it still refuses.
# All files are written under a temporary directory that is removed on exit.
#
# Usage: datasets/scripts/test-download.sh
# Exit status: 0 when every test passes, 1 otherwise.

set -euo pipefail

TEST_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-download-test.XXXXXX")"
STUB_LOG="${TEST_TMP}/curl.log"
URL_TABLE="${TEST_TMP}/urls.tsv"
: >"$STUB_LOG"
: >"$URL_TABLE"

# Fail-closed curl on PATH: any call that bypasses the stub function fails.
mkdir -p "${TEST_TMP}/bin"
printf '#!/bin/sh\necho "network disabled in test-download.sh" >&2\nexit 99\n' >"${TEST_TMP}/bin/curl"
chmod 0755 "${TEST_TMP}/bin/curl"
PATH="${TEST_TMP}/bin:${PATH}"

unset SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD CROSSREF_MAILTO OPENALEX_MAILTO
export TMPDIR="$TEST_TMP"
set --
# shellcheck source=datasets/scripts/download.sh
source "${TEST_HERE}/download.sh"
trap 'rm -rf -- "$TEST_TMP"' EXIT

# ------------------------------------------------------------------ stubs --

STUB_IGNORE_MAXFS=0
curl() { python3 -I "${TEST_HERE}/lib/stub_curl.py" "$STUB_LOG" "$URL_TABLE" "$STUB_IGNORE_MAXFS" "$@"; }
sleep() { :; }
FAKE_NOW=""
date() {
  if [[ -n "$FAKE_NOW" && "$*" == "-u +%Y-%m-%dT%H:%M:%SZ" ]]; then
    printf '%s\n' "$FAKE_NOW"
  else
    command date "$@"
  fi
}
eval "orig_M() $(declare -f M | tail -n +2)"
OVERRIDE_BATCH=""
M() {
  if [[ -n "$OVERRIDE_BATCH" && "$1" == "datasets.crossref.full_retrieval.api_batch_size" ]]; then
    printf '%s\n' "$OVERRIDE_BATCH"
  else
    orig_M "$1"
  fi
}

# ---------------------------------------------------------------- helpers --

PASSED=0
FAILED=0
pass() {
  PASSED=$((PASSED + 1))
  printf 'PASS %s\n' "$1"
}
fail() {
  FAILED=$((FAILED + 1))
  printf 'FAIL %s: %s\n' "$1" "$2"
}

# run_fn <logfile> <function> : run in a subshell with errexit active,
# return its status without stopping the harness.
RC=0
run_fn() {
  local logf="$1"
  shift
  set +e
  (
    set -e
    "$@"
  ) >"$logf" 2>&1
  RC=$?
  set -e
}

fresh_state() {
  DRY_RUN=0
  WORK_DIR="$(mktemp -d "${TEST_TMP}/work.XXXXXX")"
  MAX_DOIS=""
  CROSSREF_ROUTE="api"
  OPENALEX_ENTITIES=""
  STUB_IGNORE_MAXFS=0
  OVERRIDE_BATCH=""
  FAKE_NOW=""
}

# Number of logged stub calls whose URL contains $1.
calls_matching() {
  python3 -I -c '
import json, sys
n = 0
for line in open(sys.argv[1], encoding="utf-8"):
    r = json.loads(line)
    if "args" in r and any(sys.argv[2] in a for a in r["args"] if not a.startswith("-")):
        n += 1
print(n)' "$STUB_LOG" "$1"
}

# Largest "written" byte count logged for URLs containing $1.
max_written() {
  python3 -I -c '
import json, sys
m = 0
for line in open(sys.argv[1], encoding="utf-8"):
    r = json.loads(line)
    if "written" in r and sys.argv[2] in (r.get("url") or ""):
        m = max(m, r["written"])
print(m)' "$STUB_LOG" "$1"
}

# Every logged call (since line $1) carries --max-time and --max-filesize.
all_calls_bounded_since() {
  python3 -I -c '
import json, sys
bad = []
for i, line in enumerate(open(sys.argv[1], encoding="utf-8"), 1):
    if i <= int(sys.argv[2]):
        continue
    r = json.loads(line)
    if "args" in r and not ("--max-time" in r["args"] and "--max-filesize" in r["args"]):
        bad.append(" ".join(a for a in r["args"] if not a.startswith("-"))[-90:])
for b in bad:
    print("unbounded call:", b)
sys.exit(1 if bad else 0)' "$STUB_LOG" "$1"
}

log_lines() { wc -l <"$STUB_LOG"; }

# DOIs (lower-case, sorted) present in the crossref batch files of $1.
dois_in_out() {
  python3 -I -c '
import glob, json, os, sys
s = set()
for p in glob.glob(os.path.join(sys.argv[1], "crossref-api-batch-*.jsonl")):
    for line in open(p, encoding="utf-8"):
        if line.strip():
            s.add(json.loads(line)["DOI"].lower())
print("\n".join(sorted(s)))' "$1"
}

# Every batch file i holds exactly the i-th chunk (size $3) of list $2.
batches_match_chunks() {
  python3 -I -c '
import glob, json, os, sys
out, lst, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
dois = sorted({l.strip().lower() for l in open(lst) if l.strip()})
chunks = [dois[i:i + size] for i in range(0, len(dois), size)]
files = sorted(glob.glob(os.path.join(out, "crossref-api-batch-*.jsonl")))
if len(files) != len(chunks):
    sys.exit(f"{len(files)} batch files, expected {len(chunks)}")
for f, c in zip(files, chunks):
    got = [json.loads(l)["DOI"].lower() for l in open(f) if l.strip()]
    if got != c:
        sys.exit(f"{os.path.basename(f)} holds {got[:3]}..., expected {c[:3]}...")
' "$1" "$2" "$3"
}

tree_hash() { (cd "$1" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}'); }

make_doi_list() { # <file> <prefix> <n>
  local i
  : >"$1"
  for ((i = 0; i < $3; i++)); do printf '10.9999/%s.%03d\n' "$2" "$i" >>"$1"; done
}

# ------------------------------------------------------------------ tests --

test_gate_still_refuses() {
  local name="gate: require_full_gate refuses without SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD"
  run_fn "${TEST_TMP}/gate.log" require_full_gate
  if ((RC != 0)) && grep -q 'Refusing' "${TEST_TMP}/gate.log"; then pass "$name"; else fail "$name" "status ${RC}"; fi
}

# Finding 2: batch identity tied to DOI membership; provenance dates.
test_crossref_batches() {
  local out="${TEST_TMP}/cr-out" a="${TEST_TMP}/list-a.txt" b="${TEST_TMP}/list-b.txt" before n0 n1 h0 h1
  make_doi_list "$a" a 12
  make_doi_list "$b" b 12
  fresh_state
  OUT_DIR="$out"
  DOI_LIST="$a"
  FAKE_NOW="2026-01-01T00:00:00Z"
  n0="$(calls_matching api.crossref.org)"
  run_fn "${TEST_TMP}/cr1.log" full_crossref
  n1="$(calls_matching api.crossref.org)"
  local name="F2 crossref: list A (12 DOIs) into an empty <out> fetches 2 batches holding exactly list A"
  if ((RC == 0)) && [[ "$((n1 - n0))" == 2 && "$(dois_in_out "$out")" == "$(sort "$a")" ]]; then
    pass "$name"
  else fail "$name" "status ${RC}, $((n1 - n0)) request(s); $(tail -n 2 "${TEST_TMP}/cr1.log" | tr '\n' ' ')"; fi

  name="F2 crossref: abstracts are dropped from batch files"
  if ! grep -q '"abstract"' "$out"/crossref-api-batch-*.jsonl; then pass "$name"; else fail "$name" "abstract found"; fi

  fresh_state
  OUT_DIR="$out"
  DOI_LIST="$a"
  FAKE_NOW="2026-01-05T00:00:00Z"
  n0="$(calls_matching api.crossref.org)"
  run_fn "${TEST_TMP}/cr2.log" full_crossref
  n1="$(calls_matching api.crossref.org)"
  name="F2 crossref: rerun of the same list is a no-op and keeps RETRIEVED_AT (no data retrieved)"
  if ((RC == 0)) && [[ "$((n1 - n0))" == 0 && "$(cat "$out/RETRIEVED_AT")" == "2026-01-01T00:00:00Z" ]]; then
    pass "$name"
  else fail "$name" "status ${RC}, $((n1 - n0)) request(s), RETRIEVED_AT $(cat "$out/RETRIEVED_AT" 2>/dev/null)"; fi

  # The reviewer's failure input: same <out>, different DOI list.
  fresh_state
  OUT_DIR="$out"
  DOI_LIST="$b"
  FAKE_NOW="2026-01-06T00:00:00Z"
  h0="$(tree_hash "$out")"
  run_fn "${TEST_TMP}/cr3.log" full_crossref
  h1="$(tree_hash "$out")"
  name="F2 crossref: a different DOI list into the same <out> never silently skips (refused, <out> unchanged)"
  if ((RC != 0)) && [[ "$h0" == "$h1" ]]; then
    pass "$name"
  elif ((RC == 0)) && [[ "$(dois_in_out "$out")" == "$(sort "$b")" ]]; then
    pass "$name (list B fully fetched)"
  else
    fail "$name" "status ${RC}; <out> now holds $(dois_in_out "$out" | grep -c '/b\.') of 12 list-B DOIs and $(dois_in_out "$out" | grep -c '/a\.') list-A DOIs"
  fi
  name="F2 crossref: the refusal names both DOI-list hashes"
  if grep -q 'DOI list' "${TEST_TMP}/cr3.log" && grep -Eq '[0-9a-f]{64}.*[0-9a-f]{64}' "${TEST_TMP}/cr3.log"; then
    pass "$name"
  else fail "$name" "$(tail -n 1 "${TEST_TMP}/cr3.log")"; fi

  # Same list, different batch size: per-batch membership changes.
  fresh_state
  OUT_DIR="$out"
  DOI_LIST="$a"
  OVERRIDE_BATCH=5
  h0="$(tree_hash "$out")"
  run_fn "${TEST_TMP}/cr4.log" full_crossref
  h1="$(tree_hash "$out")"
  name="F2 crossref: same list with a different batch size never reuses batches of other membership"
  if ((RC != 0)) && [[ "$h0" == "$h1" ]]; then
    pass "$name (refused, <out> unchanged)"
  elif ((RC == 0)) && batches_match_chunks "$out" "$a" 5 >"${TEST_TMP}/cr4.chk" 2>&1; then
    pass "$name (all batches rebuilt)"
  else fail "$name" "status ${RC}; $(cat "${TEST_TMP}/cr4.chk" 2>/dev/null)"; fi

  # A batch whose content was written for other DOIs (sha256 rewritten to
  # match) must be fetched again.
  fresh_state
  OUT_DIR="$out"
  DOI_LIST="$a"
  printf '{"DOI":"10.9999/zz.001"}\n' >"$out/crossref-api-batch-000000.jsonl"
  (cd "$out" && sha256sum crossref-api-batch-000000.jsonl >crossref-api-batch-000000.jsonl.sha256)
  run_fn "${TEST_TMP}/cr5.log" full_crossref
  name="F2 crossref: a batch whose content does not match its DOI membership is fetched again"
  if ((RC == 0)) && batches_match_chunks "$out" "$a" 10 >"${TEST_TMP}/cr5.chk" 2>&1; then
    pass "$name"
  else fail "$name" "status ${RC}; $(cat "${TEST_TMP}/cr5.chk" 2>/dev/null)"; fi

  # Interrupted run resumed on a later date: RETRIEVED_AT is the latest
  # retrieval, and each batch records its own retrieval time.
  local out2="${TEST_TMP}/cr-out2"
  fresh_state
  OUT_DIR="$out2"
  DOI_LIST="$a"
  FAKE_NOW="2026-02-01T00:00:00Z"
  run_fn "${TEST_TMP}/cr6.log" full_crossref
  rm -f -- "$out2"/crossref-api-batch-000001.*
  fresh_state
  OUT_DIR="$out2"
  DOI_LIST="$a"
  FAKE_NOW="2026-02-03T00:00:00Z"
  run_fn "${TEST_TMP}/cr7.log" full_crossref
  name="F2 crossref: after a resumed run RETRIEVED_AT is the latest batch retrieval time"
  if ((RC == 0)) && [[ "$(cat "$out2/RETRIEVED_AT")" == "2026-02-03T00:00:00Z" ]]; then
    pass "$name"
  else fail "$name" "status ${RC}, RETRIEVED_AT=$(cat "$out2/RETRIEVED_AT" 2>/dev/null)"; fi
  name="F2 crossref: per-batch provenance records each batch's own retrieval time and DOI-set hash"
  if grep -qx 'retrieved_at=2026-02-01T00:00:00Z' "$out2/crossref-api-batch-000000.meta" 2>/dev/null &&
    grep -qx 'retrieved_at=2026-02-03T00:00:00Z' "$out2/crossref-api-batch-000001.meta" 2>/dev/null &&
    grep -Eqx 'doi_set_sha256=[0-9a-f]{64}' "$out2/crossref-api-batch-000001.meta" 2>/dev/null &&
    grep -qx 'first_retrieved_at=2026-02-01T00:00:00Z' "$out2/RETRIEVAL_WINDOW" 2>/dev/null; then
    pass "$name"
  else fail "$name" "missing or wrong .meta / RETRIEVAL_WINDOW"; fi

  # A non-empty <out> with no record of which DOI list it holds.
  local out3="${TEST_TMP}/cr-out3"
  mkdir -p "$out3"
  printf 'x\n' >"$out3/crossref-api-batch-000000.jsonl"
  fresh_state
  OUT_DIR="$out3"
  DOI_LIST="$a"
  h0="$(tree_hash "$out3")"
  run_fn "${TEST_TMP}/cr8.log" full_crossref
  h1="$(tree_hash "$out3")"
  name="F2 crossref: a non-empty <out> without a DOI-list record is refused and left unchanged"
  if ((RC != 0)) && [[ "$h0" == "$h1" ]]; then pass "$name"; else fail "$name" "status ${RC}"; fi
}

# Finding 5: byte caps hold during the transfer, even without Content-Length.
test_byte_caps() {
  local d="${TEST_TMP}/caps" url="https://example.invalid/big" name
  mkdir -p "$d"
  printf '%s\tGEN:4194304\n' "$url" >>"$URL_TABLE"
  printf '%s\tGEN:1024\n' "https://example.invalid/exact" >>"$URL_TABLE"
  fresh_state
  STUB_IGNORE_MAXFS=1
  run_fn "${TEST_TMP}/cap1.log" bounded_get "$url" "$d/big" 1024
  name="F5 bounded_get: 4 MiB body without Content-Length, cap 1024 -> fails, fewer than 1 MiB transferred, no oversized file left"
  local w size=0
  w="$(max_written example.invalid/big)"
  [[ -f "$d/big" ]] && size="$(wc -c <"$d/big")"
  if ((RC != 0 && w < 1048576 && size <= 1024)); then
    pass "$name (stub delivered ${w} bytes)"
  else fail "$name" "status ${RC}, stub delivered ${w} bytes, file ${size} bytes"; fi

  fresh_state
  run_fn "${TEST_TMP}/cap2.log" bounded_get "https://example.invalid/exact" "$d/exact" 1024
  name="F5 bounded_get: a body of exactly the cap (1024 bytes) is accepted"
  if ((RC == 0)) && [[ "$(wc -c <"$d/exact")" == 1024 ]]; then pass "$name"; else fail "$name" "status ${RC}"; fi
}

# Finding 5: every full-mode transfer carries --max-time and a byte cap.
test_full_mode_bounds() {
  local start name
  local dblp_url dblp_md5 orcid_url
  dblp_url="$(M datasets.dblp.source.download_url)"
  dblp_md5="$(M datasets.dblp.source.checksum_url)"
  orcid_url="$(M datasets.orcid.source.files.0.url)"
  printf 'd41d8cd98f00b204e9800998ecf8427e  x\n' >"${TEST_TMP}/fake.md5"
  {
    printf '%s\t%s\n' "$dblp_md5" "${TEST_TMP}/fake.md5"
    printf '%s\tGEN:2048\n' "$dblp_url"
    printf '%s\tGEN:2048\n' "$orcid_url"
  } >>"$URL_TABLE"

  start="$(log_lines)"
  fresh_state
  OUT_DIR="${TEST_TMP}/full-dblp"
  run_fn "${TEST_TMP}/fdblp.log" full_dblp
  fresh_state
  OUT_DIR="${TEST_TMP}/full-orcid"
  run_fn "${TEST_TMP}/forcid.log" full_orcid
  name="F5 full mode: every dblp and orcid transfer passes --max-time and --max-filesize (truncated fixtures are then rejected)"
  if all_calls_bounded_since "$start" >"${TEST_TMP}/fb.chk" 2>&1 && (($(calls_matching "$dblp_url") >= 1 && $(calls_matching "$orcid_url") >= 1)); then
    pass "$name"
  else fail "$name" "$(tr '\n' ' ' <"${TEST_TMP}/fb.chk")"; fi
  name="F5 full mode: the 2048-byte dblp and orcid fixtures are rejected as short (size mismatch), not accepted"
  if grep -q 'size mismatch' "${TEST_TMP}/fdblp.log" && grep -q 'size mismatch' "${TEST_TMP}/forcid.log"; then
    pass "$name"
  else fail "$name" "dblp: $(tail -n 1 "${TEST_TMP}/fdblp.log"); orcid: $(tail -n 1 "${TEST_TMP}/forcid.log")"; fi
}

# Findings 5 and 6: OpenAlex partitions.
test_openalex_partitions() {
  local date murl base fx out rel1 rel2 n0 n1 name start
  date="$(M datasets.openalex.snapshot.date)"
  murl="$(M datasets.openalex.source.snapshot_manifest_url)"
  base="$(M datasets.openalex.sampling.https_base)"
  fx="${TEST_TMP}/oa-fixtures"
  out="${TEST_TMP}/oa-out"
  mkdir -p "$fx"
  printf '{"id":"W1"}\n{"id":"W2"}\n' | gzip -n -9 >"$fx/p1.gz"
  seq 1 400 | sed 's/.*/{"id":"W&","n":&}/' | gzip -n -9 >"$fx/p2.gz"
  rel1="data/jsonl/works/updated_date=2026-01-01/part_0000.gz"
  rel2="data/jsonl/works/updated_date=2026-01-02/part_0000.gz"
  python3 -I -c '
import json, os, sys
d, r1, r2, p1, p2, out = sys.argv[1:7]
files = [{"url": "s3://openalex/" + r, "meta": {"content_length": os.path.getsize(p), "record_count": 0}}
         for r, p in ((r1, p1), (r2, p2))]
json.dump({"date": d, "entities": [{"entity": "works", "files": files}]}, open(out, "w"))
' "$date" "$rel1" "$rel2" "$fx/p1.gz" "$fx/p2.gz" "$fx/manifest.json"
  {
    printf '%s\t%s\n' "$murl" "$fx/manifest.json"
    printf '%s\t%s\n' "${base}${rel1}" "$fx/p1.gz"
    printf '%s\t%s\n' "${base}${rel2}" "$fx/p2.gz"
  } >>"$URL_TABLE"

  start="$(log_lines)"
  fresh_state
  OUT_DIR="$out"
  OPENALEX_ENTITIES="works"
  run_fn "${TEST_TMP}/oa1.log" full_openalex
  name="F6 openalex: first run fetches both partitions intact"
  if ((RC == 0)) && cmp -s "$fx/p1.gz" "$out/$rel1" && cmp -s "$fx/p2.gz" "$out/$rel2"; then
    pass "$name"
  else fail "$name" "status ${RC}; $(tail -n 2 "${TEST_TMP}/oa1.log" | tr '\n' ' ')"; fi
  name="F5 full mode: every openalex transfer passes --max-time and --max-filesize"
  if all_calls_bounded_since "$start" >"${TEST_TMP}/oab.chk" 2>&1; then pass "$name"; else fail "$name" "$(tr '\n' ' ' <"${TEST_TMP}/oab.chk")"; fi

  # The reviewer's failure input: same length, corrupted bytes.
  python3 -I -c '
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
mid = len(b) // 2
b[mid] ^= 0xFF
open(p, "wb").write(bytes(b))
' "$out/$rel2"
  fresh_state
  OUT_DIR="$out"
  OPENALEX_ENTITIES="works"
  n0="$(calls_matching "$rel2")"
  run_fn "${TEST_TMP}/oa2.log" full_openalex
  n1="$(calls_matching "$rel2")"
  name="F6 openalex: a same-length corrupted partition is not skipped; it is fetched again and verifies"
  if ((RC == 0)) && cmp -s "$fx/p2.gz" "$out/$rel2" && gzip -t "$out/$rel2" 2>/dev/null; then
    pass "$name ($((n1 - n0)) re-fetch)"
  else fail "$name" "status ${RC}; file still corrupted: $(cmp -s "$fx/p2.gz" "$out/$rel2" && echo no || echo yes)"; fi

  # Known checksum: a sidecar mismatch must not be skipped.
  printf '%064d  part_0000.gz\n' 0 >"$out/${rel1}.sha256"
  fresh_state
  OUT_DIR="$out"
  OPENALEX_ENTITIES="works"
  n0="$(calls_matching "$rel1")"
  run_fn "${TEST_TMP}/oa3.log" full_openalex
  n1="$(calls_matching "$rel1")"
  name="F6 openalex: a partition whose recorded sha256 does not verify is fetched again"
  if ((RC == 0)) && ((n1 - n0 == 1)) && cmp -s "$fx/p1.gz" "$out/$rel1" &&
    (cd "$(dirname "$out/$rel1")" && sha256sum --status -c "$(basename "$rel1").sha256"); then
    pass "$name"
  else fail "$name" "status ${RC}, $((n1 - n0)) fetch(es)"; fi

  # Intact partitions are skipped (idempotence).
  fresh_state
  OUT_DIR="$out"
  OPENALEX_ENTITIES="works"
  n0="$(calls_matching "part_0000.gz")"
  run_fn "${TEST_TMP}/oa4.log" full_openalex
  n1="$(calls_matching "part_0000.gz")"
  name="F6 openalex: intact partitions (size, gzip -t, sha256) are skipped"
  if ((RC == 0)) && ((n1 == n0)); then pass "$name"; else fail "$name" "status ${RC}, $((n1 - n0)) fetch(es)"; fi

  # A truncated partition resumes and verifies.
  truncate -s 100 "$out/$rel2"
  rm -f -- "$out/${rel2}.sha256"
  fresh_state
  OUT_DIR="$out"
  OPENALEX_ENTITIES="works"
  run_fn "${TEST_TMP}/oa5.log" full_openalex
  name="F6 openalex: a truncated partition is completed and verifies"
  if ((RC == 0)) && cmp -s "$fx/p2.gz" "$out/$rel2"; then pass "$name"; else fail "$name" "status ${RC}"; fi

  # Server sends more than content_length without Content-Length.
  local rel3="data/jsonl/works/updated_date=2026-01-03/part_0000.gz" out4="${TEST_TMP}/oa-out4"
  python3 -I -c '
import json, sys
d, r, out = sys.argv[1:4]
json.dump({"date": d, "entities": [{"entity": "works", "files": [{"url": "s3://openalex/" + r, "meta": {"content_length": 2048, "record_count": 0}}]}]}, open(out, "w"))
' "$date" "$rel3" "$fx/manifest-big.json"
  sed -i "\#^${murl}	#d" "$URL_TABLE"
  {
    printf '%s\t%s\n' "$murl" "$fx/manifest-big.json"
    printf '%s\tGEN:4194304\n' "${base}${rel3}"
  } >>"$URL_TABLE"
  fresh_state
  STUB_IGNORE_MAXFS=1
  OUT_DIR="$out4"
  OPENALEX_ENTITIES="works"
  run_fn "${TEST_TMP}/oa6.log" full_openalex
  local w size=0
  w="$(max_written "$rel3")"
  [[ -f "$out4/$rel3" ]] && size="$(wc -c <"$out4/$rel3")"
  name="F5 openalex: a partition body larger than content_length (no Content-Length) is cut during transfer"
  if ((RC != 0 && w < 1048576 && size <= 2048)); then
    pass "$name (stub delivered ${w} bytes, file ${size} bytes)"
  else fail "$name" "status ${RC}, stub delivered ${w} bytes, file ${size} bytes"; fi
}

test_gate_still_refuses
test_crossref_batches
test_byte_caps
test_full_mode_bounds
test_openalex_partitions

printf 'RESULT: %s (%d passed, %d failed)\n' "$( ((FAILED == 0)) && echo PASS || echo FAIL)" "$PASSED" "$FAILED"
((FAILED == 0))
