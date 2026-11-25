# Experiment C (embedding backlog), run of 2026-11-25

Result: FAILED, stop reason NO_VECTORS_WRITTEN (script exit 1, not timed out). Run once, not retried.

## Urgent items

1. The embedding-worker log group received DEBUG HTTP wire logging that includes credential headers.
   Line counts only, 17:17-17:47Z: "DEBUG" 8,884,454; "x-amz-security-token" 387,776;
   "X-Amz-Security-Token" 193,888; "Authorization: AWS4" 193,888; "AWS4-HMAC-SHA256" 290,832;
   "Credential=" 193,888. Group retention is 30 days. This extends the query-api finding to the worker.
2. Estimated cost (about USD 1.5) is above the manifest cap (USD 1.0), mostly from log ingestion.
3. All 300 messages were acknowledged and deleted although every item was rejected. Nothing retried and
   nothing reached the DLQ.
4. No ongoing runaway: queue and DLQ empty, worker at 0. Another run now would likely repeat the failure and cost.

## Identity

| field | value |
|---|---|
| experiment record / run directory | C-embedding-backlog-6ce56c9-20261125T171458Z |
| ingestion run ID | run:f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60 |
| dataset snapshot | dblp, 2026-11-01 |
| manifest | experiments/manifests/C-embedding-backlog.yaml (sha256 284aadc0d4dca911ccfac63a0b8942bcf1e22bf3916fdb29f6e466c48ce15b78) |
| commit | 6ce56c9 |
| environment / Region | dev / us-east-2 |
| script start / end (UTC) | 17:14:58 / 17:46:43 |
| deployed (observed 17:50:26Z) | query-api, graph-evidence-api, embedding-worker: revision 8, image tag 6ce56c9 |
| limits | max 3600 s, 2 tasks, 2 SQS workers, cost cap USD 1.0 (declared only) |

## Measured

Queue
- Sent 300, accepted 300, refused 0, unknown 0.
- Peak visible 300; peak in flight 2.
- DLQ: 0 throughout (baseline 0, increase 0).
- SQS sums: sent 300, received 300, deleted 300.
- Oldest message age, maximum: 1437 s.

Worker task counts (runner polls about every 18 s; time is the first poll showing the value)

| UTC | desired | running | pending | visible | in flight |
|---|---|---|---|---|---|
| 17:15:01 (precondition) | 0 | 0 | 0 | 0 | 0 |
| 17:15:32 (after send; SQS count lag) | 0 | 0 | 0 | 0 | 0 |
| 17:15:49 | 0 | 0 | 0 | 300 | 0 |
| 17:17:37 | 2 | 0 | 2 | 300 | 0 |
| 17:17:54 | 2 | 2 | 0 | 300 | 0 |
| 17:18:12 | 2 | 2 | 0 | 298 | 2 |
| 17:23:17 | 2 | 2 | 0 | 238 | 2 |
| 17:28:19 | 2 | 2 | 0 | 169 | 2 |
| 17:33:22 | 2 | 2 | 0 | 103 | 2 |
| 17:38:07 | 2 | 2 | 0 | 25 | 2 |
| 17:39:53 (first empty poll) | 2 | 2 | 0 | 0 | 0 |
| 17:46:25 | 2 | 2 | 0 | 0 | 0 |
| 17:46:43 | 0 | 0 | 0 | 0 | 0 |

Full series (107 polls, 0 failed): raw runner/observations.jsonl.
Container Insights agrees: desired 2 from 17:17 to 17:46; running 1 at 17:17 and 2 from 17:18; no datapoints
outside that span. CPU average max 58.5 percent, memory average max 23.7 percent.

Timing (runner milestones, seconds from the start of the watch)
- First task running: 140.1 (scale-out 0 to 2 observed; desired went straight to 2).
- Drained (second consecutive empty poll): 1476.7. The DRAIN_TIMEOUT limit is 1500, so the margin was 23 s.
- Back to 0 tasks: 1868.2 (391.5 s after the drain mark).
- First running poll (17:17:54) to first empty poll (17:39:53): about 22 minutes.
- Worker batch lines: first 17:18:19, last 17:39:34.

Outcomes
- Vectors written: 0 observed (new SUCCEEDED put batches 0, new FAILED put batches 0, ledger read OK).
- Item rejections: 90,600, all store=s3vectors code=EMBEDDING_FAILED (302 per message).
- Messages: received 300, acknowledged 300, retried 0, deadLettered 0.
- SQS redeliveries: none seen. SDK-level retries: not observed.

Error evidence (counts from the worker log group, 17:17-17:47Z)
- "ValidationException" 96,600 lines; "Operation not allowed" 94,800 lines.
- "invoke HTTP/1.1" 3,000 lines; "HTTP/1.1 400" 3,000 lines (10 per message if one line per request).
- AccessDenied, ThrottlingException, ResourceNotFoundException, ModelNotReady, "not authorized": 0 each.
- A sanitized message extract from a 30 s sample read "ValidationException: Operation not allowed".
- AWS/Bedrock CloudWatch metrics in us-east-2: none listed.

## Inferred, not established
- The 3,000 HTTP 400 responses are the Bedrock embedding calls returning that ValidationException, and this
  caused the EMBEDDING_FAILED rejects. The counts and the code path (VectorIngestor embed step) fit, but
  requests and responses were not matched one to one. The underlying account or model cause is unknown.
- Fix 55b0d3b was not exercised: the failure is at the embed step, before the vector write.
- Items reached the embed step, so they were not skipped as unchanged.
- Whether this relates to the smoke "evidence" failure was not examined.

## Estimated cost (billing not observed; prices from memory, unverified)

| item | basis | USD |
|---|---|---|
| CloudWatch Logs ingestion | 2,800,405,476 bytes, 10,569,194 events (AWS/Logs, hourly bucket from 17:10Z) at 0.50/GB; no free tier assumed | about 1.40 |
| Fargate | about 1 task-hour (2 x about 29 min), 0.5 vCPU, 1 GB (manifest defaults, deployed size not verified) | about 0.03 |
| S3, DynamoDB, SQS, other | about 90,600 item reads; not measured | under 0.15 (assumption) |
| Bedrock | requests rejected with 400, assumed not billed | 0 |
| total | | about 1.5 |

The manifest estimated USD 0.11 incremental (1.6 in its put-failure case) and did not include log volume.
Thirty-day log storage adds a small amount not included above.

## Final state (observed 18:24:30Z)
Work queue 0 visible, 0 in flight (0 delayed at 17:53Z). DLQ 0. Worker desired/running/pending 0/0/0.

## Evidence
- Raw (read-only, SHA256SUMS verified, nothing missing, --allow-missing not used):
  /home/olmos/scholarchain/experiments/raw/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z
  sha256 of SHA256SUMS: d57023b0a99a439af0e8e85873ca7b1cda9701e9094e95bfefe770af9b27c7a0
- Derived:
  /home/olmos/scholarchain/experiments/derived/C-embedding-backlog/C-embedding-backlog-6ce56c9-20261125T171458Z
- Run record: /home/olmos/scholarchain/build/experiment-C-embedding-backlog-6ce56c9-20261125T171458Z.json
- Runner output: /home/olmos/scholarchain/build/experiments/C-embedding-backlog-6ce56c9-20261125T171458Z
- Console: /tmp/claude-1000/-home-olmos-scholarchain/cc749a84-4b18-48a2-b7d8-c6756d56c0b3/scratchpad/experiment-C-console.log
- The error counts, log volume and final state above came from ad hoc read-only queries. They are not part
  of the sealed raw data and live only in this report. CloudWatch logs expire after 30 days.

## Log hygiene of collected files
33 files scanned (raw, derived, runner output, run record, console), counts only. Zero matches for access key
IDs, signatures, session tokens, authorization headers, secret words or DEBUG. A 12-digit/ARN/URL pattern
matched 2 lines in raw runner/sends.jsonl and 2 in its build copy; for the raw file all matches are inside
message IDs or body hashes (0 outside them). Judged safe to commit. This is a pattern scan, not a proof.
worker_outcomes.jsonl (8.8 MB, fixed fields only) is the large file.
