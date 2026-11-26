# Service limits and Region availability

Only limits and availability facts the implementation relies on. Every
"checked 2026-11-25" entry was read from the linked AWS documentation page on
that date through the `aws-docs` tool. Quotas change; recheck before a larger
run. Deployment Region: **us-east-2** (moved from us-east-1 because an
organization policy denied resource creation elsewhere;
[phase-14 gate](agent-runs/phase-14/human-gate.md),
[denied-actions.txt](agent-runs/phase-14/logs/denied-actions.txt)).

## 1. Limits

### S3 Vectors

Source: [Limitations and restrictions](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html), checked 2026-11-25.

| Limit | Value | Where used |
|---|---|---|
| Dimension | 1 to 4,096 | configured 1024; `ServiceLimits.VectorMaxDimension` |
| Total metadata per vector | 100 KB ENHANCED, 40 KB CLASSIC | metadata policy |
| Filterable metadata per vector | 40 KB ENHANCED, 2 KB CLASSIC | ingestion keeps 2 KB on both modes |
| Filterable string value | 2 KB | `VectorServiceLimits` |
| Metadata keys per vector | 50 | `VectorServiceLimits` |
| Non-filterable keys per index | 10 | 6 declared |
| Vectors per PutVectors / DeleteVectors | 500 | batch size validated |
| Vectors per GetVectors | 100 | batch size validated |
| Top-K per QueryVectors | 10,000 | `vectorLimit` far below |
| Request payload | 20 MiB | `VectorServiceLimits` |
| Put+Delete requests per second per index | 1,000 | ingestion rate limiter |
| Vectors inserted+deleted per second per index | 2,500 | ingestion rate limiter |

The same page also lists full-text search and per-page limits that this project
does not use. Vector buckets created on or after 2026-09-30 default to ENHANCED
indexes; Terraform sets the services to ENHANCED while the code default is
CLASSIC ([infra README, mismatch 5](../infra/terraform/README.md)). That date
was recorded from AWS documentation on 2026-11-21 (Phase 07 report) and **was
not rechecked** here.

### DynamoDB

Sources: [Constraints](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html),
[BatchWriteItem](https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_BatchWriteItem.html),
[Tagging](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Tagging.html), checked 2026-11-25.

| Limit | Value | Where used |
|---|---|---|
| Item size | 400 KB | `DynamoItemLimits`; oversized job results are refused |
| Partition key / sort key | 2048 B / 1024 B | key length checks |
| BatchWriteItem | 25 operations, 16 MB, no per-item condition | batch bound 25; conditional writes are issued per item |
| Table name | 3 to 255 of `A-Za-z0-9_.-` | configuration validation |
| Tag value | up to 256 characters; letters, white space, numbers, `+ - = . _ : /` | A first apply failed on commas ([phase-14 gate](agent-runs/phase-14/human-gate.md)) |

The tagging page as read does not list `@`, which the Phase 14 repair work
order listed as allowed.

### SQS

Sources: [Message quotas](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html),
[ReceiveMessage](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html),
[Visibility timeout](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html), checked 2026-11-25.

| Limit | Value | Where used |
|---|---|---|
| Message size | up to 1,048,576 bytes | `SqsWorkQueue.MaxBodyBytes` |
| Messages per receive | 1 to 10 | worker `batchSize` default 1 |
| Visibility timeout | 0 s to 12 h (default 30 s) | worker default 1800 s |
| Retention | 60 s to 14 days (default 4 days) | queue 4 days, DLQ 14 days |
| In-flight messages, standard queue | about 120,000 | far above worker concurrency |

### Neptune

Sources: [Capacity scaling](https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless-capacity-scaling.html),
[Serverless](https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless.html),
[openCypher compliance](https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html),
[Loader command](https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html),
[Limits](https://docs.aws.amazon.com/neptune/latest/userguide/limits.html), checked 2026-11-25.

| Fact | Value | Effect |
|---|---|---|
| Serverless capacity | minimum 1.0 NCU, maximum 128 NCU | configured 1 to 4 |
| Serverless engine | 1.2.0.1 or later | configured 1.4.0.0 |
| openCypher | no `shortestPath()` or `allShortestPaths()`; `LIMIT`/`SKIP` must be static | bounded variable-length templates |
| Loader queue | up to 64 queued jobs; same-Region S3 only | loader step |
| Connectivity | VPC only; SSL required | private subnets |

### Bedrock embedding model

Source: [Titan Text Embeddings models](https://docs.aws.amazon.com/bedrock/latest/userguide/titan-embedding-models.html), checked 2026-11-25.

| Limit | Value |
|---|---|
| `amazon.titan-embed-text-v2:0` input | up to 8,192 tokens or 50,000 characters |
| Output dimensions | 1,024 (default), 512, 256 |

Account request quotas (requests per minute) were **not checked**. The account
cannot invoke Bedrock at all (section 3). Quotas and limits for the synthesis
model were **not checked**.

### Application Load Balancer

Source: [Quotas](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-limits.html), checked 2026-11-25.

| Limit | Value | Effect |
|---|---|---|
| Condition values per rule | 5 (not adjustable) | listener rules split ([phase-14 repair-03](agent-runs/phase-14/repair-03.md)) |
| Rules per load balancer | 100 (default) | well within |

### ECS on Fargate

Source: [Task definition differences for Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/fargate-tasks-services.html), checked 2026-11-25.
Valid pairs include 512 CPU units with 1 to 4 GB and 1024 with 2 to 8 GB. The
configured sizes are 0.5 vCPU/1 GB and 1 vCPU/2 GB.

## 2. Region availability for us-east-2

| Service or feature | us-east-2 | Basis |
|---|---|---|
| Neptune | listed | [Neptune limits, Regions](https://docs.aws.amazon.com/neptune/latest/userguide/limits.html), checked 2026-11-25 |
| Neptune Serverless | listed | [Serverless constraints](https://docs.aws.amazon.com/neptune/latest/userguide/neptune-serverless.html), checked 2026-11-25 |
| Titan Text Embeddings V2, in-Region | listed | [Model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-amazon-titan-text-embeddings-v2.html), checked 2026-11-25. Listing is not account access |
| S3 Vectors | **documentation row not verified** (the page's table was truncated when read) | Observed instead: vector bucket and 5 indexes applied ([phase-14 gate](agent-runs/phase-14/human-gate.md)). [Regions page](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-regions-quotas.html) |
| EMR release `emr-spark-8.1.0` | **Region availability not verified** | [Release page](https://docs.aws.amazon.com/emr/latest/ReleaseGuide/emr-spark810-release.html), checked 2026-11-25: Spark 4.1.1, Scala 2.13.17, matching the build. No cluster was ever created. Whether the label is accepted for EMR on EC2 clusters is unverified |
| ECS, ECR, DynamoDB, SQS, ALB, KMS, Secrets Manager, CloudWatch, S3 | **not checked in documentation** | Observed: applied in us-east-2 ([phase-14 gate](agent-runs/phase-14/human-gate.md)) |
| Step Functions | **not checked**, not created | only with `enable_emr` |

## 3. Account restrictions observed (not documented quotas)

| Restriction | Evidence |
|---|---|
| Resource creation denied outside the permitted Region by organization policy | [denied-actions.txt](agent-runs/phase-14/logs/denied-actions.txt) |
| Free account plan rejected the Neptune engine and backup retention; upgraded to Paid | [phase-14 gate](agent-runs/phase-14/human-gate.md) |
| Bedrock `authorizationStatus: NOT_AUTHORIZED` for Titan Embed V2, Cohere Embed v4 and Nova Micro in us-east-1, us-east-2 and us-west-2; `InvokeModel` returns `ValidationException: Operation not allowed`; IAM and organization policy simulation says allowed | [bedrock-evidence.txt](agent-runs/phase-15/bedrock-evidence.txt) |

## 4. Not checked

IAM quotas, ECS service quotas and Fargate vCPU quota, CloudWatch Logs limits,
Secrets Manager and KMS deletion windows (the infra README states 7 and 30
days), S3 request-rate limits, and service pricing. Cost figures elsewhere in
the repository are unverified estimates.
