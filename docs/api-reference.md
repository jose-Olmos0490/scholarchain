# ScholarChain REST API reference (v1)

Authoritative contract:
[api-contract.md](../modules/services/src/main/resources/api-contract.md) and
[api-examples/](../modules/services/src/main/resources/api-examples/), owned by
`service-engineer`. `ContractSpec` checks the route table, error codes and
response shapes against the running service in-process with fakes; it passed
inside `sbt clean check` on 2026-11-25.

**All examples are illustrative.** They come from test fixtures
(`Ada`/`Bea`, `s3://test-graph/...`, `test-model-v1`), not from a deployed run.
No `curl` command here was replayed, because the live entry points need AWS
stores and no environment was called. In the deployed dev environment, queries
returned 200 without evidence IDs
([smoke log](agent-runs/phase-15/smoke-test-transcribed.log)).

There is no OpenAPI document; the contract is Markdown plus JSON examples.

## 1. Headers

| Direction | Header | Meaning |
|---|---|---|
| request | `Authorization: Bearer <token>` | every route except `/healthz`, `/readyz` |
| request | `Content-Type: application/json` | required on `POST /v1/query` |
| request | `Idempotency-Key` | optional, 8-128 characters of `A-Za-z0-9_.:-` |
| request | `X-Trace-Id` or `traceparent` | optional; a safe value is kept |
| response | `X-Request-Id`, `X-Trace-Id` | every response |
| response | `Idempotent-Replayed: true` | replay of an earlier request |
| response | `Retry-After` | 429 and retryable 503 |
| response | `Location` | 202 |

## 2. Routes

| Method | Path | Scope | Success | Served by |
|---|---|---|---|---|
| POST | `/v1/query` | `query:write` | 200 answer, or 202 when `mode` is `async` | query-api |
| GET | `/v1/jobs/{jobId}` | `query:read` | 200 | query-api |
| GET | `/v1/jobs/{jobId}/result` | `query:read` | 200; 409 until finished | query-api |
| GET | `/v1/explain/{queryId}` | `query:read` | 200 audit view | query-api |
| GET | `/v1/evidence/{evidenceId}` | `evidence:read` | 200 | graph-evidence-api |
| GET | `/v1/graph/nodes/{nodeId}` | `graph:read` | 200 (researcher nodes only) | graph-evidence-api |
| GET | `/v1/graph/nodes/{nodeId}/neighbors` | `graph:read` | 200 page | graph-evidence-api |
| GET | `/healthz` | none | 200 | all three |
| GET | `/readyz` | none | 200, or 503 | all three |
| GET | `/metrics` | `ops:read` | 200 text; 404 when disabled (default) | all three |

The embedding worker serves no API route.

## 3. `POST /v1/query`

Fields: `question`, `operations`, `researcherNames`, `researcherIds`,
`windowStartYear`, `windowEndYear`, `topics`, `venues`, `institutions`,
`rankingMetric`, `graphLimit`, `vectorLimit`, `maxPathDepth`, `mode` (`sync`
default, or `async`). A question or at least one operation is required. Unknown
fields are refused (400). A window needs both years. Limits above configured
bounds are refused (422), never clamped. Defaults: body up to 65,536 bytes,
question up to 2,000 characters, lists up to 20 items.

Operations: `RESEARCHER_RANKING`, `MAXIMUM_TEAM_SIZE`, `DIRECT_COAUTHORS`,
`SHORTEST_PATH`, `COMPONENT_LOOKUP`, `COMMUNITY_COMPARISON`, `BRIDGE_RANKING`,
`TEMPORAL_COMPARISON`, `TOPIC_FILTER`, `VENUE_FILTER`, `INSTITUTION_FILTER`,
`SEMANTIC_EXPLANATION`. Being in the enum does not mean storage supports every
combination: ranking by bridge measures is refused by storage today
(`OperationAllowlist.scala`).

Request ([query-request.json](../modules/services/src/main/resources/api-examples/query-request.json)):

```json
{
  "operations": ["DIRECT_COAUTHORS"],
  "researcherIds": ["researcher:orcid:cfffda985bade820091700e59f466d5366135a58f7e67aeac6f79fa80793118d"],
  "windowStartYear": 2010,
  "windowEndYear": 2013,
  "mode": "sync"
}
```

Command form (not replayed; `BASE_URL` and the token come from your
environment):

```bash
curl -sS -X POST "$BASE_URL/v1/query" \
  -H "Authorization: Bearer $SCHOLARCHAIN_API_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-0001" \
  --data @modules/services/src/main/resources/api-examples/query-request.json
```

Passing the token with `-H` exposes it in the process list; the smoke script
uses a mode-600 header file instead. Use HTTPS; the dev ALB serves plain HTTP
only ([threat model](threat-model.md)).

200 response, abridged (`provenance` objects removed; full file
[query-response-200.json](../modules/services/src/main/resources/api-examples/query-response-200.json)):

```json
{
  "queryId": "query:08af586f...",
  "traceId": "trace-00000000000000000000000000000002",
  "answer": {
    "schemaVersion": "answer-response-v1",
    "summary": "Ada has coauthored with Bea.",
    "claims": [
      {
        "claimId": "claim:e7872808...",
        "text": "researcher:orcid:cfffda98... and researcher:orcid:ecf428c7... have 3 joint publication(s) in 2010-2013.",
        "evidenceIds": ["evidence:4f1f7c9c..."],
        "verificationStatus": "VERIFIED",
        "uncertainty": null
      }
    ],
    "evidenceBundleId": "bundle:078a4aa0...",
    "abstained": false,
    "uncertainty": null
  },
  "verification": "VERIFIED",
  "evidenceIds": ["evidence:4f1f7c9c..."],
  "removedClaimCount": 0,
  "interpretations": [],
  "links": {"evidence": ["/v1/evidence/evidence:4f1f7c9c..."], "explain": "/v1/explain/query:08af586f..."},
  "jobId": "job-00000000000000000000000000000003",
  "state": "SUCCEEDED"
}
```

`verification`: `VERIFIED`, `PARTIALLY_SUPPORTED`, `UNSUPPORTED` or
`CONFLICTING`. A refusal, an unknown or ambiguous researcher, or a plan with no
deterministic evidence gives an abstaining answer.

202 response ([query-response-202.json](../modules/services/src/main/resources/api-examples/query-response-202.json)):
`jobId`, `queryId`, `state: "QUEUED"`, `stage`, `traceId`, `attempts`,
`createdAt`, `updatedAt`, `links.self`, `links.explain`.

Idempotency: same key and body replays the same `queryId`; the same key with a
different request gives 409.

## 4. Jobs

[job.json](../modules/services/src/main/resources/api-examples/job.json):
`jobId`, `queryId`, `state`, `stage`, `traceId`, `attempts`, timestamps,
`links` (`self`, `explain`, and `result` once `SUCCEEDED`); a failed job has an
`error` object. A job is visible only to the key that created it (others get
404). Known deferred defect: a job can stay `RUNNING` forever
([phase-11 gate](agent-runs/phase-11/human-gate.md), T7).

## 5. Evidence

[evidence.json](../modules/services/src/main/resources/api-examples/evidence.json):
`evidenceId`, `evidenceType`, `provenance` (`provenance-v1`), `sourceRecord`
(`referencedIds`, `workId`, `sourceDataset`, `sourceRecordId`), `sourceUri`,
and, when present, `graphPath`, `textPointer` (`chunkId`, `textHash`),
`content` (`text`, `truncated`, `trust: "untrusted-data"`), `retrievalScore`.
A malformed ID is 400; an unknown one is 404.

## 6. Graph

Node ([graph-node.json](../modules/services/src/main/resources/api-examples/graph-node.json)):
`nodeId`, `nodeType`, `displayName`, `orcid`, `metrics`, `provenance`, `links`.

Neighbors parameters: `windowStartYear` and `windowEndYear` (required),
`limit`, `depth` (default 1), `edgeTypes`, `timeoutMs`, `cursor`. Only
`COAUTHORED_WITH` at depth 1 is served. There is no `direction` parameter.
Response ([graph-neighbors.json](../modules/services/src/main/resources/api-examples/graph-neighbors.json)):
`nodeId`, `window`, `depth`, `edgeTypes`, `limit`, `items[]` (`neighborId`,
`edge`), `nextCursor`, `truncated`.

## 7. Explain

[explain.json](../modules/services/src/main/resources/api-examples/explain.json):
`queryId`, `runId`, `plan`, `stagesExecuted` (latency, outcome),
`promptVersions`, `modelVersions`, `metricVersions`, `researcherIds`,
`evidenceIds`, `scoreComponents`, `counters` (tokens, latency, evidence items,
claims), `verification` (status, answer hash), `provenance`, `note`. No hidden
reasoning, raw prompt or raw completion is stored or returned. 409 while the
query is unfinished.

## 8. Error model

```json
{
  "error": {
    "code": "UNPROCESSABLE",
    "status": 422,
    "message": "an operation is not in the allowlist of runtime operations",
    "retryable": false,
    "reason": "UNSUPPORTED_OPERATION"
  },
  "requestId": "req-00000000000000000000000000000012",
  "traceId": "trace-00000000000000000000000000000013"
}
```

Optional members: `reason`, `retryAfterSeconds`, `details`.

| Status | code | retryable | Cause |
|---|---|---|---|
| 400 | `BAD_REQUEST` | false | malformed JSON, field, parameter, ID, cursor or Idempotency-Key |
| 401 | `UNAUTHENTICATED` | false | missing, malformed or unknown token |
| 403 | `FORBIDDEN` | false | key lacks the scope |
| 404 | `NOT_FOUND` | false | unknown route, job, evidence, node or query |
| 409 | `CONFLICT` | false (true while a job runs) | key reuse with another request; unfinished job |
| 422 | `UNPROCESSABLE` | false | unsupported operation, metric, window, limit or depth |
| 429 | `RATE_LIMITED` | true | `RATE_LIMIT` or `AUTH_FAILURE_LIMIT` |
| 500 | `INTERNAL_ERROR` | false | unexpected fault; cause not returned |
| 502 | `DOWNSTREAM_FAILURE` | per cause | store or workflow stage failed |
| 503 | `UNAVAILABLE` | true | open circuit, full executor, not ready, job store or overload |
| 504 | `DOWNSTREAM_TIMEOUT` | true | deadline passed |

Rate limit default: 120 requests per 60 s per key, per task. Failed
authentication default: 10 per 300 s per source.

## 9. Differences from the course specification's example contract

| Specification | Implementation |
|---|---|
| nested `constraints`, `analysis`, `retrieval`, `output` request objects | flat fields |
| `claims` and per-researcher metric table at the top of the response | claims under `answer.claims`; no researcher table |
| flat error body with `error`, `message`, `traceId`, `retryable` | nested `error` object plus `requestId`, `traceId` |
| neighbors `direction`, depth 1..3, five edge types | no `direction`; depth 1; `COAUTHORED_WITH` only |
| explain lists query-template IDs, indexes, metadata filters, retry and cache counters | not in the explain view |
