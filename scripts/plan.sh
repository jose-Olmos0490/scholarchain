#!/usr/bin/env bash
# plan.sh - produce a reviewable, non-destructive Terraform plan for one environment.
#
#   scripts/plan.sh --env dev [--git-sha <sha>] [--account-id <12 digits>] [--profile <name>]
#                   [--region <region>] [--enable-emr true|false] [--enable-neptune true|false]
#                   [--alb-ingress-cidr <cidr>]... [--offline] [--allow-dirty]
#
# Writes /tmp/scholarchain-plans/<env>-<gitsha>.tfplan (never into the repository), prints the
# full plan, then records its sha256, the add/change/destroy counts, every IAM change and every
# stateful change. This script never applies anything. Applying the plan is a human-only step
# done by hand after reviewing the output; agents and scripts never do it.
#
# --offline plans without AWS credentials (no account check; the plan can never be applied).
# --allow-dirty is for local review only. Its plan is written to <env>-<gitsha>.DIRTY.tfplan and
# the summary says dirty=true; deploy.sh refuses it. A dirty plan never overwrites a clean one.
# alb_ingress_cidrs has no default: set it in terraform.tfvars or pass --alb-ingress-cidr
# (repeatable; 0.0.0.0/0 and ::/0 are rejected by Terraform).
#
# Also writes build/plan-summary.json (untracked): environment, plan file, plan SHA-256, commit
# SHA, target account (aws sts get-caller-identity) and Region, add/change/destroy counts, every
# IAM and policy change, and every action on a stateful resource, all taken from
# "terraform show -json". The same data is printed in text form.
#
# Required environment variables: none. AWS read credentials are needed unless --offline: set
#   AWS_PROFILE or pass --profile (the profile must be able to call sts:GetCallerIdentity and
#   the read calls the AWS provider makes while planning).
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region; there is no
#   built-in default, and even --offline needs one (it is a Terraform input).
# Optional: TF_VAR_aws_account_id (expected account; a mismatch with the credentials is refused),
#   SC_PLAN_DIR (default /tmp/scholarchain-plans), SC_BUILD_DIR (default <repo>/build).
# Exit codes: 0 plan written, 1 failed (dirty tree, account mismatch, terraform error), 2 usage.
# Human approval point: the human reviews the plan, then applies THAT SAVED FILE by hand; the
# exact command is printed at the end of this script's output. No script and no agent runs it.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
GIT_SHA=""
ACCOUNT_ID=""
SC_PROFILE=""
SC_REGION=""
ENABLE_EMR=""
ENABLE_NEPTUNE=""
OFFLINE=false
ALLOW_DIRTY=false
declare -a ALB_CIDRS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:?--git-sha needs a value}"; shift 2 ;;
    --account-id) ACCOUNT_ID="${2:?--account-id needs a value}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    --enable-emr) ENABLE_EMR="${2:?--enable-emr needs true|false}"; shift 2 ;;
    --enable-neptune) ENABLE_NEPTUNE="${2:?--enable-neptune needs true|false}"; shift 2 ;;
    --alb-ingress-cidr) ALB_CIDRS+=("${2:?--alb-ingress-cidr needs a value}"); shift 2 ;;
    --offline) OFFLINE=true; shift ;;
    --allow-dirty) ALLOW_DIRTY=true; shift ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
validate_env_name "$ENV_NAME"
resolve_region
require_cmd terraform git python3 sha256sum tee timeout
ENV_DIR="$(env_dir "$ENV_NAME")"

step "check the working tree"
if [[ "$ALLOW_DIRTY" == true ]]; then
  warn "--allow-dirty: this plan is NOT reproducible from a commit and cannot be deployed"
else
  require_clean_tree
fi
[[ -n "$GIT_SHA" ]] || GIT_SHA="$(head_sha)"
info "git sha: $GIT_SHA"

step "resolve the AWS account"
if [[ "$OFFLINE" == true ]]; then
  [[ -n "$ACCOUNT_ID" ]] || ACCOUNT_ID="000000000000"
  warn "--offline: no credentials, no account check; this plan can never be applied"
else
  require_cmd aws
  if [[ -z "$ACCOUNT_ID" ]]; then
    ACCOUNT_ID="${TF_VAR_aws_account_id:-}"
  fi
  CALLER_ACCOUNT="$(account_id)"
  [[ -n "$ACCOUNT_ID" ]] || ACCOUNT_ID="$CALLER_ACCOUNT"
  [[ "$ACCOUNT_ID" == "$CALLER_ACCOUNT" ]] ||
    die "account mismatch: requested $ACCOUNT_ID but the credentials belong to $CALLER_ACCOUNT"
fi
[[ "$ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || die "account id must be 12 digits"
info "account: $ACCOUNT_ID  region: $SC_REGION"

mkdir -p "$SC_PLAN_DIR"
chmod 700 "$SC_PLAN_DIR"
assert_private_dir "$SC_PLAN_DIR"
PLAN_FILE="$(plan_path "$ENV_NAME" "$GIT_SHA")"
if [[ "$ALLOW_DIRTY" == true ]]; then
  PLAN_FILE="${PLAN_FILE%.tfplan}.DIRTY.tfplan"
fi
LOG_FILE="${PLAN_FILE%.tfplan}.plan.log"
SUMMARY_FILE="${PLAN_FILE%.tfplan}.summary.txt"

declare -a TF_VARS=(
  -var "git_sha=$GIT_SHA"
  -var "aws_account_id=$ACCOUNT_ID"
  -var "aws_region=$SC_REGION"
  -var "environment=$ENV_NAME"
)
if [[ ${#ALB_CIDRS[@]} -gt 0 ]]; then
  ALB_JSON="$(printf '"%s",' "${ALB_CIDRS[@]}")"
  TF_VARS+=(-var "alb_ingress_cidrs=[${ALB_JSON%,}]")
fi
[[ -n "$SC_PROFILE" ]] && TF_VARS+=(-var "aws_profile=$SC_PROFILE")
[[ "$OFFLINE" == true ]] && TF_VARS+=(-var "offline_plan=true")
[[ -n "$ENABLE_EMR" ]] && TF_VARS+=(-var "enable_emr=$ENABLE_EMR")
[[ -n "$ENABLE_NEPTUNE" ]] && TF_VARS+=(-var "enable_neptune=$ENABLE_NEPTUNE")

step "terraform fmt -check -recursive"
terraform -chdir="$SC_REPO_ROOT/infra/terraform" fmt -check -recursive

step "terraform init (providers pinned in versions.tf)"
timeout --foreground 600 terraform -chdir="$ENV_DIR" init -input=false -no-color

step "terraform validate"
terraform -chdir="$ENV_DIR" validate -no-color

step "terraform plan (full output below, also saved to $LOG_FILE)"
# -lock-timeout bounds the wait for a state lock; the whole command has a hard deadline.
timeout --foreground 1800 terraform -chdir="$ENV_DIR" plan -input=false -lock-timeout=60s -no-color \
  "${TF_VARS[@]}" -out="$PLAN_FILE" 2>&1 | tee "$LOG_FILE"

[[ -s "$PLAN_FILE" ]] || die "terraform did not write the plan file $PLAN_FILE"
chmod 600 "$PLAN_FILE"

step "record the plan checksum and summary"
PLAN_SHA256="$(sha256_of "$PLAN_FILE")"
printf '%s  %s\n' "$PLAN_SHA256" "$(basename "$PLAN_FILE")" >"${PLAN_FILE}.sha256"

terraform -chdir="$ENV_DIR" show -json "$PLAN_FILE" >"${PLAN_FILE%.tfplan}.plan.json"
chmod 600 "${PLAN_FILE%.tfplan}.plan.json"

python3 -I - "${PLAN_FILE%.tfplan}.plan.json" "$ENV_NAME" "$GIT_SHA" "$PLAN_SHA256" "$PLAN_FILE" "$OFFLINE" "$ALLOW_DIRTY" >"$SUMMARY_FILE" <<'PY'
import json
import sys

plan_json, env, sha, digest, plan_file, offline, dirty = sys.argv[1:8]
data = json.load(open(plan_json))
changes = data.get("resource_changes", [])

def kind(actions):
    a = sorted(actions)
    if a == ["no-op"] or a == ["read"]:
        return None
    if a == ["create"]:
        return "add"
    if a == ["delete"]:
        return "destroy"
    if a == ["update"]:
        return "change"
    if a == ["create", "delete"]:
        return "replace"
    return "other"

counts = {"add": 0, "change": 0, "destroy": 0, "replace": 0, "other": 0}
iam, stateful, replaced = [], [], []
STATEFUL = (
    "aws_s3_bucket", "aws_dynamodb_table", "aws_neptune_cluster", "aws_neptune_cluster_instance",
    "aws_sqs_queue", "aws_kms_key", "aws_s3vectors_vector_bucket", "aws_s3vectors_index",
    "aws_secretsmanager_secret", "aws_ecr_repository", "aws_emr_cluster", "aws_cloudwatch_log_group",
    "aws_efs_file_system", "aws_sns_topic",
)
for rc in changes:
    k = kind(rc["change"]["actions"])
    if k is None:
        continue
    counts[k] += 1
    line = "%-8s %s" % (k, rc["address"])
    if rc["type"].startswith("aws_iam_") or rc["type"] in ("aws_kms_key", "aws_kms_alias", "aws_s3_bucket_policy", "aws_sqs_queue_policy", "aws_sns_topic_policy"):
        iam.append(line)
    if rc["type"] in STATEFUL:
        stateful.append(line)
    if k in ("replace", "destroy") and rc["type"] in STATEFUL:
        replaced.append(line)

add = counts["add"] + counts["replace"]
destroy = counts["destroy"] + counts["replace"]
print("PLAN_SUMMARY env=%s git_sha=%s offline=%s dirty=%s" % (env, sha, offline, dirty))
print("PLAN_FILE=%s" % plan_file)
print("PLAN_SHA256=%s" % digest)
print("PLAN_COUNTS add=%d change=%d destroy=%d (replacements counted in both add and destroy: %d)" % (add, counts["change"], destroy, counts["replace"]))
print("")
print("IAM_AND_POLICY_CHANGES (%d)" % len(iam))
for l in sorted(iam):
    print("  " + l)
print("")
print("STATEFUL_CHANGES (%d)" % len(stateful))
for l in sorted(stateful):
    print("  " + l)
print("")
print("STATEFUL_REPLACEMENTS_OR_DESTROYS (%d)" % len(replaced))
for l in sorted(replaced):
    print("  " + l)
PY

cat "$SUMMARY_FILE"

step "write build/plan-summary.json"
mkdir -p "$SC_BUILD_DIR"
python3 -I - "${PLAN_FILE%.tfplan}.plan.json" "$SC_BUILD_DIR/plan-summary.json" "$ENV_NAME" "$GIT_SHA" \
  "$PLAN_SHA256" "$PLAN_FILE" "$ACCOUNT_ID" "$SC_REGION" "$OFFLINE" "$ALLOW_DIRTY" "$(utc_now)" <<'PY'
import json
import sys

plan_json, out, env, sha, digest, plan_file, account, region, offline, dirty, now = sys.argv[1:12]
data = json.load(open(plan_json))
STATEFUL = {
    "aws_s3_bucket", "aws_dynamodb_table", "aws_neptune_cluster", "aws_neptune_cluster_instance",
    "aws_sqs_queue", "aws_kms_key", "aws_s3vectors_vector_bucket", "aws_s3vectors_index",
    "aws_secretsmanager_secret", "aws_ecr_repository", "aws_emr_cluster", "aws_cloudwatch_log_group",
    "aws_efs_file_system", "aws_sns_topic",
}
POLICY = {"aws_kms_key", "aws_kms_alias", "aws_s3_bucket_policy", "aws_sqs_queue_policy", "aws_sns_topic_policy"}
add = change = destroy = 0
iam, stateful = [], []
for rc in data.get("resource_changes", []):
    actions = sorted(rc["change"]["actions"])
    if actions in (["no-op"], ["read"]):
        continue
    if actions == ["create"]:
        add += 1
    elif actions == ["update"]:
        change += 1
    elif actions == ["delete"]:
        destroy += 1
    elif actions == ["create", "delete"]:
        add += 1
        destroy += 1
    entry = {"address": rc["address"], "type": rc["type"], "actions": rc["change"]["actions"]}
    if rc["type"].startswith("aws_iam_") or rc["type"] in POLICY:
        iam.append(entry)
    if rc["type"] in STATEFUL:
        stateful.append(entry)
doc = {
    "environment": env, "commitSha": sha, "dirty": dirty == "true", "offline": offline == "true",
    "generatedAtUtc": now, "account": account, "region": region,
    "planFile": plan_file, "planSha256": digest,
    "counts": {"add": add, "change": change, "destroy": destroy},
    "iamChanges": sorted(iam, key=lambda e: e["address"]),
    "statefulResourceActions": sorted(stateful, key=lambda e: e["address"]),
}
with open(out, "w") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")
PY
info "PLAN_SUMMARY_JSON=$SC_BUILD_DIR/plan-summary.json"

step "reject placeholder tags"
OWNER_TAG="$(python3 -I - "${PLAN_FILE%.tfplan}.plan.json" <<'PY'
import json
import sys

d = json.load(open(sys.argv[1]))
for rc in d.get("resource_changes", []):
    tags = (rc["change"].get("after") or {}).get("tags_all") or {}
    if "owner" in tags:
        print(tags["owner"])
        break
PY
)"
if [[ "$OWNER_TAG" == "CHANGE_ME" || -z "$OWNER_TAG" ]]; then
  if [[ "$OFFLINE" == true || "$ALLOW_DIRTY" == true ]]; then
    warn "owner tag is '${OWNER_TAG:-unset}' (tolerated for an offline or dirty review plan)"
  else
    die "owner tag is '${OWNER_TAG:-unset}': set owner in terraform.tfvars before a reviewable plan"
  fi
fi

step "done: hand the plan to the human architect"
info "Review $LOG_FILE and $SUMMARY_FILE. Nothing was applied."
if [[ "$ALLOW_DIRTY" == true ]]; then
  warn "dirty=true: this plan cannot be approved or deployed (deploy.sh refuses *.DIRTY.tfplan)"
  exit 0
fi
info "Human-only next step, after review of account, Region and cost: apply exactly this saved file by hand:"
info "  terraform -chdir=$ENV_DIR apply $PLAN_FILE"
info "Then approve the application rollout by exporting APPROVED_PLAN_SHA256=$PLAN_SHA256 for scripts/deploy.sh."
