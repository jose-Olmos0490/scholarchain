#!/usr/bin/env bash
# deploy.sh - application rollout only: build, push (tagged by Git SHA) and roll the ECS services.
#
#   APPROVED_PLAN_SHA256=<sha256 of the approved plan file> \
#   scripts/deploy.sh --env dev [--git-sha <sha>] [--profile <name>] [--region <region>]
#
# What it does NOT do: it never runs Terraform apply and never changes infrastructure. The
# infrastructure (cluster, services, roles, queues, tables) must already exist, created by the
# human from the approved plan. Here the plan is only evidence: the script refuses to continue
# unless the plan file for this commit exists, its sha256 equals APPROVED_PLAN_SHA256, and the
# plan was made for this account, this commit and this environment.
#
# Rollout: the task definition family that Terraform manages is cloned with the new image tag
# (<ecr repo>:<git sha>) and the service is moved to it; Terraform ignores the service's task
# definition so the two do not fight. The previous task definitions are recorded so that
# scripts/rollback.sh can return to them.
#
# CONSTITUTIONAL RECONCILIATION (flagged for the human): the work order for this script asked
# it to "apply only that exact saved plan file". docs/agents.md forbids deploy.sh from running
# Terraform apply, and the guard blocks apply wrappers. So the plan file is only verified here
# (sha256, account, commit, environment, not dirty, not offline). Applying the saved plan is a
# separate, human-only step: scripts/plan.sh prints the exact command at the end of its output.
#
# LIMIT (what is and is not verified): deploy.sh does NOT verify that the human actually applied
# the approved plan. Beyond the plan file's own checks above, the only evidence of an apply is
# that the ECS cluster scholarchain-<env> is ACTIVE; resources the plan would have changed (roles,
# queues, tables, task-definition families) are not compared with the live account. The human
# confirms the apply happened (and that it was THIS plan) before running /deploy.
# Plan integrity: SC_PLAN_DIR must be owned by the invoking user with mode 700 (otherwise the
# script refuses). The plan is copied once into a private mode-700 temp directory; the checksum
# and every terraform show run on that copy, which removes the check-then-use window.
#
# Required environment variables: APPROVED_PLAN_SHA256 (sha256 of the approved plan file),
#   SCHOLARCHAIN_APPROVED_ENVS (set by the human before the session; must list --env).
#   AWS credentials come from the standard chain or --profile (AWS_PROFILE).
# Optional: SC_STABLE_TIMEOUT (seconds to wait per service, default 900), SC_PLAN_DIR.
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; no default.
# Exit codes: 0 rolled out and stable, 1 refused or failed, 2 usage error.
# Human approval points: the plan review and its checksum (APPROVED_PLAN_SHA256), the approved
#   environment list, and the /deploy work order.
# Evidence: $SC_PLAN_DIR/deploy-<env>-<sha>.json and build/deploy-record-<env>-<sha>.json with
#   registry image digests and task definition ARNs and revisions (new and previous).
# Rollback behavior: a failed stability wait stops the script with exit 1 and names
#   scripts/rollback.sh --env <env> --to-sha <previous sha>; the previous task definitions are
#   written to $SC_PLAN_DIR/previous-<env>-<sha>.txt. Nothing is rolled back automatically.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
GIT_SHA=""
SC_PROFILE=""
SC_REGION=""
SERVICES=(query-api graph-evidence-api embedding-worker)
STABLE_TIMEOUT="${SC_STABLE_TIMEOUT:-900}"

# shellcheck disable=SC2034  # SC_PROFILE is read by aws_call in lib/common.sh
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?--git-sha needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
validate_env_name "$ENV_NAME"
resolve_region
require_cmd aws docker sbt terraform git python3 sha256sum timeout
if [[ -z "${APPROVED_PLAN_SHA256:-}" ]]; then
  die "APPROVED_PLAN_SHA256 is required (sha256 of the approved plan file)"
fi
[[ "$APPROVED_PLAN_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "APPROVED_PLAN_SHA256 must be 64 lowercase hex characters"

step "refuse a dirty tree and an unapproved environment"
require_clean_tree
require_approved_env "$ENV_NAME"
HEAD_SHA="$(head_sha)"
[[ -n "$GIT_SHA" ]] || GIT_SHA="$HEAD_SHA"
[[ "$GIT_SHA" == "$HEAD_SHA" ]] || die "--git-sha $GIT_SHA is not HEAD ($HEAD_SHA); check out the commit being deployed"
info "deploying git sha $GIT_SHA to environment $ENV_NAME"

step "verify the approved plan checksum (private copy, one read)"
# The plan directory must belong to the invoking user and be mode 700, so nobody else could have
# swapped the plan. The plan is then copied ONCE into a private directory; the checksum and every
# later terraform show use that same copy, so the bytes that were approved are the bytes inspected.
assert_private_dir "$SC_PLAN_DIR"
SOURCE_PLAN="$(plan_path "$ENV_NAME" "$GIT_SHA")"
[[ -f "$SOURCE_PLAN" && ! -L "$SOURCE_PLAN" ]] ||
  die "no plan file for this commit: $SOURCE_PLAN (run scripts/plan.sh and have it applied by the human)"
PRIV_DIR="$(mktemp -d)"
trap 'rm -rf "$PRIV_DIR" "${TASKDEF_JSON:-}"' EXIT
PLAN_FILE="$PRIV_DIR/$(basename "$SOURCE_PLAN")"
(umask 077 && cp -- "$SOURCE_PLAN" "$PLAN_FILE")
ACTUAL_SHA256="$(sha256_of "$PLAN_FILE")"
if [[ "$ACTUAL_SHA256" != "$APPROVED_PLAN_SHA256" ]]; then
  die "plan checksum mismatch: file $ACTUAL_SHA256, approved $APPROVED_PLAN_SHA256"
fi
info "plan checksum matches: $ACTUAL_SHA256 (checked on the private copy that is inspected below)"
[[ "$SOURCE_PLAN" != *.DIRTY.tfplan ]] || die "a DIRTY plan cannot approve a deployment"
DIRTY_FILE="${SOURCE_PLAN%.tfplan}.DIRTY.tfplan"
if [[ -f "$DIRTY_FILE" && "$(sha256_of "$DIRTY_FILE")" == "$APPROVED_PLAN_SHA256" ]]; then
  die "the approved checksum belongs to a DIRTY plan ($DIRTY_FILE); plan again from a clean tree"
fi
SUMMARY="${SOURCE_PLAN%.tfplan}.summary.txt"
if [[ -f "$SUMMARY" ]] && grep -q 'dirty=true' "$SUMMARY"; then
  die "the plan summary says dirty=true; plan again from a clean tree"
fi

step "verify the plan belongs to this account, commit and environment"
ACCOUNT_ID="$(account_id)"
[[ "$ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || die "could not resolve the AWS account"
[[ "$(plan_var "$ENV_NAME" "$PLAN_FILE" aws_account_id)" == "$ACCOUNT_ID" ]] || die "plan was made for another account"
[[ "$(plan_var "$ENV_NAME" "$PLAN_FILE" git_sha)" == "$GIT_SHA" ]] || die "plan was made for another commit"
[[ "$(plan_var "$ENV_NAME" "$PLAN_FILE" environment)" == "$ENV_NAME" ]] || die "plan was made for another environment"
[[ "$(plan_var "$ENV_NAME" "$PLAN_FILE" offline_plan)" != "True" && "$(plan_var "$ENV_NAME" "$PLAN_FILE" offline_plan)" != "true" ]] ||
  die "an offline plan cannot approve a deployment"
[[ "$(plan_var "$ENV_NAME" "$PLAN_FILE" owner)" != "CHANGE_ME" ]] ||
  die "the plan carries the placeholder owner tag CHANGE_ME; set owner in terraform.tfvars and plan again"

NAME_PREFIX="scholarchain-$ENV_NAME"
CLUSTER="$NAME_PREFIX"
REGISTRY="$ACCOUNT_ID.dkr.ecr.$SC_REGION.amazonaws.com"

step "verify the infrastructure exists (applied by the human)"
STATUS="$(aws_call ecs describe-clusters --clusters "$CLUSTER" --query 'clusters[0].status' --output text)"
[[ "$STATUS" == "ACTIVE" ]] || die "ECS cluster $CLUSTER is '$STATUS'; the human must apply the approved plan first"

step "build the service images (sbt dockerLocal; no push yet)"
(cd "$SC_REPO_ROOT" && timeout --foreground 3600 sbt -batch dockerLocal)
VERSION="$(cd "$SC_REPO_ROOT" && timeout --foreground 300 sbt -batch -error "print services/version" | tail -n 1)"
[[ -n "$VERSION" ]] || die "could not read the sbt project version"

step "log in to ECR (the token is never printed)"
aws_call ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

RECORD="$SC_PLAN_DIR/deploy-$ENV_NAME-$GIT_SHA.json"
PREVIOUS="$SC_PLAN_DIR/previous-$ENV_NAME-$GIT_SHA.txt"
mkdir -p "$SC_PLAN_DIR"
chmod 700 "$SC_PLAN_DIR"
: >"$PREVIOUS"
RELEASE_ITEMS=()

for svc in "${SERVICES[@]}"; do
  REPO="scholarchain/$ENV_NAME/$svc"
  IMAGE="$REGISTRY/$REPO:$GIT_SHA"

  step "push $svc as $IMAGE (immutable tag)"
  if aws_call ecr describe-images --repository-name "$REPO" --image-ids "imageTag=$GIT_SHA" >/dev/null 2>&1; then
    info "tag $GIT_SHA already exists in $REPO; not pushing (tags are immutable)"
  else
    docker tag "scholarchain-$svc:$VERSION" "$IMAGE"
    timeout --foreground 1200 docker push "$IMAGE"
  fi
  DIGEST="$(aws_call ecr describe-images --repository-name "$REPO" --image-ids "imageTag=$GIT_SHA" \
    --query 'imageDetails[0].imageDigest' --output text)"
  [[ "$DIGEST" == sha256:* ]] || die "no digest for $IMAGE"
  info "digest $svc: $DIGEST"

  step "register a task definition revision for $svc and move the service to it"
  FAMILY="$NAME_PREFIX-$svc"
  CURRENT="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query 'services[0].taskDefinition' --output text)"
  [[ "$CURRENT" == arn:* ]] || die "service $svc not found in $CLUSTER"
  printf '%s %s\n' "$svc" "$CURRENT" >>"$PREVIOUS"

  TASKDEF_JSON="$(mktemp)"
  aws_call ecs describe-task-definition --task-definition "$FAMILY" --query taskDefinition --output json |
    python3 -I -c '
import json, sys
td = json.load(sys.stdin)
image = sys.argv[1]
for k in ("taskDefinitionArn", "revision", "status", "requiresAttributes", "compatibilities",
          "registeredAt", "registeredBy", "deregisteredAt"):
    td.pop(k, None)
for c in td["containerDefinitions"]:
    c["image"] = image
json.dump(td, sys.stdout)
' "$IMAGE" >"$TASKDEF_JSON"
  NEW_ARN="$(aws_call ecs register-task-definition --cli-input-json "file://$TASKDEF_JSON" \
    --query 'taskDefinition.taskDefinitionArn' --output text)"
  rm -f "$TASKDEF_JSON"
  info "registered $NEW_ARN"

  DESIRED="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query 'services[0].desiredCount' --output text)"
  MIN_CAP="$(aws_call application-autoscaling describe-scalable-targets --service-namespace ecs \
    --resource-ids "service/$CLUSTER/$svc" --query 'ScalableTargets[0].MinCapacity' --output text)"
  [[ "$MIN_CAP" =~ ^[0-9]+$ ]] || MIN_CAP=1
  if [[ "$DESIRED" -lt "$MIN_CAP" ]]; then
    DESIRED="$MIN_CAP"
  fi
  aws_call ecs update-service --cluster "$CLUSTER" --service "$svc" --task-definition "$NEW_ARN" \
    --desired-count "$DESIRED" >/dev/null

  RELEASE_ITEMS+=("$svc|$IMAGE|$DIGEST|$NEW_ARN|$CURRENT")
done

step "wait for the services to become stable (timeout ${STABLE_TIMEOUT}s each)"
for svc in "${SERVICES[@]}"; do
  DESIRED="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query 'services[0].desiredCount' --output text)"
  if [[ "$DESIRED" -eq 0 ]]; then
    info "$svc has desired count 0 (scales from zero on demand); not waiting"
    continue
  fi
  SC_AWS_TIMEOUT="$STABLE_TIMEOUT" aws_call ecs wait services-stable --cluster "$CLUSTER" --services "$svc" ||
    die "$svc did not become stable within ${STABLE_TIMEOUT}s; run scripts/rollback.sh --env $ENV_NAME --to-sha <previous sha>"
  info "$svc is stable"
done

step "record the release"
python3 -I - "$RECORD" "$ENV_NAME" "$GIT_SHA" "$ACCOUNT_ID" "$APPROVED_PLAN_SHA256" "$SC_REGION" "$(utc_now)" "${RELEASE_ITEMS[@]}" <<'PY'
import json
import sys

path, env, sha, account, plan_sha, region, now, *items = sys.argv[1:]
services = []
for it in items:
    name, image, digest, new_td, old_td = it.split("|")
    services.append({"service": name, "image": image, "digest": digest,
                     "taskDefinition": new_td, "taskDefinitionRevision": int(new_td.rsplit(":", 1)[1]),
                     "previousTaskDefinition": old_td,
                     "previousTaskDefinitionRevision": int(old_td.rsplit(":", 1)[1])})
json.dump({"environment": env, "gitSha": sha, "account": account, "region": region,
           "deployedAtUtc": now, "approvedPlanSha256": plan_sha, "services": services},
          open(path, "w"), indent=2)
PY
cat "$RECORD"
mkdir -p "$SC_BUILD_DIR"
cp "$RECORD" "$SC_BUILD_DIR/deploy-record-$ENV_NAME-$GIT_SHA.json"
info "RELEASE_RECORD=$RECORD"
info "RELEASE_RECORD_COPY=$SC_BUILD_DIR/deploy-record-$ENV_NAME-$GIT_SHA.json"
info "next: scripts/smoke-test.sh --env $ENV_NAME"
