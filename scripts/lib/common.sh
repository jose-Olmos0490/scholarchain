# shellcheck shell=bash
# Shared helpers for the ScholarChain operational scripts. Sourced, never executed.
# Conventions every script follows:
#   - bash strict mode, no interactive prompt, no `set -x` (arguments can hold tokens)
#   - progress markers are greppable:  ==> [<script>] STEP: <text>
#   - exit codes: 0 success, 1 failed gate or runtime error, 2 usage error, 3 dry run (cleanup.sh)
#   - failures end the script with a non-zero status and an "ERROR:" line on stderr
#   - every cloud or network call has a timeout
#   - no Region is hardcoded: --region, then AWS_REGION, AWS_DEFAULT_REGION, the profile's Region
#   - plan files live under /tmp/scholarchain-plans, never in the repository; generated JSON
#     records live under build/ and evidence/ (untracked outputs, ignored by require_clean_tree)

SC_PLAN_DIR="${SC_PLAN_DIR:-/tmp/scholarchain-plans}"
SC_STEP_NO=0
SC_SCRIPT="$(basename "${BASH_SOURCE[1]:-$0}")"
SC_SCRIPT_FILE="${BASH_SOURCE[1]:-$0}"

# Absolute path of the repository root (the directory above scripts/).
SC_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Directory for generated, untracked records (checksums, summaries, manifests).
SC_BUILD_DIR="${SC_BUILD_DIR:-$SC_REPO_ROOT/build}"

step() {
  SC_STEP_NO=$((SC_STEP_NO + 1))
  printf '==> [%s] STEP: %s\n' "$SC_SCRIPT" "$*"
}

# Prints the leading comment block of the calling script (its usage text, including the names
# of the required environment variables) and exits 0.
print_help() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SC_SCRIPT_FILE"
  exit 0
}

usage_error() {
  printf 'ERROR: %s\n' "$*" >&2
  printf 'Run %s --help for usage.\n' "$SC_SCRIPT" >&2
  exit 2
}

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

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

# Refuses any uncommitted or untracked change. The only tolerated entries are untracked files
# under build/ and evidence/, the generated outputs of these scripts (a tracked file that
# changes under those paths is still refused).
require_clean_tree() {
  local status dirty rc=0
  # Capture git's own output and status first: a git failure (not a repository, corrupt index)
  # must stop the script, never read as a clean tree.
  status="$(git -C "$SC_REPO_ROOT" status --porcelain --untracked-files=all)" ||
    die "git status failed; cannot prove the working tree is clean"
  # grep exit 1 (no line left after the filter) means clean; any other non-zero status is an error.
  dirty="$(printf '%s' "$status" | grep -Ev '^\?\? (build|evidence)/')" || rc=$?
  if [[ "$rc" -gt 1 ]]; then
    die "could not filter the git status output (grep exit $rc)"
  fi
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

# Bucket names follow the Terraform naming convention (modules/datalake):
#   scholarchain-<env>-<area>-<account>-<region>
# SC_<AREA>_BUCKET overrides the derived name (for example SC_ARTIFACTS_BUCKET).
bucket_name() {
  local env="$1" area="$2" account="$3" override_var
  override_var="SC_$(printf '%s' "$area" | tr '[:lower:]' '[:upper:]')_BUCKET"
  if [[ -n "${!override_var:-}" ]]; then
    printf '%s' "${!override_var}"
  else
    printf 'scholarchain-%s-%s-%s-%s' "$env" "$area" "$account" "$SC_REGION"
  fi
}

# Newest file (by mtime) under a directory matching a -name pattern, or nothing.
newest_file() {
  find "$1" -name "$2" -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2-
}

# retry <attempts> <command...>: re-runs a command that failed, with a growing pause. Use it
# ONLY for idempotent calls (describe, list, get, head, an upload of the same object to the
# same key). Never wrap a call that creates something, starts an execution or pushes a tag.
retry() {
  local attempts="$1" n=1 pause=5
  shift
  until "$@"; do
    if [[ "$n" -ge "$attempts" ]]; then
      return 1
    fi
    warn "attempt $n of $attempts failed; retrying in ${pause}s: $1 $2"
    sleep "$pause"
    n=$((n + 1))
    pause=$((pause * 2))
  done
}

# Validates a Region string so a typo cannot reach a cloud call.
validate_region() {
  [[ "$1" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die "invalid Region '$1'"
}

# resolve_region: no Region is hardcoded anywhere. Precedence: --region (already in SC_REGION),
# AWS_REGION, AWS_DEFAULT_REGION, then the Region configured for the profile (SC_PROFILE, or the
# default profile). Fails with a clear message when none is resolvable. Call it after argument
# parsing in every script that talks to AWS.
resolve_region() {
  if [[ -z "${SC_REGION:-}" ]]; then SC_REGION="${AWS_REGION:-}"; fi
  if [[ -z "$SC_REGION" ]]; then SC_REGION="${AWS_DEFAULT_REGION:-}"; fi
  if [[ -z "$SC_REGION" ]] && command -v aws >/dev/null 2>&1; then
    local -a profile=()
    [[ -z "${SC_PROFILE:-}" ]] || profile=(--profile "$SC_PROFILE")
    SC_REGION="$(timeout --foreground 20 aws "${profile[@]}" configure get region 2>/dev/null)" || SC_REGION=""
  fi
  [[ -n "$SC_REGION" ]] ||
    die "no AWS Region: pass --region, or set AWS_REGION or AWS_DEFAULT_REGION, or configure a Region for the profile"
  validate_region "$SC_REGION"
}

# assert_private_dir <dir>: the directory must exist, be owned by the invoking user and have mode
# 700 exactly, so no other local user can swap or edit files in it between checks and use.
assert_private_dir() {
  local d="$1" owner mode
  [[ -d "$d" && ! -L "$d" ]] || die "$d is not a plain directory"
  owner="$(stat -c %u "$d")"
  mode="$(stat -c %a "$d")"
  [[ "$owner" == "$(id -u)" ]] || die "$d is not owned by the invoking user (owner uid $owner)"
  [[ "$mode" == "700" ]] || die "$d has mode $mode, expected 700; fix with: chmod 700 $d"
}
