#!/usr/bin/env bash
# .claude/hooks/format-scala.sh
#
# Owner: factory-engineer (constitutional; floor invariant 3 - never weaken).
# Event: PostToolUse   Matcher: Write|Edit|MultiEdit
#
# Formats the edited Scala source (*.scala, *.sc, *.sbt) with scalafmt using
# the repository .scalafmt.conf.
#   - Non-Scala file, missing file, or no file path: exit 0 silently (harmless).
#   - scalafmt or .scalafmt.conf unavailable: exit 0 and tell the agent via
#     additionalContext that formatting was skipped and that 'sbt check' (which
#     includes format checking) remains the gate. Nothing is hidden.
#   - scalafmt fails (for example, the edit left unparseable code): exit 2 with
#     scalafmt's output on stderr, so the agent sees the failure and must repair
#     it. Formatter failures are never swallowed.

set -u
set -o pipefail

INPUT="$(cat 2>/dev/null || true)"

get_path() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$INPUT" | jq -r 'try (.tool_input.file_path // .tool_input.notebook_path // empty) catch empty' 2>/dev/null
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$INPUT" | python3 -I -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    sys.exit(0)
ti = d.get("tool_input") or {}
p = ti.get("file_path") or ti.get("notebook_path") or ""
print(p)
' 2>/dev/null
  else
    printf '%s' "$INPUT" | tr '\n' ' ' |
      grep -o '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -n 1 |
      sed -E 's/^"file_path"[[:space:]]*:[[:space:]]*"(.*)"$/\1/'
  fi
}

note() {
  # Emit PostToolUse additionalContext (static, JSON-safe text only).
  printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"%s"}}\n' "$1"
  exit 0
}

FILE="$(get_path)"
[[ -z "$FILE" ]] && exit 0

case "$FILE" in
  *.scala | *.sc | *.sbt) ;;
  *) exit 0 ;;
esac

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ROOT="${ROOT%/}"
[[ "$FILE" != /* ]] && FILE="$ROOT/$FILE"
[[ -f "$FILE" ]] || exit 0

CONF="$ROOT/.scalafmt.conf"
if [[ ! -f "$CONF" ]]; then
  note "format-scala.sh: .scalafmt.conf not found (owned by build-engineer); formatting skipped. sbt check remains the format gate."
fi

FMT=()
if command -v scalafmt >/dev/null 2>&1; then
  FMT=(scalafmt)
elif command -v cs >/dev/null 2>&1; then
  FMT=(cs launch scalafmt --)
elif command -v coursier >/dev/null 2>&1; then
  FMT=(coursier launch scalafmt --)
else
  note "format-scala.sh: scalafmt not installed; formatting skipped. Run sbt fmt; sbt check enforces formatting."
fi

if OUT="$("${FMT[@]}" --non-interactive --quiet --config "$CONF" "$FILE" 2>&1)"; then
  exit 0
fi

{
  echo "format-scala.sh: scalafmt FAILED on $FILE"
  echo "The edit was written but does not format (often a syntax error). Fix the file; do not report completion."
  printf '%s\n' "$OUT" | tail -n 40
} >&2
exit 2
