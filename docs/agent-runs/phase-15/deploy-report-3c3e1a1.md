# Deployment report: 3c3e1a1 to dev (INC-20261125-debug-credential-logging)

Assembled by the orchestrator from deploy-engineer's reports.

**STATUS: Deployed; CONTAINED (not RESOLVED) per human-run counts.**

## Update after human-run checks
This update supersedes the "Containment: NOT VERIFIED" section, the containment and gate 6 risks, and the containment part of "Exit gate" below, which record the state at deploy time.
- Containment (human-run, counts only, not verified by any agent): new API tasks produced 136 log lines and 0 matches, including 10 minutes before task start. All post-rollout matches came from old task streams (query-api 2130, last 20:05:06Z; graph-evidence-api 860, last 20:05:34Z).
- Gate 6: code-reviewer returned APPROVE for the logback fix on 2026-11-25T19:51:07Z (review-report-04.md). It covers that fix only; phase-15 round 3 remains REQUEST_CHANGES, accepted in human-gate.md.
- Still open: API token rotation, existing log material, Neptune audit log, TLS.

## Identity
| Item | Value |
|---|---|
| Commit | 3c3e1a106ac9f1c49129e28d4414a9a562d64228 |
| Plan sha256 | 47dd1a5c6eec644af747780ea97ec0cd3814bbe389fbb4e33121839b215c42d6 (file hash matched) |
| Account | <ACCOUNT_ID> (IAM user CS441_Dev) |
| Region / environment | us-east-2 / dev |
| Release recorded | 2026-11-25T20:05:54Z |

The Terraform apply is as stated by the human; it was not independently verified.

## Images and task definitions (all tag 3c3e1a1, all newly pushed)
| Service | Revision (previous) | ECR digest | Running |
|---|---|---|---|
| query-api | 10 (8) | sha256:697621aa2e9f5c49af0885f280ef5c939d00c15f23f2d4476ceada3a49788498 | task 194a6c8e91fb48de928ae91902467fe4, same digest, HEALTHY, 20:03:38Z, 1/1 |
| graph-evidence-api | 10 (8) | sha256:ec8ac7405ca5c7807fb99230735c4ef3e80208effdf71e3609baa9b0065d750b | task 72763c5753c74ebfad1d6a4b487d358d, same digest, HEALTHY, 20:04:09Z, 1/1 |
| embedding-worker | 10 (8) | sha256:7fb0bf74bf64f58374eadcfad98d8e1f1266f7b96b09adaca0c5dd55f6bbe0bb | desired 0, no running digest to check |

ECR digests equal the local image IDs from scripts/package.sh (run by the orchestrator, complete manifest).

## Commands
- scripts/package.sh (orchestrator): exit 0
- scripts/deploy.sh --env dev --region us-east-2: succeeded
- scripts/smoke-test.sh --env dev --region us-east-2 --base-url http://<ALB_HOSTNAME>: exit 1 (ALB listens on HTTP:80 only)
- rollback.sh: NOT run

## Smoke test
```text
PASS healthz                GET /healthz -> 200
PASS readyz                 GET /readyz -> 200
PASS query                  POST /v1/query -> 200
FAIL evidence            the query returned no evidence ID to fetch
PASS replay                 POST /v1/query -> 200
PASS idempotent-replay    same queryId and Idempotent-Replayed: true
PASS no-token               POST /v1/query -> 401
PASS bad-operation          POST /v1/query -> 422
SMOKE RESULT: FAIL (1 check(s) failed)
```
The single failure is the expected "evidence" check (as in Phase 14); not a rollback trigger.

## Alarms
In ALARM, state timestamps predating this deploy: query-api TargetTracking AlarmLow, graph-evidence-api TargetTracking AlarmLow, scholarchain-dev-embedding-worker-scale-in. INSUFFICIENT_DATA alarms were not checked.

## Containment: NOT VERIFIED
No query ran. The guard blocked `aws logs start-query` through the aws-api MCP server:
```text
Blocked: non-read AWS API call via MCP
Rule:    the aws-api MCP server is read-only inspection only
```
It was not retried or routed another way. Run containment-counts.sh (same scratchpad directory) and append its output here.

Result: ______

## Guard blocks (none worked around)
1. deploy-engineer `printf` precheck (not an approved command). The agent stopped, then was resumed without it.
2. deploy-engineer `git -C ... status --porcelain` re-check. No tree re-check by the agent after deploy; the orchestrator afterwards saw a clean tree at 3c3e1a1.
3. The containment query.
4. Orchestrator precheck containing `aws configure get region` (blocked as secret retrieval); rerun without it.

## Files
Nothing authored or edited in the repository. Script outputs: build/package-manifest.json, build/deploy-record-dev-3c3e1a1.json, build/smoke-test-dev.txt, /tmp/scholarchain-plans/{deploy,previous}-dev-3c3e1a1.*, sbt target/.

## Risks
- deploy.md gate 6 (code-review APPROVE) is not evidenced for this commit.
- Until containment passes, assume the new tasks may still log credentials or secrets.
- The smoke test sent the bearer token over plain HTTP to an internet-facing ALB. Rotate it.
- Rolling back to revision 8 (6ce56c9) would restore the logging defect.
- The deploy does not remove material already in the logs and does not address the Neptune audit log.
- The embedding worker has not run on this image. The evidence route is untested.
- AWS calls used an administrator identity.
- docker login stores the ECR credential unencrypted in ~/.docker/config.json.

## Exit gate
NOT met: containment unverified, and no evidence-backed smoke response.
