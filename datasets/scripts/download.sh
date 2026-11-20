#!/usr/bin/env bash
# datasets/scripts/download.sh
#
# Owner: dataset-engineer.
#
# Bounded, idempotent retrieval for the ScholarChain datasets. All source URLs
# and sample parameters are read from datasets/dataset-manifest.yaml, so the
# manifest stays the single record of what is fetched.
#
# Modes
#   (default)  SAMPLE mode. Rebuilds the small committed excerpts under
#              datasets/samples/. Every transfer is capped during the
#              transfer (HTTP Range, or the body is streamed through
#              head -c <cap+1>; see common.sh capped_transfer) and has a
#              --max-time. A sample whose SHA-256 already matches
#              datasets/samples/SHA256SUMS is skipped without any network I/O.
#              OpenAlex samples have abstract_inverted_index removed before
#              install (manifest datasets.openalex.sample_policy).
#   --dry-run  Prints the plan (URLs, byte caps, skip decisions). Performs NO
#              network I/O and writes nothing.
#   --full     FULL mode for exactly one dataset. Requires BOTH --full and
#              SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1 in the environment, plus an
#              explicit --out directory. Every transfer has a byte cap
#              (the published size) enforced during the transfer and a
#              --max-time from the manifest (full_retrieval). A file is
#              skipped only when it verifies: dblp/orcid by publisher MD5;
#              OpenAlex by published content_length + gzip -t + its .sha256
#              when one was recorded. A complete-size file that fails
#              verification is quarantined (<file>.corrupt-<UTC>, never
#              deleted) and fetched again. Full mode never writes to S3; staging to
#              s3://${RAW_BUCKET}/... is done by an approved script under
#              scripts/ (owner: infra-engineer) with human approval.
#              crossref: REST API only, bounded to the DOIs in --doi-list
#              (the DBLP-subset DOI list); --max-dois caps it further.
#              <out> is bound to one normalised DOI list and batch size
#              (<out>/CROSSREF_RUN); a non-empty <out> recorded for another
#              list, or with no record, is refused. Each batch records its
#              DOI-set hash and retrieval time (.meta); RETRIEVED_AT is the
#              latest batch retrieval time. The
#              bulk Public Data File (Requester Pays S3, the requester is
#              billed) is documented in the manifest as an alternative; this
#              script does not perform it (--crossref-route requester-pays
#              prints the documented steps and exits 3).
#
# Optional, no defaults, never committed:
#   CROSSREF_MAILTO, OPENALEX_MAILTO  polite-pool contact, only sent if set.
#
# Usage
#   download.sh [--dataset dblp|openalex|crossref|orcid|all] [--dry-run]
#   download.sh --full --dataset <name> --out <dir> [--openalex-entities a,b] [--dry-run]
#   download.sh --full --dataset crossref --out <dir> --doi-list <file> [--max-dois N] [--dry-run]

set -euo pipefail

# shellcheck source=datasets/scripts/common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

usage() {
  sed -n '2,54p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

DATASET="all"
DRY_RUN=0
FULL=0
OUT_DIR=""
OPENALEX_ENTITIES=""
DOI_LIST=""
MAX_DOIS=""
CROSSREF_ROUTE="api"

parse_args() {
  while (($# > 0)); do
    case "$1" in
      --dataset)
        DATASET="${2:?--dataset needs a value}"
        shift 2
        ;;
      --dry-run)
        DRY_RUN=1
        shift
        ;;
      --full)
        FULL=1
        shift
        ;;
      --out)
        OUT_DIR="${2:?--out needs a value}"
        shift 2
        ;;
      --openalex-entities)
        OPENALEX_ENTITIES="${2:?--openalex-entities needs a value}"
        shift 2
        ;;
      --doi-list)
        DOI_LIST="${2:?--doi-list needs a value}"
        shift 2
        ;;
      --max-dois)
        MAX_DOIS="${2:?--max-dois needs a value}"
        [[ "$MAX_DOIS" =~ ^[1-9][0-9]*$ ]] || die "--max-dois must be a positive integer"
        shift 2
        ;;
      --crossref-route)
        CROSSREF_ROUTE="${2:?--crossref-route needs a value}"
        shift 2
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        usage >&2
        die "unknown argument: $1"
        ;;
    esac
  done

  case "$DATASET" in
    dblp | openalex | crossref | orcid | all) ;;
    *) die "unknown dataset: $DATASET" ;;
  esac
}

M() { py manifest_get.py "$MANIFEST_FILE" "$1"; }

WORK_DIR=""
cleanup() {
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    rm -rf -- "$WORK_DIR"
  fi
}

# The scratch directory is created once in the parent shell (see main) so
# that command substitutions never create stray directories.
work_dir() {
  [[ -n "$WORK_DIR" ]] || die "internal: work directory requested in dry-run"
  printf '%s\n' "$WORK_DIR"
}

plan() { printf 'DRY-RUN  %s\n' "$*"; }

# install_sample <tmpfile> <relative-path>: enforce caps, move into place,
# record the checksum.
install_sample() {
  local tmp="$1" rel="$2"
  check_sample_limits "$tmp"
  mkdir -p "$(dirname "${SAMPLES_DIR}/${rel}")"
  mv -f -- "$tmp" "${SAMPLES_DIR}/${rel}"
  record_sha256 "$rel"
}

# skip_if_current <relative-path>: returns 0 (and logs) when no work is needed.
skip_if_current() {
  local rel="$1"
  if sample_is_current "$rel"; then
    if ((DRY_RUN)); then
      plan "SKIP ${rel} (sha256 matches SHA256SUMS; no network I/O)"
    else
      log "SKIP ${rel} (sha256 matches SHA256SUMS)"
    fi
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------- samples --

sample_dblp() {
  local dtd_rel dtd_url md5_url out_rel url cap w st
  dtd_rel="$(M datasets.dblp.sampling.dtd_output)"
  dtd_url="$(M datasets.dblp.source.dtd_url)"
  md5_url="$(M datasets.dblp.source.dtd_checksum_url)"
  out_rel="$(M datasets.dblp.sampling.output)"
  url="$(M datasets.dblp.source.download_url)"
  cap="$(M datasets.dblp.sampling.max_compressed_bytes)"

  if ! skip_if_current "$dtd_rel"; then
    if ((DRY_RUN)); then
      plan "FETCH ${dtd_url} (cap 65536 bytes) + ${md5_url} (cap 1024 bytes) -> ${dtd_rel}"
    else
      w="$(work_dir)"
      bounded_get "$dtd_url" "$w/dtd" 65536
      bounded_get "$md5_url" "$w/dtd.md5" 1024
      [[ "$(md5sum "$w/dtd" | awk '{print $1}')" == "$(awk '{print $1}' "$w/dtd.md5")" ]] ||
        die "DTD md5 does not match the publisher .md5 file"
      log "DTD md5 matches publisher checksum"
      install_sample "$w/dtd" "$dtd_rel"
    fi
  fi

  if ! skip_if_current "$out_rel"; then
    if ((DRY_RUN)); then
      plan "STREAM ${url} with HTTP Range bytes=0-$((cap - 1)) (cap ${cap} compressed bytes; stops early when quotas fill) -> ${out_rel}"
    else
      w="$(work_dir)"
      log "streaming at most ${cap} compressed bytes of ${url}"
      set +e
      curl --fail --silent --show-error --location --max-time 600 \
        --user-agent "$SCHOLARCHAIN_USER_AGENT" \
        --range "0-$((cap - 1))" "$url" |
        py dblp_sample.py "$w/dblp.xml" "$cap"
      st=("${PIPESTATUS[@]}")
      set -e
      # curl exits 23 when the selector stops reading early; that is expected.
      [[ "${st[0]}" == 0 || "${st[0]}" == 23 ]] || die "curl failed with status ${st[0]}"
      [[ "${st[1]}" == 0 ]] || die "DBLP selector status ${st[1]} (3 = byte cap reached before quotas filled)"
      install_sample "$w/dblp.xml" "$out_rel"
    fi
  fi
}

# linked_dois <n>: DOIs from the DBLP excerpt, written to a temp file.
linked_dois() {
  local n="$1" rel f
  rel="$(M datasets.dblp.sampling.output)"
  f="$(work_dir)/dois.txt"
  [[ -f "${SAMPLES_DIR}/${rel}" ]] || die "DBLP excerpt ${rel} is missing; run --dataset dblp first"
  py sample_tools.py dblp-dois "${SAMPLES_DIR}/${rel}" "$n" >"$f"
  [[ -s "$f" ]] || die "no DOIs found in ${rel}"
  printf '%s\n' "$f"
}

sample_openalex() {
  local date murl base entity rel w pick s3url gzlen https api_rel api n sel url strip
  date="$(M datasets.openalex.snapshot.date)"
  strip="$(M datasets.openalex.sample_policy.strip_key)"
  murl="$(M datasets.openalex.source.snapshot_manifest_url)"
  base="$(M datasets.openalex.sampling.https_base)"

  for entity in works authors; do
    rel="openalex/openalex-${entity}-snapshot-${date}-excerpt.jsonl"
    skip_if_current "$rel" && continue
    if ((DRY_RUN)); then
      plan "FETCH ${murl} (cap 4194304 bytes), pick first ${entity} partition with 5-50 records and <= 102400 gzip bytes, FETCH it (cap = its published content_length), remove ${strip} from every line, keep whole lines -> ${rel} (<= ${SAMPLE_MAX_RECORDS} records, <= ${SAMPLE_MAX_BYTES} bytes)"
      continue
    fi
    w="$(work_dir)"
    if [[ ! -f "$w/oa-manifest.json" ]]; then
      bounded_get "$murl" "$w/oa-manifest.json" 4194304
      local got
      got="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["date"])' "$w/oa-manifest.json")"
      [[ "$got" == "$date" ]] ||
        die "OpenAlex snapshot is now ${got}, manifest pins ${date}. A human must re-verify and update the manifest."
    fi
    pick="$(py sample_tools.py openalex-pick-file "$w/oa-manifest.json" "$entity" 5 50 102400)"
    read -r s3url gzlen _ <<<"$pick"
    https="${base}${s3url#s3://openalex/}"
    log "OpenAlex ${entity} partition: ${s3url} (${gzlen} bytes)"
    bounded_get "$https" "$w/${entity}.gz" "$gzlen"
    [[ "$(wc -c <"$w/${entity}.gz")" == "$gzlen" ]] || die "size mismatch for ${https}"
    gzip -t "$w/${entity}.gz"
    # abstract_inverted_index is removed from each line BEFORE the record
    # and byte caps are applied (manifest datasets.openalex.sample_policy).
    py sample_tools.py jsonl-head "$w/${entity}.gz" "$w/${entity}.jsonl" "$SAMPLE_MAX_RECORDS" "$SAMPLE_MAX_BYTES" "$strip"
    install_sample "$w/${entity}.jsonl" "$rel"
  done

  api_rel="$(M datasets.openalex.sampling.api_linked_output)"
  if ! skip_if_current "$api_rel"; then
    api="$(M datasets.openalex.sampling.api_works_url)"
    n="$(M datasets.openalex.sampling.linked_doi_count)"
    sel="$(M datasets.openalex.sampling.api_select)"
    if ((DRY_RUN)); then
      plan "GET ${api}?filter=doi:<first ${n} DOIs of the DBLP excerpt>&select=${sel}&per-page=50 (one request, cap 2097152 bytes; ${strip} removed if present) -> ${api_rel}"
    else
      w="$(work_dir)"
      url="$(py sample_tools.py openalex-url "$api" "$(linked_dois "$n")" "$sel" "${OPENALEX_MAILTO:-}")"
      bounded_get "$url" "$w/oa-api.json" 2097152
      py sample_tools.py openalex-api-jsonl "$w/oa-api.json" "$w/dois.txt" "$w/oa-api.jsonl" "$strip"
      install_sample "$w/oa-api.jsonl" "$api_rel"
    fi
  fi
}

sample_crossref() {
  local rel api n w url
  rel="$(M datasets.crossref.sampling.output)"
  skip_if_current "$rel" && return 0
  api="$(M datasets.crossref.sampling.api_works_url)"
  n="$(M datasets.crossref.sampling.linked_doi_count)"
  if ((DRY_RUN)); then
    plan "GET ${api}?filter=doi:<first ${n} DOIs of the DBLP excerpt>&rows=${n} (one request, cap 2097152 bytes; abstract field dropped) -> ${rel}"
    return 0
  fi
  w="$(work_dir)"
  url="$(py sample_tools.py crossref-url "$api" "$(linked_dois "$n")" "${CROSSREF_MAILTO:-}")"
  bounded_get "$url" "$w/cr.json" 2097152
  py sample_tools.py crossref-jsonl "$w/cr.json" "$w/dois.txt" "$w/cr.jsonl"
  install_sample "$w/cr.jsonl" "$rel"
}

sample_orcid() {
  local rel api id w
  rel="$(M datasets.orcid.sampling.output)"
  skip_if_current "$rel" && return 0
  api="$(M datasets.orcid.sampling.api_base)"
  id="$(M datasets.orcid.sampling.orcid_id)"
  if ((DRY_RUN)); then
    plan "GET ${api}/${id}/record Accept: application/vnd.orcid+xml (cap 1048576 bytes), reduce to iD + name + affiliations + work identifiers (orcid_reduce.py) -> ${rel}"
    return 0
  fi
  w="$(work_dir)"
  bounded_get "${api}/${id}/record" "$w/orcid-raw.xml" 1048576 \
    --header "Accept: application/vnd.orcid+xml"
  grep -q "<common:path>${id}</common:path>" "$w/orcid-raw.xml" || die "ORCID response does not describe ${id}"
  # Privacy policy: the raw response (emails, biography, addresses, ...) is
  # never installed; only the reduced record is.
  py orcid_reduce.py "$w/orcid-raw.xml" "$w/orcid.xml"
  rm -f -- "$w/orcid-raw.xml"
  install_sample "$w/orcid.xml" "$rel"
}

# ------------------------------------------------------------- full mode --

# full_max_time <dataset>: --max-time for one full-mode transfer, from the
# manifest (datasets.<dataset>.full_retrieval.max_time_seconds).
full_max_time() { M "datasets.$1.full_retrieval.max_time_seconds"; }

FULL_MD5_WANT=""
md5_matches() { [[ "$(md5sum "$1" | awk '{print $1}')" == "$FULL_MD5_WANT" ]]; }
md5_and_gzip_ok() { md5_matches "$1" && gzip -t "$1" 2>/dev/null; }

full_dblp() {
  local url md5u dtd name expected w max_time
  url="$(M datasets.dblp.source.download_url)"
  md5u="$(M datasets.dblp.source.checksum_url)"
  dtd="$(M datasets.dblp.source.dtd_url)"
  expected="$(M datasets.dblp.size.observed.remote_bytes)"
  max_time="$(full_max_time dblp)"
  name="${url##*/}"
  if ((DRY_RUN)); then
    plan "FULL dblp: ${url} (${expected} bytes per HTTP HEAD; byte cap ${expected}, --max-time ${max_time}) + ${md5u} + ${dtd} -> ${OUT_DIR}/; skip when size, publisher md5 and gzip -t verify; resume a shorter file; quarantine and refetch a full-size file that fails; then write ${name}.sha256"
    return 0
  fi
  mkdir -p "$OUT_DIR"
  w="$(work_dir)"
  bounded_get "$md5u" "$w/md5" 1024
  FULL_MD5_WANT="$(awk '{print $1}' "$w/md5")"
  [[ "$FULL_MD5_WANT" =~ ^[0-9a-f]{32}$ ]] || die "publisher md5 file ${md5u} is malformed"
  if [[ -f "${OUT_DIR}/${name}" ]] && [[ "$(wc -c <"${OUT_DIR}/${name}")" == "$expected" ]] && md5_and_gzip_ok "${OUT_DIR}/${name}"; then
    log "SKIP ${name} (size, md5 and gzip -t verify)"
  else
    resume_fetch "$url" "${OUT_DIR}/${name}" "$expected" "$max_time" md5_and_gzip_ok
  fi
  cp -f -- "$w/md5" "${OUT_DIR}/${name}.md5"
  bounded_get "$dtd" "${OUT_DIR}/${dtd##*/}" 65536
  (cd "$OUT_DIR" && sha256sum "$name" >"${name}.sha256")
  log "full dblp done: $(cat "${OUT_DIR}/${name}.sha256")"
}

# openalex_partition_ok <file> <content_length>: size, gzip -t, and the
# recorded <file>.sha256 when one exists (written after a verified fetch).
openalex_partition_ok() {
  local f="$1" len="$2"
  [[ -f "$f" ]] || return 1
  [[ "$(wc -c <"$f")" == "$len" ]] || return 1
  gzip -t "$f" 2>/dev/null || return 1
  if [[ -f "${f}.sha256" ]]; then
    (cd "$(dirname "$f")" && sha256sum --status -c "$(basename "$f").sha256") || return 1
  fi
  return 0
}
gzip_ok() { gzip -t "$1" 2>/dev/null; }

full_openalex() {
  [[ -n "$OPENALEX_ENTITIES" ]] || die "--openalex-entities is required in full mode (e.g. works,authors); there is no default"
  local date murl base w max_time
  date="$(M datasets.openalex.snapshot.date)"
  murl="$(M datasets.openalex.source.snapshot_manifest_url)"
  base="$(M datasets.openalex.sampling.https_base)"
  max_time="$(full_max_time openalex)"
  if ((DRY_RUN)); then
    plan "FULL openalex entities=${OPENALEX_ENTITIES}: fetch ${murl}, then every listed partition over HTTPS from ${base} -> ${OUT_DIR}/data/jsonl/... (byte cap = published content_length, --max-time ${max_time} each); skip a partition only when its size, gzip -t and recorded .sha256 verify; resume a shorter one; quarantine and refetch a full-size one that fails; write <partition>.sha256 after each verified fetch"
    plan "FULL openalex published sizes (manifest.yaml): works=$(M datasets.openalex.size.expected.works_jsonl_bytes) bytes, authors=$(M datasets.openalex.size.expected.authors_jsonl_bytes) bytes, all jsonl=$(M datasets.openalex.size.expected.all_jsonl_bytes) bytes"
    return 0
  fi
  w="$(work_dir)"
  bounded_get "$murl" "$w/oa-manifest.json" 4194304
  local got
  got="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["date"])' "$w/oa-manifest.json")"
  [[ "$got" == "$date" ]] || die "OpenAlex snapshot is now ${got}, manifest pins ${date}. A human must re-verify."
  mkdir -p "$OUT_DIR"
  cp -f -- "$w/oa-manifest.json" "${OUT_DIR}/manifest.json"
  python3 -I -c '
import json, sys
m = json.load(open(sys.argv[1]))
want = set(sys.argv[2].split(","))
for e in m["entities"]:
    if e["entity"] in want:
        for f in e["files"]:
            print(f["url"], int(f["meta"]["content_length"]))
' "$w/oa-manifest.json" "$OPENALEX_ENTITIES" >"$w/files.txt"
  local s3url len rel dest
  while read -r s3url len; do
    rel="${s3url#s3://openalex/}"
    [[ "$rel" != "$s3url" && "$rel" != *..* ]] || die "unexpected partition URL in manifest.json: ${s3url}"
    dest="${OUT_DIR}/${rel}"
    if openalex_partition_ok "$dest" "$len"; then
      log "SKIP ${rel} (size, gzip -t$([[ -f "${dest}.sha256" ]] && printf ' and sha256') verify)"
      continue
    fi
    # Not verified. A recorded checksum no longer describes this file, and
    # a file of full length (or longer) cannot be resumed: set both aside.
    [[ -f "${dest}.sha256" ]] && quarantine "${dest}.sha256"
    if [[ -f "$dest" ]] && (($(wc -c <"$dest") >= len)); then quarantine "$dest"; fi
    resume_fetch "${base}${rel}" "$dest" "$len" "$max_time" gzip_ok
    (cd "$(dirname "$dest")" && sha256sum "$(basename "$dest")" >"$(basename "$dest").sha256")
  done <"$w/files.txt"
  log "full openalex done for ${OPENALEX_ENTITIES}"
}

full_orcid() {
  local i n name url bytes dest max_time
  max_time="$(full_max_time orcid)"
  n="$(python3 -I -c 'import sys,yaml; print(len(yaml.safe_load(open(sys.argv[1]))["datasets"]["orcid"]["source"]["files"]))' "$MANIFEST_FILE")"
  for ((i = 0; i < n; i++)); do
    name="$(M "datasets.orcid.source.files.${i}.name")"
    url="$(M "datasets.orcid.source.files.${i}.url")"
    bytes="$(M "datasets.orcid.source.files.${i}.bytes")"
    FULL_MD5_WANT="$(M "datasets.orcid.source.files.${i}.md5")"
    dest="${OUT_DIR}/${name}"
    if ((DRY_RUN)); then
      plan "FULL orcid: ${url} -> ${dest} (${bytes} bytes = byte cap, md5 ${FULL_MD5_WANT}, --max-time ${max_time}); skip when size and md5 verify; resume a shorter file; quarantine and refetch a full-size file that fails"
      continue
    fi
    mkdir -p "$OUT_DIR"
    if [[ -f "$dest" ]] && [[ "$(wc -c <"$dest")" == "$bytes" ]] && md5_matches "$dest"; then
      log "SKIP ${name} (size and md5 match figshare)"
      continue
    fi
    resume_fetch "$url" "$dest" "$bytes" "$max_time" md5_matches
  done
}

# crossref_batch_ok <out> <base> <doi-set-sha256> <batch-dois.txt>: true only
# when the batch file, its .sha256 and .meta exist, the sha256 verifies, the
# .meta records the same DOI-set hash, and every item's DOI belongs to the
# batch's DOI set.
crossref_batch_ok() {
  local out="$1" base="$2" want="$3" dois="$4"
  [[ -f "${out}/${base}.jsonl" && -f "${out}/${base}.jsonl.sha256" && -f "${out}/${base}.meta" ]] || return 1
  (cd "$out" && sha256sum --status -c "${base}.jsonl.sha256") || return 1
  grep -qx "doi_set_sha256=${want}" "${out}/${base}.meta" || return 1
  py sample_tools.py crossref-batch-check "${out}/${base}.jsonl" "$dois" >/dev/null 2>&1
}

# meta_value <file> <key>: value of "key=value" in a .meta / record file.
meta_value() { sed -n "s/^$2=//p" "$1" | head -n 1; }

full_crossref() {
  plan_or_log() { if ((DRY_RUN)); then plan "$*"; else log "$*"; fi; }
  case "$CROSSREF_ROUTE" in
    api) ;;
    requester-pays)
      plan_or_log "Crossref bulk alternative: Requester Pays S3 bucket $(M datasets.crossref.source.alternative_bulk.requester_pays_bucket). The REQUESTER IS BILLED for listing and transfer."
      plan_or_log "Not performed by this script. A human must approve the cost, confirm the 2026 object key (manifest: UNVERIFIED), and run the transfer through an approved scripts/ entry point (owner: infra-engineer)."
      exit 3
      ;;
    *) die "unknown --crossref-route: ${CROSSREF_ROUTE} (api | requester-pays)" ;;
  esac
  [[ -n "$DOI_LIST" ]] || die "crossref full mode needs --doi-list <file>: the DOIs of the selected DBLP subset (Hadoop DBLP output). There is no default."
  [[ -f "$DOI_LIST" ]] || die "DOI list not found: $DOI_LIST"

  local api batch maxb interval max_time w total nb i base bdois bhash list_hash rec now
  api="$(M datasets.crossref.source.api_url)"
  batch="$(M datasets.crossref.full_retrieval.api_batch_size)"
  maxb="$(M datasets.crossref.full_retrieval.max_response_bytes)"
  interval="$(M datasets.crossref.full_retrieval.request_interval_seconds)"
  max_time="$(M datasets.crossref.full_retrieval.request_max_time_seconds)"
  [[ "$batch" =~ ^[1-9][0-9]*$ ]] || die "full_retrieval.api_batch_size must be a positive integer"

  # Normalise: trim, lower-case, keep DOI-shaped lines only, unique, sorted
  # (deterministic batch membership), then apply --max-dois.
  normalise_dois() {
    awk '{gsub(/^[ \t]+|[ \t\r]+$/, ""); $0 = tolower($0)} /^10\.[0-9]+\/[^ ]+$/' "$DOI_LIST" |
      LC_ALL=C sort -u |
      if [[ -n "$MAX_DOIS" ]]; then head -n "$MAX_DOIS"; else cat; fi
  }
  total="$(normalise_dois | wc -l)"
  ((total > 0)) || die "no DOI-shaped lines in $DOI_LIST"
  nb=$(((total + batch - 1) / batch))

  if ((DRY_RUN)); then
    plan "FULL crossref (REST API route): ${total} distinct DOIs from ${DOI_LIST}${MAX_DOIS:+ (capped by --max-dois ${MAX_DOIS})} -> ${nb} request(s) of <= ${batch} DOIs to ${api}?filter=doi:..., each capped at ${maxb} bytes during transfer, --max-time ${max_time}, ${interval}s apart; abstract field dropped"
    plan "FULL crossref identity: ${OUT_DIR}/CROSSREF_RUN binds <out> to the sha256 of the normalised DOI list and the batch size; a non-empty <out> with a different record, or none, is REFUSED. A batch is skipped only when its .sha256 verifies, its .meta DOI-set hash equals the batch's, and every item DOI is in the batch. RETRIEVED_AT = latest batch retrieved_at."
    return 0
  fi

  w="$(work_dir)"
  normalise_dois >"$w/dois-normalised.txt"
  list_hash="$(sha256_of "$w/dois-normalised.txt")"
  rec="${OUT_DIR}/CROSSREF_RUN"
  if [[ -d "$OUT_DIR" && -n "$(ls -A -- "$OUT_DIR")" ]]; then
    [[ -f "$rec" ]] ||
      die "<out> ${OUT_DIR} is not empty and has no CROSSREF_RUN record of the DOI list it holds. Refusing; use a new, empty --out."
    [[ "$(meta_value "$rec" doi_list_sha256)" == "$list_hash" ]] ||
      die "<out> ${OUT_DIR} holds the DOI list with sha256 $(meta_value "$rec" doi_list_sha256); this run's normalised DOI list has sha256 ${list_hash}. Refusing to mix DOI lists in one <out>; use a new, empty --out."
    [[ "$(meta_value "$rec" batch_size)" == "$batch" ]] ||
      die "<out> ${OUT_DIR} was built with batch size $(meta_value "$rec" batch_size); this run uses ${batch}. Refusing; use a new, empty --out."
    [[ -f "${OUT_DIR}/DOI_LIST.txt" && "$(sha256_of "${OUT_DIR}/DOI_LIST.txt")" == "$list_hash" ]] ||
      die "${OUT_DIR}/DOI_LIST.txt does not match CROSSREF_RUN. Refusing."
  else
    mkdir -p "$OUT_DIR"
    cp -f -- "$w/dois-normalised.txt" "${OUT_DIR}/DOI_LIST.txt"
    printf 'doi_list_sha256=%s\ndoi_count=%s\nbatch_size=%s\napi_url=%s\n' \
      "$list_hash" "$total" "$batch" "$api" >"${rec}.tmp"
    mv -f -- "${rec}.tmp" "$rec"
  fi

  split -l "$batch" -d -a 6 "$w/dois-normalised.txt" "$w/batch-"
  local fetched=0
  for ((i = 0; i < nb; i++)); do
    printf -v base 'crossref-api-batch-%06d' "$i"
    printf -v bdois '%s/batch-%06d' "$w" "$i"
    bhash="$(sha256_of "$bdois")"
    if crossref_batch_ok "$OUT_DIR" "$base" "$bhash" "$bdois"; then
      log "SKIP ${base}.jsonl (sha256, DOI-set hash and item DOIs verify)"
      continue
    fi
    ((fetched == 0)) || sleep "$interval"
    capped_transfer "$w/resp.json" "$maxb" "$max_time" truncate \
      "$(py sample_tools.py crossref-url "$api" "$bdois" "${CROSSREF_MAILTO:-}")"
    py sample_tools.py crossref-jsonl "$w/resp.json" "$bdois" "$w/out.jsonl"
    now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    rm -f -- "${OUT_DIR}/${base}.meta"
    mv -f -- "$w/out.jsonl" "${OUT_DIR}/${base}.jsonl"
    (cd "$OUT_DIR" && sha256sum "${base}.jsonl" >"${base}.jsonl.sha256")
    printf 'doi_set_sha256=%s\ndoi_count=%s\nretrieved_at=%s\n' \
      "$bhash" "$(wc -l <"$bdois")" "$now" >"${OUT_DIR}/${base}.meta.tmp"
    mv -f -- "${OUT_DIR}/${base}.meta.tmp" "${OUT_DIR}/${base}.meta"
    fetched=$((fetched + 1))
  done

  # Provenance dates come from the batches themselves: RETRIEVED_AT is the
  # latest batch retrieval time; RETRIEVAL_WINDOW records the span.
  local first last
  first="$(for ((i = 0; i < nb; i++)); do meta_value "$(printf '%s/crossref-api-batch-%06d.meta' "$OUT_DIR" "$i")" retrieved_at; done | LC_ALL=C sort | head -n 1)"
  last="$(for ((i = 0; i < nb; i++)); do meta_value "$(printf '%s/crossref-api-batch-%06d.meta' "$OUT_DIR" "$i")" retrieved_at; done | LC_ALL=C sort | tail -n 1)"
  [[ -n "$first" && -n "$last" ]] || die "internal: batch .meta files lack retrieved_at"
  printf '%s\n' "$last" >"${OUT_DIR}/RETRIEVED_AT.tmp"
  mv -f -- "${OUT_DIR}/RETRIEVED_AT.tmp" "${OUT_DIR}/RETRIEVED_AT"
  printf 'first_retrieved_at=%s\nlast_retrieved_at=%s\nbatches=%s\n' "$first" "$last" "$nb" >"${OUT_DIR}/RETRIEVAL_WINDOW.tmp"
  mv -f -- "${OUT_DIR}/RETRIEVAL_WINDOW.tmp" "${OUT_DIR}/RETRIEVAL_WINDOW"
  log "full crossref (API route) done: ${nb} batch file(s), ${fetched} fetched, RETRIEVED_AT ${last}, in ${OUT_DIR}"
}

# ------------------------------------------------------------------ main --

main() {
  parse_args "$@"
  require_cmd python3 sha256sum awk sort
  [[ -f "$MANIFEST_FILE" ]] || die "manifest not found: $MANIFEST_FILE"
  trap cleanup EXIT
  if ((!DRY_RUN)); then
    WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-datasets.XXXXXX")"
  fi

  if ((FULL)); then
    [[ "$DATASET" != "all" ]] || die "full mode needs exactly one --dataset"
    [[ -n "$OUT_DIR" ]] || die "full mode needs --out <dir>"
    require_full_gate
    require_cmd curl gzip md5sum head truncate
    log "FULL-CORPUS mode for ${DATASET} (dry-run=${DRY_RUN})"
    "full_${DATASET}"
    exit 0
  fi

  ((DRY_RUN)) || require_cmd curl gzip md5sum head
  log "SAMPLE mode for ${DATASET} (dry-run=${DRY_RUN})"
  local d
  if [[ "$DATASET" == "all" ]]; then
    # dblp first: the OpenAlex and Crossref linked samples use its DOIs.
    for d in dblp openalex crossref orcid; do "sample_${d}"; done
  else
    "sample_${DATASET}"
  fi
  log "done"
}

# Run only when executed; test-download.sh sources this file as a library.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
