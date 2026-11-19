#!/usr/bin/env bash
# .claude/hooks/validate-agent-files.sh
#
# Owner: factory-engineer (constitutional).
# Usage:
#   .claude/hooks/validate-agent-files.sh --all    # full report; exit 0 pass, 1 fail
#   .claude/hooks/validate-agent-files.sh --hook   # PostToolUse on Write|Edit|MultiEdit:
#        acts only when a constitutional file was written; exit 2 + stderr on failure
#
# Validates:
#   frontmatter (name, description, tools; name == filename), unique names,
#   required roster, tool fences, MCP tool references, one-writer ownership
#   (by probing guard-dangerous.sh), ownership and routing tables in
#   docs/agents.md, routing collisions and orphans, hook wiring, executable
#   bits, shell syntax, JSON syntax, floor-invariant rules, commands naming
#   responsible agents and gates, MCP server documentation and secret hygiene,
#   and dangerous-command guard probes.
#
# Degrades gracefully: JSON checks use jq or python3. If neither exists, they
# are reported as SKIP, and --all exits 1 (incomplete is not success).
# The shellcheck linter is optional (SKIP when absent).

set -u
set -o pipefail

MODE="all"
[[ "${1:-}" == "--hook" ]] && MODE="hook"

ROOT="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$ROOT" ]]; then
  ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || pwd)"
fi
ROOT="${ROOT%/}"
cd "$ROOT" || exit 1

if [[ "$MODE" == "hook" ]]; then
  INPUT="$(cat 2>/dev/null || true)"
  FP=""
  if command -v jq >/dev/null 2>&1; then
    FP="$(printf '%s' "$INPUT" | jq -r 'try (.tool_input.file_path // empty) catch empty' 2>/dev/null)"
  elif command -v python3 >/dev/null 2>&1; then
    FP="$(printf '%s' "$INPUT" | python3 -I -c 'import json,sys
try: print((json.loads(sys.stdin.read()).get("tool_input") or {}).get("file_path",""))
except Exception: pass' 2>/dev/null)"
  else
    FP="$(printf '%s' "$INPUT" | tr '\n' ' ' | grep -o '"file_path"[[:space:]]*:[[:space:]]*"[^"]*"' | head -n 1 | sed -E 's/.*:[[:space:]]*"(.*)"$/\1/')"
  fi
  REL="${FP#"$ROOT"/}"
  REL="${REL#./}"
  case "$REL" in
    CLAUDE.md | docs/agents.md | .mcp.json | .claude/settings.json | .claude/agents/* | .claude/hooks/* | .claude/commands/*) ;;
    *) exit 0 ;;
  esac
fi

GUARD="$ROOT/.claude/hooks/guard-dangerous.sh"
SETTINGS="$ROOT/.claude/settings.json"
MCP="$ROOT/.mcp.json"
AGENTS_MD="$ROOT/docs/agents.md"

REQUIRED_AGENTS=(factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer
  entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer
  test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer
  documentation-engineer incident-responder dependency-updater)
REQUIRED_HOOKS=(guard-dangerous.sh format-scala.sh verify-tests-ran.sh validate-agent-files.sh)
REQUIRED_COMMANDS=(deploy rollback incident run-pipeline experiment)

OUT=""
FAILS=0
SKIPS=0
PASSES=0
pass() { OUT+="PASS  $*"$'\n'; PASSES=$((PASSES + 1)); }
fail() { OUT+="FAIL  $*"$'\n'; FAILS=$((FAILS + 1)); }
skip() { OUT+="SKIP  $*"$'\n'; SKIPS=$((SKIPS + 1)); }
section() { OUT+=$'\n'"== $* =="$'\n'; }

JSONP=none
command -v jq >/dev/null 2>&1 && JSONP=jq
[[ "$JSONP" == none ]] && command -v python3 >/dev/null 2>&1 && JSONP=python

json_ok() {
  case "$JSONP" in
    jq) jq empty "$1" >/dev/null 2>&1 ;;
    python) python3 -I -m json.tool "$1" >/dev/null 2>&1 ;;
    *) return 2 ;;
  esac
}
# json_q <file> <jq-expr> : prints lines (jq only; python fallback for the few expressions used)
json_q() {
  if [[ "$JSONP" == jq ]]; then
    jq -r "$2" "$1" 2>/dev/null
  elif [[ "$JSONP" == python ]]; then
    python3 -I - "$1" "$2" <<'PY' 2>/dev/null
import json, sys
d = json.load(open(sys.argv[1]))
e = sys.argv[2]
def out(x):
    for i in x: print(i)
if e == ".mcpServers | keys[]": out(sorted(d.get("mcpServers", {}).keys()))
elif e == ".enabledMcpjsonServers[]": out(d.get("enabledMcpjsonServers", []))
elif e == ".permissions.deny[]": out(d.get("permissions", {}).get("deny", []))
elif e.startswith(".hooks | to_entries[]"):
    for k, v in d.get("hooks", {}).items():
        for g in v:
            for h in g.get("hooks", []):
                print(f"{k}\t{g.get('matcher') or '-'}\t{h.get('command','')}")
elif e.startswith("[.. | strings]"):
    def walk(x):
        if isinstance(x, dict):
            for v in x.values(): yield from walk(v)
        elif isinstance(x, list):
            for v in x: yield from walk(v)
        elif isinstance(x, str): yield x
    out(walk(d))
PY
  fi
}

fm_get() {
  # fm_get <file> <key> : value of a single-line frontmatter key
  awk -v k="$2" 'NR==1 && $0!="---" {exit} NR>1 && $0=="---" {exit} NR>1 { if (index($0, k":")==1) { sub("^" k ":[ \t]*", ""); print; exit } }' "$1"
}

# ---------------------------------------------------------------------------
section "JSON syntax"
for f in "$SETTINGS" "$MCP"; do
  if [[ ! -f "$f" ]]; then fail "missing ${f#"$ROOT"/}"; continue; fi
  json_ok "$f"
  rc=$?
  if [[ $rc -eq 0 ]]; then pass "parses: ${f#"$ROOT"/} ($JSONP)"
  elif [[ $rc -eq 2 ]]; then skip "no JSON parser (jq/python3) for ${f#"$ROOT"/}"
  else fail "invalid JSON: ${f#"$ROOT"/}"; fi
done

# ---------------------------------------------------------------------------
section "Agent roster and frontmatter"
declare -A SEEN_NAME=()
declare -A TOOLS_OF=()
declare -A DESC_OF=()
for a in "${REQUIRED_AGENTS[@]}"; do
  [[ -f ".claude/agents/$a.md" ]] && pass "required agent present: $a" || fail "required agent missing: .claude/agents/$a.md"
done
for f in .claude/agents/*.md; do
  [[ -e "$f" ]] || continue
  stem="$(basename "$f" .md)"
  found=0
  for a in "${REQUIRED_AGENTS[@]}"; do [[ "$a" == "$stem" ]] && found=1; done
  [[ $found -eq 1 ]] || fail "orphan agent not in authority matrix: $f"
  [[ "$(head -n 1 "$f")" == "---" ]] || { fail "$f: frontmatter must start on line 1 with ---"; continue; }
  name="$(fm_get "$f" name)"
  desc="$(fm_get "$f" description)"
  tools="$(fm_get "$f" tools)"
  [[ -n "$name" ]] && pass "$stem: name present" || fail "$stem: name missing"
  [[ "$name" == "$stem" ]] || fail "$stem: name '$name' does not match filename"
  if [[ -n "${SEEN_NAME[$name]:-}" ]]; then fail "duplicate agent name: $name"; fi
  SEEN_NAME[$name]=1
  if [[ ${#desc} -ge 120 ]]; then pass "$stem: routing description present (${#desc} chars)"; else fail "$stem: description missing or too short for routing (${#desc} chars)"; fi
  if [[ -z "$tools" ]]; then fail "$stem: explicit tools field missing (would inherit all tools)"; continue; fi
  bad=0
  declare -A dup=()
  IFS=',' read -r -a toks <<<"$tools"
  norm=""
  for t in "${toks[@]}"; do
    t="$(printf '%s' "$t" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
    if [[ ! "$t" =~ ^([A-Z][A-Za-z]+|mcp__[a-z0-9-]+__[a-z0-9_]+)$ ]]; then fail "$stem: malformed or wildcard tool token '$t'"; bad=1; fi
    if [[ -n "${dup[$t]:-}" ]]; then fail "$stem: duplicate tool '$t'"; bad=1; fi
    dup[$t]=1
    norm+="$t "
  done
  unset dup
  [[ $bad -eq 0 ]] && pass "$stem: tools well-formed: ${norm% }"
  TOOLS_OF[$stem]=" ${norm}"
  DESC_OF[$stem]="$desc"
done
[[ ${#SEEN_NAME[@]} -eq ${#REQUIRED_AGENTS[@]} ]] && pass "unique agent names: ${#SEEN_NAME[@]}" || fail "agent count ${#SEEN_NAME[@]} != required ${#REQUIRED_AGENTS[@]}"

# ---------------------------------------------------------------------------
section "Tool fences"
has_tool() { [[ "${TOOLS_OF[$1]:-}" == *" $2 "* ]]; }
for a in "${REQUIRED_AGENTS[@]}"; do
  for t in Agent Task; do has_tool "$a" "$t" && fail "$a has $t: subagents must not invoke one another"; done
done
pass "no subagent holds Agent/Task (hub-and-spoke)"
for t in Write Edit MultiEdit NotebookEdit Bash; do
  has_tool code-reviewer "$t" && fail "code-reviewer holds write-capable tool $t"
done
has_tool code-reviewer Write || has_tool code-reviewer Edit || has_tool code-reviewer Bash || pass "code-reviewer: no Write/Edit/NotebookEdit/Bash"
for t in Edit NotebookEdit MultiEdit; do has_tool deploy-engineer "$t" && fail "deploy-engineer holds $t (may not author)"; done
has_tool deploy-engineer Edit || pass "deploy-engineer: no Edit (cannot author scripts/Terraform)"
has_tool incident-responder Edit && fail "incident-responder holds Edit" || pass "incident-responder: no Edit"
has_tool dependency-updater Write && fail "dependency-updater holds Write" || pass "dependency-updater: Edit only (values in existing ledger)"
# MCP tool references must point at declared servers.
if [[ -f "$MCP" && "$JSONP" != none ]]; then
  servers=" $(json_q "$MCP" '.mcpServers | keys[]' | tr '\n' ' ')"
  for a in "${REQUIRED_AGENTS[@]}"; do
    for t in ${TOOLS_OF[$a]:-}; do
      if [[ "$t" == mcp__* ]]; then
        srv="${t#mcp__}"
        srv="${srv%%__*}"
        [[ "$servers" == *" $srv "* ]] || fail "$a references MCP server '$srv' not declared in .mcp.json"
      fi
    done
  done
  for t in ${TOOLS_OF[code-reviewer]:-} ${TOOLS_OF[test-engineer]:-}; do
    [[ "$t" == mcp__aws-api__* ]] && fail "reviewer/test agent holds cloud API tool $t"
  done
  pass "MCP tool references resolve to declared servers"
fi

# ---------------------------------------------------------------------------
section "Guard probes (PreToolUse exit codes)"
if [[ ! -x "$GUARD" ]]; then
  fail "guard not executable: $GUARD"
else
  probe() {
    # probe <expected-exit> <agent|-> <approved-envs|-> <tool> <field> <value> <label>
    local exp="$1" agent="$2" envs="$3" tool="$4" fld="$5" val="$6" label="$7" payload rc
    local agent_json=""
    [[ "$agent" != "-" ]] && agent_json=",\"agent_type\":\"$agent\""
    val="${val//\\/\\\\}"
    val="${val//\"/\\\"}"
    payload="{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"$tool\",\"cwd\":\"$ROOT\"$agent_json,\"tool_input\":{\"$fld\":\"$val\"}}"
    if [[ "$envs" == "-" ]]; then
      printf '%s' "$payload" | env -u SCHOLARCHAIN_APPROVED_ENVS CLAUDE_PROJECT_DIR="$ROOT" "$GUARD" >/dev/null 2>&1
    else
      printf '%s' "$payload" | SCHOLARCHAIN_APPROVED_ENVS="$envs" CLAUDE_PROJECT_DIR="$ROOT" "$GUARD" >/dev/null 2>&1
    fi
    rc=$?
    if [[ "$rc" == "$exp" ]]; then pass "guard exit=$rc  [$agent] $label"; else fail "guard exit=$rc expected=$exp  [$agent] $label"; fi
  }
  B=Bash
  C="command"
  probe 2 - - $B $C "terraform apply" "terraform apply"
  probe 2 - - $B $C "terraform -chdir=infra/terraform/environments/dev apply -auto-approve" "terraform -chdir apply -auto-approve"
  probe 2 - - $B $C "terraform destroy" "terraform destroy"
  probe 2 - - $B $C "echo 'terraform destroy'" "echo 'terraform destroy' (spec probe 3)"
  probe 2 - - $B $C "bash scripts/apply-infra.sh" "apply wrapper script"
  probe 2 - - $B $C "git push --force origin main" "git push --force"
  probe 2 - - $B $C "git push -f origin main" "git push -f"
  probe 2 - - $B $C "git push --force-with-lease" "git push --force-with-lease"
  probe 2 - - $B $C "git reset --hard HEAD~1" "git reset --hard"
  probe 2 - - $B $C "git clean -fdx" "git clean -fdx"
  probe 2 - - $B $C "rm -rf /" "rm -rf /"
  probe 2 - - $B $C "rm -rf ~" "rm -rf ~"
  probe 2 - - $B $C "rm -rf ." "rm -rf ."
  probe 2 - - $B $C "rm -rf \$WORKDIR" "rm -rf \$VAR"
  probe 2 - - $B $C "dd if=/dev/zero of=/dev/sda" "dd to block device"
  probe 2 - - $B $C "aws s3 rm s3://scholarchain-raw --recursive" "aws s3 rm --recursive"
  probe 2 - - $B $C "aws s3 rb s3://scholarchain-raw --force" "aws s3 rb"
  probe 2 - - $B $C "aws s3 sync ./out s3://scholarchain-curated --delete" "aws s3 sync --delete"
  probe 2 - - $B $C "aws dynamodb delete-table --table-name RunLedger" "dynamodb delete-table"
  probe 2 - - $B $C "aws neptune delete-db-cluster --db-cluster-identifier sc" "neptune delete-db-cluster"
  probe 2 - - $B $C "aws s3vectors delete-index --vector-bucket-name v --index-name researcher-profile-v1" "s3vectors delete-index"
  probe 2 - - $B $C "aws s3vectors delete-vector-bucket --vector-bucket-name v" "s3vectors delete-vector-bucket"
  probe 2 - - $B $C "aws ecr delete-repository --repository-name query --force" "ecr delete-repository"
  probe 2 - - $B $C "aws kms schedule-key-deletion --key-id k" "kms schedule-key-deletion"
  probe 2 - - $B $C "aws emr terminate-clusters --cluster-ids j-1" "emr terminate-clusters"
  probe 2 - - $B $C "aws emr modify-instance-groups --instance-groups InstanceGroupId=ig-1,InstanceCount=200" "emr large scale-out"
  probe 2 - - $B $C "aws secretsmanager get-secret-value --secret-id x" "secretsmanager get-secret-value"
  probe 2 - - $B $C "cat .env" "cat .env"
  probe 2 - - $B $C "cat ~/.aws/credentials" "cat ~/.aws/credentials"
  probe 2 - - $B $C "touch .claude/.last-test-run" "forging the test marker"
  probe 2 - - $B $C "bash scripts/cleanup.sh --env dev" "cleanup is human-only"
  probe 2 - - $B $C "scripts/deploy.sh --env dev" "orchestrator cannot deploy"
  probe 0 - - $B $C "sbt test" "benign sbt test"
  probe 0 - - $B $C "git status --porcelain" "benign git status"
  probe 0 - - $B $C "git push origin main" "plain push is not blocked by guard (settings: ask)"
  probe 0 spark-engineer - $B $C "sbt check" "benign sbt check"
  probe 0 spark-engineer - $B $C "rm -rf modules/spark/target" "rm build output"
  probe 2 spark-engineer - $B $C "rm -rf modules/core/src" "rm outside territory"
  probe 2 spark-engineer - $B $C "echo x >> .claude/settings.json" "constitution write via shell"
  probe 2 spark-engineer - $B $C "git commit -am wip" "subagent commit"
  probe 0 infra-engineer - $B $C "terraform plan -out=tfplan" "terraform plan"
  probe 0 infra-engineer - $B $C "bash -n scripts/deploy.sh" "syntax-check deploy script"
  probe 0 infra-engineer - $B $C "aws ec2 describe-vpcs --region us-east-1" "read-only aws"
  probe 2 infra-engineer - $B $C "aws s3 cp out.json s3://scholarchain-raw/x" "mutating aws (s3 cp)"
  probe 2 infra-engineer dev $B $C "scripts/deploy.sh --env dev" "infra may not deploy"
  probe 2 infra-engineer - $B $C "terraform apply tfplan" "infra may not apply"
  probe 0 deploy-engineer dev $B $C "scripts/deploy.sh --env dev" "deploy in approved env"
  probe 0 deploy-engineer dev $B $C "git status --porcelain && scripts/smoke-test.sh --env dev" "status + smoke in approved env"
  probe 2 deploy-engineer - $B $C "scripts/deploy.sh --env dev" "deploy with no approved envs"
  probe 2 deploy-engineer dev $B $C "scripts/deploy.sh --env prod" "deploy to unapproved env"
  probe 2 deploy-engineer dev $B $C "SCHOLARCHAIN_APPROVED_ENVS=prod scripts/deploy.sh --env prod" "inline env approval"
  probe 2 deploy-engineer dev $B $C "sbt compile" "deploy-engineer non-approved command"
  probe 2 deploy-engineer dev $B $C "vi scripts/deploy.sh" "deploy-engineer editing scripts"
  probe 0 incident-responder dev $B $C "scripts/rollback.sh --env dev" "incident rollback in approved env"
  probe 2 incident-responder dev $B $C "scripts/deploy.sh --env dev" "incident-responder may not deploy"
  probe 0 experiment-engineer dev $B $C "scripts/run-experiment.sh --env dev --manifest experiments/A/manifest.yaml" "approved experiment"
  probe 2 experiment-engineer dev $B $C "scripts/deploy.sh --env dev" "experiment may not deploy"
  probe 0 hadoop-engineer - $B $C "scripts/run-bounded-pipeline.sh --local" "local bounded pipeline"
  probe 2 hadoop-engineer dev $B $C "scripts/run-bounded-pipeline.sh --env dev" "cloud pipeline by module owner"
  probe 2 test-engineer - $B $C "sed -i s/a/b/ modules/core/src/main/scala/A.scala" "test-engineer shell edit of production"
  W=Write
  F=file_path
  probe 0 test-engineer - $W $F "$ROOT/tests/adversarial/DblpXmlSpec.scala" "test-engineer writes tests/"
  probe 2 test-engineer - $W $F "$ROOT/modules/core/src/main/scala/A.scala" "test-engineer writes production"
  probe 2 code-reviewer - $W $F "$ROOT/modules/core/src/main/scala/A.scala" "code-reviewer Write"
  probe 2 code-reviewer - Edit $F "$ROOT/tests/x.scala" "code-reviewer Edit"
  probe 2 spark-engineer - $W $F "$ROOT/CLAUDE.md" "non-factory writes CLAUDE.md"
  probe 2 spark-engineer - Edit $F "$ROOT/.claude/agents/spark-engineer.md" "non-factory edits own role file"
  probe 2 - - $W $F "$ROOT/build.sbt" "orchestrator doing specialist work"
  probe 2 build-engineer - $W $F "$ROOT/.env" "write .env"
  probe 0 factory-engineer - $W $F "$ROOT/.claude/agents/x.md" "factory writes agent file"
  probe 2 factory-engineer - $W $F "$ROOT/docs/agent-runs/README.md" "factory writes human-owned evidence"
  probe 2 factory-engineer - $W $F "$ROOT/spec/scholarchain-spec.md" "spec/ is human-owned"
  probe 2 factory-engineer - $W $F "$ROOT/build.sbt" "factory writes build files"
  probe 2 infra-engineer - $W $F "/home/elsewhere/x.tf" "write outside repository"
  M=mcp__aws-api__call_aws
  probe 0 infra-engineer - $M cli_command "aws s3 ls" "MCP aws read-only"
  probe 2 infra-engineer - $M cli_command "aws s3 rb s3://x" "MCP aws destructive"
  probe 2 deploy-engineer - $M cli_command "aws dynamodb put-item --table-name RunLedger --item {}" "MCP aws write"

  # Degraded-mode probe: no jq and no python3 on PATH.
  tmpbin="$(mktemp -d)"
  for t in bash sh cat grep sed tr head git dirname pwd env printf; do
    p="$(command -v "$t" 2>/dev/null)" && ln -s "$p" "$tmpbin/$t" 2>/dev/null
  done
  rc=0
  printf '%s' '{"tool_name":"Bash","tool_input":{"command":"terraform apply -auto-approve"}}' |
    PATH="$tmpbin" CLAUDE_PROJECT_DIR="$ROOT" "$(command -v bash)" "$GUARD" >/dev/null 2>&1 || rc=$?
  [[ $rc -eq 2 ]] && pass "guard degraded mode (no jq/python3) still blocks terraform apply" || fail "guard degraded mode exit=$rc expected 2"
  rc=0
  printf '%s' 'not json at all: git push --force' |
    CLAUDE_PROJECT_DIR="$ROOT" "$GUARD" >/dev/null 2>&1 || rc=$?
  [[ $rc -eq 2 ]] && pass "guard blocks dangerous text in unparseable payload" || fail "guard unparseable payload exit=$rc expected 2"
  rm -rf "$tmpbin"
fi

# ---------------------------------------------------------------------------
section "One-writer-per-artifact (via guard ownership map)"
if [[ -x "$GUARD" ]]; then
  WRITERS=("${REQUIRED_AGENTS[@]}" orchestrator)
  writers_of() {
    local path="$1" a list="" payload agent_json
    for a in "${WRITERS[@]}"; do
      agent_json=""
      [[ "$a" != orchestrator ]] && agent_json=",\"agent_type\":\"$a\""
      payload="{\"tool_name\":\"Write\",\"cwd\":\"$ROOT\"$agent_json,\"tool_input\":{\"file_path\":\"$ROOT/$path\"}}"
      if printf '%s' "$payload" | CLAUDE_PROJECT_DIR="$ROOT" "$GUARD" >/dev/null 2>&1; then list+="$a "; fi
    done
    printf '%s' "${list% }"
  }
  own() {
    # own <path> <expected writers (space-separated, or "none")>
    local got
    got="$(writers_of "$1")"
    [[ -z "$got" ]] && got="none"
    if [[ "$got" == "$2" ]]; then pass "owner($1) = $got"; else fail "owner($1) = '$got' expected '$2'"; fi
  }
  own CLAUDE.md factory-engineer
  own docs/agents.md factory-engineer
  own .claude/agents/spark-engineer.md factory-engineer
  own .claude/hooks/guard-dangerous.sh factory-engineer
  own .claude/commands/deploy.md factory-engineer
  own .claude/settings.json factory-engineer
  own .mcp.json factory-engineer
  own build.sbt build-engineer
  own project/plugins.sbt build-engineer
  own project/build.properties build-engineer
  own .scalafmt.conf build-engineer
  own .gitignore build-engineer
  own compose.yaml build-engineer
  own docker/Dockerfile.query build-engineer
  own project/Dependencies.scala "build-engineer dependency-updater"
  own modules/core/src/main/scala/WorkRecord.scala domain-engineer
  own datasets/dataset-manifest.yaml dataset-engineer
  own datasets/scripts/sample-dblp.sh dataset-engineer
  own modules/hadoop/src/main/scala/DblpExtractJob.scala hadoop-engineer
  own modules/entity-resolution/src/main/scala/Policy.scala entity-resolution-engineer
  own modules/spark/src/main/scala/GraphBuilder.scala spark-engineer
  own modules/storage/src/main/scala/NeptuneLoader.scala storage-engineer
  own modules/rag/src/main/scala/QueryPlanner.scala rag-engineer
  own prompts/runtime/answer-synthesis-v1.md rag-engineer
  own modules/services/src/main/scala/QueryRoutes.scala service-engineer
  own tests/adversarial/DblpXmlSpec.scala test-engineer
  own infra/terraform/environments/dev/main.tf infra-engineer
  own scripts/deploy.sh infra-engineer
  own .github/workflows/check.yml infra-engineer
  own reports/deployments/2026-11-20-abc1234.md deploy-engineer
  own experiments/A/manifest.yaml experiment-engineer
  own load-tests/query-load.scala experiment-engineer
  own README.md documentation-engineer
  own docs/architecture.md documentation-engineer
  own reports/final-report.md documentation-engineer
  own docs/incidents/INC-001.md incident-responder
  own docs/agent-runs/phase-01/agent-report.md orchestrator
  own spec/scholarchain-spec.md none
  own docs/agent-runs/README.md none
  own docs/agent-runs/phase-01/human-gate.md none
  own docs/agent-runs/phase-01/prompt.md none
  own docs/agent-runs/phase-01/commit.txt none
  own .claude/settings.local.json none
  own .claude/.last-test-run none
fi

# ---------------------------------------------------------------------------
section "docs/agents.md ownership, routing, collisions, orphans"
if [[ -f "$AGENTS_MD" ]]; then
  for a in "${REQUIRED_AGENTS[@]}"; do
    grep -Eq "^\| \`$a\` \|" "$AGENTS_MD" && pass "ownership table row: $a" || fail "ownership table missing row for $a"
  done
  grep -q 'spec/\*\*' "$AGENTS_MD" && grep -qi 'human-owned' "$AGENTS_MD" && pass "spec/ listed as human-owned" || fail "spec/ not listed as human-owned"
  grep -qi 'Subagents do not invoke one another' "$AGENTS_MD" && pass "hub-and-spoke rule stated" || fail "hub-and-spoke rule missing"
  for kw in "Constitutional change" "Data destruction" "Security change" "Terraform apply" "Deployment" "Expensive scale change" "Ambiguous or missing ownership"; do
    grep -q "| $kw" "$AGENTS_MD" && pass "escalation rule: $kw" || fail "escalation rule missing: $kw"
  done
  RT="$(awk '/<!-- routing-table:start -->/{f=1;next} /<!-- routing-table:end -->/{f=0} f' "$AGENTS_MD" | grep '^| ' | grep -v '^| Lifecycle stage |')"
  if [[ -z "$RT" ]]; then
    fail "routing table markers not found"
  else
    stages="$(printf '%s\n' "$RT" | awk -F'|' '{gsub(/^ +| +$/,"",$2); print $2}')"
    dupst="$(printf '%s\n' "$stages" | sort | uniq -d)"
    [[ -z "$dupst" ]] && pass "routing stages unique ($(printf '%s\n' "$stages" | wc -l | tr -d ' ') stages)" || fail "duplicate routing stages: $dupst"
    for p in 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17; do
      printf '%s\n' "$stages" | grep -Eq "^Phase ${p}([a-z])?:" && pass "lifecycle Phase $p routed" || fail "lifecycle Phase $p has no routing row (orphan stage)"
    done
    while IFS= read -r row; do
      prim="$(printf '%s' "$row" | awk -F'|' '{print $3}')"
      if printf '%s' "$prim" | grep -q '`'; then
        pa="$(printf '%s' "$prim" | grep -o '`[a-z-]*`' | tr -d '`' | head -n 1)"
        [[ -f ".claude/agents/$pa.md" ]] || fail "routing row names nonexistent agent: $pa"
        n="$(printf '%s' "$prim" | grep -o '`[a-z-]*`' | wc -l | tr -d ' ')"
        [[ "$n" -eq 1 ]] || fail "routing row has $n primary agents (collision): $row"
      fi
    done <<<"$RT"
    for a in "${REQUIRED_AGENTS[@]}"; do
      printf '%s\n' "$RT" | awk -F'|' '{print $3}' | grep -q "\`$a\`" && pass "agent routed as primary somewhere: $a" || fail "agent never primary in routing table (orphan agent): $a"
    done
  fi
  # Routing-description collisions: each territory token is claimed only by its owner
  # (the positive part of a description precedes "Do not use"/"Never"/"Does not").
  declare -A TOKEN=(
    [factory-engineer]="CLAUDE.md" [build-engineer]="build.sbt" [domain-engineer]="modules/core"
    [dataset-engineer]="datasets/" [hadoop-engineer]="modules/hadoop" [entity-resolution-engineer]="modules/entity-resolution"
    [spark-engineer]="modules/spark" [storage-engineer]="modules/storage" [rag-engineer]="modules/rag"
    [service-engineer]="modules/services" [test-engineer]="tests/" [code-reviewer]="APPROVE or REQUEST_CHANGES"
    [infra-engineer]="infra/terraform" [deploy-engineer]="reports/deployments" [experiment-engineer]="experiments/"
    [documentation-engineer]="README.md" [incident-responder]="docs/incidents" [dependency-updater]="project/Dependencies.scala")
  positive() { printf '%s' "$1" | sed -E 's/(Do not use|Never |Does not ).*$//'; }
  claims() {
    # claims <text> <token> : token present at a path/word boundary
    local re
    re="$(printf '%s' "$2" | sed 's/[][\.*^$/]/\\&/g')"
    printf '%s' "$1" | grep -Eq "(^|[^A-Za-z0-9_.-])$re"
  }
  for a in "${REQUIRED_AGENTS[@]}"; do
    tok="${TOKEN[$a]}"
    pos="$(positive "${DESC_OF[$a]:-}")"
    if claims "$pos" "$tok"; then pass "description of $a claims its territory '$tok'"; else fail "description of $a does not claim '$tok'"; fi
    collide=0
    for b in "${REQUIRED_AGENTS[@]}"; do
      [[ "$a" == "$b" ]] && continue
      posb="$(positive "${DESC_OF[$b]:-}")"
      if claims "$posb" "$tok"; then fail "routing collision: $b's description also claims '$tok' (owner $a)"; collide=1; fi
    done
    [[ $collide -eq 0 ]] && pass "no other agent's routing description claims '$tok'"
  done
else
  fail "docs/agents.md missing"
fi
grep -q '^@docs/agents.md' CLAUDE.md 2>/dev/null && pass "CLAUDE.md imports @docs/agents.md" || fail "CLAUDE.md does not import @docs/agents.md"

# ---------------------------------------------------------------------------
section "Hooks: wiring, executable bits, syntax"
if [[ -f "$SETTINGS" && "$JSONP" != none ]]; then
  # Empty matchers are rendered as "-" so that tab-splitting (IFS whitespace) cannot collapse fields.
  HOOKLINES="$(json_q "$SETTINGS" '.hooks | to_entries[] | .key as $k | .value[] | .matcher as $m | .hooks[] | "\($k)\t\(if ($m // "") == "" then "-" else $m end)\t\(.command)"')"
  for h in "${REQUIRED_HOOKS[@]}"; do
    printf '%s\n' "$HOOKLINES" | grep -q "/.claude/hooks/$h" && pass "settings references $h" || fail "settings does not reference $h (orphan hook)"
  done
  while IFS=$'\t' read -r ev mt cmd; do
    [[ -z "$cmd" ]] && continue
    script="$(printf '%s' "$cmd" | grep -o '\.claude/hooks/[A-Za-z0-9_.-]*' | head -n 1)"
    if [[ -z "$script" ]]; then fail "hook command not under .claude/hooks: $cmd"; continue; fi
    if [[ -f "$script" ]]; then pass "${ev}[${mt}] -> $script exists"; else fail "${ev}[${mt}] -> $script missing"; fi
    [[ -x "$script" ]] && pass "$script executable" || fail "$script not executable"
  done <<<"$HOOKLINES"
  printf '%s\n' "$HOOKLINES" | grep -Eq "^PreToolUse	[^	]*Bash[^	]*	.*guard-dangerous.sh" && pass "floor 1: PreToolUse Bash guard wired" || fail "floor 1: PreToolUse Bash guard not wired"
  printf '%s\n' "$HOOKLINES" | grep -Eq "^Stop	.*verify-tests-ran.sh" && pass "floor 2: Stop test hook wired" || fail "floor 2: Stop test hook not wired"
  printf '%s\n' "$HOOKLINES" | grep -Eq "^SubagentStop	.*verify-tests-ran.sh" && pass "floor 2: SubagentStop test hook wired" || fail "floor 2: SubagentStop test hook not wired"
  printf '%s\n' "$HOOKLINES" | grep -Eq "^PostToolUse	[^	]*(Write|Edit)[^	]*	.*format-scala.sh" && pass "floor 3: PostToolUse Scala formatter wired" || fail "floor 3: formatter not wired"
  dupev="$(printf '%s\n' "$HOOKLINES" | awk -F'\t' '{print $1"\t"$2"\t"$3}' | sort | uniq -d)"
  [[ -z "$dupev" ]] && pass "no duplicate hook registrations (event+matcher+command)" || fail "duplicate hook registration: $dupev"
fi
for s in .claude/hooks/*; do
  [[ -f "$s" ]] || continue
  found=0
  for h in "${REQUIRED_HOOKS[@]}"; do [[ "$(basename "$s")" == "$h" ]] && found=1; done
  [[ $found -eq 1 ]] || fail "unexpected hook script not in matrix: $s"
  if bash -n "$s" 2>/dev/null; then pass "bash -n $s"; else fail "bash -n $s"; fi
  if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$s" >/dev/null 2>&1; then pass "shellcheck -S warning $s"; else fail "shellcheck -S warning $s"; fi
  else
    skip "shellcheck not installed ($s)"
  fi
done
# Formatter and validator path scoping (co-registered on the same matcher; must not conflict).
rc=0; printf '%s' '{"tool_input":{"file_path":"README.md"}}' | CLAUDE_PROJECT_DIR="$ROOT" .claude/hooks/format-scala.sh >/dev/null 2>&1 || rc=$?
[[ $rc -eq 0 ]] && pass "format-scala.sh exits 0 for non-Scala file" || fail "format-scala.sh exit=$rc for non-Scala file"
rc=0; printf '%s' '{"tool_input":{"file_path":"modules/core/A.scala"}}' | CLAUDE_PROJECT_DIR="$ROOT" .claude/hooks/validate-agent-files.sh --hook >/dev/null 2>&1 || rc=$?
[[ $rc -eq 0 ]] && pass "validate-agent-files.sh --hook ignores non-constitutional files" || fail "validate --hook exit=$rc on Scala file"

# ---------------------------------------------------------------------------
section "Permission floor (settings.json deny rules)"
if [[ -f "$SETTINGS" && "$JSONP" != none ]]; then
  DENY="$(json_q "$SETTINGS" '.permissions.deny[]')"
  need() { printf '%s\n' "$DENY" | grep -Fxq "$1" && pass "deny: $1 ($2)" || fail "deny rule missing: $1 ($2)"; }
  need 'Read(**/.env*)' "floor 4"
  need 'Read(./.env*)' "floor 4"
  need 'Read(**/secrets/**)' "floor 4"
  need 'Read(**/*.pem)' "floor 4"
  need 'Read(**/*.key)' "floor 4"
  need 'Read(**/id_*)' "floor 4"
  need 'Read(~/.aws/**)' "floor 4"
  need 'Read(**/*.tfstate)' "floor 4"
  need 'Bash(terraform apply:*)' "floor 5"
  need 'Bash(terraform destroy:*)' "floor 5"
  need 'Bash(git push --force:*)' "floor 7"
  need 'Bash(git push -f:*)' "floor 7"
  need 'Bash(git push --force-with-lease:*)' "floor 7"
  need 'Bash(aws s3 rm:*)' "floor 6"
  need 'Bash(aws s3 rb:*)' "floor 6"
  need 'Bash(aws dynamodb delete-table:*)' "floor 6"
  need 'Bash(aws neptune delete-db-cluster:*)' "floor 6"
  need 'Bash(aws s3vectors delete-index:*)' "floor 6"
  need 'Bash(aws s3vectors delete-vector-bucket:*)' "floor 6"
  need 'Bash(aws ecr delete-repository:*)' "floor 6"
  need 'Bash(aws kms schedule-key-deletion:*)' "floor 6"
  need 'Bash(aws emr terminate-clusters:*)' "floor 6"
  need 'Bash(scripts/cleanup.sh:*)' "human-only cleanup"
  need 'Write(./spec/**)' "spec human-owned"
  grep -q '"disableBypassPermissionsMode": "disable"' "$SETTINGS" && pass "bypassPermissions mode disabled" || fail "bypassPermissions mode not disabled"
  grep -Eq '"(allow|ask|deny)"' "$SETTINGS" && grep -q '"allow"' "$SETTINGS" && grep -q '"deny"' "$SETTINGS" && pass "explicit allow and deny lists present" || fail "allow/deny lists missing"
  printf '%s\n' "$DENY" | grep -q '\*\*\*' && fail "suspicious wildcard in deny list"
fi

# ---------------------------------------------------------------------------
section "Commands"
for c in "${REQUIRED_COMMANDS[@]}"; do
  f=".claude/commands/$c.md"
  if [[ ! -f "$f" ]]; then fail "missing command $f"; continue; fi
  ag="$(grep -o '\*\*Responsible agent:\*\* `[a-z-]*`' "$f" | head -n 1 | grep -o '`[a-z-]*`' | tr -d '`')"
  if [[ -n "$ag" && -f ".claude/agents/$ag.md" ]]; then pass "/$c -> $ag"; else fail "/$c does not name an existing responsible agent"; fi
  grep -q '^## Gates' "$f" && pass "/$c states gates" || fail "/$c has no Gates section"
  [[ -n "$(fm_get "$f" description)" ]] && pass "/$c has description" || fail "/$c missing description frontmatter"
  grep -q '^allowed-tools:' "$f" && fail "/$c grants allowed-tools (widening)"
done
for f in .claude/commands/*.md; do
  b="$(basename "$f" .md)"; found=0
  for c in "${REQUIRED_COMMANDS[@]}"; do [[ "$c" == "$b" ]] && found=1; done
  [[ $found -eq 1 ]] || fail "orphan command not in matrix: $f"
  grep -q "/$b" "$AGENTS_MD" || fail "command /$b not listed in docs/agents.md"
done

# ---------------------------------------------------------------------------
section "MCP servers"
if [[ -f "$MCP" && "$JSONP" != none ]]; then
  S1="$(json_q "$MCP" '.mcpServers | keys[]' | sort | tr '\n' ' ')"
  S2="$(json_q "$SETTINGS" '.enabledMcpjsonServers[]' | sort | tr '\n' ' ')"
  [[ "$S1" == "$S2" ]] && pass "enabledMcpjsonServers == .mcp.json servers ($S1)" || fail "enabled servers '$S2' != declared '$S1'"
  for s in $S1; do
    grep -Eq "^\| \`$s\` \|" "$AGENTS_MD" && pass "MCP server documented with trust boundary: $s" || fail "MCP server not documented in docs/agents.md: $s"
  done
  if json_q "$MCP" '[.. | strings] | .[]' | grep -Eq '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN)'; then
    fail ".mcp.json contains a literal secret"
  else
    pass ".mcp.json contains no literal secrets (env-var references only)"
  fi
  grep -q '"READ_OPERATIONS_ONLY": "true"' "$MCP" && pass "aws-api server is read-only" || fail "aws-api server lacks READ_OPERATIONS_ONLY=true"
  grep -q '"GITHUB_READ_ONLY": "1"' "$MCP" && pass "github server is read-only" || fail "github server lacks GITHUB_READ_ONLY=1"
fi

# ---------------------------------------------------------------------------
SUMMARY="validate-agent-files: PASS=$PASSES FAIL=$FAILS SKIP=$SKIPS"
if [[ "$MODE" == "hook" ]]; then
  if [[ $FAILS -gt 0 ]]; then
    { printf '%s' "$OUT" | grep '^FAIL'; echo "$SUMMARY"; echo "Fix the constitutional files above (factory-engineer) before reporting."; } >&2
    exit 2
  fi
  exit 0
fi
printf '%s\n%s\n' "$OUT" "$SUMMARY"
if [[ $FAILS -gt 0 ]]; then exit 1; fi
if [[ "$JSONP" == none ]]; then echo "INCOMPLETE: no JSON parser available"; exit 1; fi
exit 0
