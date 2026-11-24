#!/usr/bin/env bash
# smoke-test.sh - bounded end-to-end check of a deployed environment through the ALB.
#
#   SCHOLARCHAIN_API_TOKEN=<bearer token>  SMOKE_RESEARCHER_ID=<researcher:... of the served run> \
#   scripts/smoke-test.sh --env dev [--base-url https://host] [--window 2010-2013]
#                         [--profile <name>] [--region us-east-1]
#
# Checks, in order: /healthz, /readyz, one bounded query (DIRECT_COAUTHORS for one researcher,
# graphLimit 5), one evidence fetch for an ID returned by that query, idempotency (the same
# Idempotency-Key and body replays the same queryId with Idempotent-Replayed: true), and two
# expected errors (no token -> 401, unsupported operation -> 422). Every call has a connect and
# a total timeout. The token is read from the environment and never printed.
#
# /healthz and /readyz are routed by the ALB to the query API only. The graph API's own
# health is checked by the ALB target group; its routes are exercised through the evidence
# fetch above (evidence is served by the graph-evidence API).

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

ENV_NAME=""
BASE_URL=""
WINDOW="2010-2013"
SC_PROFILE=""
SC_REGION="us-east-1"
CURL_CONNECT=10
CURL_TOTAL=45
FAILED=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_NAME="${2:?--env needs a value}"; shift 2 ;;
    --base-url) BASE_URL="${2:?--base-url needs a value}"; shift 2 ;;
    --window) WINDOW="${2:?--window needs YYYY-YYYY}"; shift 2 ;;
    --profile) SC_PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --region) SC_REGION="${2:?--region needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$ENV_NAME" ]] || die "--env is required"
validate_env_name "$ENV_NAME"
require_cmd curl python3 timeout
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

AUTH=(--header "Authorization: Bearer $SCHOLARCHAIN_API_TOKEN")
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
if [[ "$FAILED" -eq 0 ]]; then
  echo "SMOKE RESULT: PASS"
else
  echo "SMOKE RESULT: FAIL ($FAILED check(s) failed)"
  exit 1
fi
