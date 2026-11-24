#!/usr/bin/env bash
# deploy.sh - application rollout only: build, push (tagged by Git SHA) and roll the ECS services.
#
#   APPROVED_PLAN_SHA256=<sha256 of the approved plan file> \
#   scripts/deploy.sh --env dev [--git-sha <sha>] [--profile <name>] [--region us-east-1]
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

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
GIT_SHA=""
SC_PROFILE=""
SC_REGION="us-east-1"
SERVICES=(query-api graph-evidence-api embedding-worker)
STABLE_TIMEOUT="${SC_STABLE_TIMEOUT:-900}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?--git-sha needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || die "--env is required"
validate_env_name "$ENV_NAME"
require_cmd aws docker sbt terraform git python3 sha256sum timeout
: "${APPROVED_PLAN_SHA256:?APPROVED_PLAN_SHA256 is required (sha256 of the approved plan file)}"
[[ "$APPROVED_PLAN_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "APPROVED_PLAN_SHA256 must be 64 lowercase hex characters"

step "refuse a dirty tree and an unapproved environment"
require_clean_tree
require_approved_env "$ENV_NAME"
HEAD_SHA="$(head_sha)"
[[ -n "$GIT_SHA" ]] || GIT_SHA="$HEAD_SHA"
[[ "$GIT_SHA" == "$HEAD_SHA" ]] || die "--git-sha $GIT_SHA is not HEAD ($HEAD_SHA); check out the commit being deployed"
info "deploying git sha $GIT_SHA to environment $ENV_NAME"

step "verify the approved plan checksum"
PLAN_FILE="$(plan_path "$ENV_NAME" "$GIT_SHA")"
[[ -f "$PLAN_FILE" ]] || die "no plan file for this commit: $PLAN_FILE (run scripts/plan.sh and have it applied by the human)"
ACTUAL_SHA256="$(sha256_of "$PLAN_FILE")"
if [[ "$ACTUAL_SHA256" != "$APPROVED_PLAN_SHA256" ]]; then
  die "plan checksum mismatch: file $ACTUAL_SHA256, approved $APPROVED_PLAN_SHA256"
fi
info "plan checksum matches: $ACTUAL_SHA256"
[[ "$PLAN_FILE" != *.DIRTY.tfplan ]] || die "a DIRTY plan cannot approve a deployment"
DIRTY_FILE="${PLAN_FILE%.tfplan}.DIRTY.tfplan"
if [[ -f "$DIRTY_FILE" && "$(sha256_of "$DIRTY_FILE")" == "$APPROVED_PLAN_SHA256" ]]; then
  die "the approved checksum belongs to a DIRTY plan ($DIRTY_FILE); plan again from a clean tree"
fi
SUMMARY="${PLAN_FILE%.tfplan}.summary.txt"
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
  trap 'rm -f "$TASKDEF_JSON"' EXIT
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
python3 -I - "$RECORD" "$ENV_NAME" "$GIT_SHA" "$ACCOUNT_ID" "$APPROVED_PLAN_SHA256" "${RELEASE_ITEMS[@]}" <<'PY'
import json
import sys

path, env, sha, account, plan_sha, *items = sys.argv[1:]
services = []
for it in items:
    name, image, digest, new_td, old_td = it.split("|")
    services.append({"service": name, "image": image, "digest": digest,
                     "taskDefinition": new_td, "previousTaskDefinition": old_td})
json.dump({"environment": env, "gitSha": sha, "account": account,
           "approvedPlanSha256": plan_sha, "services": services}, open(path, "w"), indent=2)
PY
cat "$RECORD"
info "RELEASE_RECORD=$RECORD"
info "next: scripts/smoke-test.sh --env $ENV_NAME"
