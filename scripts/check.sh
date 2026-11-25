#!/usr/bin/env bash
# check.sh - the local, credential-free quality gate for the whole repository.
#
#   scripts/check.sh [--skip-sbt] [--skip-terraform] [--skip-shellcheck] [--help]
#
# Gates (every gate runs; the script exits 1 at the end if any failed, 4 if none failed but some
# were skipped, 0 only if EVERY gate ran and passed):
#   agent-files   .claude/hooks/validate-agent-files.sh --all
#   json          jq parses every *.json under .claude/ and .mcp.json
#   bash-syntax   bash -n on every script in .claude/hooks/, scripts/ and scripts/lib/
#   lint          the ShellCheck tool on the scripts/ files (FAILS if shellcheck is not installed,
#                 unless --skip-shellcheck is passed explicitly)
#   sbt           sbt check = scalafmtCheckAll, scalafmtSbtCheck, Test/compile, tests
#                 (all modules, contract tests) and markTestRun
#   contracts     dataset manifest and sample validators (datasets/scripts), no network
#   schemas       jq parses prompts/manifest.json, prompts/schemas/*.json and the
#                 entity-resolution schemas
#   python-tests  python3 -m unittest discover on experiments/tests and load-tests/tests (offline
#                 fakes; needs PyYAML). Both suites run; either one failing fails the gate
#   terraform     terraform fmt -check -recursive infra/terraform, then for every directory under
#                 infra/terraform/environments: init -backend=false and validate
#
# Needs no AWS credentials and makes no AWS call. terraform init downloads the pinned provider
# from the public registry (network access, no credentials).
#
# Required environment variables: none.
# Optional environment variables: SC_SBT_TIMEOUT (seconds, default 5400),
#   SC_TF_TIMEOUT (seconds per terraform command, default 600), SC_PY_TIMEOUT (seconds per Python
#   suite, default 600), SC_SBT_FULL_LOG=1 (do not filter the sbt output to its own
#   [info]/[warn]/[error]/[success] lines).
# Exit codes: 0 every gate ran and passed ("CHECK RESULT: PASS"),
#   1 at least one gate failed ("CHECK RESULT: FAIL"),
#   2 usage error,
#   4 no gate failed but at least one was skipped by flag ("CHECK RESULT: INCOMPLETE (<n> skipped)").
#   Exit 4 is deliberate: a partial run is never reported as a pass. In an INCOMPLETE run every
#   gate that did run passed; the skipped gates were not checked. If a gate failed AND another was
#   skipped, the result is FAIL (exit 1). Skipping a gate is therefore never enough for a "done" claim:
#   a run with --skip-sbt (the sbt gate takes tens of minutes) proves everything except sbt check.
# Evidence: the gate summary below (greppable lines "GATE <name>: PASS|FAIL|SKIP").

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

SKIP_SBT=false
SKIP_TF=false
SKIP_SHELLCHECK=false
SBT_TIMEOUT="${SC_SBT_TIMEOUT:-5400}"
TF_TIMEOUT="${SC_TF_TIMEOUT:-600}"
PY_TIMEOUT="${SC_PY_TIMEOUT:-600}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-sbt) SKIP_SBT=true; shift ;;
    --skip-terraform) SKIP_TF=true; shift ;;
    --skip-shellcheck) SKIP_SHELLCHECK=true; shift ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

cd "$SC_REPO_ROOT"
declare -a RESULTS=()
FAILED=0
SKIPPED=0

# gate <name> <function>: runs the function in a subshell with errexit on, records the result.
gate() {
  local name="$1" fn="$2" rc=0
  step "gate $name"
  # A subshell run as its own command keeps errexit active inside the gate function
  # (errexit is ignored inside anything on the left of ||, &&, or an if condition).
  set +e
  ( set -e; "$fn" )
  rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    RESULTS+=("GATE $name: PASS")
  else
    RESULTS+=("GATE $name: FAIL (exit $rc)")
    FAILED=$((FAILED + 1))
  fi
}

skip() {
  step "gate $1 skipped: $2"
  RESULTS+=("GATE $1: SKIP ($2)")
  SKIPPED=$((SKIPPED + 1))
}

shell_files() {
  find .claude/hooks scripts -type f -name '*.sh' | sort
}

g_agent_files() {
  .claude/hooks/validate-agent-files.sh --all
}

g_json() {
  local f n=0
  require_cmd jq
  while IFS= read -r f; do
    jq empty "$f"
    n=$((n + 1))
  done < <({ find .claude -type f -name '*.json' | sort; [[ -f .mcp.json ]] && printf '%s\n' .mcp.json; } || true)
  info "parsed $n JSON file(s)"
}

g_bash_syntax() {
  local f n=0
  while IFS= read -r f; do
    bash -n "$f"
    n=$((n + 1))
  done < <(shell_files)
  info "bash -n passed on $n script(s)"
}

g_shellcheck() {
  # Only scripts/ (this territory) is linted. The hooks under .claude/ belong to
  # factory-engineer and get the bash -n gate only.
  local -a files
  mapfile -t files < <(shell_files | grep -v '^\.claude/')
  shellcheck -x -P SCRIPTDIR "${files[@]}"
}

g_sbt() {
  require_cmd sbt
  if [[ "${SC_SBT_FULL_LOG:-0}" == "1" ]]; then
    timeout --foreground "$SBT_TIMEOUT" sbt -batch check
  else
    # The Spark and Hadoop tests print gigabytes of repeated stack traces (3.6 GB in one run).
    # Show only sbt's own lines ([info], [warn], [error], [success]); the exit status is sbt's.
    # Set SC_SBT_FULL_LOG=1 for the unfiltered stream.
    timeout --foreground "$SBT_TIMEOUT" sbt -batch check 2>&1 |
      grep -a --line-buffered -E '^\[(info|warn|error|success)\]'
  fi
}

g_contracts() {
  datasets/scripts/validate-manifest.sh
  datasets/scripts/validate-samples.sh
}

g_schemas() {
  local f n=0
  require_cmd jq
  while IFS= read -r f; do
    jq empty "$f"
    n=$((n + 1))
  done < <(find prompts datasets modules/entity-resolution/src/main/resources \
    -type f \( -name 'manifest.json' -o -name '*.schema.json' \) -not -path 'datasets/raw/*' | sort)
  [[ "$n" -gt 0 ]] || die "no schema files found"
  info "parsed $n schema/manifest file(s)"
}

g_python_tests() {
  local d rc=0
  require_cmd python3 timeout
  for d in experiments/tests load-tests/tests; do
    [[ -d "$d" ]] || die "missing test directory: $d"
    info "python tests in $d"
    # -B: no __pycache__ in the tree
    timeout --foreground "$PY_TIMEOUT" python3 -B -m unittest discover -s "$d" || rc=1
  done
  return "$rc"
}

g_terraform() {
  local d name
  require_cmd terraform
  terraform -chdir=infra/terraform fmt -check -recursive
  for d in infra/terraform/environments/*/; do
    name="$(basename "$d")"
    info "environment $name"
    TF_IN_AUTOMATION=1 timeout --foreground "$TF_TIMEOUT" terraform -chdir="$d" init -backend=false -input=false -no-color
    TF_IN_AUTOMATION=1 timeout --foreground "$TF_TIMEOUT" terraform -chdir="$d" validate -no-color
  done
}

gate agent-files g_agent_files
gate json g_json
gate bash-syntax g_bash_syntax
if [[ "$SKIP_SHELLCHECK" == true ]]; then
  skip shellcheck "--skip-shellcheck"
elif ! command -v shellcheck >/dev/null 2>&1; then
  warn "shellcheck is not installed: the lint gate FAILS (install it, or pass --skip-shellcheck explicitly)"
  step "gate shellcheck"
  RESULTS+=("GATE shellcheck: FAIL (shellcheck not installed)")
  FAILED=$((FAILED + 1))
else
  gate shellcheck g_shellcheck
fi
if [[ "$SKIP_SBT" == true ]]; then skip sbt "--skip-sbt"; else gate sbt g_sbt; fi
gate contracts g_contracts
gate schemas g_schemas
gate python-tests g_python_tests
if [[ "$SKIP_TF" == true ]]; then skip terraform "--skip-terraform"; else gate terraform g_terraform; fi

step "summary"
printf '%s\n' "${RESULTS[@]}"
if [[ "$FAILED" -gt 0 ]]; then
  echo "CHECK RESULT: FAIL ($FAILED gate(s) failed, $SKIPPED skipped)"
  exit 1
fi
if [[ "$SKIPPED" -gt 0 ]]; then
  echo "CHECK RESULT: INCOMPLETE ($SKIPPED skipped)"
  echo "NOTE: every gate that ran passed; the skipped gate(s) above were NOT checked. Exit status 4 is intended."
  exit 4
fi
echo "CHECK RESULT: PASS"
