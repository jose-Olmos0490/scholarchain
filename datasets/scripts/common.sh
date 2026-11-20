#!/usr/bin/env bash
# datasets/scripts/common.sh
#
# Owner: dataset-engineer. Shared helpers for the dataset scripts.
# Sourced, never executed directly. Callers already run in strict mode.
#
# Nothing in this file performs network I/O at source time.

# Variables defined here are consumed by the scripts that source this file.
# shellcheck disable=SC2034
set -euo pipefail

DATASETS_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASETS_DIR="$(cd "${DATASETS_SCRIPTS_DIR}/.." && pwd)"
SAMPLES_DIR="${DATASETS_DIR}/samples"
SHA256SUMS_FILE="${SAMPLES_DIR}/SHA256SUMS"
MANIFEST_FILE="${DATASETS_DIR}/dataset-manifest.yaml"
PY_LIB_DIR="${DATASETS_SCRIPTS_DIR}/lib"

# Generic client name. No personal contact details are ever sent.
SCHOLARCHAIN_USER_AGENT="scholarchain-dataset-sampler/0.1"

# Hard limits that every committed sample must respect (work order Phase 03).
# "200 KB" means 200,000 bytes (human decision 2026-11-19, review finding 3).
SAMPLE_MAX_BYTES=200000
SAMPLE_MAX_RECORDS=50

# --max-time for small fixed-size fetches (sample objects, .md5 files, the
# OpenAlex manifest.json). Full-corpus transfers take theirs from the
# manifest (full_retrieval.*max_time_seconds).
SMALL_FETCH_MAX_TIME=120

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() {
  log "ERROR: $*"
  exit 1
}

require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "required command not found: $c"
  done
}

# py <script> [args...]: run a helper in isolated mode (-I) so nothing in the
# current directory or a downloaded tree can shadow standard modules.
py() {
  local script="$1"
  shift
  python3 -I "${PY_LIB_DIR}/${script}" "$@"
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

# expected_sha256 <relative-path-under-samples>: prints the recorded hash or
# nothing when the file is not yet listed.
expected_sha256() {
  local rel="$1"
  [[ -f "$SHA256SUMS_FILE" ]] || return 0
  awk -v p="$rel" '$2 == p || $2 == "*"p {print $1; exit}' "$SHA256SUMS_FILE"
}

# sample_is_current <relative-path>: true when the file exists and its
# SHA-256 matches the SHA256SUMS entry. Used to skip already-correct files.
sample_is_current() {
  local rel="$1" want have
  want="$(expected_sha256 "$rel")"
  [[ -n "$want" && -f "${SAMPLES_DIR}/${rel}" ]] || return 1
  have="$(sha256_of "${SAMPLES_DIR}/${rel}")"
  [[ "$have" == "$want" ]]
}

# record_sha256 <relative-path>: insert or replace the SHA256SUMS line.
record_sha256() {
  local rel="$1" sum tmp
  sum="$(sha256_of "${SAMPLES_DIR}/${rel}")"
  tmp="$(mktemp)"
  if [[ -f "$SHA256SUMS_FILE" ]]; then
    awk -v p="$rel" '$2 != p' "$SHA256SUMS_FILE" >"$tmp"
  fi
  printf '%s  %s\n' "$sum" "$rel" >>"$tmp"
  LC_ALL=C sort -k2 "$tmp" >"$SHA256SUMS_FILE"
  rm -f -- "$tmp"
  log "recorded sha256 ${sum}  ${rel}"
}

# check_sample_limits <file>: enforce the byte cap (SAMPLE_MAX_BYTES). Record caps are checked
# per format by validate-samples.sh.
check_sample_limits() {
  local f="$1" size
  size="$(wc -c <"$f")"
  if ((size > SAMPLE_MAX_BYTES)); then
    die "sample $f is ${size} bytes, above the ${SAMPLE_MAX_BYTES}-byte cap"
  fi
}

# capped_transfer <dest> <max-bytes> <max-time-seconds> <truncate|append> <curl args... url>
# Streams the response body through "head -c <remaining+1>", so at most
# max-bytes (+1 byte, the overflow witness) ever reach <dest>, whether or
# not the server sends Content-Length and whatever the curl version
# (--max-filesize is only enforced during the transfer on curl >= 8.4 or
# when Content-Length is known). Every transfer gets --max-time.
#   truncate: <dest> is replaced; on failure it is removed.
#   append:   resume; the request starts at the current size of <dest>
#             (--continue-at <offset>); on failure <dest> is cut back to
#             that offset, so a failed attempt never leaves extra bytes.
capped_transfer() {
  local dest="$1" max="$2" max_time="$3" mode="$4" before=0 remaining size got
  local -a st resume=()
  shift 4
  [[ "$max" =~ ^[0-9]+$ ]] || die "internal: byte cap must be an integer (got '${max}')"
  [[ "$max_time" =~ ^[1-9][0-9]*$ ]] || die "internal: --max-time must be a positive integer (got '${max_time}')"
  case "$mode" in
    truncate) : >"$dest" ;;
    append)
      if [[ -f "$dest" ]]; then before="$(wc -c <"$dest")"; fi
      if ((before > 0)); then resume=(--continue-at "$before"); fi
      ;;
    *) die "internal: capped_transfer mode must be truncate or append" ;;
  esac
  ((before <= max)) || die "${dest} is ${before} bytes, already above its ${max}-byte cap"
  remaining=$((max - before))
  set +e
  curl --fail --silent --show-error --location \
    --max-time "$max_time" --max-filesize "$remaining" \
    --user-agent "$SCHOLARCHAIN_USER_AGENT" "${resume[@]}" "$@" |
    head -c "$((remaining + 1))" >>"$dest"
  st=("${PIPESTATUS[@]}")
  set -e
  size="$(wc -c <"$dest")"
  got=$((size - before))
  if ((got > remaining)) || [[ "${st[0]}" != 0 || "${st[1]}" != 0 ]]; then
    if [[ "$mode" == truncate ]]; then rm -f -- "$dest"; else truncate -s "$before" -- "$dest"; fi
    ((got > remaining)) &&
      die "transfer to ${dest} exceeded its ${max}-byte cap; stopped after $((before + remaining + 1)) bytes"
    die "transfer to ${dest} failed (curl status ${st[0]}, head status ${st[1]})"
  fi
  return 0
}

# bounded_get <url> <dest> <max-bytes> [extra curl args...]
# Fetches a small object; refuses anything larger than max-bytes during the
# transfer (see capped_transfer).
bounded_get() {
  local url="$1" dest="$2" max="$3"
  shift 3
  capped_transfer "$dest" "$max" "$SMALL_FETCH_MAX_TIME" truncate "$@" "$url"
}

# quarantine <file>: move a file that failed verification aside (never
# deleted; a human decides what to do with it) so it can be fetched again.
quarantine() {
  local f="$1" q
  q="${f}.corrupt-$(date -u +%Y%m%dT%H%M%SZ)"
  mv -f -- "$f" "$q"
  log "QUARANTINED ${f} -> ${q}"
}

# resume_fetch <url> <dest> <expected-bytes> <max-time-seconds> <verify-fn>
# Full-mode download of one large object of known size: resumes a shorter
# partial file, quarantines a file that is too long or that has the full
# size but fails <verify-fn dest>, never transfers more than the expected
# size, and fails unless the final file has exactly that size and passes
# <verify-fn>. One fresh retry is made when a resumed file fails to verify.
resume_fetch() {
  local url="$1" dest="$2" expected="$3" max_time="$4" verify="$5" have attempt
  mkdir -p "$(dirname "$dest")"
  for attempt in 1 2; do
    have=0
    [[ -f "$dest" ]] && have="$(wc -c <"$dest")"
    if ((have > expected)); then
      quarantine "$dest"
      have=0
    elif ((have == expected)) && ! "$verify" "$dest"; then
      quarantine "$dest"
      have=0
    fi
    if ((have < expected)); then
      capped_transfer "$dest" "$expected" "$max_time" append "$url"
    fi
    have="$(wc -c <"$dest")"
    ((have == expected)) || die "size mismatch for ${dest}: ${have} != ${expected}"
    if "$verify" "$dest"; then
      return 0
    fi
    log "verification failed for ${dest} (attempt ${attempt})"
    quarantine "$dest"
  done
  die "verification failed twice for ${dest}"
}

# Full-corpus gate: both the --full flag and the environment variable are
# required. Anything else refuses before any transfer begins.
require_full_gate() {
  if [[ "${SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD:-}" != "1" ]]; then
    die "full-corpus mode needs --full AND SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1 (set by a human). Refusing."
  fi
}
