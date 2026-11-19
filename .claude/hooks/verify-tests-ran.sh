#!/usr/bin/env bash
# .claude/hooks/verify-tests-ran.sh
#
# Owner: factory-engineer (constitutional; floor invariant 2 - never weaken).
# Events: Stop, SubagentStop
#
# Refuses completion (exit 2, reason on stderr) while the repository test
# marker .claude/.last-test-run is missing or older than any relevant source
# change. The marker is written only by 'sbt markTestRun', which 'sbt check'
# runs after tests pass. A stale marker therefore means the current tree has
# no passing test run.
#
# Relevant sources: build.sbt, project/, modules/, tests/, prompts/, datasets/.
# Build outputs (target/, .bsp/, .metals/, .bloop/) are ignored.
#
# Honest-failure exit: an agent that cannot get tests to pass may stop only
# if its final message declares 'STATUS: FAILED' or 'STATUS: BLOCKED-ON'. It
# can never stop with 'STATUS: COMPLETE' on a stale tree.
#
# SubagentStop exemption: agents whose write territory contains no
# test-relevant sources (code-reviewer, deploy-engineer, incident-responder,
# documentation-engineer, infra-engineer, factory-engineer) cannot make the
# tree stale; guard-dangerous.sh enforces their territories. They are not
# trapped by staleness someone else caused. The orchestrator's Stop is always
# checked.
#
# Degrades gracefully: needs only find(1). Uses jq or python3, when present,
# to read hook fields. sbt is not invoked here. When sbt is absent, the message
# says so, and the agent must report BLOCKED-ON.

set -u
set -o pipefail

INPUT="$(cat 2>/dev/null || true)"

field() {
  local path="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$INPUT" | jq -r --arg p "$path" \
      'try (getpath($p | split(".")) // empty | if type == "string" then . else tojson end) catch empty' 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$INPUT" | python3 -I -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    sys.exit(0)
v = d.get(sys.argv[1]) if isinstance(d, dict) else None
if v is not None:
    print(v if isinstance(v, str) else json.dumps(v))
' "$path" 2>/dev/null
  else
    printf '%s' "$INPUT" | tr '\n' ' ' |
      grep -o "\"$path\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n 1 |
      sed -E 's/^"[^"]*"[[:space:]]*:[[:space:]]*"(.*)"$/\1/'
  fi
}

EVENT="$(field hook_event_name)"
AGENT="$(field agent_type)"

if [[ "$EVENT" == "SubagentStop" ]]; then
  case "$AGENT" in
    code-reviewer | deploy-engineer | incident-responder | documentation-engineer | infra-engineer | factory-engineer)
      exit 0
      ;;
  esac
fi

ROOT="${CLAUDE_PROJECT_DIR:-$(field cwd)}"
[[ -z "$ROOT" ]] && ROOT="$(pwd)"
ROOT="${ROOT%/}"
cd "$ROOT" 2>/dev/null || exit 0

MARKER=".claude/.last-test-run"

roots=()
for r in build.sbt project modules tests prompts datasets; do
  [[ -e "$r" ]] && roots+=("$r")
done
[[ ${#roots[@]} -eq 0 ]] && exit 0

prune=(\( -name target -o -name .bsp -o -name .metals -o -name .bloop -o -name .scala-build -o -name node_modules -o -name .idea \) -prune)

if [[ -f "$MARKER" ]]; then
  STALE="$(find "${roots[@]}" "${prune[@]}" -o -type f -newer "$MARKER" -print 2>/dev/null | head -n 5)"
else
  STALE="$(find "${roots[@]}" "${prune[@]}" -o -type f -print 2>/dev/null | head -n 5)"
fi
[[ -z "$STALE" ]] && exit 0

# Honest-failure exit: inspect the agent's final message.
LAST="$(field last_assistant_message)"
if [[ -z "$LAST" ]]; then
  TRANSCRIPT="$(field agent_transcript_path)"
  [[ -z "$TRANSCRIPT" ]] && TRANSCRIPT="$(field transcript_path)"
  if [[ -n "$TRANSCRIPT" && -f "$TRANSCRIPT" ]]; then
    if command -v jq >/dev/null 2>&1; then
      LAST="$(tail -n 200 "$TRANSCRIPT" | jq -rs '
        [ .[] | select(.type == "assistant") ] | last | .message.content
        | if type == "array" then map(select(.type == "text") | .text) | join("\n")
          elif type == "string" then . else "" end' 2>/dev/null)"
    else
      LAST="$(tail -n 20 "$TRANSCRIPT" 2>/dev/null)"
    fi
  fi
fi
if printf '%s' "$LAST" | grep -Eq 'STATUS:[[:space:]]*(FAILED|BLOCKED-ON)'; then
  echo "verify-tests-ran.sh: tests are stale, but the agent reported FAILED/BLOCKED-ON honestly; stop allowed." >&2
  exit 0
fi

SBT_NOTE=""
command -v sbt >/dev/null 2>&1 || SBT_NOTE=" sbt is not installed in this environment: report STATUS: BLOCKED-ON with evidence."
[[ -f build.sbt ]] || SBT_NOTE="$SBT_NOTE There is no build.sbt yet: report STATUS: BLOCKED-ON build-engineer."

{
  echo "verify-tests-ran.sh: completion refused - tests are stale."
  if [[ -f "$MARKER" ]]; then
    echo "These files changed after the last passing test run ($MARKER):"
  else
    echo "No passing test run is recorded ($MARKER missing). Relevant files present:"
  fi
  printf '%s\n' "$STALE" | sed 's/^/  /'
  echo "Run 'sbt check' (it updates the marker only when tests pass), then finish."
  echo "If tests fail and the fix is outside your territory, end with 'STATUS: FAILED' or"
  echo "'STATUS: BLOCKED-ON <owner>' plus the failing output. Never claim COMPLETE.$SBT_NOTE"
} >&2
exit 2
