#!/usr/bin/env bash
# cleanup.sh - HUMAN-ONLY teardown of one environment. Agents never run this script.
#
#   scripts/cleanup.sh --env dev [--profile <name>] [--region us-east-1]            (dry run)
#   CONFIRM_CLEANUP=<env>-<account-id> APPROVED_DESTROY_SHA256=<sha256 of the saved destroy plan> \
#     scripts/cleanup.sh --env dev [--profile <name>] [--region us-east-1]          (execute)
#
# The script refuses to run unless stdin and stdout are both terminals (a human at a shell).
#
# Dry run (no CONFIRM_CLEANUP): writes a destroy plan, saves it together with its sha256, LISTS
# every resource it would remove (and which buckets still hold objects) and exits with status 3.
# Confirmed run: requires CONFIRM_CLEANUP exactly equal to <env>-<account-id> AND
# APPROVED_DESTROY_SHA256 equal to the sha256 of the saved destroy plan. It executes only that
# saved plan: it never re-plans, so what was reviewed is what is destroyed.
#
# It never empties a bucket, never disables deletion protection and never changes retention:
#   - buckets are force_destroy=false, so a bucket that still holds objects makes the destroy fail
#     (deleting data is a human decision made deliberately, outside this script);
#   - Neptune, DynamoDB tables and the ALB carry deletion protection while protect_stateful=true
#     (the default, in every environment). Cleanup requires a reviewed Terraform change with
#     protect_stateful=false, applied by the human, FIRST; until then the destroy is refused.
# Resource inventory is printed before and after.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
SC_PROFILE=""
SC_REGION="us-east-1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -t 0 && -t 1 ]] ||
  die "cleanup.sh must be run by a human in an interactive terminal (stdin and stdout must be TTYs); it refuses to run from agents, pipes, CI or redirected output. Open a terminal and run it yourself."

[[ -n "$ENV_NAME" ]] || die "--env is required"
validate_env_name "$ENV_NAME"
require_cmd terraform aws python3 timeout git sha256sum
require_approved_env "$ENV_NAME"
ENV_DIR="$(env_dir "$ENV_NAME")"

step "resolve the account"
ACCOUNT_ID="$(account_id)"
[[ "$ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || die "could not resolve the AWS account"
EXPECTED="$ENV_NAME-$ACCOUNT_ID"
info "environment $ENV_NAME, account $ACCOUNT_ID, region $SC_REGION"

mkdir -p "$SC_PLAN_DIR"
chmod 700 "$SC_PLAN_DIR"
SHA="$(head_sha)"
DESTROY_PLAN="$SC_PLAN_DIR/cleanup-$ENV_NAME-$SHA.tfplan"
DESTROY_SHA_FILE="${DESTROY_PLAN}.sha256"

declare -a TF_VARS=(
  -var "git_sha=$SHA"
  -var "aws_account_id=$ACCOUNT_ID"
  -var "aws_region=$SC_REGION"
  -var "environment=$ENV_NAME"
)
[[ -n "$SC_PROFILE" ]] && TF_VARS+=(-var "aws_profile=$SC_PROFILE")

CONFIRMED=false
[[ "${CONFIRM_CLEANUP:-}" == "$EXPECTED" ]] && CONFIRMED=true

step "inventory before: resources in the Terraform state"
timeout --foreground 600 terraform -chdir="$ENV_DIR" init -input=false -no-color >/dev/null
terraform -chdir="$ENV_DIR" state list | sort || true

if [[ "$CONFIRMED" == true ]]; then
  step "verify the saved destroy plan (no re-plan)"
  : "${APPROVED_DESTROY_SHA256:?APPROVED_DESTROY_SHA256 is required (the sha256 printed by the dry run)}"
  [[ "$APPROVED_DESTROY_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "APPROVED_DESTROY_SHA256 must be 64 lowercase hex characters"
  [[ -f "$DESTROY_PLAN" ]] || die "no saved destroy plan at $DESTROY_PLAN; run the dry run first"
  ACTUAL_SHA256="$(sha256_of "$DESTROY_PLAN")"
  [[ "$ACTUAL_SHA256" == "$APPROVED_DESTROY_SHA256" ]] ||
    die "destroy plan checksum mismatch: file $ACTUAL_SHA256, approved $APPROVED_DESTROY_SHA256"
  info "destroy plan checksum matches: $ACTUAL_SHA256"
else
  step "write the destroy plan (full output below)"
  timeout --foreground 1800 terraform -chdir="$ENV_DIR" plan -destroy -input=false -lock-timeout=60s -no-color \
    "${TF_VARS[@]}" -out="$DESTROY_PLAN"
  chmod 600 "$DESTROY_PLAN"
  printf '%s  %s\n' "$(sha256_of "$DESTROY_PLAN")" "$(basename "$DESTROY_PLAN")" >"$DESTROY_SHA_FILE"
  info "DESTROY_PLAN=$DESTROY_PLAN"
  info "DESTROY_PLAN_SHA256=$(sha256_of "$DESTROY_PLAN")"
fi

step "list every targeted resource"
terraform -chdir="$ENV_DIR" show -json "$DESTROY_PLAN" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
rows = [(rc["address"], rc["change"]["actions"]) for rc in d.get("resource_changes", []) if "delete" in rc["change"]["actions"]]
for addr, acts in sorted(rows):
    print("TARGET %-8s %s" % ("/".join(acts), addr))
print("TARGET_COUNT=%d" % len(rows))
'

step "list buckets that still hold objects (the destroy fails on these by design)"
for area in raw curated graph rag artifacts logs; do
  bucket="scholarchain-$ENV_NAME-$area-$ACCOUNT_ID-$SC_REGION"
  # shellcheck disable=SC2016  # the backticks are JMESPath literals, not command substitution
  keys="$(aws_call s3api list-objects-v2 --bucket "$bucket" --max-items 1 --query 'length(Contents || `[]`)' --output text 2>/dev/null || echo 'absent')"
  case "$keys" in
    0) info "bucket $bucket is empty" ;;
    absent) info "bucket $bucket does not exist" ;;
    *) warn "bucket $bucket is NOT empty; empty it deliberately (data destruction is a human decision)" ;;
  esac
done

step "require documented human confirmation"
if [[ "$CONFIRMED" != true ]]; then
  warn "dry run only. Review the destroy plan, then execute exactly that saved plan with:"
  warn "  CONFIRM_CLEANUP=$EXPECTED APPROVED_DESTROY_SHA256=$(sha256_of "$DESTROY_PLAN") scripts/cleanup.sh --env $ENV_NAME"
  warn "destroy plan saved at $DESTROY_PLAN"
  warn "while protect_stateful=true, a reviewed change with protect_stateful=false must be applied by the human first"
  exit 3
fi

step "execute the approved destroy plan (the saved file, no re-plan)"
timeout --foreground 3600 terraform -chdir="$ENV_DIR" apply -input=false -lock-timeout=60s -no-color "$DESTROY_PLAN"

step "inventory after: resources left in the Terraform state"
terraform -chdir="$ENV_DIR" state list | sort || true
info "cleanup finished for $EXPECTED"
