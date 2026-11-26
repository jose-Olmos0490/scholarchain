# Deployment report: 6ce56c9 to dev, 2026-11-25

## Outcome

STATUS: FAILED

- The rollout of 6ce56c9 completed and the services stabilized.
- The smoke test FAILED (1 check): the bounded query returned no evidence ID.
- The rollback required by policy was attempted and DENIED by the permission system. It did not run.
- **dev is still running 6ce56c9 (task revision 8).** The deployment does not meet the exit gate.

## Identity (recorded before acting)

| Item | Value |
|---|---|
| Commit | 6ce56c9f3b8e32a03530efaa62d0094d7295874e |
| Plan checksum | 2a196bb0bdd1da82fe9006d68797a24639d21bd6412e020bcf3ce70160fd87db (sha256sum of /tmp/scholarchain-plans/dev-6ce56c9.tfplan matched; deploy.sh matched it again) |
| Account (observed) | <ACCOUNT_ID>, IAM user CS441_Dev |
| Region | us-east-2 |
| Environment | dev |
| Cost bound | none given |

## Preconditions

- `git status --porcelain` empty before the deploy and at the end; HEAD = 6ce56c9.
- Terraform apply (7 add / 0 change / 4 destroy): stated by the human. Not independently verified; I did not inspect the plan contents.
- deploy.md gate 6 (code review APPROVE) is NOT literally met. Phase 15 round 3 is REQUEST_CHANGES (docs/agent-runs/phase-15/review-report-03.md); findings accepted as known risks in docs/agent-runs/phase-15/human-gate.md.
- Before the deploy: all three services on task revision 5, image tag 2365bf5. ECR held no 6ce56c9 tag in any of the three repositories, so deploy.sh pushed all three.

## Deploy

`scripts/deploy.sh --env dev --region us-east-2`: succeeded. Release recorded 2026-11-25T16:52:57Z.

- Release record: /tmp/scholarchain-plans/deploy-dev-6ce56c9.json
- Copy written by the script: /home/olmos/scholarchain/build/deploy-record-dev-6ce56c9.json
- query-api and graph-evidence-api: "is stable". embedding-worker: desired 0, not waited on.

| Service | Task revision (previous) | ECR digest for tag 6ce56c9 (= release record = local image ID) | Config digest | Running task imageDigest |
|---|---|---|---|---|
| query-api | 8 (5) | sha256:887ebe5ad8da86b4c29c1a59c6a50fa975835dac3f4a30846b81cde96249bae1 | sha256:b862c54dcf554fae03f4075a3f5a26f48b92ee50b846854c8c1aac35661635ce | same as ECR; task 6031e502..., HEALTHY |
| graph-evidence-api | 8 (5) | sha256:4fe0cb046f5907910156ba628c9ef1793c0cd520e762e8ba0919088104334d37 | sha256:65b06f64355c1303f85529616e82840d8d0803f082df6459d00aa476b59ef057 | same as ECR; task ba974696..., HEALTHY |
| embedding-worker | 8 (5) | sha256:11840f0b5f09dd9ba0020333a921a3cb2255260c66c840a4183c9e5811d2466f | sha256:0008c98fd02d5545c6f12d8dda35ef771ae80fcc52f7c607d97b800252c05f23 | no running task |

- All three revision 8 definitions are ACTIVE and use image tag :6ce56c9.
- The local image IDs supplied in the work order equal the ECR **manifest** digests (OCI image manifest), not the config digests. The config digests differ from the local IDs for that reason.

## Fix 55b0d3b in the embedding-worker

- `git log 55b0d3b~1..6ce56c9` lists 55b0d3b below 1cb0cc4, 17d42f5 and 6ce56c9. (`git merge-base` was blocked by the guard.)
- Worker revision 8 uses tag 6ce56c9, digest sha256:11840f0b..., built by this run from a clean tree at HEAD.
- **No running worker task was observed** (desired 0, running 0). The fix has not been exercised live. I did not scale it or send messages.

## Smoke test

Command: `scripts/smoke-test.sh --env dev --region us-east-2 --base-url http://<ALB_HOSTNAME>`

Plaintext HTTP was used. The only listener on the ALB is HTTP:80 with path forwarding rules. At this commit, modules/compute/main.tf creates that listener only with no certificate and allow_plaintext_http=true. The https default was not attempted. The ALB is internet-facing.

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

Summary also written by the script to /home/olmos/scholarchain/build/smoke-test-dev.txt.

Cause: NOT DETERMINED. The script deletes the response body on exit. Unverified possibilities: SMOKE_RESEARCHER_ID is not in the served run, or has no coauthors in the default window 2010-2013; or an application or data defect. The graph evidence route was never exercised. It is not known whether 2365bf5 passes the same check.

## Rollback

- Attempted: `scripts/rollback.sh --env dev --to-sha 2365bf5 --region us-east-2`.
- Result: "Permission for this tool use was denied". Not run, not retried, no alternative route used.
- No smoke test against a prior revision.
- No data repair.

## Health and alarms (final read, about 2026-11-25T16:57Z)

- query-api 1/1, graph-evidence-api 1/1, embedding-worker 0/0; rollouts COMPLETED; revision 8.
- Alarms in ALARM (states and timestamps unchanged from before the deploy):
  - graph-evidence-api TargetTracking AlarmLow
  - query-api TargetTracking AlarmLow
  - scholarchain-dev-embedding-worker-scale-in (Terraform comments call this its normal idle state)
- 5xx, no-healthy-targets, p95-latency, throttle, DLQ and worker scale-out alarms: OK. This was only minutes after rollout.

## Security finding

The new query-api task (log group /scholarchain/dev/query-api) logs at DEBUG, including AWS SDK HTTP headers and wire content. The entries I read contain the task role's access key ID, request signature and session token. No secret access key appeared in what I read. That material also entered this session's tool output; it is not reproduced here.

Not checked: whether Secrets Manager responses (which would include secret values) are logged at startup; whether other services or revision 5 log the same way. I stopped reading raw logs. Do not paste or archive these logs before they are checked.

## Tooling deviations

- The aws-api MCP server failed: profile `scholarchain-readonly` could not be found. (A first batched call was also blocked by the guard.) Read-only calls were made with the AWS CLI through Bash using default credentials (CS441_Dev, which the phase-15 human gate records as AdministratorAccess).
- Guard blocks, none worked around: a shell loop, a backtick JMESPath query, `git merge-base`, `git grep`, a filtered log query.
- docker login warned that the ECR credential is stored unencrypted in /home/olmos/.docker/config.json.
