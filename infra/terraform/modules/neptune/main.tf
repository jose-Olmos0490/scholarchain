# Neptune Serverless, behind the enable_neptune toggle. One writer instance, no
# replica (cost). IAM database authentication is on: the runtime roles hold only
# read actions (see modules/iam); there is no password and no public endpoint.

resource "aws_neptune_subnet_group" "this" {
  count = var.enabled ? 1 : 0

  name       = "${var.name_prefix}-neptune"
  subnet_ids = var.data_subnet_ids
}

resource "aws_neptune_cluster_parameter_group" "this" {
  count = var.enabled ? 1 : 0

  name        = "${var.name_prefix}-neptune-cluster"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune cluster parameters"

  parameter {
    name  = "neptune_enable_audit_log"
    value = "1"
  }
}

resource "aws_neptune_parameter_group" "this" {
  count = var.enabled ? 1 : 0

  name        = "${var.name_prefix}-neptune-instance"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune instance parameters"

  parameter {
    name  = "neptune_query_timeout"
    value = tostring(var.query_timeout_ms)
  }
}

# ---- bulk-loader role: assumed by Neptune itself, read-only on the export prefix ----

data "aws_iam_policy_document" "loader_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "loader_permissions" {
  statement {
    sid     = "ReadExportObjects"
    actions = ["s3:GetObject"]
    resources = [
      "${var.graph_bucket_arn}/*",
      "${var.rag_bucket_arn}/${var.neptune_export_prefix}*",
    ]
  }

  statement {
    sid       = "ListExportBuckets"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [var.graph_bucket_arn, var.rag_bucket_arn]
  }

  statement {
    sid       = "DecryptExportObjects"
    actions   = ["kms:Decrypt", "kms:DescribeKey"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role" "loader" {
  count = var.enabled ? 1 : 0

  name               = "${var.name_prefix}-neptune-bulk-loader"
  assume_role_policy = data.aws_iam_policy_document.loader_trust.json
}

resource "aws_iam_role_policy" "loader" {
  count = var.enabled ? 1 : 0

  name   = "read-export-objects"
  role   = aws_iam_role.loader[0].id
  policy = data.aws_iam_policy_document.loader_permissions.json
}

resource "aws_neptune_cluster" "this" {
  count = var.enabled ? 1 : 0

  cluster_identifier                   = "${var.name_prefix}-graph"
  engine                               = "neptune"
  engine_version                       = var.engine_version
  neptune_subnet_group_name            = aws_neptune_subnet_group.this[0].name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this[0].name
  vpc_security_group_ids               = [var.security_group_id]
  storage_encrypted                    = true
  kms_key_arn                          = var.kms_key_arn
  iam_database_authentication_enabled  = true
  iam_roles                            = [aws_iam_role.loader[0].arn]
  backup_retention_period              = var.backup_retention_period
  copy_tags_to_snapshot                = true
  deletion_protection                  = var.deletion_protection
  skip_final_snapshot                  = !var.deletion_protection
  final_snapshot_identifier            = var.deletion_protection ? "${var.name_prefix}-graph-final" : null
  enable_cloudwatch_logs_exports       = ["audit"]
  apply_immediately                    = !var.deletion_protection

  serverless_v2_scaling_configuration {
    min_capacity = var.min_ncu
    max_capacity = var.max_ncu
  }

  depends_on = [aws_iam_role_policy.loader]
}

resource "aws_neptune_cluster_instance" "writer" {
  count = var.enabled ? 1 : 0

  identifier                   = "${var.name_prefix}-graph-writer"
  cluster_identifier           = aws_neptune_cluster.this[0].id
  instance_class               = "db.serverless"
  engine                       = "neptune"
  engine_version               = var.engine_version
  neptune_parameter_group_name = aws_neptune_parameter_group.this[0].name
  neptune_subnet_group_name    = aws_neptune_subnet_group.this[0].name
  publicly_accessible          = false
  auto_minor_version_upgrade   = false
  apply_immediately            = !var.deletion_protection
}
