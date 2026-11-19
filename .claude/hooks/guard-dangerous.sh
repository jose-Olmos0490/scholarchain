#!/usr/bin/env bash
# .claude/hooks/guard-dangerous.sh
#
# Owner: factory-engineer (constitutional; changes require human ratification).
# Event: PreToolUse
# Matcher: Bash|Write|Edit|MultiEdit|NotebookEdit|mcp__aws-api__call_aws
#
# Protocol: reads the Claude Code hook JSON from stdin.
#   exit 0 -> no objection (permission rules in .claude/settings.json still apply)
#   exit 2 -> BLOCK; the stderr message is shown to the agent
#
# Enforces (floor invariants 1, 4, 5, 6, 7, plus matrix rules):
#   - destructive Terraform, Git, AWS (S3, DynamoDB, Neptune, S3 Vectors,
#     ECR, KMS, EMR), database, filesystem, and disk commands
#   - secret and credential reads through the shell
#   - constitutional-file, spec/, and human-record writes by non-owners
#   - per-agent write territories (docs/agents.md ownership map)
#   - per-agent script-execution authority and approved environments
#   - read-only AWS for every agent (CLI and aws-api MCP)
#
# Degrades gracefully: uses jq, else python3, else raw-text matching, which is
# stricter (patterns are matched against the whole payload).

set -u
set -o pipefail

INPUT="$(cat 2>/dev/null || true)"

PARSER=none
if command -v jq >/dev/null 2>&1; then
  PARSER=jq
elif command -v python3 >/dev/null 2>&1; then
  PARSER=python
fi

# field <dotted.path> : prints the string value (or JSON for non-strings), or nothing.
field() {
  local path="$1"
  case "$PARSER" in
    jq)
      printf '%s' "$INPUT" | jq -r --arg p "$path" \
        'try (getpath($p | split(".")) // empty | if type == "string" then . else tojson end) catch empty' \
        2>/dev/null
      ;;
    python)
      printf '%s' "$INPUT" | python3 -I -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    sys.exit(0)
for k in sys.argv[1].split("."):
    if isinstance(d, dict) and k in d:
        d = d[k]
    else:
        sys.exit(0)
if d is None:
    sys.exit(0)
print(d if isinstance(d, str) else json.dumps(d))
' "$path" 2>/dev/null
      ;;
    *)
      local key="${path##*.}"
      printf '%s' "$INPUT" | tr '\n' ' ' |
        grep -o "\"$key\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n 1 |
        sed -E 's/^"[^"]*"[[:space:]]*:[[:space:]]*"(.*)"$/\1/'
      ;;
  esac
}

block() {
  # $1 = what was blocked, $2 = rule, $3 = what to do instead
  {
    echo "BLOCKED by .claude/hooks/guard-dangerous.sh"
    echo "  Blocked: $1"
    echo "  Rule:    $2"
    echo "  Instead: $3"
    echo "  Authority: docs/agents.md (ownership and escalation) and CLAUDE.md (security invariants)."
  } >&2
  exit 2
}

TOOL="$(field tool_name)"
AGENT="$(field agent_type)"
[[ -z "$AGENT" ]] && AGENT="$(field agent_name)"
WHO="${AGENT:-orchestrator}"

ROOT="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$ROOT" ]]; then
  ROOT="$(field cwd)"
  if [[ -n "$ROOT" ]] && git -C "$ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    ROOT="$(git -C "$ROOT" rev-parse --show-toplevel)"
  fi
fi
[[ -z "$ROOT" ]] && ROOT="$(pwd)"
ROOT="${ROOT%/}"

LEDGER="project/Dependencies.scala"

# ---------------------------------------------------------------------------
# Ownership map (mirror of docs/agents.md). Returns 0 if $1 may write $2.
# $2 is a repository-relative path without a leading "./".
# ---------------------------------------------------------------------------
owner_allows() {
  local a="$1" p="$2" slashes
  case "$a" in
    factory-engineer)
      case "$p" in
        CLAUDE.md | docs/agents.md | .claude/settings.json | .mcp.json) return 0 ;;
        .claude/agents/* | .claude/hooks/* | .claude/commands/*)
          slashes="${p//[^\/]/}"
          [[ ${#slashes} -eq 2 ]] && return 0
          ;;
      esac
      ;;
    build-engineer)
      case "$p" in
        build.sbt | .scalafmt.conf | .gitignore | .dockerignore | .jvmopts | .sbtopts) return 0 ;;
        project/*) return 0 ;;
        compose*.yml | compose*.yaml | docker-compose*.yml | docker-compose*.yaml) return 0 ;;
        Dockerfile* | docker/*) return 0 ;;
      esac
      ;;
    dependency-updater) [[ "$p" == "$LEDGER" ]] && return 0 ;;
    domain-engineer) [[ "$p" == modules/core/* ]] && return 0 ;;
    dataset-engineer) [[ "$p" == datasets/* ]] && return 0 ;;
    hadoop-engineer) [[ "$p" == modules/hadoop/* ]] && return 0 ;;
    entity-resolution-engineer) [[ "$p" == modules/entity-resolution/* ]] && return 0 ;;
    spark-engineer) [[ "$p" == modules/spark/* ]] && return 0 ;;
    storage-engineer) [[ "$p" == modules/storage/* ]] && return 0 ;;
    rag-engineer) [[ "$p" == modules/rag/* || "$p" == prompts/* ]] && return 0 ;;
    service-engineer) [[ "$p" == modules/services/* ]] && return 0 ;;
    test-engineer) [[ "$p" == tests/* ]] && return 0 ;;
    code-reviewer) return 1 ;;
    infra-engineer)
      [[ "$p" == infra/terraform/* || "$p" == scripts/* || "$p" == .github/workflows/* ]] && return 0
      ;;
    deploy-engineer) [[ "$p" == reports/deployments/* ]] && return 0 ;;
    experiment-engineer) [[ "$p" == experiments/* || "$p" == load-tests/* ]] && return 0 ;;
    documentation-engineer)
      case "$p" in
        docs/agents.md | docs/agent-runs/* | docs/incidents/* | reports/deployments/*) return 1 ;;
        README.md | docs/* | reports/*) return 0 ;;
      esac
      ;;
    incident-responder) [[ "$p" == docs/incidents/* ]] && return 0 ;;
    orchestrator)
      case "$p" in
        docs/agent-runs/phase-*/agent-report.md | docs/agent-runs/phase-*/review-report.md | docs/agent-runs/phase-*/test-report.md)
          slashes="${p//[^\/]/}"
          [[ ${#slashes} -eq 3 ]] && return 0
          ;;
      esac
      ;;
  esac
  return 1
}

owner_of_hint() {
  local p="$1" a
  for a in factory-engineer build-engineer dependency-updater domain-engineer dataset-engineer \
    hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer \
    service-engineer test-engineer infra-engineer deploy-engineer experiment-engineer \
    documentation-engineer incident-responder orchestrator; do
    if owner_allows "$a" "$p"; then
      printf '%s' "$a"
      return 0
    fi
  done
  printf '%s' "no owner (escalate to factory-engineer for a ratified ownership amendment)"
}

is_human_owned() {
  case "$1" in
    spec | spec/*) return 0 ;;
    docs/agent-runs/README.md) return 0 ;;
    docs/agent-runs/*/prompt.md | docs/agent-runs/*/human-gate.md | docs/agent-runs/*/commit.txt) return 0 ;;
    docs/agent-runs/*/repair-*.md) return 0 ;;
    .claude/settings.local.json) return 0 ;;
  esac
  return 1
}

# ---------------------------------------------------------------------------
# File-writing tools: Write, Edit, MultiEdit, NotebookEdit
# ---------------------------------------------------------------------------
check_file_write() {
  local fp rel
  fp="$(field tool_input.file_path)"
  [[ -z "$fp" ]] && fp="$(field tool_input.notebook_path)"
  [[ -z "$fp" ]] && fp="$(field tool_input.path)"
  if [[ -z "$fp" ]]; then
    block "$TOOL call without a recognizable file path" \
      "every write must name a path that can be checked against the ownership map" \
      "retry with an explicit repository-relative or absolute file_path"
  fi
  case "$fp" in
    */../* | ../* | */..) block "$TOOL to path containing '..': $fp" \
      "paths must be canonical so ownership can be checked" "use a canonical path" ;;
  esac
  if [[ "$fp" == /* ]]; then
    if [[ "$fp" == "$ROOT"/* ]]; then
      rel="${fp#"$ROOT"/}"
    elif [[ "$fp" == /tmp/* ]]; then
      return 0
    else
      block "$TOOL outside the repository: $fp" \
        "agents write only inside the repository (or /tmp scratch)" \
        "write inside your territory listed in docs/agents.md"
    fi
  else
    rel="${fp#./}"
  fi

  # Secret material is never written by agents.
  case "/$rel" in
    */.env* | */secrets/* | *.pem | *.key | */id_* | *.tfstate | *.tfstate.*)
      block "$TOOL to secret-material path: $rel" \
        "floor invariant 4: secret material is never read or written by agents" \
        "use Secrets Manager references or *.example.* placeholder templates"
      ;;
  esac

  if is_human_owned "$rel"; then
    block "$TOOL to human-owned path: $rel" \
      "spec/, human gate/prompt/repair/commit records, docs/agent-runs/README.md and settings.local.json are human-owned" \
      "return the proposed content in your report; the human architect writes it"
  fi

  if [[ "$rel" == .claude/.last-test-run ]]; then
    block "$TOOL to the test marker .claude/.last-test-run" \
      "only 'sbt markTestRun' (run by 'sbt check' after tests pass) may update the marker" \
      "run 'sbt check'"
  fi

  # Immutable raw experiment results.
  if [[ "$rel" == experiments/*/raw/* || "$rel" == experiments/raw/* ]]; then
    if [[ -e "$ROOT/$rel" ]]; then
      block "$TOOL to existing raw experiment result: $rel" \
        "raw metrics are immutable after collection" \
        "write a new run directory; derive corrections by script into derived/"
    fi
  fi

  if ! owner_allows "$WHO" "$rel"; then
    block "$WHO attempted $TOOL on $rel" \
      "one writer per artifact; owner of this path: $(owner_of_hint "$rel")" \
      "decline, return STATUS: BLOCKED-ON naming that owner, and let the orchestrator route it"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Command checks (Bash and aws-api MCP)
# ---------------------------------------------------------------------------
CMD=""
m() { printf '%s' "$CMD" | grep -Eq -- "$1"; }
mi() { printf '%s' "$CMD" | grep -Eiq -- "$1"; }

segments() {
  # Split a command line into simple-command segments on ; && || | and newlines.
  printf '%s' "$1" | sed -E 's/(\&\&|\|\||;|\||\(|\)|`|\$\()/\n/g' |
    sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' | grep -v '^$' || true
}

strip_assignments() {
  # Remove leading VAR=value assignments and a leading "env"/"command"/"exec"/"time".
  local s="$1"
  while [[ "$s" =~ ^[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+(.*)$ ]]; do
    s="${BASH_REMATCH[1]}"
  done
  while [[ "$s" =~ ^(env|command|exec|time|nohup)[[:space:]]+(.*)$ ]]; do
    s="${BASH_REMATCH[2]}"
  done
  printf '%s' "$s"
}

aws_readonly_segment() {
  # $1: a segment starting with "aws". Returns 0 if read-only.
  local s="$1"
  s="$(printf '%s' "$s" | sed -E 's/[[:space:]]--(profile|region|output|endpoint-url|query|color|cli-read-timeout|cli-connect-timeout)([[:space:]]+|=)[^[:space:]]+//g; s/[[:space:]]--(no-cli-pager|no-paginate|debug|no-verify-ssl)//g')"
  if printf '%s' "$s" | grep -Eq '^aws[[:space:]]+s3[[:space:]]+ls([[:space:]]|$)'; then return 0; fi
  if printf '%s' "$s" | grep -Eq '^aws[[:space:]]+(help|--version)([[:space:]]|$)'; then return 0; fi
  if printf '%s' "$s" | grep -Eq '^aws[[:space:]]+[a-z0-9-]+[[:space:]]+(describe|list|get|head|search|lookup|filter|batch-get|query|scan|wait|validate|estimate|simulate|tail|start-query|help)([a-z0-9-]*)([[:space:]]|$)'; then
    return 0
  fi
  return 1
}

approved_env_ok() {
  # $1: segment invoking a live script. Requires --env <approved>.
  local seg="$1" env list e
  if [[ "$seg" =~ --env[=[:space:]]+([A-Za-z0-9_-]+) ]]; then
    env="${BASH_REMATCH[1]}"
  else
    return 1
  fi
  list="${SCHOLARCHAIN_APPROVED_ENVS:-}"
  [[ -z "$list" ]] && return 1
  for e in ${list//,/ }; do
    [[ "$e" == "$env" ]] && return 0
  done
  return 1
}

check_global_command() {
  # ---- Terraform (floor 5) ----
  if mi '(^|[^[:alnum:]_])(terraform|tofu|terragrunt|tf)([[:space:]]+[^;&|]*)?[[:space:]](apply|destroy)([[:space:]]|$|[^[:alnum:]_.-])'; then
    block "terraform/tofu/terragrunt apply or destroy" \
      "floor invariant 5: Terraform apply and destroy are never run by agents, directly or via wrappers" \
      "present the plan (scripts/plan.sh) and its checksum; the human architect applies"
  fi
  if mi '(-auto-approve|TF_CLI_ARGS[A-Za-z_]*=.*(apply|destroy|auto-approve)|terragrunt[[:space:]]+run-all)'; then
    block "auto-approve / TF_CLI_ARGS / run-all wrapper" \
      "floor invariant 5: apply/destroy equivalents and wrappers are denied" \
      "produce a non-destructive plan only"
  fi
  if mi '(^|[^[:alnum:]_])(terraform|tofu)[[:space:]]+[^;&|]*(state[[:space:]]+(rm|mv|push|replace-provider)|force-unlock|taint|untaint|import|workspace[[:space:]]+delete)'; then
    block "Terraform state mutation" \
      "Terraform state is changed only by human-approved apply" \
      "describe the needed state change in your report for human action"
  fi
  if mi '(^|[^[:alnum:]_])make[[:space:]]+[^;&|]*(apply|destroy)'; then
    block "make target that applies or destroys" "floor invariant 5: wrappers are denied" "run plan only"
  fi
  if mi '[[:alnum:]_./-]*(apply|destroy)[[:alnum:]_-]*\.sh' &&
    ! m '^[[:space:]]*(bash[[:space:]]+-n|shellcheck)[[:space:]][^;&|`$]*$'; then
    block "apply/destroy wrapper script" \
      "floor invariant 5: infrastructure apply/destroy paths are human-only" \
      "ask the human architect to run it"
  fi
  if mi '(^|[^[:alnum:]_])(cdk[[:space:]]+(deploy|destroy)|pulumi[[:space:]]+(up|destroy)|sam[[:space:]]+(deploy|delete)|eksctl[[:space:]]+delete|cloudformation[[:space:]]+(deploy|create-stack|update-stack|delete-stack|execute-change-set))'; then
    block "non-Terraform infrastructure mutation" \
      "CLAUDE.md: all AWS infrastructure goes through Terraform; no side doors" \
      "route to infra-engineer for Terraform authoring"
  fi

  # ---- Git (floor 1 and 7) ----
  if mi '(^|[^[:alnum:]_])git[[:space:]]+([^;&|]*[[:space:]])?push([[:space:]][^;&|]*)?[[:space:]](--force|--force-with-lease|--force-if-includes|-[a-zA-Z]*f[a-zA-Z]*|--mirror|--delete|--prune|\+[^[:space:]]+|:[^[:space:]]+)([[:space:]=]|$)'; then
    block "git force push / destructive push" \
      "floor invariant 7: force pushes (--force, -f, --force-with-lease) and remote ref deletion are denied" \
      "the human architect pushes normally after ratification"
  fi
  if mi '(^|[^[:alnum:]_])git[[:space:]]+([^;&|]*[[:space:]])?(reset[[:space:]][^;&|]*--hard|clean[[:space:]][^;&|]*-[a-zA-Z]*f|checkout[[:space:]]+(-f|--force)|checkout[[:space:]][^;&|]*--[[:space:]]+\.|restore[[:space:]]+([^;&|]*[[:space:]])?\.([[:space:]]|$)|branch[[:space:]]+[^;&|]*-D|stash[[:space:]]+(drop|clear)|filter-branch|filter-repo|update-ref[[:space:]]+-d|reflog[[:space:]]+expire|gc[[:space:]][^;&|]*--prune)'; then
    block "destructive git operation (reset --hard, clean -f, discard-all, branch -D, history rewrite)" \
      "floor invariant 1: destructive Git commands are blocked" \
      "use git status/diff to inspect; ask the human to discard or rewrite"
  fi

  # ---- Filesystem / disk (floor 1) ----
  if mi '(^|[^[:alnum:]_])(mkfs(\.[a-z0-9]+)?|wipefs|shred|fdisk|sfdisk|gdisk|parted|mkswap)([[:space:]]|$)' ||
    mi '(^|[^[:alnum:]_])dd[[:space:]][^;&|]*of=/dev/' ||
    mi '>[[:space:]]*/dev/(sd|nvme|hd|xvd|vd|disk|mmcblk)' ||
    mi ':\(\)[[:space:]]*\{' ||
    mi '(^|[^[:alnum:]_])(chmod|chown)[[:space:]]+-[a-zA-Z]*R[a-zA-Z]*[[:space:]]+[^;&|]*[[:space:]](/|~|\$HOME)([[:space:]]|$)'; then
    block "disk/filesystem wipe or recursive permission change on a root path" \
      "floor invariant 1: disk and filesystem destruction is blocked" "none; escalate to the human"
  fi
  if mi '(^|[^[:alnum:]_])sudo([[:space:]]|$)'; then
    block "sudo" "agents never escalate privileges" "ask the human to run privileged commands"
  fi
  if mi '(^|[^[:alnum:]_])find[[:space:]]+(/|~|\$HOME|\.)([[:space:]][^;&|]*)?[[:space:]](-delete|-exec[[:space:]]+rm)'; then
    block "find -delete / -exec rm on a broad path" "floor invariant 1" "remove specific build outputs (e.g. target/) explicitly"
  fi
  if mi '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(ba|z|da)?sh([[:space:]]|$)' || mi 'base64[[:space:]]+(-d|--decode)[^|]*\|[[:space:]]*(ba|z)?sh' || mi '(^|[;&|[:space:]])eval[[:space:]]'; then
    block "piping downloaded or decoded content into a shell / eval" \
      "unreviewable command execution bypasses the guard" "run reviewed, source-controlled scripts"
  fi

  # ---- Databases (destructive) ----
  if mi '(DROP[[:space:]]+(TABLE|DATABASE|SCHEMA|INDEX)|TRUNCATE[[:space:]]+TABLE|DETACH[[:space:]]+DELETE|\.drop\(\)|g\.V\(\)[^;]*\.drop)'; then
    block "destructive database/graph statement (DROP/TRUNCATE/DETACH DELETE/drop())" \
      "data destruction requires human escalation" "return BLOCKED-ON human with the intended change"
  fi
  if mi '(curl|wget|http)[^;&|]*(-X|--request)[[:space:]]*DELETE'; then
    block "HTTP DELETE request" "data destruction requires human escalation" "ScholarChain APIs expose no DELETE; escalate"
  fi

  # ---- AWS destructive / security / scale (floor 6 + matrix) ----
  if mi '(^|[^[:alnum:]_])aws[[:space:]]'; then
    if mi '[[:space:]](delete-[a-z0-9-]+|terminate-[a-z0-9-]+|remove-[a-z0-9-]+|batch-delete-[a-z0-9-]+|deregister-[a-z0-9-]+|purge-queue|schedule-key-deletion|disable-key|disable-key-rotation|cancel-key-deletion|reset-graph)([[:space:]]|$)'; then
      block "destructive AWS operation (delete-*/terminate-*/remove-*/purge/key deletion)" \
        "floor invariant 6: destructive AWS operations on S3, DynamoDB, Neptune, S3 Vectors, ECR, KMS, EMR and others are denied" \
        "return BLOCKED-ON human; deletion happens only through human-run scripts/cleanup.sh"
    fi
    if mi '[[:space:]]s3[[:space:]]+(rm|rb|mv)([[:space:]]|$)' || mi '[[:space:]]s3[[:space:]]+sync[[:space:]][^;&|]*--delete'; then
      block "aws s3 rm / rb / mv / sync --delete" \
        "floor invariant 6: recursive and destructive S3 operations are denied" "escalate to the human"
    fi
    if mi '[[:space:]]dynamodb[[:space:]]+(batch-write-item|update-table|update-continuous-backups|restore-table[a-z-]*)([[:space:]]|$)'; then
      block "DynamoDB bulk write / table mutation" "data and table changes go through loaders or Terraform" "escalate"
    fi
    if mi '[[:space:]](neptune|rds|docdb)[[:space:]]+(modify-db-[a-z-]+|failover-db-cluster|reboot-db-instance|restore-db-[a-z-]+)([[:space:]]|$)' ||
      mi '[[:space:]](neptunedata|neptune-graph)[[:space:]][^;&|]*(DELETE|DROP|REMOVE|drop\(\)|cancel-)'; then
      block "Neptune cluster mutation or destructive graph query" "Neptune is changed only through Terraform or reviewed loaders" "escalate"
    fi
    if mi '[[:space:]]s3vectors[[:space:]]+(put-vector-bucket-policy|put-vectors)([[:space:]]|$)'; then
      block "S3 Vectors write/policy change via CLI" "vector writes go through storage loaders run by approved scripts" "escalate"
    fi
    if mi '[[:space:]]ecr[[:space:]]+(put-image-tag-mutability|put-lifecycle-policy|set-repository-policy|put-image)([[:space:]]|$)'; then
      block "ECR policy/tag-mutability change" "images are immutable Git-SHA tags managed by Terraform and deploy scripts" "escalate"
    fi
    if mi '[[:space:]]kms[[:space:]]+(put-key-policy|revoke-grant|retire-grant|create-grant|decrypt|update-key-description|enable-key)([[:space:]]|$)'; then
      block "KMS key policy/grant change or decrypt" "security change: human escalation" "escalate"
    fi
    if mi '[[:space:]]emr[[:space:]]+(create-cluster|run-job-flow|modify-instance-groups|modify-instance-fleet|add-instance-groups|add-instance-fleet|put-managed-scaling-policy|put-auto-scaling-policy|set-termination-protection|modify-cluster)([[:space:]]|$)'; then
      block "unreviewed EMR creation, scale-out, or termination-protection change" \
        "expensive scale changes require human approval and go through Terraform or approved scripts" \
        "submit an approved experiment manifest via /experiment or /run-pipeline"
    fi
    if mi '[[:space:]]ecs[[:space:]]+update-service[[:space:]][^;&|]*--desired-count' ||
      mi '[[:space:]](application-autoscaling[[:space:]]+(register-scalable-target|put-scaling-policy)|autoscaling[[:space:]]+(set-desired-capacity|update-auto-scaling-group))'; then
      block "direct scale change (ECS desired count / autoscaling)" \
        "expensive scale changes require human approval" "use approved scripts and manifests"
    fi
    if mi '[[:space:]]iam[[:space:]]+(create|put|attach|detach|update|add|delete|remove|tag|untag|upload|set)-[a-z0-9-]+' ||
      mi '[[:space:]]s3api[[:space:]]+(put-bucket-policy|put-bucket-acl|put-object-acl|put-public-access-block|put-bucket-lifecycle[a-z-]*)'; then
      block "IAM or bucket security change" "security changes require human escalation and Terraform" "route to infra-engineer and the human"
    fi
    if mi '[[:space:]](secretsmanager[[:space:]]+(get-secret-value|batch-get-secret-value|put-secret-value)|ssm[[:space:]]+get-parameters?(-by-path)?[[:space:]][^;&|]*--with-decryption|configure[[:space:]]+(get|export-credentials|set)|sts[[:space:]]+(get-session-token|get-federation-token|assume-role[a-z-]*)|ecr[[:space:]]+(get-login-password|get-authorization-token)|codeartifact[[:space:]]+get-authorization-token)'; then
      block "secret or credential retrieval via AWS CLI" \
        "floor invariant 4: secret material is never read by agents" "reference secrets by ARN"
    fi
  fi

  # ---- Secret reads through the shell (floor 4) ----
  if mi '(^|[[:space:]/=<"'"'"'])\.env([.][[:alnum:]_.-]*|rc)?([[:space:]"'"'"';|&)>]|$)' ||
    mi '(^|[[:space:]/=<"'"'"'])id_(rsa|dsa|ecdsa|ed25519)[[:alnum:]_.-]*' ||
    mi '[[:alnum:]_-]\.(pem|key|p12|pfx)([[:space:]"'"'"';|&)]|$)' ||
    mi '(^|[[:space:]/=<"'"'"'~])\.(aws|ssh|gnupg|kube)/' ||
    mi '(^|[[:space:]/=<"'"'"'])secrets/' ||
    mi '(credentials(\.json|\.csv|\.ini)?|\.netrc|\.git-credentials|\.docker/config\.json)([[:space:]"'"'"';|&)]|$)' ||
    mi '\.tfstate' ||
    mi '(^|[;&|[:space:]])(printenv|gh[[:space:]]+auth[[:space:]]+token)([[:space:]]|$)' ||
    mi '(^|[;&|][[:space:]]*)env[[:space:]]*($|[|;&>])'; then
    block "shell access to secret material (.env*, private keys, credential files, cloud credential dirs, tfstate, environment dump)" \
      "floor invariant 4: secret reads are denied" \
      "use Secrets Manager ARNs or *.example.* placeholder templates; never print secrets"
  fi
}

# Write-ish shell operations (used for protected-path checks).
WRITE_OP='(>|\btee\b|\bsed\b[^|;&]*[[:space:]]-[a-zA-Z]*i|\bperl\b[^|;&]*[[:space:]]-[a-zA-Z]*i|\bmv\b|\bcp\b|\brm\b|\brmdir\b|\btouch\b|\btruncate\b|\bchmod\b|\bchown\b|\bln\b|\binstall\b|\bpatch\b|\bdd\b|\bunzip\b|\btar\b[^|;&]*[[:space:]]-?[a-zA-Z]*x|\bgit[[:space:]]+(apply|checkout|restore|am|mv|rm|stash)\b)'

check_shell_protected_paths() {
  local c
  # Drop harmless redirections before looking for write operators.
  c="$(printf '%s' "$CMD" | sed -E 's/[0-9]*>+[[:space:]]*\/dev\/null//g; s/[0-9]*>&[0-9]+//g')"
  printf '%s' "$c" | grep -Eq -- "$WRITE_OP" || return 0

  if printf '%s' "$c" | grep -Eq '(^|[[:space:]"'"'"'=/])spec(/|[[:space:]"'"'"']|$)'; then
    block "shell write operation touching spec/" "spec/ is human-owned and read-only" "read it with the Read tool"
  fi
  if printf '%s' "$c" | grep -Eq '\.last-test-run'; then
    block "shell write to the test marker" "only 'sbt markTestRun' may update .claude/.last-test-run" "run 'sbt check'"
  fi
  if printf '%s' "$c" | grep -Eq '(human-gate\.md|commit\.txt|settings\.local\.json|docs/agent-runs/README\.md)'; then
    block "shell write to human-owned records" "human gate, commit, and local settings records are human-owned" "report proposed content instead"
  fi
  if [[ "$WHO" != "orchestrator" ]] && printf '%s' "$c" | grep -Eq 'docs/agent-runs'; then
    block "subagent shell write under docs/agent-runs/" "phase evidence is human-owned; only the orchestrator assembles reports" "return your final report instead"
  fi
  if [[ "$WHO" != "factory-engineer" ]] &&
    printf '%s' "$c" | grep -Eq '(^|[^[:alnum:]_.-])(CLAUDE\.md|\.mcp\.json|docs/agents\.md|\.claude/(agents|hooks|commands|settings[^[:space:]]*\.json))'; then
    block "$WHO shell write touching constitutional files" \
      "only factory-engineer modifies ratified constitutional files, and only with human ratification" \
      "return BLOCKED-ON factory-engineer"
  fi
  if printf '%s' "$c" | grep -Eq 'experiments/([^[:space:]]*/)?raw(/|[[:space:]]|$)' &&
    printf '%s' "$c" | grep -Eq '(\brm\b|\bmv\b|\btruncate\b|\bsed\b[^|;&]*-[a-zA-Z]*i|>[^>])'; then
    block "modifying or deleting raw experiment results" "raw metrics are immutable" "create a new run"
  fi
  if [[ "$WHO" == "test-engineer" ]] &&
    printf '%s' "$c" | grep -Eq '(^|[[:space:]"'"'"'=])(\./)?(modules|project|build\.sbt|infra|scripts|datasets|prompts)(/|[[:space:]]|$)'; then
    block "test-engineer shell write touching production paths" \
      "test-engineer writes only under tests/" "report the defect and its owner"
  fi
  if [[ "$WHO" == "code-reviewer" ]]; then
    block "code-reviewer shell write" "reviewer-class agents never write" "report findings with owners"
  fi
  return 0
}

check_rm_targets() {
  local seg s toks i t recursive target rel
  while IFS= read -r seg; do
    s="$(strip_assignments "$seg")"
    read -r -a toks <<<"$s"
    [[ ${#toks[@]} -eq 0 ]] && continue
    if [[ "${toks[0]}" == "xargs" ]]; then
      for t in "${toks[@]:1}"; do
        if [[ "${t##*/}" == "rm" || "${t##*/}" == "rmdir" || "${t##*/}" == "shred" ]]; then
          block "xargs-driven deletion" "deletion targets cannot be verified" "remove explicit build-output paths"
        fi
      done
      continue
    fi
    [[ "${toks[0]##*/}" == "rm" ]] || continue
    recursive=0
    local targets=()
    for ((i = 1; i < ${#toks[@]}; i++)); do
      t="${toks[$i]}"
      case "$t" in
        --recursive | -r | -R) recursive=1 ;;
        --*) ;;
        -*) [[ "$t" == *[rR]* ]] && recursive=1 ;;
        *) targets+=("$t") ;;
      esac
    done
    [[ $recursive -eq 1 ]] || continue
    [[ ${#targets[@]} -eq 0 ]] && block "recursive rm without explicit targets" "floor invariant 1" "name explicit build-output paths"
    for target in "${targets[@]}"; do
      target="${target//\"/}"
      target="${target//\'/}"
      case "$target" in
        *'$'* | *'`'* | '~'* | '*' | '.*' | './*' | '.' | './' | '..' | '../'* | '/' | '/*' | *'..'*)
          block "rm -r on broad, root, home, wildcard, parent, or variable path: $target" \
            "floor invariant 1: broad recursive deletion is blocked" "remove an explicit build-output path such as modules/x/target"
          ;;
      esac
      if [[ "$target" == /* ]]; then
        if [[ "$target" == /tmp/?* ]]; then continue; fi
        if [[ "$target" == "$ROOT"/* ]]; then rel="${target#"$ROOT"/}"; else
          block "rm -r outside the repository: $target" "floor invariant 1" "only repository build outputs or /tmp scratch may be removed"
        fi
      else
        rel="${target#./}"
      fi
      rel="${rel%/}"
      if printf '%s' "$rel" | grep -Eq '(^|/)(target|\.bsp|\.metals|\.bloop|\.scala-build|\.terraform|node_modules|__pycache__|\.pytest_cache)(/|$)'; then
        continue
      fi
      if [[ "$rel" != */* ]]; then
        block "rm -r of a top-level repository path: $rel" "floor invariant 1: top-level directories are never removed by agents" "escalate to the human"
      fi
      if ! owner_allows "$WHO" "$rel/x"; then
        block "rm -r outside $WHO's territory: $rel" "one writer per artifact" "return BLOCKED-ON the owner: $(owner_of_hint "$rel/x")"
      fi
      if [[ "$rel" == experiments/*raw* ]]; then
        block "rm -r of raw experiment results: $rel" "raw metrics are immutable" "keep failed runs; never delete raw data"
      fi
    done
  done < <(segments "$CMD")
  return 0
}

check_scripts_and_agent_fences() {
  local seg s first name allowed
  while IFS= read -r seg; do
    s="$(strip_assignments "$seg")"
    if [[ "$seg" =~ (^|[[:space:]])SCHOLARCHAIN_APPROVED_ENVS= ]]; then
      block "inline change of SCHOLARCHAIN_APPROVED_ENVS" "approved environments are set only by the human before the session" "ask the human to approve the environment"
    fi
    first="${s%%[[:space:]]*}"

    # Every direct AWS CLI call must be read-only, for every agent.
    if [[ "$first" == "aws" ]] && ! aws_readonly_segment "$s"; then
      block "mutating AWS CLI call: $s" \
        "agents get read-only cloud inspection; the write path is human-applied Terraform plus approved scripts" \
        "use describe/list/get, or route the change to the owning author"
    fi

    # Script execution authority.
    if [[ "$s" =~ (^|[[:space:]/])scripts/([A-Za-z0-9_-]+)\.sh ]]; then
      name="${BASH_REMATCH[2]}"
      case "$first" in
        cat | less | head | tail | wc | grep | rg | sha256sum | shellcheck | ls | stat | file | diff | git) continue ;;
        bash | sh)
          if [[ "$s" =~ ^(bash|sh)[[:space:]]+-n[[:space:]] ]]; then continue; fi
          ;;
        chmod) [[ "$WHO" == "infra-engineer" ]] && continue ;;
      esac
      allowed=0
      case "$name" in
        cleanup) allowed=0 ;;
        deploy) [[ "$WHO" == "deploy-engineer" ]] && allowed=1 ;;
        rollback | smoke-test) [[ "$WHO" == "deploy-engineer" || "$WHO" == "incident-responder" ]] && allowed=1 ;;
        run-experiment) [[ "$WHO" == "experiment-engineer" ]] && allowed=1 ;;
        archive-evidence) [[ "$WHO" == "experiment-engineer" || "$WHO" == "deploy-engineer" ]] && allowed=1 ;;
        run-bounded-pipeline)
          if [[ "$WHO" == "experiment-engineer" ]]; then
            allowed=1
          elif [[ "$s" =~ (^|[[:space:]])--local([[:space:]]|$) ]]; then
            case "$WHO" in hadoop-engineer | spark-engineer | entity-resolution-engineer | storage-engineer | test-engineer) allowed=1 ;; esac
          fi
          ;;
        check | plan | package) allowed=1 ;;
        *) allowed=0 ;;
      esac
      if [[ $allowed -ne 1 ]]; then
        if [[ "$name" == "cleanup" ]]; then
          block "execution of scripts/cleanup.sh" "cleanup requires explicit human invocation with before/after inventory" "the human architect runs it"
        fi
        block "$WHO executing scripts/$name.sh" \
          "script execution authority (docs/agents.md 'Disambiguation rules: Scripts')" \
          "return BLOCKED-ON the executing owner (deploy-engineer / incident-responder / experiment-engineer)"
      fi
      case "$name" in
        deploy | rollback | smoke-test | run-experiment)
          if ! approved_env_ok "$s"; then
            block "scripts/$name.sh without an approved --env" \
              "live scripts run only in human-approved environments (SCHOLARCHAIN_APPROVED_ENVS='${SCHOLARCHAIN_APPROVED_ENVS:-<unset>}')" \
              "pass --env <name> for an environment the human approved before the session"
          fi
          ;;
        run-bounded-pipeline)
          if [[ ! "$s" =~ (^|[[:space:]])--local([[:space:]]|$) ]] && ! approved_env_ok "$s"; then
            block "cloud pipeline run without an approved --env" "live scripts run only in approved environments" "use --local or an approved --env"
          fi
          ;;
      esac
    fi

    # Strict allowlists for operator agents.
    if [[ "$WHO" == "deploy-engineer" || "$WHO" == "incident-responder" ]]; then
      if printf '%s' "$s" | sed -E 's/[0-9]*>+[[:space:]]*\/dev\/null//g; s/[0-9]*>&[0-9]+//g' | grep -q '>'; then
        block "$WHO using shell redirection: $s" \
          "operators write only their reports, through the Write tool, inside their territory" \
          "use the Write tool for reports/deployments/** or docs/incidents/**"
      fi
      if ! printf '%s' "$s" | grep -Eq '^((bash[[:space:]]+)?(\./)?scripts/(deploy|smoke-test|rollback|archive-evidence)\.sh([[:space:]]|$)|git[[:space:]]+(status|rev-parse|log|diff|show|describe)([[:space:]]|$)|sha256sum([[:space:]]|$)|aws[[:space:]]|jq([[:space:]]|$)|date([[:space:]]|$)|pwd$|ls([[:space:]]|$)|test[[:space:]]|echo([[:space:]]|$)|grep([[:space:]]|$)|head([[:space:]]|$)|tail([[:space:]]|$)|wc([[:space:]]|$)|sort([[:space:]]|$))'; then
        block "$WHO running a non-approved command: $s" \
          "operators execute only approved scripts plus read-only inspection" \
          "route authoring or other work back to the orchestrator"
      fi
    fi

    # Agents never commit, push, tag, or rewrite history; the human commits.
    if [[ "$WHO" != "orchestrator" ]] && printf '%s' "$s" | grep -Eq '^git[[:space:]]+([^[:space:]]+[[:space:]]+)*(commit|push|tag|merge|rebase|cherry-pick|am|revert|reset|switch|checkout)([[:space:]]|$)'; then
      if ! printf '%s' "$s" | grep -Eq '^git[[:space:]]+(status|diff|log|show|rev-parse|describe|ls-files|blame)([[:space:]]|$)'; then
        block "$WHO running a history- or branch-changing git command" "the human commits each accepted phase" "leave changes in the working tree and report them"
      fi
    fi
  done < <(segments "$CMD")
  return 0
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------
case "$TOOL" in
  Write | Edit | MultiEdit | NotebookEdit)
    check_file_write
    exit 0
    ;;
  Bash)
    CMD="$(field tool_input.command)"
    ;;
  mcp__aws-api__call_aws)
    CMD="$(field tool_input.cli_command)"
    [[ -z "$CMD" ]] && CMD="$(field tool_input.command)"
    [[ -z "$CMD" ]] && CMD="$(field tool_input)"
    CMD="$(printf '%s' "$CMD" | tr '\n' ' ')"
    check_global_command
    while IFS= read -r seg; do
      s="$(strip_assignments "$seg")"
      [[ "$s" != aws* ]] && s="aws $s"
      if ! aws_readonly_segment "$s"; then
        block "non-read AWS API call via MCP: $s" \
          "the aws-api MCP server is read-only inspection only" "use describe/list/get operations"
      fi
    done < <(segments "$CMD")
    exit 0
    ;;
  "")
    # Unknown or unparseable payload: scan the raw text (stricter, never weaker).
    CMD="$(printf '%s' "$INPUT" | tr '\n' ' ')"
    check_global_command
    exit 0
    ;;
  *)
    exit 0
    ;;
esac

CMD="$(printf '%s' "$CMD" | tr '\n' ' ')"
[[ -z "$CMD" ]] && exit 0

check_global_command
check_shell_protected_paths
check_rm_targets
check_scripts_and_agent_fences
exit 0
