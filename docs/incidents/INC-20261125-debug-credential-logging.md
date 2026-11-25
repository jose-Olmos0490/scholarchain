# INC-20261125-debug-credential-logging

Task role credential material and the API-key secret response written to
CloudWatch Logs.

| Field | Value |
|---|---|
| Incident ID | INC-20261125-debug-credential-logging |
| Status | **CONTAINED** (not RESOLVED). Open items are listed under "Containment" |
| Environment | dev, us-east-2, account `<account-id>` |
| Start (UTC) | 2026-11-24T13:58:52Z (first SigV4 authorization header found) |
| End (UTC) | Not ended; the incident is not resolved. Last matches in the old API task streams: query-api 2026-11-25T20:05:06Z, graph-evidence-api 2026-11-25T20:05:34Z (human-run counts, see "Containment"). At diagnosis, 530 new query-api authorization header lines matched after 2026-11-25T18:45:00Z (checked about 18:54Z) |
| Deployed at diagnosis | commit 6ce56c9, task revision 8 on all three services; query-api 1/1, graph-evidence-api 1/1, embedding-worker 0/0 |
| Severity (assessment) | High for confidentiality of logs. No evidence of misuse (see limits below) |
| Report type | Diagnosis, with a later "Containment" update. At diagnosis, nothing was changed, deleted, redeployed, scaled or stopped |
| Author | incident-responder |

## Containment

Status: **CONTAINED**, not RESOLVED. The sections after this one record the
diagnosis as it stood before the fix was deployed.

**Source of these facts:** HUMAN-RUN, counts-only CloudWatch Logs Insights
results, supplied by the human architect. No agent ran or verified these
queries, and no agent verified the deployment for this update.

### Fix

Commit `3c3e1a1` was deployed at about 2026-11-25T20:05Z.

### New tasks

| Service | New task ID |
|---|---|
| query-api | `194a6c8e91fb48de928ae91902467fe4` |
| graph-evidence-api | `72763c5753c74ebfad1d6a4b487d358d` |

The two new tasks together produced 136 log lines, with **0 matches** for
`X-Amz-Security-Token`, `Authorization: AWS4`, `SecretString` or `DEBUG`,
including 10 minutes before task start.

### Old tasks

All matches after rollout came from the old task streams.

| Service | Old task ID | Matches | Last match (UTC) |
|---|---|---|---|
| query-api | `6031e50296a34b86a0014826c7dc2fec` | 2130 | 2026-11-25T20:05:06Z |
| graph-evidence-api | `ba974696b5e6461a91dd3af2c236bdb8` | 860 | 2026-11-25T20:05:34Z |

### Scope and limits

- The result covers the two new API tasks. No result for embedding-worker under
  the fix is recorded here.
- The result does not cover the Neptune audit log, which the service logging
  fix does not change.
- 136 lines is a small sample. These results do not show how the new tasks
  behave under all request paths.

### Open items

The status stays CONTAINED because these remain open.

| Open item | Detail |
|---|---|
| API token rotation | Not done. The post-deploy smoke test also sent the bearer token over plain HTTP to the internet-facing ALB |
| Existing logged material | Still in the log groups |
| Neptune audit log | Still open. See "Second source: Neptune audit log" |
| TLS | Still open. The diagnosis found only an HTTP:80 listener on the ALB |

## Detection source

1. deploy-engineer deployment report for 6ce56c9 (2026-11-25): DEBUG wire
   logging in `/scholarchain/dev/query-api` with access key ID, signature and
   session token.
2. Experiment C report, run
   `C-embedding-backlog-6ce56c9-20261125T171458Z`: the same in
   `/scholarchain/dev/embedding-worker`.

No alarm or automated control detected this. Both detections were incidental.

## User-visible impact

- No user-visible outage or wrong answer is attributed to this incident.
- Observed side effect: high log volume. Experiment C recorded 2,800,405,476
  bytes ingested for the worker and an estimated (not billed) cost above the
  manifest cap.
- The smoke-test evidence failure and the experiment C `EMBEDDING_FAILED`
  result are separate problems. No causal link was examined or established.

## Trace and run IDs

| Item | ID |
|---|---|
| Experiment run | `C-embedding-backlog-6ce56c9-20261125T171458Z` |
| Ingestion run | `run:f6023de34a312366583b3eacbc2b2a689234cf1abe79e184fb66875bac297a60` |
| query-api task, at diagnosis | `6031e50296a34b86a0014826c7dc2fec` |
| graph-evidence-api task, at diagnosis | `ba974696b5e6461a91dd3af2c236bdb8` |
| embedding-worker tasks (stopped) | `692cf1bddaf848e398863e00406099a1`, `9245b2c349a6480397df515d2354e023` |
| Earlier query-api tasks with matches | `4775d98fe553485bbd091b79efbc3741`, `d3c3bf01d15449d5a4ae28b38d811298` |
| Earlier graph-evidence-api tasks with matches | `5cb4975c3cc94e18b1c00928f06bb153`, `bea8268bacaa48bc9acdd5d915553995`, `21e3170c4c024a9dbeef07b37c58a0c1`, `77315b6442794dda8061df766999a047` |

Log stream names are `<service>/<service>/<task ID>`. No application trace
IDs were read, because no raw message was read.

## Observed facts

### What is in the logs

Counts are matching log events (CloudWatch Logs Insights `recordsMatched`),
window 2026-11-24 to the time of the query on 2026-11-25. The API services were
still running the unfixed revision at query time, so these counts are not final
totals.

| Indicator | query-api | graph-evidence-api | embedding-worker |
|---|---|---|---|
| `Authorization: AWS4` | 84,462 | 24,098 | 193,888 (experiment C) |
| `X-Amz-Security-Token` | 84,462 | 24,106 | 193,888 (experiment C) |
| `Credential=ASIA` (temporary access key ID) | 84,490 | 24,106 | 193,888 |
| `SecretString` (Secrets Manager response) | **4** | **4** | 0 |
| of those, also containing `sha256` and `http-outgoing` | 4 | 4 | not applicable |

`DEBUG` matched 12,908,240 events across the three groups.

Zero matches across all three groups for:

- Secret access key labels: `SecretAccessKey`, `secretAccessKey`,
  `secret_access_key`, `aws_secret_access_key`, `AWS_SECRET_ACCESS_KEY`.
- API bearer token header: `Bearer ` and `bearer `.
- Any line containing `uthorization` without `AWS4-HMAC`.
- Long-term access key credentials: `Credential=AKIA`.
- `token` on any line that contains `SecretString`.
- `X-Forwarded-For` (checked on the two API groups only).

Answers to the three specific questions:

| Question | Answer |
|---|---|
| Secrets Manager secret values in logs? | **Yes.** 8 events carry the `GetSecretValue` response body of `scholarchain/dev/api-keys`, in both API groups |
| API bearer token in logs? | **Not found.** 0 matches for the header |
| Secret access keys in logs? | **Not found.** 0 matches for any labelled form |

Limits: these are text matches. A token or key printed without a label or
prefix would not be found. No raw message was read and the secret was not
read.

### Second source: Neptune audit log (outside the original scope)

`/aws/neptune/scholarchain-dev-graph/audit`:

- `X-Amz-Security-Token`, `AWS4-HMAC` and `Credential=ASIA` each matched
  12,116 of 12,119 events.
- 0 matches for `Bearer `, `SecretString`, `SecretAccessKey`,
  `Credential=AKIA`.
- First `AWS4-HMAC` match found: 2026-11-24T17:38:33Z.
- The group has **no retention limit** (never expires) and no KMS key ID in
  its description. Deletion protection is off.
- This is written by the Neptune engine, not by the application. A logging fix
  in the services will not stop it.
- Which principals appear in it was not determined.

### Retention and distribution

- 30 days on `/scholarchain/dev/query-api`, `graph-evidence-api` and
  `embedding-worker` (verified). All three use customer KMS key
  `273bf872-218e-4e72-a848-6f2b5bade30e` (enabled).
- `pipeline` and `emr-steps` showed 0 stored bytes and were not searched.
  `vpc-flow` was not searched.
- The three service groups have no subscription filters. The Region has no
  export tasks and no CloudWatch Logs resource policies.

### Who can read the logs

- IAM user `CS441_Dev`, the only IAM user, with `AdministratorAccess`.
- Role `AccountFullAccessRole`, also `AdministratorAccess`. Its trust
  principal is `account-access.amazonaws.com`. Last used 2026-11-24 in
  us-east-1.
- The account root user (by AWS design).
- `ReadOnlyAccess` is attached to no one. The inline policies of the three
  task roles grant no log read.
- Not examined: policies of the other service roles, and any
  organization-level access.
- CloudTrail `FilterLogEvents`, `GetLogEvents` and `StartQuery` events (up to
  the newest 200 per name) show only `CS441_Dev`. This includes agent sessions
  and this diagnosis.

### Copy outside AWS

The deploy-engineer report states that some of the credential material also
entered that agent session's tool output. This means a copy probably exists in
the local Claude Code transcript and was sent to the model provider (the
second part is inference). Transcripts were not opened.

## Timeline (UTC)

| Time | Event |
|---|---|
| 2026-11-19 07:02 | `ab21a0f` adds `logbackClassic` to the dependency ledger |
| 2026-11-24 02:28 | `f6e6beb` adds the deployable entrypoints, with a test logback config only |
| 2026-11-24 13:13 | `/scholarchain/dev/*` log groups created |
| 2026-11-24 13:42 | Task revision 1 registered (image a75b607) |
| 2026-11-24 13:58:52 | **First credential header** (graph-evidence-api, task `5cb4975c...`) |
| 2026-11-24 14:26 | Secret `scholarchain/dev/api-keys` last changed |
| 2026-11-24 14:27:20 | **First secret response body logged** (graph-evidence-api) |
| 2026-11-24 17:13 | Image 2365bf5 pushed (query-api); revision 5 registered at 17:14 |
| 2026-11-24 17:15:00 | query-api first `DEBUG`, credential header and secret response |
| 2026-11-24 17:37 to 17:41 | New API tasks start; secret response logged again by both |
| 2026-11-24 17:38:33 | First credential match found in the Neptune audit log |
| 2026-11-25 16:50 to 16:52 | 6ce56c9 (revision 8) rollout; secret response logged by both APIs |
| 2026-11-25 16:52:57 | Release recorded; smoke test follows, sending the bearer token over plain HTTP |
| 2026-11-25 16:55:39 | query-api logs the secret response again |
| about 2026-11-25 16:57 | deploy-engineer observes the DEBUG credential logging |
| 2026-11-25 17:17:50 | embedding-worker first credential header (experiment C) |
| 2026-11-25 17:46:35 | embedding-worker scaled to 0; its logging stops |
| 2026-11-25 about 18:10 to 18:54 | Diagnosis; API services still logging credential headers |
| 2026-11-25 about 20:05 | Fix `3c3e1a1` deployed (human-run results, see "Containment") |
| 2026-11-25 20:05:06 | Last match in the old query-api task stream (human-run) |
| 2026-11-25 20:05:34 | Last match in the old graph-evidence-api task stream (human-run) |

## Root cause

### Application services (confirmed)

**Nothing sets DEBUG explicitly. It is Logback's default, which applies
because the services ship no logging configuration.**

- `project/Dependencies.scala:80` declares `logback-classic` as a runtime
  dependency, and `project/Dependencies.scala:155-156` adds it to `services`.
- `modules/services/src/main/resources/` at 6ce56c9 contains only
  `api-contract.md`, `api-examples/` and `scholarchain-services.conf`. There
  is no `logback.xml`. The same is true at a75b607 and 2365bf5.
- The only logback file is
  `modules/services/src/test/resources/logback-test.xml:1-7` (root `WARN`).
  It is not packaged, so tests run quiet while production runs at DEBUG.
- `docker/launch.sh:10-18` passes no logging flag.
- `infra/terraform/modules/compute/main.tf:118` passes only the configured
  environment map.
- Deployed definitions: no `LOG_LEVEL` or `JAVA_TOOL_OPTIONS` variable in the
  revisions queried, and no logging-related name, command override or ECS
  `secrets` entry in the full name lists read (query-api 1, 5, 8; the other
  two services at 8).
- Inference (standard Logback behavior, not reproduced here): with no
  configuration file the root logger is DEBUG to the console. That enables the
  AWS SDK request logger and the Apache HTTP header and wire loggers. Wire
  logging records response bodies, which is how the secret reached the logs.
  The `http-outgoing` matches are consistent with this.
- `SecretsManagerApiKeySource.scala:7-10` states that nothing read from the
  secret is logged. Library wire logging defeats that statement.

### Earlier revisions

Exposure did not start with 6ce56c9. Matches exist from tasks that ran on
2026-11-24, before 6ce56c9 was built. The first falls when only a75b607
revisions (1 and 2) were registered, and later ones fall in the 2365bf5
period. Mapping a stopped task to its revision is inference from timestamps.
Revisions 3, 6 and 7 name image tags `6bfde3b`, `08fc89b` and `6ce56c9`; the
query-api repository holds only a75b607, 2365bf5 and 6ce56c9.

**A rollback would not help.** No earlier image has a logging configuration.

### Neptune audit log

`infra/terraform/modules/neptune/main.tf:19-22` enables
`neptune_enable_audit_log`, and line 107 exports `audit` to CloudWatch. The
log group is not managed in `modules/logging/main.tf:4-15`, so it has no
retention and no environment KMS key. That the engine records request headers
is inferred from the match counts.

## Risk assessment (inference)

- The access key ID, signature and session token cannot sign **new** requests
  without the secret access key, which was not found.
- A logged signed request might be replayable for the short SigV4 validity
  window (minutes). The AWS endpoints involved limit where it could be used,
  and Neptune is not publicly accessible.
- ECS task credentials are temporary and are believed to expire within about
  6 hours (from general AWS knowledge, not verified). At diagnosis, running
  tasks kept receiving and logging fresh ones.
- The logged secret holds token **digests**, key IDs and scopes by design
  (`Auth.scala:23-24`, `Auth.scala:69`). That the live secret holds nothing
  else is inferred; no `token` text appeared on those lines. A high-entropy
  token cannot practically be recovered from its SHA-256 digest. Token entropy
  was not checked.
- The bearer token itself was not found in logs. It **was** sent over plain
  HTTP to an internet-facing ALB (the only listener is HTTP:80). Treat it as
  exposed in transit and rotate it.
- DEBUG wire logs very likely also contain request and response bodies of
  application data (questions, evidence, graph rows). Not checked.

Blast radius if a task role were misused (from the live inline policies):

| Role | Notable permissions |
|---|---|
| query-api | DynamoDB read and `PutItem` on three tables, Neptune read, vector read, RAG object read, Bedrock invoke, read the API-key secret |
| graph-evidence-api | DynamoDB read, Neptune read, read the API-key secret |
| embedding-worker | Receive and delete queue messages, DLQ send, `PutItem` on ledger and registry, RAG object put, `PutVectors`, embedding invoke |

## Recovery action

**None taken at diagnosis.** The work order was diagnosis only. No rollback was
authorized and a rollback would not fix this. The fix deployed afterwards is
recorded under "Containment".

### Proposed actions (each needs its own work order or human approval)

As proposed at diagnosis. See "Containment" for what has been recorded since.

| # | Action | Owner | Approval |
|---|---|---|---|
| 1 | Decide on containment now: either scale query-api and graph-evidence-api to 0 until fixed (stops new exposure, takes the API down), or keep them running and accept continued logging | human | human |
| 2 | Add `modules/services/src/main/resources/logback.xml`: root INFO or WARN; `software.amazon.awssdk` (including `.request`), `org.apache.http` (including `.wire` and `.headers`), `io.netty` and `io.undertow` at WARN or higher; no environment override that can lower the AWS or HTTP loggers | service-engineer | code review |
| 3 | Add the tests in "Preventive test or control" | service-engineer, test-engineer | code review |
| 4 | Build and redeploy all three services from the fixed commit, then repeat the count queries on the new streams (expect 0). Do not run the worker or experiment C again before this | deploy-engineer | `/deploy` approval |
| 5 | Stop credential capture in the Neptune audit log: disable audit export, or manage the group in Terraform with short retention and the KMS key | infra-engineer; human applies | **security change** |
| 6 | After the fix is live, remove affected material: delete affected streams in the three service groups and the Neptune audit group, or shorten retention. Retention applies to a whole group. Record counts first (this report) | human | **destructive, human only** |
| 7 | Decide on session revocation for the three task roles (deny sessions issued before the fix time), then force new tasks. Revoking before the fix only produces new logged credentials | human | **security change** |
| 8 | Rotate the API keys (digests logged; token sent over HTTP). Do it after the fix, or the new response is logged too | human | **security change** |
| 9 | Require TLS on the ALB, and make the smoke test refuse to send the token over HTTP | infra-engineer | security change, human plan sign-off |
| 10 | Create the missing `scholarchain-readonly` profile with a read-only role that cannot read log content or secrets by default; stop agents using the administrator user | human | **security change** |
| 11 | Remove or protect the local agent transcript that holds credential material | human | human |
| 12 | Check for misuse more fully (data events are not recorded; no CloudTrail trail was returned for us-east-2) and consider enabling one | human; infra-engineer | security change |

## Data-integrity assessment

- **No evidence of data modification, deletion or loss from this incident.**
- CloudTrail write management events, newest 1,000 only: no write attributed
  to a task-ID session other than `logs:CreateLogStream` (believed to be the
  ECS log driver). Other events are attributed to `CS441_Dev`, AWS service
  roles or service principals. The results were truncated, so older events
  were not reviewed.
- Not covered: DynamoDB, S3, vector, SQS and Neptune data-plane access. Source
  IPs were not examined. **Absence of misuse is therefore not proven.**
- Confidentiality is affected: credential material and the secret response are
  stored in logs.
- Separate issue seen in experiment C: 300 queue messages were acknowledged
  and deleted although every item was rejected. That is not part of this
  incident but needs an owner (storage-engineer, service-engineer).

## Follow-up owner

Human architect owns the incident: containment, deletion, revocation,
rotation and access changes. service-engineer owns the root-cause fix.
infra-engineer owns the Neptune audit and TLS changes. See the table above.

## Preventive test or control

1. **Configuration test** (`modules/services/src/test`): load the
   **production** `logback.xml` explicitly, because `logback-test.xml`
   otherwise takes precedence. Assert that the file exists and that
   `software.amazon.awssdk.request`, `org.apache.http.wire` and
   `org.apache.http.headers` are not enabled at DEBUG.
2. **Canary test** (`tests/**`): under the production logging configuration,
   run a signed SDK call against a local fake endpoint with fake credentials
   and a canary secret value. Assert that the captured output has none of the
   canary strings and no authorization or security-token header.
3. **Packaging check**: fail `sbt check` if the services jar has no
   `logback.xml` (build-engineer).
4. **Post-deploy gate**: count `Authorization: AWS4`, `X-Amz-Security-Token`,
   `SecretString` and `DEBUG` in the new streams and fail on any match
   (infra-engineer authors, deploy-engineer runs).
5. **Alarm**: metric filter on those strings, plus a log-ingestion volume
   alarm (infra-engineer).

## Investigation notes

- Identity: AWS CLI through Bash as `arn:aws:iam::<account-id>:user/CS441_Dev`
  (administrator). The `aws-api` MCP server failed because profile
  `scholarchain-readonly` does not exist.
- All calls were describe, list, get, filter or Logs Insights queries.
  Message content was never returned to this session: Insights results were
  projected to status and statistics, and `filter-log-events` to timestamp and
  stream name. Nothing raw was written to disk.
- Side effect: the Insights queries are kept in query history, and each filter
  query holds up to one matching raw event in its server-side result, readable
  by the same principals as the logs. Scan cost is estimated at under USD 0.50
  (not billed data).
- Guard blocks, all on command form: a compound `cd` command; `git -C ... log`
  and `git -C ... grep`; two MCP calls passed as a list; task-definition
  queries containing `||` or `contains()`; `git ls-tree`; `git grep`. Simpler
  allowed read-only forms were used instead (`git log`, `git show`,
  `git diff`, the Read tool, queries without filter functions). The guard and
  its settings were not changed.
