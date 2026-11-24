# Least-privilege IAM. Runtime roles (the three ECS task roles) and the deployment
# role are separate. Every statement names specific ARNs; the only "*" resources are
# the four actions AWS defines without resource-level support (ecr:GetAuthorization
# Token, ecs:RegisterTaskDefinition, ecs:DescribeTaskDefinition, and the Step
# Functions log-delivery calls), each justified inline.
#
# Neptune runtime access is read-only: ReadDataViaQuery, GetQueryStatus and
# GetEngineStatus. The runtime roles cannot write the graph, start a bulk load or
# cancel a query.

locals {
  tables = var.table_arns

  neptune_resource = "arn:aws:neptune-db:${var.region}:${var.account_id}:${var.neptune_cluster_resource_id}/*"

  # ---- Bedrock model ARNs. An id with a geography prefix is an inference profile
  # (account-scoped ARN) that routes to the underlying foundation model in several Regions.
  profile_pattern = "^(us|eu|apac|jp|au|ca|global|us-gov)\\."

  all_model_ids = distinct(compact([var.embedding_model_id, var.synthesis_model_id, var.verification_model_id]))

  model_arns = {
    for id in local.all_model_ids : id => can(regex(local.profile_pattern, id)) ? [
      "arn:aws:bedrock:${var.region}:${var.account_id}:inference-profile/${id}",
      "arn:aws:bedrock:*::foundation-model/${replace(id, "/^[a-z-]+\\./", "")}",
    ] : ["arn:aws:bedrock:${var.region}::foundation-model/${id}"]
  }

  query_bedrock_arns  = flatten([for id in local.all_model_ids : local.model_arns[id]])
  worker_bedrock_arns = flatten([for id in compact([var.embedding_model_id]) : local.model_arns[id]])

  kms_via_query  = ["dynamodb.${var.region}.amazonaws.com", "s3.${var.region}.amazonaws.com", "secretsmanager.${var.region}.amazonaws.com"]
  kms_via_graph  = ["dynamodb.${var.region}.amazonaws.com", "secretsmanager.${var.region}.amazonaws.com"]
  kms_via_worker = ["dynamodb.${var.region}.amazonaws.com", "s3.${var.region}.amazonaws.com", "sqs.${var.region}.amazonaws.com"]

  task_roles = ["query-api", "graph-evidence-api", "embedding-worker"]
}

# ---------------------------------------------------------------------------
# ECS task execution role (shared by the three services): pull images, write logs.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "PullImages"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = values(var.ecr_repository_arns)
  }

  statement {
    # ecr:GetAuthorizationToken does not support resource-level permissions.
    sid       = "EcrLoginToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "WriteServiceLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [for k in local.task_roles : "${var.log_group_arns[k]}:*"]
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "execution" {
  name   = "pull-images-write-logs"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# ---------------------------------------------------------------------------
# Runtime task role: query-api
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "query_api" {
  statement {
    sid     = "SessionsAuditEvidenceJobsReadWrite"
    actions = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query", "dynamodb:BatchGetItem"]
    resources = compact([
      local.tables["query_session"],
      local.tables["evidence_bundle"],
      lookup(local.tables, "researcher_metric_cache", ""),
    ])
  }

  statement {
    sid       = "GraphReadModelReadOnly"
    actions   = ["dynamodb:GetItem", "dynamodb:Query", "dynamodb:BatchGetItem"]
    resources = [local.tables["graph_read_model"]]
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "NeptuneReadOnly"
      actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:GetEngineStatus"]
      resources = [local.neptune_resource]
    }
  }

  statement {
    sid       = "VectorsReadOnly"
    actions   = ["s3vectors:GetIndex", "s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = values(var.vector_index_arns)
  }

  statement {
    sid       = "RagTextReadOnly"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["rag"]}/*"]
  }

  statement {
    sid       = "RagBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.bucket_arns["rag"]]
  }

  dynamic "statement" {
    for_each = length(local.query_bedrock_arns) > 0 ? [1] : []

    content {
      sid       = "InvokeConfiguredModels"
      actions   = ["bedrock:InvokeModel"]
      resources = local.query_bedrock_arns
    }
  }

  statement {
    sid       = "ReadApiKeyDigests"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.api_keys_secret_arn]
  }

  statement {
    sid       = "UseEnvironmentKeyThroughServices"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = local.kms_via_query
    }
  }
}

# ---------------------------------------------------------------------------
# Runtime task role: graph-evidence-api (read-only everywhere except nothing)
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "graph_evidence_api" {
  statement {
    sid       = "EvidenceAndReadinessReads"
    actions   = ["dynamodb:GetItem", "dynamodb:Query", "dynamodb:BatchGetItem"]
    resources = [local.tables["evidence_bundle"], local.tables["query_session"]]
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "NeptuneReadOnly"
      actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:GetEngineStatus"]
      resources = [local.neptune_resource]
    }
  }

  statement {
    sid       = "ReadApiKeyDigests"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.api_keys_secret_arn]
  }

  statement {
    sid       = "UseEnvironmentKeyThroughServices"
    actions   = ["kms:Decrypt", "kms:DescribeKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = local.kms_via_graph
    }
  }
}

# ---------------------------------------------------------------------------
# Runtime task role: embedding-worker (the only runtime role that writes vectors)
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "embedding_worker" {
  statement {
    sid       = "ConsumeEmbeddingQueue"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"]
    resources = [var.embedding_queue_arn]
  }

  statement {
    sid       = "BuryPoisonMessages"
    actions   = ["sqs:SendMessage"]
    resources = [var.embedding_dlq_arn]
  }

  statement {
    sid       = "LedgerAndArtifactRegistry"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query"]
    resources = [local.tables["run_ledger"], local.tables["artifact_registry"]]
  }

  statement {
    sid       = "RagObjectsReadWriteNoDelete"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${var.bucket_arns["rag"]}/*"]
  }

  statement {
    sid       = "RagBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.bucket_arns["rag"]]
  }

  statement {
    sid       = "WriteVectors"
    actions   = ["s3vectors:GetIndex", "s3vectors:GetVectors", "s3vectors:PutVectors"]
    resources = values(var.vector_index_arns)
  }

  dynamic "statement" {
    for_each = length(local.worker_bedrock_arns) > 0 ? [1] : []

    content {
      sid       = "InvokeEmbeddingModelOnly"
      actions   = ["bedrock:InvokeModel"]
      resources = local.worker_bedrock_arns
    }
  }

  statement {
    sid       = "UseEnvironmentKeyThroughServices"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = local.kms_via_worker
    }
  }
}

locals {
  task_role_documents = {
    "query-api"          = data.aws_iam_policy_document.query_api.json
    "graph-evidence-api" = data.aws_iam_policy_document.graph_evidence_api.json
    "embedding-worker"   = data.aws_iam_policy_document.embedding_worker.json
  }
}

resource "aws_iam_role" "task" {
  for_each = toset(local.task_roles)

  name               = "${var.name_prefix}-task-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "task" {
  for_each = toset(local.task_roles)

  name   = "runtime-access"
  role   = aws_iam_role.task[each.key].id
  policy = local.task_role_documents[each.key]
}

# ---------------------------------------------------------------------------
# Loader role (write path into DynamoDB, S3 and Neptune). Trusted by ECS tasks for
# one-off loader runs. No loader entrypoint exists in the code yet (the loaders are
# library classes in modules/storage), so nothing uses this role until one does.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "loader" {
  statement {
    sid       = "ReadPipelineOutputs"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arns["curated"]}/*", "${var.bucket_arns["graph"]}/*"]
  }

  statement {
    sid       = "WriteLoadOutputsNoDelete"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${var.bucket_arns["graph"]}/*", "${var.bucket_arns["rag"]}/*"]
  }

  statement {
    sid       = "ListBuckets"
    actions   = ["s3:ListBucket"]
    resources = [var.bucket_arns["curated"], var.bucket_arns["graph"], var.bucket_arns["rag"]]
  }

  statement {
    sid       = "WriteTablesCreateOnly"
    actions   = ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:Query", "dynamodb:BatchWriteItem"]
    resources = values(var.table_arns)
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "StartAndPollBulkLoads"
      actions   = ["neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus", "neptune-db:ListLoaderJobs", "neptune-db:GetEngineStatus"]
      resources = [local.neptune_resource]
    }
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "PassTheNeptuneLoaderRoleToNeptuneOnly"
      actions   = ["iam:PassRole"]
      resources = [var.neptune_loader_role_arn]

      condition {
        test     = "StringEquals"
        variable = "iam:PassedToService"
        values   = ["rds.amazonaws.com"]
      }
    }
  }

  statement {
    sid       = "UseEnvironmentKeyThroughServices"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["dynamodb.${var.region}.amazonaws.com", "s3.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "loader" {
  name               = "${var.name_prefix}-loader"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "loader" {
  name   = "load-access"
  role   = aws_iam_role.loader.id
  policy = data.aws_iam_policy_document.loader.json
}

# ---------------------------------------------------------------------------
# EMR: service role and EC2 instance profile
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "emr_service_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:elasticmapreduce:${var.region}:${var.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "emr_service_extra" {
  statement {
    sid       = "PassOnlyTheClusterInstanceRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_instance.arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }

  statement {
    # EBS volume encryption for the cluster nodes: EMR creates the volumes through EC2
    # on behalf of these roles.
    sid       = "UseEnvironmentKeyForEbsVolumes"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey", "kms:CreateGrant"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ec2.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "emr_service" {
  name               = "${var.name_prefix}-emr-service"
  assume_role_policy = data.aws_iam_policy_document.emr_service_trust.json
}

# AWS-maintained policy; it only acts on resources tagged
# for-use-with-amazon-emr-managed-policies=true (the network module tags the subnets and security groups).
resource "aws_iam_role_policy_attachment" "emr_service_managed" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

resource "aws_iam_role_policy" "emr_service_extra" {
  name   = "pass-instance-role"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_extra.json
}

data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "emr_instance" {
  statement {
    sid     = "ReadInputsAndJobArtifacts"
    actions = ["s3:GetObject"]
    resources = [
      "${var.bucket_arns["raw"]}/*",
      "${var.bucket_arns["curated"]}/*",
      "${var.bucket_arns["graph"]}/*",
      "${var.bucket_arns["artifacts"]}/*",
    ]
  }

  statement {
    sid     = "WriteJobOutputs"
    actions = ["s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
    resources = [
      "${var.bucket_arns["curated"]}/*",
      "${var.bucket_arns["graph"]}/*",
      "${var.bucket_arns["logs"]}/emr/*",
    ]
  }

  statement {
    # The EMR file system commits output by copy then delete inside the output buckets.
    sid     = "ReplaceOwnIntermediateOutputs"
    actions = ["s3:DeleteObject"]
    resources = [
      "${var.bucket_arns["curated"]}/*",
      "${var.bucket_arns["graph"]}/*",
    ]
  }

  statement {
    sid     = "ListDataBuckets"
    actions = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [
      var.bucket_arns["raw"],
      var.bucket_arns["curated"],
      var.bucket_arns["graph"],
      var.bucket_arns["artifacts"],
      var.bucket_arns["logs"],
    ]
  }

  statement {
    sid     = "ReadEmrPublicBootstrapAndLibraries"
    actions = ["s3:GetObject", "s3:ListBucket"]
    resources = [
      "arn:aws:s3:::${var.region}.elasticmapreduce",
      "arn:aws:s3:::${var.region}.elasticmapreduce/*",
    ]
  }

  statement {
    sid       = "UseEnvironmentKeyThroughS3"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${var.region}.amazonaws.com"]
    }
  }

  statement {
    # EBS volume encryption for the cluster nodes: EMR creates the volumes through EC2
    # on behalf of these roles.
    sid       = "UseEnvironmentKeyForEbsVolumes"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey", "kms:CreateGrant"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ec2.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "emr_instance" {
  name               = "${var.name_prefix}-emr-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_role_policy" "emr_instance" {
  name   = "job-data-access"
  role   = aws_iam_role.emr_instance.id
  policy = data.aws_iam_policy_document.emr_instance.json
}

resource "aws_iam_instance_profile" "emr" {
  name = "${var.name_prefix}-emr-instance"
  role = aws_iam_role.emr_instance.name
}

# ---------------------------------------------------------------------------
# Step Functions role (bounded pipeline). Created only when EMR is enabled.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "sfn_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "sfn" {
  count = var.emr_enabled ? 1 : 0

  statement {
    sid       = "DriveStepsOnTheScholarChainCluster"
    actions   = ["elasticmapreduce:AddJobFlowSteps", "elasticmapreduce:DescribeStep", "elasticmapreduce:CancelSteps", "elasticmapreduce:DescribeCluster"]
    resources = [var.emr_cluster_arn]
  }

  statement {
    # Needed by the .sync integration; scoped to the one managed rule Step Functions creates.
    sid       = "ManagedCompletionRule"
    actions   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = ["arn:aws:events:${var.region}:${var.account_id}:rule/StepFunctionsGetEventForEMRAddJobFlowStepsRule"]
  }

  statement {
    # Log delivery APIs do not support resource-level permissions.
    sid = "StateMachineLogDelivery"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role" "sfn" {
  count = var.emr_enabled ? 1 : 0

  name               = "${var.name_prefix}-sfn-pipeline"
  assume_role_policy = data.aws_iam_policy_document.sfn_trust.json
}

resource "aws_iam_role_policy" "sfn" {
  count = var.emr_enabled ? 1 : 0

  name   = "drive-emr-steps"
  role   = aws_iam_role.sfn[0].id
  policy = data.aws_iam_policy_document.sfn[0].json
}

# ---------------------------------------------------------------------------
# Application-deployment role (assumed by a human or CI for scripts/deploy.sh).
# It can push images and roll the three ECS services. It cannot change IAM, the
# network, data stores or any Terraform-managed infrastructure.
# ---------------------------------------------------------------------------

locals {
  service_arns = [for s in var.service_names : "arn:aws:ecs:${var.region}:${var.account_id}:service/${var.ecs_cluster_name}/${s}"]
}

data "aws_iam_policy_document" "deployer_trust" {
  count = length(var.deployer_principal_arns) > 0 ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = var.deployer_principal_arns
    }

    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
  }
}

data "aws_iam_policy_document" "deployer" {
  count = length(var.deployer_principal_arns) > 0 ? 1 : 0

  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = values(var.ecr_repository_arns)
  }

  statement {
    # ecr:GetAuthorizationToken does not support resource-level permissions.
    sid       = "EcrLoginToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "RollServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = local.service_arns
  }

  statement {
    # ecs:RegisterTaskDefinition and ecs:DescribeTaskDefinition do not support
    # resource-level permissions. The new revision can only run under the roles
    # allowed by the PassRole statement below.
    sid       = "TaskDefinitionRevisions"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "ObserveRollout"
    actions   = ["ecs:ListTasks", "ecs:DescribeTasks"]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = ["arn:aws:ecs:${var.region}:${var.account_id}:cluster/${var.ecs_cluster_name}"]
    }
  }

  statement {
    sid     = "PassOnlyTheServiceRoles"
    actions = ["iam:PassRole"]
    resources = concat(
      [aws_iam_role.execution.arn],
      [for r in aws_iam_role.task : r.arn],
    )

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "deployer" {
  count = length(var.deployer_principal_arns) > 0 ? 1 : 0

  name                 = "${var.name_prefix}-app-deployer"
  assume_role_policy   = data.aws_iam_policy_document.deployer_trust[0].json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "deployer" {
  count = length(var.deployer_principal_arns) > 0 ? 1 : 0

  name   = "push-images-roll-services"
  role   = aws_iam_role.deployer[0].id
  policy = data.aws_iam_policy_document.deployer[0].json
}
