# Seven logical areas. The code (modules/storage ObjectLayout) has four configured
# buckets (raw, curated, graph, rag) and three AREAS that are only key prefixes:
# reports/, logs/ and neptune-export/, all placed in the rag bucket by the services
# (ObjectArea.Rag is the "auxiliary area" bucket). Therefore:
#   raw, curated, graph, rag  -> one bucket each (the four the code names)
#   reports, neptune-export   -> prefixes of the rag bucket (as the code does)
#   artifacts                 -> bucket (EMR job jars; not read by application code)
#   logs                      -> bucket (EMR, ALB and S3 access logs; SSE-S3 because
#                                ALB and S3 log delivery cannot write SSE-KMS objects)

locals {
  suffix = "${var.account_id}-${var.region}"

  buckets = {
    raw       = { encryption = "kms", versioning = true, intermediate = false }
    curated   = { encryption = "kms", versioning = true, intermediate = true }
    graph     = { encryption = "kms", versioning = true, intermediate = true }
    rag       = { encryption = "kms", versioning = true, intermediate = false }
    artifacts = { encryption = "kms", versioning = true, intermediate = false }
    logs      = { encryption = "aes256", versioning = false, intermediate = false }
  }

  bucket_names = { for k, _ in local.buckets : k => "${var.name_prefix}-${k}-${local.suffix}" }
}

resource "aws_s3_bucket" "this" {
  #checkov:skip=CKV2_AWS_62:No consumer of S3 event notifications exists
  #checkov:skip=CKV_AWS_144:Single-Region course environment: no cross-region replication
  #checkov:skip=CKV_AWS_19:Checkov cannot follow for_each keys to the separate SSE configuration; logs bucket uses SSE-S3 resource; the plan JSON shows it on every bucket
  #checkov:skip=CKV_AWS_145:Checkov cannot follow for_each keys to the separate SSE configuration; logs bucket uses SSE-S3 because ALB and S3 log delivery cannot write SSE-KMS resource; the plan JSON shows it on every bucket
  #checkov:skip=CKV_AWS_21:Checkov cannot follow for_each keys to the separate versioning configuration; the logs bucket is intentionally Suspended (expiring log objects) resource; the plan JSON shows it on every bucket
  #checkov:skip=CKV_AWS_18:Checkov cannot follow for_each keys to the separate logging configuration; every bucket except logs logs to the logs bucket, which is the log target resource; the plan JSON shows it on every bucket
  for_each = local.buckets

  bucket        = local.bucket_names[each.key]
  force_destroy = false # never: a bucket holding data cannot be deleted by Terraform
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    bucket_key_enabled = each.value.encryption == "kms"

    apply_server_side_encryption_by_default {
      sse_algorithm     = each.value.encryption == "kms" ? "aws:kms" : "AES256"
      kms_master_key_id = each.value.encryption == "kms" ? var.kms_key_arn : null
    }
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  versioning_configuration {
    status = each.value.versioning ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  #checkov:skip=CKV_AWS_300:The abort-incomplete-multipart rule is a for_each dynamic rule that checkov cannot evaluate; present in the plan JSON for every bucket
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  dynamic "rule" {
    for_each = each.value.versioning ? [1] : []

    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"

      filter {}

      noncurrent_version_expiration {
        noncurrent_days = var.noncurrent_version_expiration_days
      }
    }
  }

  # Intermediate data is recomputable from raw and the run manifests.
  dynamic "rule" {
    for_each = each.value.intermediate ? ["intermediate/", "tmp/"] : []

    content {
      id     = "expire-${trimsuffix(rule.value, "/")}"
      status = "Enabled"

      filter {
        prefix = rule.value
      }

      expiration {
        days = var.intermediate_expiration_days
      }
    }
  }

  dynamic "rule" {
    for_each = each.key == "logs" ? [1] : []

    content {
      id     = "expire-logs"
      status = "Enabled"

      filter {}

      expiration {
        days = var.logs_expiration_days
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# S3 server access logs of every other bucket go to the logs bucket.
resource "aws_s3_bucket_logging" "this" {
  for_each = { for k, v in local.buckets : k => v if k != "logs" }

  bucket        = aws_s3_bucket.this[each.key].id
  target_bucket = aws_s3_bucket.this["logs"].id
  target_prefix = "s3-access/${each.key}/"

  depends_on = [aws_s3_bucket_policy.this]
}

data "aws_elb_service_account" "main" {}

data "aws_iam_policy_document" "bucket" {
  for_each = local.buckets

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.this[each.key].arn,
      "${aws_s3_bucket.this[each.key].arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []

    content {
      sid       = "AlbAccessLogs"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this["logs"].arn}/alb/AWSLogs/${var.account_id}/*"]

      principals {
        type        = "AWS"
        identifiers = [data.aws_elb_service_account.main.arn]
      }
    }
  }

  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []

    content {
      sid       = "S3ServerAccessLogs"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this["logs"].arn}/s3-access/*"]

      principals {
        type        = "Service"
        identifiers = ["logging.s3.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [var.account_id]
      }
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  policy = data.aws_iam_policy_document.bucket[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}
