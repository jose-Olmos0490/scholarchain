#!/usr/bin/env bash
# rollback.sh - move the three ECS services back to the task definition that runs an earlier,
# already-pushed image tag (a Git SHA). Application rollout only: no Terraform, no data change.
#
#   scripts/rollback.sh --env dev --to-sha <git sha previously deployed> [--profile <name>] [--region <region>]
#
# The target revision is found, never guessed: for each service the ACTIVE task definition
# revisions of its family are searched (newest first, at most 25) for one whose container image
# ends in ":<sha>". If any service has no such revision nothing is changed.
#
# Required environment variables: SCHOLARCHAIN_APPROVED_ENVS (set by the human; must list --env).
#   AWS credentials come from the standard chain or --profile (AWS_PROFILE).
# Optional: SC_STABLE_TIMEOUT (seconds to wait per service, default 900).
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; no default.
# Partial failure: if an update-service call fails after earlier services were moved, the script
#   names the services already moved and prints the exact idempotent re-run command.
# Exit codes: 0 rolled back and stable, 1 refused or failed, 2 usage error.
# Human approval point: the approved environment list and the /rollback or /deploy work order.
# Evidence: the step markers and the "image tag" lines on stdout (capture them in the report).

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
TO_SHA=""
SC_PROFILE=""
SC_REGION=""
SERVICES=(query-api graph-evidence-api embedding-worker)
STABLE_TIMEOUT="${SC_STABLE_TIMEOUT:-900}"
MAX_REVISIONS=25

# shellcheck disable=SC2034  # SC_PROFILE is read by aws_call in lib/common.sh
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --to-sha) TO_SHA="${2:?--to-sha needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
[[ -n "$TO_SHA" ]] || usage_error "--to-sha is required (the Git SHA of the release to return to)"
validate_env_name "$ENV_NAME"
resolve_region
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
  # Capture the AWS output and exit status first, so an AWS error is reported as such and not
  # mistaken for "no matching revision" (a process substitution would swallow the status).
  LIST_RC=0
  LIST_OUT="$(aws_call ecs list-task-definitions --family-prefix "$FAMILY" --status ACTIVE --sort DESC \
    --max-items "$MAX_REVISIONS" --query 'taskDefinitionArns[]' --output text 2>&1)" || LIST_RC=$?
  [[ "$LIST_RC" -eq 0 ]] ||
    die "aws ecs list-task-definitions failed for $FAMILY (exit $LIST_RC): $LIST_OUT; nothing was changed"
  mapfile -t ARNS < <(printf '%s' "$LIST_OUT" | tr '\t' '\n')
  for arn in "${ARNS[@]}"; do
    [[ -n "$arn" ]] || continue
    IMAGE="$(aws_call ecs describe-task-definition --task-definition "$arn" \
      --query 'taskDefinition.containerDefinitions[0].image' --output text)" ||
      die "aws ecs describe-task-definition failed for $arn; nothing was changed"
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
MOVED=()
for svc in "${SERVICES[@]}"; do
  UPD_RC=0
  UPD_OUT="$(aws_call ecs update-service --cluster "$CLUSTER" --service "$svc" --task-definition "${TARGET[$svc]}" 2>&1 >/dev/null)" || UPD_RC=$?
  if [[ "$UPD_RC" -ne 0 ]]; then
    warn "update-service FAILED for $svc (exit $UPD_RC): $UPD_OUT"
    if [[ "${#MOVED[@]}" -gt 0 ]]; then
      warn "ALREADY MOVED to the $TO_SHA task definitions: ${MOVED[*]}"
    else
      warn "no service had been moved yet"
    fi
    warn "NOT moved: $svc and any service after it. The services are now on mixed revisions."
    warn "re-run the same command (it is idempotent): scripts/rollback.sh --env $ENV_NAME --to-sha $TO_SHA"
    die "rollback incomplete: $svc could not be moved"
  fi
  MOVED+=("$svc")
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
