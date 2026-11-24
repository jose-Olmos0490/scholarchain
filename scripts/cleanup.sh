#!/usr/bin/env bash
# cleanup.sh - HUMAN-ONLY teardown of one environment. Agents never run this script.
#
#   scripts/cleanup.sh --env dev [--profile <name>] [--region <region>]            (dry run)
#   CONFIRM_CLEANUP=<env>-<account-id> APPROVED_DESTROY_SHA256=<sha256 of the saved destroy plan> \
#   CLEANUP_LOGS_PRESERVED=yes [CLEANUP_LOCAL_EVIDENCE_ACK=yes] \
#   scripts/cleanup.sh --env dev [--profile <name>] [--region <region>]
#                                                                                   (execute)
#
# The script refuses to run unless stdin and stdout are both terminals (a human at a shell).
# It never prompts: confirmation is the environment variables below, set deliberately.
#
# Required environment variables:
#   SCHOLARCHAIN_APPROVED_ENVS   set by the human; must list --env
#   CONFIRM_CLEANUP              execute only: must equal <env>-<account-id> (environment name, a
#                                hyphen, the 12-digit account from `aws sts get-caller-identity`);
#                                anything else keeps the run a dry run. The dry run does NOT print the
#                                resolved value (it would put the account ID into logs): read the
#                                account yourself with `aws sts get-caller-identity`.
#   APPROVED_DESTROY_SHA256      execute only: sha256 of the saved destroy plan the dry run printed
#   CLEANUP_LOGS_PRESERVED=yes   execute only, and only when the destroy plan deletes CloudWatch log
#                                groups: the human states the logs were exported first
#   CLEANUP_LOCAL_EVIDENCE_ACK=yes  execute only, and only when the evidence archive is local-only
#                                (no EVIDENCE_S3_PREFIX was used): the human acknowledges that the
#                                evidence lives only on this machine and was copied elsewhere
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; no default.
# Exit codes: 0 cleanup finished, 1 refused or failed, 2 usage error, 3 dry run (nothing changed).
#
# Cleanup policy (documented in infra/terraform/README.md):
#   - The targeted resources are LISTED first (dry run), always.
#   - Terraform state is preserved: the script never deletes, moves or edits state, and before an
#     execute it saves a copy (terraform state pull) to build/cleanup/<env>-<sha>/state-before.json
#     (mode 600, untracked). The state backend itself is not part of the environment's plan.
#   - Logs: the logs bucket is force_destroy=false, so it survives while it holds objects; log
#     groups are only deleted when the human sets CLEANUP_LOGS_PRESERVED=yes.
#   - Final reports: an execute needs a clean tree (reports and docs are committed) and a finished
#     evidence archive at evidence/<sha>/ (scripts/archive-evidence.sh) that passes these checks:
#     SHA256SUMS exists and verifies every archived file; MISSING.txt exists and does not list the
#     test reports ("tests:" entries); release/evidence-location.txt names an S3 location (whose
#     SHA256SUMS object must exist) or, for a local-only archive, CLEANUP_LOCAL_EVIDENCE_ACK=yes is
#     set. The script checks the archive's integrity and location; it cannot judge whether the
#     evidence is sufficient, which stays a human decision.
#   - The inventory before, the destroy plan listing and the inventory after are written to
#     build/cleanup/<env>-<sha>/.
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
SC_REGION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
validate_env_name "$ENV_NAME"
resolve_region
[[ -t 0 && -t 1 ]] ||
  die "cleanup.sh must be run by a human in an interactive terminal (stdin and stdout must be TTYs); it refuses to run from agents, pipes, CI or redirected output. Open a terminal and run it yourself."

require_cmd terraform aws python3 timeout git sha256sum
require_approved_env "$ENV_NAME"
ENV_DIR="$(env_dir "$ENV_NAME")"

step "resolve the account"
ACCOUNT_ID="$(account_id)"
[[ "$ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || die "could not resolve the AWS account"
EXPECTED="$ENV_NAME-$ACCOUNT_ID"
info "environment $ENV_NAME, region $SC_REGION, account resolved from sts get-caller-identity (not printed)"

mkdir -p "$SC_PLAN_DIR"
chmod 700 "$SC_PLAN_DIR"
SHA="$(head_sha)"
RECORD_DIR="$SC_BUILD_DIR/cleanup/$ENV_NAME-$SHA"
mkdir -p "$RECORD_DIR"
chmod 700 "$RECORD_DIR"
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
# A failed listing must stop the script: an empty inventory file would look like an empty state.
INVENTORY_BEFORE="$(timeout --foreground 300 terraform -chdir="$ENV_DIR" state list)" ||
  die "terraform state list failed; the before-inventory is unavailable, refusing to continue"
printf '%s\n' "$INVENTORY_BEFORE" | sort | tee "$RECORD_DIR/inventory-before.txt"
[[ -n "$INVENTORY_BEFORE" ]] || info "the Terraform state is empty (state list succeeded and returned no resources)"

if [[ "$CONFIRMED" == true ]]; then
  step "preconditions: clean tree and a finished evidence archive (final reports preserved)"
  require_clean_tree
  EVID="$SC_REPO_ROOT/evidence/$SHA"
  [[ -f "$EVID/SHA256SUMS" ]] ||
    die "no evidence archive at evidence/$SHA/SHA256SUMS; run scripts/archive-evidence.sh first"
  (cd "$EVID" && sha256sum --check --quiet SHA256SUMS) ||
    die "evidence/$SHA/SHA256SUMS does not verify the archived files; the archive was altered or is incomplete"
  [[ -f "$EVID/MISSING.txt" ]] || die "evidence/$SHA/MISSING.txt is absent; the archive is not a finished archive"
  if grep -q '^tests:' "$EVID/MISSING.txt"; then
    grep '^tests:' "$EVID/MISSING.txt" >&2
    die "the evidence archive lists test reports as missing (evidence/$SHA/MISSING.txt); re-archive after the reports exist"
  fi
  [[ -f "$EVID/release/evidence-location.txt" ]] ||
    die "evidence/$SHA/release/evidence-location.txt is absent; the archive does not record where the evidence is stored"
  EVID_LOCATION="$(head -n 1 "$EVID/release/evidence-location.txt")"
  case "$EVID_LOCATION" in
    s3://*)
      EV_BUCKET="${EVID_LOCATION#s3://}"
      EV_KEY="${EV_BUCKET#*/}"
      EV_BUCKET="${EV_BUCKET%%/*}"
      aws_call s3api head-object --bucket "$EV_BUCKET" --key "${EV_KEY%/}/SHA256SUMS" >/dev/null ||
        die "the evidence location $EVID_LOCATION has no SHA256SUMS object (or it cannot be read); the S3 copy is not confirmed"
      info "evidence is stored at $EVID_LOCATION"
      ;;
    local-only)
      [[ "${CLEANUP_LOCAL_EVIDENCE_ACK:-}" == "yes" ]] ||
        die "the evidence archive is local-only; set CLEANUP_LOCAL_EVIDENCE_ACK=yes to acknowledge that it lives only on this machine, or re-archive with EVIDENCE_S3_PREFIX"
      warn "proceeding with local-only evidence by explicit human acknowledgement"
      ;;
    *) die "unrecognized evidence location '$EVID_LOCATION' in release/evidence-location.txt" ;;
  esac

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
rows = [(rc["address"], rc["type"], rc["change"]["actions"]) for rc in d.get("resource_changes", []) if "delete" in rc["change"]["actions"]]
for addr, typ, acts in sorted(rows):
    print("TARGET %-8s %s" % ("/".join(acts), addr))
print("TARGET_COUNT=%d" % len(rows))
print("TARGET_LOG_GROUPS=%d" % sum(1 for r in rows if r[1] == "aws_cloudwatch_log_group"))
' | tee "$RECORD_DIR/targets.txt"
LOG_GROUP_TARGETS="$(sed -n 's/^TARGET_LOG_GROUPS=//p' "$RECORD_DIR/targets.txt")"

step "list buckets that still hold objects (the destroy fails on these by design)"
for area in raw curated graph rag artifacts logs; do
  bucket="$(bucket_name "$ENV_NAME" "$area" "$ACCOUNT_ID")"
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
  warn "required confirmation form: CONFIRM_CLEANUP=<env>-<account-id>; read the 12-digit account yourself from: aws sts get-caller-identity"
  warn "  CONFIRM_CLEANUP=<env>-<account-id> APPROVED_DESTROY_SHA256=$(sha256_of "$DESTROY_PLAN") scripts/cleanup.sh --env $ENV_NAME"
  warn "destroy plan saved at $DESTROY_PLAN"
  warn "while protect_stateful=true, a reviewed change with protect_stateful=false must be applied by the human first"
  exit 3
fi

if [[ "${LOG_GROUP_TARGETS:-0}" -gt 0 && "${CLEANUP_LOGS_PRESERVED:-}" != "yes" ]]; then
  die "the destroy plan deletes $LOG_GROUP_TARGETS CloudWatch log group(s); export the logs first, then set CLEANUP_LOGS_PRESERVED=yes"
fi

step "preserve the Terraform state: save a copy before the destroy (mode 600, untracked)"
STATE_COPY="$RECORD_DIR/state-before.json"
(umask 077 && timeout --foreground 300 terraform -chdir="$ENV_DIR" state pull >"$STATE_COPY")
chmod 600 "$STATE_COPY"
info "state copy: $STATE_COPY"

step "execute the approved destroy plan (the saved file, no re-plan)"
timeout --foreground 3600 terraform -chdir="$ENV_DIR" apply -input=false -lock-timeout=60s -no-color "$DESTROY_PLAN"

step "inventory after: resources left in the Terraform state"
if INVENTORY_AFTER="$(timeout --foreground 300 terraform -chdir="$ENV_DIR" state list)"; then
  printf '%s\n' "$INVENTORY_AFTER" | sort | tee "$RECORD_DIR/inventory-after.txt"
  [[ -n "$INVENTORY_AFTER" ]] || info "the Terraform state is empty after the teardown"
else
  # The teardown already finished; an empty file here would read as "nothing left", so say it plainly.
  echo "UNAVAILABLE: terraform state list failed after the teardown; inspect the account by hand" | tee "$RECORD_DIR/inventory-after.txt"
  warn "the after-inventory is UNAVAILABLE (terraform state list failed); verify the leftovers manually"
fi
info "cleanup finished for environment $ENV_NAME"
