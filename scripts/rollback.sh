#!/usr/bin/env bash
# rollback.sh - move the three ECS services back to the task definition that runs an earlier,
# already-pushed image tag (a Git SHA). Application rollout only: no Terraform, no data change.
#
#   scripts/rollback.sh --env dev --to-sha <git sha previously deployed> [--profile <name>] [--region us-east-1]
#
# The target revision is found, never guessed: for each service the ACTIVE task definition
# revisions of its family are searched (newest first, at most 25) for one whose container image
# ends in ":<sha>". If any service has no such revision nothing is changed.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
TO_SHA=""
SC_PROFILE=""
SC_REGION="us-east-1"
SERVICES=(query-api graph-evidence-api embedding-worker)
STABLE_TIMEOUT="${SC_STABLE_TIMEOUT:-900}"
MAX_REVISIONS=25

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --to-sha) TO_SHA="${2:?--to-sha needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || die "--env is required"
[[ -n "$TO_SHA" ]] || die "--to-sha is required (the Git SHA of the release to return to)"
validate_env_name "$ENV_NAME"
[[ "$TO_SHA" =~ ^[0-9a-f]{7,40}$ ]] || die "--to-sha must be 7 to 40 lowercase hex characters"
require_cmd aws python3 timeout

step "refuse an unapproved environment"
require_approved_env "$ENV_NAME"

CLUSTER="scholarchain-$ENV_NAME"

step "find the target task definition of every service (read-only)"
declare -A TARGET
for svc in "${SERVICES[@]}"; do
  FAMILY="$CLUSTER-$svc"
  FOUND=""
  mapfile -t ARNS < <(aws_call ecs list-task-definitions --family-prefix "$FAMILY" --status ACTIVE --sort DESC \
    --max-items "$MAX_REVISIONS" --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')
  for arn in "${ARNS[@]}"; do
    [[ -n "$arn" ]] || continue
    IMAGE="$(aws_call ecs describe-task-definition --task-definition "$arn" \
      --query 'taskDefinition.containerDefinitions[0].image' --output text)"
    if [[ "$IMAGE" == *":$TO_SHA" ]]; then
      FOUND="$arn"
      break
    fi
  done
  [[ -n "$FOUND" ]] || die "no ACTIVE task definition of $FAMILY runs image tag $TO_SHA; nothing was changed"
  TARGET["$svc"]="$FOUND"
  info "$svc -> $FOUND"
done

step "move the services (no desired-count change)"
for svc in "${SERVICES[@]}"; do
  aws_call ecs update-service --cluster "$CLUSTER" --service "$svc" --task-definition "${TARGET[$svc]}" >/dev/null
  info "$svc rolling back to ${TARGET[$svc]}"
done

step "wait for the services to become stable (timeout ${STABLE_TIMEOUT}s each)"
for svc in "${SERVICES[@]}"; do
  DESIRED="$(aws_call ecs describe-services --cluster "$CLUSTER" --services "$svc" \
    --query 'services[0].desiredCount' --output text)"
  if [[ "$DESIRED" -eq 0 ]]; then
    info "$svc has desired count 0; not waiting"
    continue
  fi
  SC_AWS_TIMEOUT="$STABLE_TIMEOUT" aws_call ecs wait services-stable --cluster "$CLUSTER" --services "$svc" ||
    die "$svc did not become stable within ${STABLE_TIMEOUT}s after rollback; escalate to the human architect"
  info "$svc is stable on image tag $TO_SHA"
done

step "rollback complete; run scripts/smoke-test.sh --env $ENV_NAME"
