#!/usr/bin/env bash
# run-bounded-pipeline.sh - run the Hadoop (and optionally Spark) pipeline, locally or on EMR.
#
#   scripts/run-bounded-pipeline.sh --local [--name <run name>] [--stages hadoop[,spark]]
#   scripts/run-bounded-pipeline.sh --env dev [--git-sha <sha>] [--wait-timeout <seconds>]
#                                   [--profile <name>] [--region <region>]
#
# --local: no AWS. Runs the packaged-source drivers through sbt on the committed samples
#          (datasets/samples), writing under target/pipeline-local/<name>/ (git-ignored).
#          The spark stage uses modules/spark/config/local-run.properties exactly as the Spark
#          module documents (its input root and run ID must exist from an earlier hadoop run).
# --env:   cloud run through the Step Functions state machine created by Terraform when
#          enable_emr=true. The jars are built from the clean HEAD (sbt packageJobs), uploaded to
#          the artifacts bucket under jobs/<git sha>/, and the execution is bounded by the state
#          machine's per-step timeouts and by this script's --wait-timeout. The input volume is
#          bounded by the configured DBLP_SUBSET_MAX_WORKS and the raw prefixes, never here.
#
# The cluster itself is never created or resized here: it must already exist (enable_emr).
#
# Required environment variables: none for --local. For --env: SCHOLARCHAIN_APPROVED_ENVS (set
#   by the human; must list --env). AWS credentials come from the standard chain or --profile
#   (AWS_PROFILE). Bucket names are derived from the Terraform naming convention
#   scholarchain-<env>-<area>-<account>-<region>; SC_ARTIFACTS_BUCKET, SC_RAW_BUCKET and
#   SC_CURATED_BUCKET override them.
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION, or the profile's configured Region
#   (cloud only); there is no built-in default.
# Exit codes: 0 pipeline succeeded, 1 refused or failed (the execution is stopped at the wait
#   timeout), 2 usage error, 130/143 interrupted by INT/TERM (the cloud path still stops the Step
#   Functions execution and writes the run record before exiting).
# Human approval points (cloud): the EMR cost and bounds (cluster size, managed-scaling limits,
#   per-step timeouts, --wait-timeout) and the approved environment list, before /run-pipeline.
# Evidence: build/pipeline-run-<name>.json with the dataset snapshots (from
#   datasets/dataset-manifest.yaml), the commit SHA, the run IDs (execution ARN and name), the
#   configuration (stages, step arguments, timeouts) and the output S3 prefixes or local
#   directories, plus start and end UTC times and final status.
# Rollback behavior: none for data. A run writes under a prefix keyed by the commit SHA and
#   never overwrites an earlier run's prefix; a run that exceeds --wait-timeout is stopped.
# Signals and failures (cloud): an EXIT/TERM/INT trap stops the Step Functions execution if it is
#   still RUNNING (idempotent, 30 s timeout per call) and writes the run record with the status
#   (SUCCEEDED, STOPPED_AT_WAIT_TIMEOUT, STOPPED_ON_ERROR_OR_SIGNAL, STOP_FAILED_STILL_RUNNING,
#   UNKNOWN or the Step Functions status). If the stop call itself fails the record says so and
#   the human must stop the execution (its ARN is in the record and on the EXECUTION_ARN line).
# Needs python3 with PyYAML (preflight-checked) to write the run record.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

MODE=""
ENV_NAME=""
RUN_NAME=""
STAGES="hadoop"
GIT_SHA=""
SC_PROFILE=""
SC_REGION=""
WAIT_TIMEOUT=14400
POLL_SECONDS=30

# shellcheck disable=SC2034  # SC_PROFILE is read by aws_call in lib/common.sh
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) MODE="local"; shift ;;
    --env) ENV_NAME="${2:?--env needs a value}"; MODE="cloud"; shift 2 ;;
    --name) RUN_NAME="${2:?--name needs a value}"; shift 2 ;;
    --stages) STAGES="${2:?--stages needs hadoop[,spark]}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?--git-sha needs a value}"; shift 2 ;;
    --wait-timeout) WAIT_TIMEOUT="${2:?--wait-timeout needs seconds}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$MODE" ]] || usage_error "give --local or --env <name>"
[[ "$STAGES" =~ ^(hadoop|spark)(,(hadoop|spark))*$ ]] || usage_error "--stages must be hadoop, spark or hadoop,spark"
[[ "$WAIT_TIMEOUT" =~ ^[0-9]+$ ]] || usage_error "--wait-timeout must be a number of seconds"
[[ "$MODE" == "local" ]] || resolve_region
require_cmd git timeout python3
# The run record needs PyYAML; fail before any work rather than after a long run.
python3 -I -c 'import yaml' 2>/dev/null || die "python3 module 'yaml' (PyYAML) is required to write the run record; install it first"

# write_run_record <file> <name> <started> <status> <stages> <run ids json> <config json> <outputs json>
# Writes the run record: dataset snapshots come from datasets/dataset-manifest.yaml.
write_run_record() {
  mkdir -p "$SC_BUILD_DIR"
  python3 -I - "$SC_REPO_ROOT/datasets/dataset-manifest.yaml" "$@" <<'PY'
import json
import sys

import yaml

manifest, out, name, mode, sha, started, ended, status, stages, run_ids, config, outputs, region = sys.argv[1:14]
doc = yaml.safe_load(open(manifest))
snapshots = {}
for ds in doc.get("selected_datasets", []):
    snap = (doc["datasets"].get(ds) or {}).get("snapshot") or {}
    snapshots[ds] = {"date": snap.get("date"), "label": snap.get("label")}
record = {
    "name": name, "mode": mode, "commitSha": sha, "region": region or None,
    "startedAtUtc": started, "endedAtUtc": ended, "status": status,
    "stages": stages.split(","),
    "datasetSnapshots": snapshots,
    "runIds": json.loads(run_ids),
    "configuration": json.loads(config),
    "outputs": json.loads(outputs),
}
with open(out, "w") as fh:
    json.dump(record, fh, indent=2)
    fh.write("\n")
PY
}

local_done=true
# local_failed <name> <sha> <started> <out>: EXIT trap of the local run; writes a FAILED record
# unless the run completed.
local_failed() {
  local rc=$?
  [[ "$local_done" == true ]] && return 0
  set +e
  write_run_record "$SC_BUILD_DIR/pipeline-run-$1.json" "$1" local "$2" "$3" "$(utc_now)" FAILED "$STAGES" \
    "{\"localRunName\": \"$1\"}" "{\"stages\": \"$STAGES\", \"exitCode\": $rc}" "{\"hadoop\": \"$SC_REPO_ROOT/$4/hadoop\"}" "" ||
    warn "could not write the FAILED run record"
  return 0
}

run_local() {
  require_cmd sbt
  [[ -n "$RUN_NAME" ]] || RUN_NAME="local-$(head_sha)"
  [[ "$RUN_NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die "--name may contain only letters, digits, . _ -"
  local out="target/pipeline-local/$RUN_NAME"
  local sha started
  sha="$(head_sha)"
  started="$(utc_now)"
  local_done=false
  # A failed or interrupted local run still leaves a record, with status FAILED.
  # shellcheck disable=SC2064  # values are fixed now on purpose
  trap "local_failed '$RUN_NAME' '$sha' '$started' '$out'" EXIT

  if [[ ",$STAGES," == *",hadoop,"* ]]; then
    step "local Hadoop stage on datasets/samples -> $out/hadoop"
    (
      cd "$SC_REPO_ROOT"
      SCHOLARCHAIN_ENV=dev \
        SCHOLARCHAIN_HADOOP_NUM_REDUCERS=2 \
        SCHOLARCHAIN_HADOOP_DBLP_DTD_PATH=datasets/samples/dblp/dblp-2023-06-28.dtd \
        timeout --foreground 3600 sbt -batch \
        "hadoop/runMain scholarchain.hadoop.driver.PipelineDriver --local --input-root datasets/samples --output-root $out/hadoop --code-version $sha"
    )
  fi

  if [[ ",$STAGES," == *",spark,"* ]]; then
    step "local Spark stage with modules/spark/config/local-run.properties"
    (
      cd "$SC_REPO_ROOT"
      declare -a pairs=()
      while IFS= read -r line; do
        [[ -z "$line" || "$line" == \#* ]] && continue
        pairs+=("$line")
      done <modules/spark/config/local-run.properties
      env "${pairs[@]}" timeout --foreground 3600 sbt -batch \
        "sparkAnalytics/runMain scholarchain.spark.run.GraphPipeline --local --code-version $sha"
    )
  fi
  local_done=true
  local record="$SC_BUILD_DIR/pipeline-run-$RUN_NAME.json"
  write_run_record "$record" "$RUN_NAME" local "$sha" "$started" "$(utc_now)" SUCCEEDED "$STAGES" \
    "{\"localRunName\": \"$RUN_NAME\"}" \
    "{\"stages\": \"$STAGES\", \"hadoopReducers\": 2, \"inputRoot\": \"datasets/samples\", \"sparkConfig\": \"modules/spark/config/local-run.properties\"}" \
    "{\"hadoop\": \"$SC_REPO_ROOT/$out/hadoop\"}" ""
  info "PIPELINE_RUN_RECORD=$record"
  step "local run complete: outputs under $SC_REPO_ROOT/$out"
}

# ---- cloud run bookkeeping: shared by the normal path and the EXIT/TERM/INT trap ----
C_EXEC_ARN=""
C_EXEC_NAME=""
C_STARTED=""
C_MACHINE_ARN=""
C_PREFIX=""
C_BUCKET_ARTIFACTS=""
C_BUCKET_RAW=""
C_BUCKET_CURATED=""
C_RECORD=""
C_STATUS="UNKNOWN"
C_REASON=""
C_FINALIZED=false
C_INPUT=""

# cloud_finalize: idempotent. If the Step Functions execution is still RUNNING (wait timeout,
# error, or signal) it is stopped (30 s timeout per call), then the run record is written with the
# resulting status. Safe to call from the trap and from the normal path; the second call is a no-op.
cloud_finalize() {
  [[ "$C_FINALIZED" == false && -n "$C_EXEC_ARN" ]] || return 0
  C_FINALIZED=true
  local st="" rc=0
  case "$C_STATUS" in
    SUCCEEDED | FAILED | TIMED_OUT | ABORTED) st="$C_STATUS" ;;
    *) st="$(SC_AWS_TIMEOUT=30 aws_call stepfunctions describe-execution --execution-arn "$C_EXEC_ARN" \
      --query status --output text 2>/dev/null)" || st="" ;;
  esac
  if [[ "$st" == "RUNNING" ]]; then
    [[ -n "$C_REASON" ]] || C_REASON="error-or-signal"
    warn "stopping the Step Functions execution $C_EXEC_ARN ($C_REASON)"
    if SC_AWS_TIMEOUT=30 aws_call stepfunctions stop-execution --execution-arn "$C_EXEC_ARN" \
      --cause "run-bounded-pipeline: $C_REASON" >/dev/null; then
      if [[ "$C_REASON" == "wait-timeout" ]]; then C_STATUS="STOPPED_AT_WAIT_TIMEOUT"; else C_STATUS="STOPPED_ON_ERROR_OR_SIGNAL"; fi
    else
      C_STATUS="STOP_FAILED_STILL_RUNNING"
      warn "stop-execution FAILED; stop it by hand: aws stepfunctions stop-execution --execution-arn $C_EXEC_ARN"
    fi
  elif [[ -n "$st" ]]; then
    C_STATUS="$st"
  else
    C_STATUS="UNKNOWN"
    warn "could not read the execution status; check $C_EXEC_ARN by hand"
  fi
  write_run_record "$C_RECORD" "$C_EXEC_NAME" cloud "$GIT_SHA" "$C_STARTED" "$(utc_now)" "$C_STATUS" "$STAGES" \
    "{\"executionArn\": \"$C_EXEC_ARN\", \"executionName\": \"$C_EXEC_NAME\"}" \
    "{\"stateMachineArn\": \"$C_MACHINE_ARN\", \"waitTimeoutSeconds\": $WAIT_TIMEOUT, \"pollSeconds\": $POLL_SECONDS, \"jars\": \"s3://$C_BUCKET_ARTIFACTS/$C_PREFIX/\", \"stepInputIsRecorded\": \"executionArn input\", \"stopReason\": \"$C_REASON\"}" \
    "{\"hadoop\": \"s3://$C_BUCKET_CURATED/hadoop/$GIT_SHA/\", \"artifacts\": \"s3://$C_BUCKET_ARTIFACTS/$C_PREFIX/\", \"rawInput\": \"s3://$C_BUCKET_RAW/\"}" \
    "$SC_REGION" || rc=$?
  [[ "$rc" -eq 0 ]] || warn "could not write the run record $C_RECORD (status was $C_STATUS)"
  return "$rc"
}

# cloud_exit <exit status>: EXIT trap of the cloud run (TERM and INT convert to exit 143/130 first).
cloud_exit() {
  local rc="$1"
  trap - EXIT TERM INT
  set +e
  [[ -z "$C_INPUT" ]] || rm -f "$C_INPUT"
  cloud_finalize || { [[ "$rc" -ne 0 ]] || rc=1; }
  exit "$rc"
}

run_cloud() {
  validate_env_name "$ENV_NAME"
  require_cmd aws sbt python3
  step "refuse a dirty tree and an unapproved environment"
  require_clean_tree
  require_approved_env "$ENV_NAME"
  local head
  head="$(head_sha)"
  [[ -n "$GIT_SHA" ]] || GIT_SHA="$head"
  [[ "$GIT_SHA" == "$head" ]] || die "--git-sha $GIT_SHA is not HEAD ($head)"

  step "resolve the account, buckets and state machine"
  local account bucket_artifacts bucket_raw bucket_curated machine_arn
  account="$(account_id)"
  [[ "$account" =~ ^[0-9]{12}$ ]] || die "could not resolve the AWS account"
  bucket_artifacts="$(bucket_name "$ENV_NAME" artifacts "$account")"
  bucket_raw="$(bucket_name "$ENV_NAME" raw "$account")"
  bucket_curated="$(bucket_name "$ENV_NAME" curated "$account")"
  machine_arn="arn:aws:states:$SC_REGION:$account:stateMachine:scholarchain-$ENV_NAME-bounded-pipeline"
  retry 3 aws_call stepfunctions describe-state-machine --state-machine-arn "$machine_arn" >/dev/null ||
    die "state machine not found: $machine_arn (apply with enable_emr=true first, human-only)"

  step "package the jobs from HEAD ($GIT_SHA)"
  (cd "$SC_REPO_ROOT" && timeout --foreground 3600 sbt -batch packageJobs)
  local hadoop_jar spark_jar
  hadoop_jar="$(newest_file "$SC_REPO_ROOT/modules/hadoop/target" 'scholarchain-hadoop-assembly-*.jar')"
  spark_jar="$(newest_file "$SC_REPO_ROOT/modules/spark/target" 'scholarchain-spark-analytics-assembly-*.jar')"
  [[ -f "$hadoop_jar" && -f "$spark_jar" ]] || die "assembly jars not found after packageJobs"

  step "upload the jars to s3://$bucket_artifacts/jobs/$GIT_SHA/ (idempotent: same key, same file)"
  local prefix="jobs/$GIT_SHA"
  retry 3 aws_call s3 cp "$hadoop_jar" "s3://$bucket_artifacts/$prefix/$(basename "$hadoop_jar")" --only-show-errors
  retry 3 aws_call s3 cp "$spark_jar" "s3://$bucket_artifacts/$prefix/$(basename "$spark_jar")" --only-show-errors

  step "start the bounded execution (not retried: a second start would be a second run)"
  local input exec_name started
  started="$(utc_now)"
  exec_name="run-$GIT_SHA-$(date -u +%Y%m%dT%H%M%SZ)"
  input="$(mktemp)"
  C_INPUT="$input"
  python3 -I - "$input" "$bucket_artifacts" "$prefix" "$(basename "$hadoop_jar")" "$(basename "$spark_jar")" \
    "$bucket_raw" "$bucket_curated" "$GIT_SHA" <<'PY'
import json
import sys

out, artifacts, prefix, hjar, sjar, raw, curated, sha = sys.argv[1:9]
doc = {
    "hadoopStepArgs": [
        "hadoop", "jar", f"s3://{artifacts}/{prefix}/{hjar}",
        "scholarchain.hadoop.driver.PipelineDriver",
        "--input-root", f"s3://{raw}/", "--output-root", f"s3://{curated}/hadoop/{sha}/",
        "--code-version", sha,
    ],
    "sparkStepArgs": [
        "spark-submit", "--deploy-mode", "cluster",
        "--class", "scholarchain.spark.run.GraphPipeline",
        f"s3://{artifacts}/{prefix}/{sjar}", "--code-version", sha,
    ],
}
json.dump(doc, open(out, "w"))
PY
  # The execution ARN is derivable from the name, so a signal that arrives before start-execution
  # returns can still find and stop the execution.
  C_EXEC_ARN="arn:aws:states:$SC_REGION:$account:execution:scholarchain-$ENV_NAME-bounded-pipeline:$exec_name"
  C_EXEC_NAME="$exec_name"
  C_STARTED="$started"
  C_MACHINE_ARN="$machine_arn"
  C_PREFIX="$prefix"
  C_BUCKET_ARTIFACTS="$bucket_artifacts"
  C_BUCKET_RAW="$bucket_raw"
  C_BUCKET_CURATED="$bucket_curated"
  C_RECORD="$SC_BUILD_DIR/pipeline-run-$ENV_NAME-$exec_name.json"
  C_STATUS="UNKNOWN"
  trap 'cloud_exit $?' EXIT
  trap 'exit 143' TERM
  trap 'exit 130' INT
  local exec_arn
  exec_arn="$(aws_call stepfunctions start-execution --state-machine-arn "$machine_arn" --name "$exec_name" \
    --input "file://$input" --query executionArn --output text)"
  C_EXEC_ARN="$exec_arn"
  C_STATUS="RUNNING"
  info "EXECUTION_ARN=$exec_arn"

  step "wait for the execution (poll every ${POLL_SECONDS}s, give up after ${WAIT_TIMEOUT}s)"
  local waited=0 status=""
  while [[ "$waited" -lt "$WAIT_TIMEOUT" ]]; do
    status="$(retry 3 aws_call stepfunctions describe-execution --execution-arn "$exec_arn" --query status --output text)"
    C_STATUS="$status"
    info "status: $status (${waited}s)"
    [[ "$status" != "RUNNING" ]] && break
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
  done
  if [[ "$status" == "RUNNING" || -z "$status" ]]; then
    warn "still running after ${WAIT_TIMEOUT}s; stopping the execution"
    C_REASON="wait-timeout"
  fi

  step "stop if still running, then record the run"
  cloud_finalize
  info "PIPELINE_RUN_RECORD=$C_RECORD"
  [[ "$C_REASON" != "wait-timeout" ]] || die "execution stopped at the wait timeout"
  [[ "$C_STATUS" == "SUCCEEDED" ]] || die "execution ended with status $C_STATUS ($exec_arn)"
  step "pipeline succeeded: outputs under s3://$bucket_curated/hadoop/$GIT_SHA/"
}

case "$MODE" in
  local) run_local ;;
  cloud) run_cloud ;;
esac
