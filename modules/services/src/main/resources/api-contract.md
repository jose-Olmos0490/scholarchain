# ScholarChain services API contract (v1)

Owner: `service-engineer`. The route table and the examples under `api-examples/` are checked
against the running service by `ContractSpec`; a change to either without the other fails the
build.

All paths are versioned (`/v1/...`). All bodies are JSON (UTF-8) except `/metrics`.
Records that come from `core` (answer, claim, provenance, plan) use the core wire format, in
which an absent option is `null`.

## Headers

| Direction | Header | Meaning |
|---|---|---|
| request | `Authorization: Bearer <token>` | required on every route except `/healthz` and `/readyz` |
| request | `Content-Type: application/json` | required on `POST /v1/query` |
| request | `Idempotency-Key` | optional on `POST /v1/query`, 8-128 characters of `A-Za-z0-9_.:-` |
| request | `X-Trace-Id` or W3C `traceparent` | optional; a safe value is kept, else one is generated |
| response | `X-Request-Id`, `X-Trace-Id` | on every response, success or error |
| response | `Idempotent-Replayed: true` | the answer is a replay of an earlier request with the same key |
| response | `Retry-After` | on 429 and on a retryable 503 |
| response | `Location` | on 202 |

## Routes

| Method | Path | Scope | Success |
|---|---|---|---|
| POST | /v1/query | query:write | 200 answer, or 202 job reference when `mode` is `async` |
| GET | /v1/jobs/{jobId} | query:read | 200 job |
| GET | /v1/jobs/{jobId}/result | query:read | 200 answer; 409 until the job has finished |
| GET | /v1/evidence/{evidenceId} | evidence:read | 200 evidence item |
| GET | /v1/graph/nodes/{nodeId} | graph:read | 200 node |
| GET | /v1/graph/nodes/{nodeId}/neighbors | graph:read | 200 page of neighbors |
| GET | /v1/explain/{queryId} | query:read | 200 audit view |
| GET | /healthz | none | 200 liveness |
| GET | /readyz | none | 200 ready, 503 not ready |
| GET | /metrics | ops:read | 200 text, 404 when metrics are disabled |

## Examples

| File | Shows |
|---|---|
| `query-request.json` | body of `POST /v1/query` |
| `query-response-200.json` | synchronous answer |
| `query-response-202.json` | asynchronous job reference |
| `job.json` | `GET /v1/jobs/{jobId}` |
| `evidence.json` | `GET /v1/evidence/{evidenceId}` |
| `explain.json` | `GET /v1/explain/{queryId}` |
| `graph-node.json` | `GET /v1/graph/nodes/{nodeId}` |
| `graph-neighbors.json` | `GET /v1/graph/nodes/{nodeId}/neighbors` |
| `error-422.json` | a typed error |
| `error-503-not-ready.json` | `GET /readyz` while not ready |

## Query body

`question` (text, untrusted), `operations`, `researcherNames`, `researcherIds`,
`windowStartYear`, `windowEndYear`, `topics`, `venues`, `institutions`, `rankingMetric`,
`graphLimit`, `vectorLimit`, `maxPathDepth`, `mode` (`sync` default, or `async`). A question or at
least one operation is required. Unknown fields are refused. Limits above the configured
bounds are refused (422), never clamped.

## Graph neighbors parameters

`windowStartYear` and `windowEndYear` are required. `limit` (default: the configured page size),
`depth` (default 1), `edgeTypes` (default: all configured), `timeoutMs` (default: the configured
ceiling) and `cursor` are optional. Every value is bounded by configuration; a value above its
bound is 422, a missing window or a non-numeric value is 400. Only `COAUTHORED_WITH` at depth 1
is served, because the graph port reads direct coauthors only.

## Error model

Every error body is
`{"error": {"code", "status", "message", "retryable", "reason"?, "retryAfterSeconds"?,
"details"?}, "requestId", "traceId"}`.

| Status | code | retryable | Cause |
|---|---|---|---|
| 400 | BAD_REQUEST | false | malformed JSON, field, parameter, ID, cursor or Idempotency-Key |
| 401 | UNAUTHENTICATED | false | missing, malformed or unknown bearer token |
| 403 | FORBIDDEN | false | the key lacks the route's scope |
| 404 | NOT_FOUND | false | unknown route, job, evidence item, node or query |
| 409 | CONFLICT | false (true while a job runs) | Idempotency-Key reused with another request; result of an unfinished job |
| 422 | UNPROCESSABLE | false | well-formed but unsupported: operation, metric, window, limit or depth above a bound |
| 429 | RATE_LIMITED | true | per-key rate limit |
| 500 | INTERNAL_ERROR | false | unexpected fault; the cause is never returned |
| 502 | DOWNSTREAM_FAILURE | per cause | a store or workflow stage failed |
| 503 | UNAVAILABLE | true | open circuit, full executor, not ready |
| 504 | DOWNSTREAM_TIMEOUT | true | a deadline passed |
