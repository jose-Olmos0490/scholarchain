#!/usr/bin/env bash
# run-bounded-pipeline.sh - run the Hadoop (and optionally Spark) pipeline, locally or on EMR.
#
#   scripts/run-bounded-pipeline.sh --local [--name <run name>] [--stages hadoop[,spark]]
#   scripts/run-bounded-pipeline.sh --env dev [--git-sha <sha>] [--wait-timeout <seconds>]
#                                   [--profile <name>] [--region us-east-1]
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
SC_REGION="us-east-1"
WAIT_TIMEOUT=14400
POLL_SECONDS=30

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
    -h | --help) sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$MODE" ]] || die "give --local or --env <name>"
[[ "$STAGES" =~ ^(hadoop|spark)(,(hadoop|spark))*$ ]] || die "--stages must be hadoop, spark or hadoop,spark"
[[ "$WAIT_TIMEOUT" =~ ^[0-9]+$ ]] || die "--wait-timeout must be a number of seconds"
require_cmd git timeout

run_local() {
  require_cmd sbt
  [[ -n "$RUN_NAME" ]] || RUN_NAME="local-$(head_sha)"
  [[ "$RUN_NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die "--name may contain only letters, digits, . _ -"
  local out="target/pipeline-local/$RUN_NAME"
  local sha
  sha="$(head_sha)"

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
  step "local run complete: outputs under $SC_REPO_ROOT/$out"
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
  local suffix="$account-$SC_REGION"
  bucket_artifacts="scholarchain-$ENV_NAME-artifacts-$suffix"
  bucket_raw="scholarchain-$ENV_NAME-raw-$suffix"
  bucket_curated="scholarchain-$ENV_NAME-curated-$suffix"
  machine_arn="arn:aws:states:$SC_REGION:$account:stateMachine:scholarchain-$ENV_NAME-bounded-pipeline"
  aws_call stepfunctions describe-state-machine --state-machine-arn "$machine_arn" >/dev/null ||
    die "state machine not found: $machine_arn (apply with enable_emr=true first, human-only)"

  step "package the jobs from HEAD ($GIT_SHA)"
  (cd "$SC_REPO_ROOT" && timeout --foreground 3600 sbt -batch packageJobs)
  local hadoop_jar spark_jar
  hadoop_jar="$(find "$SC_REPO_ROOT/modules/hadoop/target" -name 'scholarchain-hadoop-assembly-*.jar' -printf '%T@ %p\n' | sort -rn | head -n 1 | cut -d' ' -f2-)"
  spark_jar="$(find "$SC_REPO_ROOT/modules/spark/target" -name 'scholarchain-spark-analytics-assembly-*.jar' -printf '%T@ %p\n' | sort -rn | head -n 1 | cut -d' ' -f2-)"
  [[ -f "$hadoop_jar" && -f "$spark_jar" ]] || die "assembly jars not found after packageJobs"

  step "upload the jars to s3://$bucket_artifacts/jobs/$GIT_SHA/"
  local prefix="jobs/$GIT_SHA"
  aws_call s3 cp "$hadoop_jar" "s3://$bucket_artifacts/$prefix/$(basename "$hadoop_jar")" --only-show-errors
  aws_call s3 cp "$spark_jar" "s3://$bucket_artifacts/$prefix/$(basename "$spark_jar")" --only-show-errors

  step "start the bounded execution"
  local input exec_name
  exec_name="run-$GIT_SHA-$(date -u +%Y%m%dT%H%M%SZ)"
  input="$(mktemp)"
  trap 'rm -f "$input"' RETURN
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
  local exec_arn
  exec_arn="$(aws_call stepfunctions start-execution --state-machine-arn "$machine_arn" --name "$exec_name" \
    --input "file://$input" --query executionArn --output text)"
  info "EXECUTION_ARN=$exec_arn"

  step "wait for the execution (poll every ${POLL_SECONDS}s, give up after ${WAIT_TIMEOUT}s)"
  local waited=0 status=""
  while [[ "$waited" -lt "$WAIT_TIMEOUT" ]]; do
    status="$(aws_call stepfunctions describe-execution --execution-arn "$exec_arn" --query status --output text)"
    info "status: $status (${waited}s)"
    [[ "$status" != "RUNNING" ]] && break
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
  done
  if [[ "$status" == "RUNNING" ]]; then
    warn "still running after ${WAIT_TIMEOUT}s; stopping the execution"
    aws_call stepfunctions stop-execution --execution-arn "$exec_arn" --cause "scripts/run-bounded-pipeline.sh wait timeout" >/dev/null
    die "execution stopped at the wait timeout"
  fi
  [[ "$status" == "SUCCEEDED" ]] || die "execution ended with status $status ($exec_arn)"
  step "pipeline succeeded: outputs under s3://$bucket_curated/hadoop/$GIT_SHA/"
}

case "$MODE" in
  local) run_local ;;
  cloud) run_cloud ;;
esac
