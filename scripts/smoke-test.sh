#!/usr/bin/env bash
# smoke-test.sh - bounded end-to-end check of a deployed environment through the ALB.
#
#   SCHOLARCHAIN_API_TOKEN=<bearer token>  SMOKE_RESEARCHER_ID=<researcher:... of the served run> \
#   scripts/smoke-test.sh --env dev [--base-url https://host] [--window 2010-2013]
#                         [--profile <name>] [--region <region>]
#
# Checks, in order: /healthz, /readyz, one bounded query (DIRECT_COAUTHORS for one researcher,
# graphLimit 5), one evidence fetch for an ID returned by that query, idempotency (the same
# Idempotency-Key and body replays the same queryId with Idempotent-Replayed: true), and two
# expected errors (no token -> 401, unsupported operation -> 422). Every call has a connect and
# a total timeout. The token is read from the environment, never printed and never placed on a
# command line: it is written to a mode-600 file in a mode-700 temp directory and passed to curl
# with --header @file (curl 7.55 or newer is required; the script fails clearly otherwise).
#
# /healthz and /readyz are routed by the ALB to the query API only. The graph API's own
# health is checked by the ALB target group; its routes are exercised through the evidence
# fetch above (evidence is served by the graph-evidence API).
#
# Required environment variables: SCHOLARCHAIN_API_TOKEN (bearer token, never printed),
#   SMOKE_RESEARCHER_ID (a researcher ID present in the served run),
#   SCHOLARCHAIN_APPROVED_ENVS (set by the human; must list --env).
# Region: --region, else AWS_REGION, AWS_DEFAULT_REGION or the profile's Region (only needed when
#   the ALB is looked up through AWS; not needed with --base-url). No built-in default.
# Optional: --base-url overrides the ALB lookup (which needs AWS read access for
#   elasticloadbalancing:DescribeLoadBalancers).
# Exit codes: 0 every check passed, 1 at least one check failed, 2 usage error.
# Evidence: PASS/FAIL lines on stdout; the final line "SMOKE RESULT: PASS" or
#   "SMOKE RESULT: FAIL (n check(s) failed)" is also written to build/smoke-test-<env>.txt.
# Rollback behavior: none here. On FAIL the deploy-engineer runs the rollback script with
#   --env <env> --to-sha <previous sha> (the previous SHA is in the deploy record).
# Only the POST /v1/query calls write anything (a query-session record keyed by the idempotency
# key); the evidence fetch and the probes are reads. Each call is bounded by curl timeouts.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
BASE_URL=""
WINDOW="2010-2013"
SC_PROFILE=""
SC_REGION=""
CURL_CONNECT=10
CURL_TOTAL=45
FAILED=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# shellcheck disable=SC2034  # SC_PROFILE is read by aws_call in lib/common.sh
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --base-url) BASE_URL="${2:?--base-url needs a value}"; shift 2 ;;
    --window) WINDOW="${2:?--window needs YYYY-YYYY}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || usage_error "--env is required"
validate_env_name "$ENV_NAME"
if [[ -z "$BASE_URL" ]]; then resolve_region; else [[ -z "$SC_REGION" ]] || validate_region "$SC_REGION"; fi
require_cmd curl python3 timeout
require_approved_env "$ENV_NAME"
: "${SCHOLARCHAIN_API_TOKEN:?SCHOLARCHAIN_API_TOKEN is required (the bearer token; never put it on the command line)}"
: "${SMOKE_RESEARCHER_ID:?SMOKE_RESEARCHER_ID is required (a researcher ID present in the served run)}"
[[ "$WINDOW" =~ ^[0-9]{4}-[0-9]{4}$ ]] || die "--window must look like 2010-2013"
WIN_START="${WINDOW%-*}"
WIN_END="${WINDOW#*-}"

step "locate the load balancer"
if [[ -z "$BASE_URL" ]]; then
  require_cmd aws
  DNS="$(aws_call elbv2 describe-load-balancers --names "scholarchain-$ENV_NAME-alb" \
    --query 'LoadBalancers[0].DNSName' --output text)"
  [[ -n "$DNS" && "$DNS" != "None" ]] || die "load balancer scholarchain-$ENV_NAME-alb not found"
  BASE_URL="https://$DNS"
  warn "using $BASE_URL; pass --base-url http://$DNS only for a dev environment with allow_plaintext_http"
fi
BASE_URL="${BASE_URL%/}"
info "base url: $BASE_URL"

# http <name> <expected status|a|b> <method> <path> [curl args...]  -> body in $WORK/<name>.body, headers in $WORK/<name>.hdr
http() {
  local name="$1" expect="$2" method="$3" path="$4"
  shift 4
  local code
  code="$(curl --silent --show-error --connect-timeout "$CURL_CONNECT" --max-time "$CURL_TOTAL" \
    --request "$method" --output "$WORK/$name.body" --dump-header "$WORK/$name.hdr" \
    --write-out '%{http_code}' "$@" "$BASE_URL$path" || true)"
  if [[ "|$expect|" == *"|$code|"* ]]; then
    printf 'PASS %-22s %s %s -> %s\n' "$name" "$method" "$path" "$code"
  else
    printf 'FAIL %-22s %s %s -> %s (expected %s)\n' "$name" "$method" "$path" "$code" "$expect"
    FAILED=$((FAILED + 1))
  fi
}

# json_get <file> <key-or-index>... prints the nested value, or nothing when it is absent.
json_get() {
  python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
for k in sys.argv[2:]:
    d = d[int(k)] if isinstance(d, list) else d[k]
print(d)' "$@" 2>/dev/null || true
}

# The bearer token must never appear on a command line (visible in ps and /proc/<pid>/cmdline).
# curl reads headers from a file with --header @file (curl 7.55 or newer). The file lives in the
# private (mode 700) mktemp directory, is created mode 600 and is removed by the EXIT trap.
CURL_VERSION="$(curl --version | awk 'NR == 1 { print $2 }')"
[[ "$CURL_VERSION" =~ ^([0-9]+)\.([0-9]+) ]] || die "cannot parse the curl version: $CURL_VERSION"
if [[ "${BASH_REMATCH[1]}" -lt 7 || ( "${BASH_REMATCH[1]}" -eq 7 && "${BASH_REMATCH[2]}" -lt 55 ) ]]; then
  die "curl $CURL_VERSION is too old: 7.55 or newer is required to read the Authorization header from a file (the token must not be on argv)"
fi
AUTH_FILE="$WORK/authorization.hdr"
(umask 077 && printf 'Authorization: Bearer %s\n' "$SCHOLARCHAIN_API_TOKEN" >"$AUTH_FILE")
chmod 600 "$AUTH_FILE"
AUTH=(--header "@$AUTH_FILE")
IDEM_KEY="smoke-$(date -u +%Y%m%dT%H%M%SZ)-$$"
BODY="$(python3 -I -c '
import json, sys
print(json.dumps({"operations": ["DIRECT_COAUTHORS"], "researcherIds": [sys.argv[1]],
                  "windowStartYear": int(sys.argv[2]), "windowEndYear": int(sys.argv[3]),
                  "graphLimit": 5, "vectorLimit": 2, "mode": "sync"}))' "$SMOKE_RESEARCHER_ID" "$WIN_START" "$WIN_END")"

step "liveness: GET /healthz"
http healthz 200 GET /healthz

step "readiness: GET /readyz"
http readyz 200 GET /readyz

step "one bounded query: POST /v1/query"
http query 200 POST /v1/query "${AUTH[@]}" --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEM_KEY" --data "$BODY"
QUERY_ID="$(json_get "$WORK/query.body" queryId)"
EVIDENCE_ID="$(json_get "$WORK/query.body" evidenceIds 0)"
[[ -n "$QUERY_ID" ]] || { echo "FAIL query-id           no queryId in the response"; FAILED=$((FAILED + 1)); }

step "one evidence fetch: GET /v1/evidence/{id}"
if [[ -n "$EVIDENCE_ID" ]]; then
  http evidence 200 GET "/v1/evidence/$EVIDENCE_ID" "${AUTH[@]}"
else
  echo "FAIL evidence            the query returned no evidence ID to fetch"
  FAILED=$((FAILED + 1))
fi

step "idempotency: same key and body replays the same query"
http replay 200 POST /v1/query "${AUTH[@]}" --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $IDEM_KEY" --data "$BODY"
REPLAY_ID="$(json_get "$WORK/replay.body" queryId)"
if [[ -n "$QUERY_ID" && "$REPLAY_ID" == "$QUERY_ID" ]] && grep -qi '^idempotent-replayed: true' "$WORK/replay.hdr"; then
  echo "PASS idempotent-replay    same queryId and Idempotent-Replayed: true"
else
  echo "FAIL idempotent-replay    queryId '$REPLAY_ID' vs '$QUERY_ID' or no Idempotent-Replayed header"
  FAILED=$((FAILED + 1))
fi

step "expected errors: no token -> 401, unsupported operation -> 422"
http no-token 401 POST /v1/query --header 'Content-Type: application/json' --data "$BODY"
BAD_BODY="$(python3 -I -c '
import json, sys
print(json.dumps({"operations": ["NOT_AN_OPERATION"], "researcherIds": [sys.argv[1]], "mode": "sync"}))' "$SMOKE_RESEARCHER_ID")"
http bad-operation 422 POST /v1/query "${AUTH[@]}" --header 'Content-Type: application/json' --data "$BAD_BODY"
ERR_CODE="$(json_get "$WORK/bad-operation.body" error code)"
[[ "$ERR_CODE" == "UNPROCESSABLE" ]] || { echo "FAIL error-model         expected error.code UNPROCESSABLE, got '$ERR_CODE'"; FAILED=$((FAILED + 1)); }

step "summary"
mkdir -p "$SC_BUILD_DIR"
if [[ "$FAILED" -eq 0 ]]; then
  echo "SMOKE RESULT: PASS" | tee "$SC_BUILD_DIR/smoke-test-$ENV_NAME.txt"
else
  echo "SMOKE RESULT: FAIL ($FAILED check(s) failed)" | tee "$SC_BUILD_DIR/smoke-test-$ENV_NAME.txt"
  exit 1
fi
