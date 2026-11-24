# shellcheck shell=bash
# Shared helpers for the ScholarChain operational scripts. Sourced, never executed.
# Conventions every script follows:
#   - bash strict mode, no interactive prompt, no `set -x` (arguments can hold tokens)
#   - progress markers are greppable:  ==> STEP <n>: <text>
#   - failures end the script with a non-zero status and an "ERROR:" line on stderr
#   - every cloud or network call has a timeout
#   - plan files and records live under /tmp/scholarchain-plans, never in the repository

SC_PLAN_DIR="${SC_PLAN_DIR:-/tmp/scholarchain-plans}"
SC_STEP_NO=0

# Absolute path of the repository root (the directory above scripts/).
SC_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

step() {
  SC_STEP_NO=$((SC_STEP_NO + 1))
  printf '==> STEP %s: %s\n' "$SC_STEP_NO" "$*"
}

info() { printf 'INFO: %s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "required command not found: $c"
  done
}

# An environment name is lower-case letters and digits, 2 to 10 characters.
validate_env_name() {
  [[ "$1" =~ ^[a-z][a-z0-9]{1,9}$ ]] || die "invalid --env '$1' (2-10 lowercase letters and digits)"
}

# The environment must be listed in SCHOLARCHAIN_APPROVED_ENVS (comma separated), which
# only the human architect sets before starting the session. The guard hook enforces the
# same rule; this check keeps the script safe when it is run by hand.
require_approved_env() {
  local env="$1" approved e
  approved="${SCHOLARCHAIN_APPROVED_ENVS:-}"
  [[ -n "$approved" ]] || die "SCHOLARCHAIN_APPROVED_ENVS is not set; no environment is approved"
  IFS=',' read -r -a list <<<"$approved"
  for e in "${list[@]}"; do
    [[ "${e// /}" == "$env" ]] && return 0
  done
  die "environment '$env' is not listed in SCHOLARCHAIN_APPROVED_ENVS"
}

env_dir() {
  local d="$SC_REPO_ROOT/infra/terraform/environments/$1"
  [[ -d "$d" ]] || die "no Terraform environment directory: $d"
  printf '%s' "$d"
}

# Short Git SHA of HEAD, the same value everywhere (plan file name, image tag, variable).
head_sha() { git -C "$SC_REPO_ROOT" rev-parse --short HEAD; }

# Refuses any uncommitted or untracked change.
require_clean_tree() {
  local dirty
  dirty="$(git -C "$SC_REPO_ROOT" status --porcelain)"
  if [[ -n "$dirty" ]]; then
    printf '%s\n' "$dirty" >&2
    die "working tree is dirty; commit or stash before this step"
  fi
}

# aws CLI with connect and read timeouts and an overall deadline. Read-only callers and
# the rollout steps in deploy.sh and rollback.sh use it; nothing here creates infrastructure.
aws_call() {
  local -a profile=()
  [[ -n "${SC_PROFILE:-}" ]] && profile=(--profile "$SC_PROFILE")
  timeout --foreground "${SC_AWS_TIMEOUT:-90}" aws --cli-connect-timeout 10 --cli-read-timeout 60 \
    "${profile[@]}" --region "${SC_REGION:?SC_REGION is not set}" "$@"
}

account_id() {
  aws_call sts get-caller-identity --query Account --output text
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

plan_path() { printf '%s/%s-%s.tfplan' "$SC_PLAN_DIR" "$1" "$2"; }

# Prints one line "<key>=<value>" per requested field of a saved plan's input variables.
plan_var() {
  local env="$1" plan="$2" name="$3"
  terraform -chdir="$(env_dir "$env")" show -json "$plan" |
    python3 -I -c "import json,sys; print(json.load(sys.stdin)['variables']['$name']['value'])"
}
