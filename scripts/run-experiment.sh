#!/usr/bin/env bash
# run-experiment.sh - run one controlled experiment from a committed manifest and record it.
#
#   scripts/run-experiment.sh --env dev --manifest experiments/<dir>/<name>.yaml
#                             [--profile <name>] [--region <region>]
#
# The manifest path must resolve (symlinks and ".." followed) to a regular file under the
# repository's experiments/ directory and must be tracked by Git; anything else is refused.
# The tree must be clean and the environment approved.
#
# Approval gate (both runners): the run is refused unless approval.status is APPROVED with a named
# approver and a YYYY-MM-DD date, `blocked` is the boolean false and no precondition starts with
# BLOCKED. The rules are approval_problems() in load-tests/sclib.py, the same ones the load tools
# apply. The manifest's `environment` must equal --env.
#
# Manifest (YAML or JSON), required keys:
#   name:      letters, digits, . _ -
#   runner:    "pipeline"   runs scripts/run-bounded-pipeline.sh --env <env> (cloud, bounded)
#              "load-test"  runs one executable file under load-tests/ (path relative to
#                           load-tests/, no ".." and no symlink out of it)
#   limits:    resource limits, recorded verbatim in the run record. Required integer keys:
#                max_duration_seconds (hard timeout of the run, 1..86400)
#              Optional integer keys (recorded; the Terraform-set bounds still apply):
#                emr_max_nodes, ecs_max_tasks, sqs_max_workers
#   args:      optional list of strings passed to the load-test file (never to a shell)
#   metrics:   optional list of {namespace, metric, dimensions} CloudWatch locations, and/or
#              {s3_prefix} output locations; recorded verbatim for scripts/archive-evidence.sh
#
# The runner receives SC_EXPERIMENT_OUT, a directory under build/experiments/ for its raw
# results; raw results are never edited by this script.
# A load-test runner also receives SC_ENV (the --env value), SC_REGION and, with --profile,
# AWS_PROFILE. It gets no --env or --profile arguments: the load tools accept only the manifest's
# `args`. --profile is refused while static credential variables are set, because the AWS CLI
# would use those instead of the profile.
#
# Required environment variables: SCHOLARCHAIN_APPROVED_ENVS (set by the human; must list
#   --env). AWS credentials come from the standard chain or --profile (AWS_PROFILE) when the
#   runner uses AWS. A load-test runner may need its own variables (for example a base URL and
#   SCHOLARCHAIN_API_TOKEN); they are passed through the environment, never printed.
# Timeouts: the runner is bounded by max_duration_seconds (TERM, then KILL 60 s later; the whole
#   process group is signalled). The pipeline runner gets an inner wait timeout shorter by a margin
#   (duration/10, 30..300 s) so it stops its Step Functions execution and records the run first.
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; no default.
# Exit codes: 0 experiment ran and the runner exited 0, 1 refused or runner failed (including
#   the max_duration_seconds timeout: runner exit 124 or 137 is recorded as timedOut), 2 usage error.
# Human approval points: the approved environment list and the approved manifest (large
#   manifests and EMR or OpenSearch scale changes need human sign-off before /experiment).
# Evidence: build/experiment-<name>-<commit>-<start>.json with commit SHA, resource limits, start
#   and end UTC times, Region, runner exit code, raw result directory and metric locations.
# Rollback behavior: none. The script changes no infrastructure; stopping a runaway run is the
#   timeout. Data written by the runner is never deleted here.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
MANIFEST_ARG=""
SC_PROFILE=""
SC_REGION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --manifest) MANIFEST_ARG="${2:?--manifest needs a path}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
[[ -n "$MANIFEST_ARG" ]] || usage_error "--manifest is required"
validate_env_name "$ENV_NAME"
resolve_region
require_cmd git python3 realpath timeout

step "refuse a dirty tree and an unapproved environment"
require_clean_tree
require_approved_env "$ENV_NAME"

step "resolve the manifest and refuse anything outside experiments/"
EXP_ROOT="$(realpath -e "$SC_REPO_ROOT/experiments" 2>/dev/null)" || die "no experiments/ directory in the repository"
# realpath -e follows symlinks and removes ".."; the result must lie under experiments/.
MANIFEST="$(realpath -e -- "$MANIFEST_ARG" 2>/dev/null)" || die "manifest not found: $MANIFEST_ARG"
case "$MANIFEST" in
  "$EXP_ROOT"/*) ;;
  *) die "manifest resolves to $MANIFEST, which is not under $EXP_ROOT; refused" ;;
esac
[[ -f "$MANIFEST" ]] || die "manifest is not a regular file: $MANIFEST"
git -C "$SC_REPO_ROOT" ls-files --error-unmatch -- "${MANIFEST#"$SC_REPO_ROOT"/}" >/dev/null 2>&1 ||
  die "manifest is not tracked by Git; commit it first so the run is reproducible"
info "manifest: ${MANIFEST#"$SC_REPO_ROOT"/}"

step "validate the manifest"
PARSED="$(python3 -I - "$MANIFEST" <<'PY'
import json
import re
import sys

import yaml

doc = yaml.safe_load(open(sys.argv[1]))
if not isinstance(doc, dict):
    sys.exit("manifest must be a mapping")
name = doc.get("name")
if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", name):
    sys.exit("name must match [A-Za-z0-9._-]+")
runner = doc.get("runner")
if runner not in ("pipeline", "load-test"):
    sys.exit("runner must be 'pipeline' or 'load-test'")
limits = doc.get("limits")
if not isinstance(limits, dict):
    sys.exit("limits must be a mapping")
dur = limits.get("max_duration_seconds")
if not isinstance(dur, int) or isinstance(dur, bool) or not 1 <= dur <= 86400:
    sys.exit("limits.max_duration_seconds must be an integer 1..86400")
for k in ("emr_max_nodes", "ecs_max_tasks", "sqs_max_workers"):
    v = limits.get(k)
    if v is not None and (not isinstance(v, int) or isinstance(v, bool) or v < 0):
        sys.exit("limits.%s must be a non-negative integer" % k)
args = doc.get("args", [])
if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
    sys.exit("args must be a list of strings")
target = doc.get("target", "")
if runner == "load-test":
    if not isinstance(target, str) or not target or target.startswith("/") or ".." in target.split("/"):
        sys.exit("target must be a relative path under load-tests/ without '..'")
print(json.dumps({"name": name, "runner": runner, "duration": dur, "args": args, "target": target,
                  "limits": limits, "metrics": doc.get("metrics", [])}))
PY
)" || die "invalid manifest ${MANIFEST#"$SC_REPO_ROOT"/}"

step "refuse an unapproved or blocked manifest, or one for another environment"
python3 -I - "$MANIFEST" "$SC_REPO_ROOT/load-tests" "$ENV_NAME" <<'PY' || die "the manifest may not run; refused"
import sys

import yaml

manifest, load_tests, env = sys.argv[1:4]
sys.dont_write_bytecode = True
sys.path.insert(0, load_tests)

import sclib  # noqa: E402

with open(manifest) as handle:
    doc = yaml.safe_load(handle)
problems = sclib.approval_problems(doc)
if doc.get("environment") != env:
    problems.append("the manifest `environment` is not the --env value")
for problem in problems:
    print("ERROR: " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
PY

jget() { python3 -I -c 'import json,sys; v=json.loads(sys.argv[1])[sys.argv[2]]; print(v if isinstance(v,(str,int)) else json.dumps(v))' "$PARSED" "$1"; }
NAME="$(jget name)"
RUNNER="$(jget runner)"
DURATION="$(jget duration)"
SHA="$(head_sha)"
STARTED="$(utc_now)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$SC_BUILD_DIR/experiments/$NAME-$SHA-$STAMP"
RECORD="$SC_BUILD_DIR/experiment-$NAME-$SHA-$STAMP.json"
mkdir -p "$OUT_DIR"
info "experiment $NAME runner $RUNNER, hard timeout ${DURATION}s, raw results in $OUT_DIR"

# Timeout layering. The runner gets max_duration_seconds as a hard limit enforced by timeout(1).
# The pipeline runner also gets an inner --wait-timeout that is SHORTER by a margin, so it stops
# its own Step Functions execution and writes its run record before the outer limit fires:
#   inner = duration - margin (margin = duration/10, clamped to 30..300 s; half the duration if
#   that would leave under 30 s), outer TERM at duration, outer KILL KILL_AFTER seconds later.
# timeout runs without --foreground, so it puts the runner in its own process group and signals
# the whole group; children of a load-test runner cannot outlive the limit.
MARGIN=$((DURATION / 10))
[[ "$MARGIN" -ge 30 ]] || MARGIN=30
[[ "$MARGIN" -le 300 ]] || MARGIN=300
INNER_WAIT=$((DURATION - MARGIN))
[[ "$INNER_WAIT" -ge 30 ]] || INNER_WAIT=$((DURATION / 2))
[[ "$INNER_WAIT" -ge 1 ]] || INNER_WAIT=1
KILL_AFTER=60

declare -a CMD
case "$RUNNER" in
  pipeline)
    CMD=("$SC_REPO_ROOT/scripts/run-bounded-pipeline.sh" --env "$ENV_NAME" --region "$SC_REGION" --wait-timeout "$INNER_WAIT")
    [[ -z "$SC_PROFILE" ]] || CMD+=(--profile "$SC_PROFILE")
    ;;
  load-test)
    LT_ROOT="$(realpath -e "$SC_REPO_ROOT/load-tests" 2>/dev/null)" || die "no load-tests/ directory in the repository"
    LT_FILE="$(realpath -e -- "$LT_ROOT/$(jget target)" 2>/dev/null)" || die "load-test target not found"
    case "$LT_FILE" in "$LT_ROOT"/*) ;; *) die "load-test target resolves outside load-tests/; refused" ;; esac
    [[ -f "$LT_FILE" && -x "$LT_FILE" ]] || die "load-test target is not an executable file"
    git -C "$SC_REPO_ROOT" ls-files --error-unmatch -- "${LT_FILE#"$SC_REPO_ROOT"/}" >/dev/null 2>&1 ||
      die "load-test target is not tracked by Git"
    CMD=("$LT_FILE")
    # The load tools take the environment and the profile from variables, not from arguments.
    export SC_ENV="$ENV_NAME"
    if [[ -n "$SC_PROFILE" ]]; then
      [[ -z "${AWS_ACCESS_KEY_ID:-}" ]] ||
        die "--profile given while AWS_ACCESS_KEY_ID is set and would take precedence; unset it first"
      export AWS_PROFILE="$SC_PROFILE"
    fi
    mapfile -t EXTRA < <(python3 -I -c 'import json,sys; print("\n".join(json.loads(sys.argv[1])["args"]))' "$PARSED")
    for a in "${EXTRA[@]}"; do [[ -z "$a" ]] || CMD+=("$a"); done
    ;;
esac

step "run the experiment (not retried: a second run is a new experiment)"
RC=0
SC_EXPERIMENT_OUT="$OUT_DIR" SC_REGION="$SC_REGION" \
  timeout --signal=TERM --kill-after="$KILL_AFTER" "$DURATION" "${CMD[@]}" || RC=$?
ENDED="$(utc_now)"
# 124: TERM at the limit; 137: the runner ignored TERM and was killed KILL_AFTER seconds later.
if [[ "$RC" -eq 124 || "$RC" -eq 137 ]]; then warn "the run hit its ${DURATION}s limit and was stopped (exit $RC)"; fi

step "record the run"
python3 -I - "$RECORD" "$PARSED" "$SHA" "$ENV_NAME" "$SC_REGION" "$STARTED" "$ENDED" "$RC" "$OUT_DIR" \
  "${MANIFEST#"$SC_REPO_ROOT"/}" "$INNER_WAIT" <<'PY'
import json
import sys

out, parsed, sha, env, region, started, ended, rc, out_dir, manifest, inner = sys.argv[1:12]
p = json.loads(parsed)
record = {
    "experiment": p["name"], "manifest": manifest, "runner": p["runner"], "environment": env,
    "commitSha": sha, "region": region, "startedAtUtc": started, "endedAtUtc": ended,
    "runnerExitCode": int(rc), "timedOut": int(rc) in (124, 137),
    "innerWaitTimeoutSeconds": int(inner) if p["runner"] == "pipeline" else None,
    "resourceLimits": p["limits"], "rawResultDirectory": out_dir, "metricLocations": p["metrics"],
}
with open(out, "w") as fh:
    json.dump(record, fh, indent=2)
    fh.write("\n")
PY
cat "$RECORD"
info "EXPERIMENT_RECORD=$RECORD"
[[ "$RC" -eq 0 ]] || die "runner exited with status $RC"
step "experiment complete"
