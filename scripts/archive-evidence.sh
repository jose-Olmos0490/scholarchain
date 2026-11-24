#!/usr/bin/env bash
# archive-evidence.sh - gather the evidence of one commit into evidence/<commit-sha>/, or into an
# S3 prefix when EVIDENCE_S3_PREFIX is set.
#
#   scripts/archive-evidence.sh --env dev [--git-sha <short sha>] [--hours 24]
#                               [--metric <namespace>:<metric>:<Dim>=<value>[,<Dim>=<value>]]...
#                               [--profile <name>] [--region <region>]
#
# Collected (each item is copied only when it exists and belongs to the commit; missing items
# are listed in MISSING.txt, never invented):
#   tests/               docs/agent-runs/*/test-report.md and review-report.md that exist in the
#                        commit's tree and are unmodified in the working tree, and the JUnit XML
#                        reports under **/target/test-reports/ that were modified since the
#                        commit's PARENT commit (reports of older runs are skipped). The
#                        system-out and system-err bodies of the XML are stripped (they can hold
#                        logs, tokens or personal data). Per-file and total size caps apply
#                        (SC_EVIDENCE_MAX_FILE_BYTES, SC_EVIDENCE_MAX_TOTAL_BYTES); anything
#                        skipped is listed in MISSING.txt, never invented.
#   plan/                build/plan-summary.json (the plan checksum, counts, IAM and stateful
#                        actions) and the plan checksum files. The plan file itself is NOT copied.
#   images/              build/package-manifest.json (only a COMPLETE one: "complete": true, jars and
#                        images both built; a partial or pre-marker manifest is listed in
#                        MISSING.txt, not archived) and build/deploy-record-*-<sha>.json (image
#                        digests, task definition revisions)
#   runs/                build/pipeline-run-*.json for this commit
#   experiments/         build/experiment-*-<sha>-*.json and their raw result directories
#   metrics/             CloudWatch exports: every --metric given, plus every metric location
#                        listed in this commit's experiment records
#   release/             release-metadata.json (commit, subject, tool versions, tree state) and
#                        evidence-location.txt (the s3:// location, or "local-only"; scripts/cleanup.sh
#                        reads it)
#   SHA256SUMS           checksums of every file above
#
# An archive is immutable: the script refuses to write into an existing non-empty
# evidence/<sha>/ or into an S3 prefix that already holds objects for this commit. It deletes
# nothing and overwrites nothing.
#
# Required environment variables: SCHOLARCHAIN_APPROVED_ENVS (set by the human; must list --env
#   when CloudWatch or S3 is used). AWS read credentials (CloudWatch GetMetricStatistics) and,
#   with EVIDENCE_S3_PREFIX, write access to that prefix, from the standard chain or --profile.
# Optional: EVIDENCE_S3_PREFIX (s3://<bucket>/<prefix>; the bundle is stored under
#   <prefix>/<commit-sha>/), SC_BUILD_DIR, SC_EVIDENCE_MAX_FILE_BYTES (per test-report file,
#   default 5242880), SC_EVIDENCE_MAX_TOTAL_BYTES (all test reports, default 52428800),
#   SC_EVIDENCE_MAX_ARCHIVE_BYTES (whole archive, default 524288000; larger is refused).
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; no default.
# Exit codes: 0 archived, 1 refused or failed, 2 usage error.
# Human approval point: the choice of EVIDENCE_S3_PREFIX bucket (a data-store write).
# Rollback behavior: none; nothing existing is changed. A failed run leaves a partial local
#   directory that the human may delete.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
GIT_SHA=""
HOURS=24
SC_PROFILE=""
SC_REGION=""
declare -a METRICS=()

# shellcheck disable=SC2034  # SC_PROFILE is read by aws_call in lib/common.sh
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?--git-sha needs a value}"; shift 2 ;;
    --hours) HOURS="${2:?--hours needs a number}"; shift 2 ;;
    --metric) METRICS+=("${2:?--metric needs namespace:metric:Dim=value}"); shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
validate_env_name "$ENV_NAME"
resolve_region
[[ "$HOURS" =~ ^[0-9]+$ && "$HOURS" -ge 1 && "$HOURS" -le 168 ]] || usage_error "--hours must be 1..168"
require_cmd git python3 sha256sum timeout find

EVIDENCE_S3_PREFIX="${EVIDENCE_S3_PREFIX:-}"
[[ -z "$EVIDENCE_S3_PREFIX" || "$EVIDENCE_S3_PREFIX" =~ ^s3://[a-z0-9][a-z0-9.-]+[a-z0-9](/[A-Za-z0-9._/-]*)?$ ]] ||
  usage_error "EVIDENCE_S3_PREFIX must look like s3://bucket/prefix"
EVIDENCE_S3_PREFIX="${EVIDENCE_S3_PREFIX%/}"

[[ -n "$GIT_SHA" ]] || GIT_SHA="$(head_sha)"
[[ "$GIT_SHA" =~ ^[0-9a-f]{7,40}$ ]] || usage_error "--git-sha must be 7 to 40 lowercase hex characters"
git -C "$SC_REPO_ROOT" cat-file -e "$GIT_SHA^{commit}" 2>/dev/null || die "unknown commit $GIT_SHA"

if [[ "${#METRICS[@]}" -gt 0 || -n "$EVIDENCE_S3_PREFIX" ]]; then
  require_cmd aws
  require_approved_env "$ENV_NAME"
fi

DEST="$SC_REPO_ROOT/evidence/$GIT_SHA"
step "prepare $DEST (an existing non-empty archive is refused)"
if [[ -d "$DEST" ]] && [[ -n "$(find "$DEST" -mindepth 1 -print -quit)" ]]; then
  die "$DEST already exists and is not empty; evidence archives are immutable"
fi
if [[ -n "$EVIDENCE_S3_PREFIX" ]]; then
  BUCKET="${EVIDENCE_S3_PREFIX#s3://}"
  BUCKET="${BUCKET%%/*}"
  KEY_PREFIX="${EVIDENCE_S3_PREFIX#s3://"$BUCKET"}"
  KEY_PREFIX="${KEY_PREFIX#/}"
  KEY="${KEY_PREFIX:+$KEY_PREFIX/}$GIT_SHA/"
  # shellcheck disable=SC2016  # the backticks are JMESPath literals, not command substitution
  COUNT="$(retry 3 aws_call s3api list-objects-v2 --bucket "$BUCKET" --prefix "$KEY" --max-items 1 \
    --query 'length(Contents || `[]`)' --output text)"
  [[ "$COUNT" == "0" ]] || die "s3://$BUCKET/$KEY already holds objects; evidence archives are immutable"
fi
mkdir -p "$DEST"/{tests,plan,images,runs,experiments,metrics,release}
: >"$DEST/MISSING.txt"

missing() { printf '%s\n' "$*" >>"$DEST/MISSING.txt"; warn "missing: $*"; }

# json_commit <file> prints the commitSha field (or nothing).
json_commit() {
  python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("commitSha",""))' "$1" 2>/dev/null || true
}

# commit_matches <file>: true when the file's commitSha is a prefix match of GIT_SHA either way.
commit_matches() {
  local c
  c="$(json_commit "$1")"
  [[ -n "$c" && ( "$c" == "$GIT_SHA"* || "$GIT_SHA" == "$c"* ) ]]
}

BUILD="$SC_BUILD_DIR"

MAX_FILE_BYTES="${SC_EVIDENCE_MAX_FILE_BYTES:-5242880}"
MAX_TOTAL_BYTES="${SC_EVIDENCE_MAX_TOTAL_BYTES:-52428800}"
MAX_ARCHIVE_BYTES="${SC_EVIDENCE_MAX_ARCHIVE_BYTES:-524288000}"
for v in "$MAX_FILE_BYTES" "$MAX_TOTAL_BYTES" "$MAX_ARCHIVE_BYTES"; do
  [[ "$v" =~ ^[0-9]+$ ]] || usage_error "SC_EVIDENCE_MAX_*_BYTES values must be integers"
done

step "test reports (since the parent commit; size-capped; system-out/system-err stripped)"
# JUnit XML must have been written since the commit's parent (older runs belong to older commits).
# A root commit has no parent: no lower bound.
PARENT_EPOCH=0
if git -C "$SC_REPO_ROOT" rev-parse --verify -q "$GIT_SHA^" >/dev/null; then
  PARENT_EPOCH="$(git -C "$SC_REPO_ROOT" log -1 --format=%ct "$GIT_SHA^")"
fi
n=0
# Markdown reports: only those that exist in the commit's tree and are unmodified on disk.
while IFS= read -r f; do
  rel="${f#"$SC_REPO_ROOT"/}"
  git -C "$SC_REPO_ROOT" cat-file -e "$GIT_SHA:$rel" 2>/dev/null || { missing "tests-skipped: $rel is not part of commit $GIT_SHA"; continue; }
  git -C "$SC_REPO_ROOT" diff --quiet "$GIT_SHA" -- "$rel" || { missing "tests-skipped: $rel differs from commit $GIT_SHA"; continue; }
  if [[ "$(stat -c %s "$f")" -gt "$MAX_FILE_BYTES" ]]; then missing "tests-skipped: $rel exceeds SC_EVIDENCE_MAX_FILE_BYTES ($MAX_FILE_BYTES)"; continue; fi
  mkdir -p "$DEST/tests/$(dirname "$rel")"
  cp -- "$f" "$DEST/tests/$rel"
  n=$((n + 1))
done < <(find "$SC_REPO_ROOT" \( -path "$SC_REPO_ROOT/evidence" -o -path "$SC_REPO_ROOT/.git" \) -prune -o \
  -path '*/docs/agent-runs/*' \( -name 'test-report.md' -o -name 'review-report.md' \) -type f -print 2>/dev/null | sort -u)
# JUnit XML: one python process filters by mtime and size, strips system-out/system-err, copies.
XML_OUT="$(find "$SC_REPO_ROOT" \( -path "$SC_REPO_ROOT/evidence" -o -path "$SC_REPO_ROOT/.git" \) -prune -o \
  -path '*/target/test-reports/*.xml' -type f -print0 2>/dev/null |
  python3 -I -c '
import os, sys
import xml.etree.ElementTree as ET

root, dest, cutoff, max_file, max_total = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
paths = sorted(p for p in sys.stdin.buffer.read().decode().split("\0") if p)
total = copied = 0
for p in paths:
    rel = os.path.relpath(p, root)
    st = os.stat(p)
    if int(st.st_mtime) < cutoff:
        print("SKIP %s: older than the parent commit" % rel); continue
    if st.st_size > max_file:
        print("SKIP %s: larger than SC_EVIDENCE_MAX_FILE_BYTES" % rel); continue
    if total + st.st_size > max_total:
        print("SKIP %s: SC_EVIDENCE_MAX_TOTAL_BYTES reached" % rel); continue
    try:
        tree = ET.parse(p)
    except ET.ParseError:
        print("SKIP %s: not parseable XML" % rel); continue
    for el in tree.getroot().iter():
        for child in list(el):
            if child.tag in ("system-out", "system-err"):
                child.text = "[stripped by archive-evidence.sh]"
                child.attrib.clear()
    out = os.path.join(dest, "tests", rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tree.write(out, encoding="utf-8", xml_declaration=True)
    total += st.st_size
    copied += 1
print("COPIED %d" % copied)
' "$SC_REPO_ROOT" "$DEST" "$PARENT_EPOCH" "$MAX_FILE_BYTES" "$MAX_TOTAL_BYTES")" || die "JUnit report filtering failed"
while IFS= read -r line; do
  case "$line" in
    SKIP\ *) missing "tests-skipped: ${line#SKIP }" ;;
    COPIED\ *) n=$((n + ${line#COPIED })) ;;
  esac
done <<<"$XML_OUT"
[[ "$n" -gt 0 ]] || missing "tests: no test reports found for commit $GIT_SHA"
info "$n test report file(s)"

step "plan checksum"
if [[ -f "$BUILD/plan-summary.json" ]] && commit_matches "$BUILD/plan-summary.json"; then
  cp "$BUILD/plan-summary.json" "$DEST/plan/"
else
  missing "plan: build/plan-summary.json absent or for another commit"
fi
for f in "$SC_PLAN_DIR/$ENV_NAME-$GIT_SHA.tfplan.sha256" "$SC_PLAN_DIR/$ENV_NAME-$GIT_SHA.summary.txt"; do
  if [[ -f "$f" ]]; then cp "$f" "$DEST/plan/"; fi
done

step "image digests and package manifest"
if [[ -f "$BUILD/package-manifest.json" ]] && commit_matches "$BUILD/package-manifest.json"; then
  # Only a complete manifest is a release package manifest. package.sh marks a --skip-jars or
  # --skip-images run ("jarsSkipped", "imagesSkipped", "complete": false); a manifest without the
  # markers predates them and cannot be shown to be complete.
  if python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
ok = d.get("complete") is True and d.get("jarsSkipped") is False and d.get("imagesSkipped") is False and d.get("jars") and d.get("images")
sys.exit(0 if ok else 1)' "$BUILD/package-manifest.json"; then
    cp "$BUILD/package-manifest.json" "$DEST/images/"
  else
    missing "images: build/package-manifest.json is PARTIAL (jars or images skipped, empty, or without completeness markers); rerun scripts/package.sh without --skip-jars/--skip-images"
  fi
else
  missing "images: build/package-manifest.json absent or for another commit"
fi
n=0
for f in "$BUILD"/deploy-record-*.json; do
  [[ -f "$f" ]] || continue
  if python3 -I -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1])).get("gitSha","").startswith(sys.argv[2]) else 1)' "$f" "$GIT_SHA"; then
    cp "$f" "$DEST/images/"
    n=$((n + 1))
  fi
done
[[ "$n" -gt 0 ]] || missing "images: no deploy record for $GIT_SHA (image digests exist only after a deploy)"

step "run manifests"
n=0
for f in "$BUILD"/pipeline-run-*.json; do
  [[ -f "$f" ]] || continue
  if commit_matches "$f"; then cp "$f" "$DEST/runs/"; n=$((n + 1)); fi
done
[[ "$n" -gt 0 ]] || missing "runs: no pipeline run record for $GIT_SHA"

step "experiment outputs"
n=0
declare -a METRIC_SPECS=()
for f in "$BUILD"/experiment-*.json; do
  [[ -f "$f" ]] || continue
  commit_matches "$f" || continue
  cp "$f" "$DEST/experiments/"
  n=$((n + 1))
  raw="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("rawResultDirectory",""))' "$f")"
  # The recorded path is untrusted data: resolve it (symlinks and ".." followed) and require that it
  # lies under the real $BUILD/experiments/ (trailing-slash prefix match) before copying anything.
  raw_real=""
  if [[ -n "$raw" ]]; then raw_real="$(realpath -e -- "$raw" 2>/dev/null)" || raw_real=""; fi
  exp_root="$(realpath -e -- "$BUILD/experiments" 2>/dev/null)" || exp_root=""
  if [[ -n "$raw_real" && -n "$exp_root" && -d "$raw_real" && "$raw_real/" == "$exp_root/"?* ]]; then
    mkdir -p "$DEST/experiments/raw"
    # Regular files only: a symlink inside the raw directory would be followed by the S3 upload.
    (cd "$(dirname "$raw_real")" && find "$(basename "$raw_real")" -type f \
      -exec cp --parents -- {} "$DEST/experiments/raw/" \;)
  else
    missing "experiments: rawResultDirectory '$raw' in $(basename "$f") is absent or outside $BUILD/experiments/; not copied"
  fi
  while IFS= read -r spec; do
    [[ -z "$spec" ]] || METRIC_SPECS+=("$spec")
  done < <(python3 -I -c '
import json, sys
for m in json.load(open(sys.argv[1])).get("metricLocations", []):
    if "namespace" in m and "metric" in m:
        dims = ",".join("%s=%s" % kv for kv in sorted((m.get("dimensions") or {}).items()))
        print("%s:%s:%s" % (m["namespace"], m["metric"], dims))' "$f")
done
[[ "$n" -gt 0 ]] || missing "experiments: no experiment record for $GIT_SHA"
METRIC_SPECS+=("${METRICS[@]}")

step "CloudWatch metric exports (last ${HOURS}h, 300 s period; read-only)"
n=0
idx=0
END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
START="$(date -u -d "$HOURS hours ago" +%Y-%m-%dT%H:%M:%SZ)"
for spec in "${METRIC_SPECS[@]}"; do
  [[ -n "$spec" ]] || continue
  ns="${spec%%:*}"
  rest="${spec#*:}"
  metric="${rest%%:*}"
  dims="${rest#*:}"
  [[ "$rest" == *:* ]] || dims=""
  declare -a dim_args=()
  if [[ -n "$dims" ]]; then
    IFS=',' read -r -a pairs <<<"$dims"
    for p in "${pairs[@]}"; do
      dim_args+=("Name=${p%%=*},Value=${p#*=}")
    done
  fi
  # Names that sanitize identically must not overwrite each other: the file name carries a running
  # index and a hash of the exact specification, and an existing file is a hard error.
  idx=$((idx + 1))
  safe="$(printf '%s_%s_%s' "$ns" "$metric" "$dims" | tr -c 'A-Za-z0-9._\n=-' '_' | cut -c1-80)"
  out_file="$DEST/metrics/$(printf '%03d' "$idx")-$(printf '%s' "$spec" | sha256sum | cut -c1-8)-$safe.json"
  [[ ! -e "$out_file" ]] || die "refusing to overwrite $out_file"
  if [[ "${#dim_args[@]}" -gt 0 ]]; then
    retry 3 aws_call cloudwatch get-metric-statistics --namespace "$ns" --metric-name "$metric" \
      --dimensions "${dim_args[@]}" --start-time "$START" --end-time "$END" --period 300 \
      --statistics Average Maximum Sum --output json >"$out_file"
  else
    retry 3 aws_call cloudwatch get-metric-statistics --namespace "$ns" --metric-name "$metric" \
      --start-time "$START" --end-time "$END" --period 300 --statistics Average Maximum Sum \
      --output json >"$out_file"
  fi
  unset dim_args
  n=$((n + 1))
done
[[ "$n" -gt 0 ]] || missing "metrics: no --metric given and no experiment metric locations"
info "$n metric export(s)"

step "release metadata"
python3 -I - "$DEST/release/release-metadata.json" "$GIT_SHA" "$(git -C "$SC_REPO_ROOT" rev-parse "$GIT_SHA")" \
  "$(git -C "$SC_REPO_ROOT" log -1 --format=%s "$GIT_SHA")" "$(git -C "$SC_REPO_ROOT" log -1 --format=%cI "$GIT_SHA")" \
  "$(git -C "$SC_REPO_ROOT" status --porcelain | wc -l | tr -d ' ')" "$(utc_now)" "$ENV_NAME" "$SC_REGION" \
  "$(terraform version 2>/dev/null | head -n 1 || true)" "$(git -C "$SC_REPO_ROOT" describe --tags --always "$GIT_SHA")" <<'PY'
import json
import sys

out, short, full, subject, committed, dirty, now, env, region, tf, describe = sys.argv[1:12]
doc = {"commitSha": short, "commitShaFull": full, "subject": subject, "committedAt": committed,
       "describe": describe, "workingTreeChangedFiles": int(dirty), "archivedAtUtc": now,
       "environment": env, "region": region, "terraform": tf}
with open(out, "w") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")
PY

step "evidence location and size check"
if [[ -n "$EVIDENCE_S3_PREFIX" ]]; then
  printf 's3://%s/%s\n' "$BUCKET" "$KEY" >"$DEST/release/evidence-location.txt"
else
  printf 'local-only\n' >"$DEST/release/evidence-location.txt"
fi
ARCHIVE_BYTES="$(du -sb "$DEST" | cut -f1)"
[[ "$ARCHIVE_BYTES" -le "$MAX_ARCHIVE_BYTES" ]] ||
  die "the archive is $ARCHIVE_BYTES bytes, over SC_EVIDENCE_MAX_ARCHIVE_BYTES ($MAX_ARCHIVE_BYTES); nothing was uploaded"

step "checksums"
SUMS_TMP="$(mktemp)"
(cd "$DEST" && find . -type f -print0 | sort -z | xargs -0 sha256sum >"$SUMS_TMP")
mv "$SUMS_TMP" "$DEST/SHA256SUMS"
info "$(wc -l <"$DEST/SHA256SUMS") file(s) checksummed in $DEST/SHA256SUMS"

if [[ -n "$EVIDENCE_S3_PREFIX" ]]; then
  step "upload to s3://$BUCKET/$KEY (idempotent: same keys, same files)"
  SC_AWS_TIMEOUT=600 retry 3 aws_call s3 cp "$DEST" "s3://$BUCKET/$KEY" --recursive --only-show-errors
  info "EVIDENCE_LOCATION=s3://$BUCKET/$KEY"
else
  info "EVIDENCE_LOCATION=$DEST"
fi
step "done"
