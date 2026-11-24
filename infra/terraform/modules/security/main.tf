# One customer-managed key encrypts every service of the environment (S3, DynamoDB,
# SQS, SNS, Neptune, CloudWatch Logs, ECR, Secrets Manager). One key keeps the cost
# at one key fee and one policy to review; it is a documented trade-off against
# per-service keys (a compromise of the key policy exposes all data classes).

data "aws_iam_policy_document" "key" {
  #checkov:skip=CKV_AWS_109:KMS key policy: the standard account-root statement that enables IAM control of the key
  #checkov:skip=CKV_AWS_111:Same statement; every principal still needs its own IAM grant
  #checkov:skip=CKV_AWS_356:In a KMS key policy resource "*" means this key only
  # Standard "IAM policies govern use of this key" statement. Without it the key
  # would be unmanageable. Every principal still needs its own IAM grant on the key.
  statement {
    sid       = "EnableIamPolicies"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.account_id}:root"]
    }
  }

  statement {
    sid    = "AllowCloudWatchLogs"
    effect = "Allow"
    actions = [
      "kms:Encrypt*",
      "kms:Decrypt*",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*",
    ]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:aws:logs:${var.region}:${var.account_id}:log-group:*"]
    }
  }

  statement {
    sid       = "AllowAlarmAndBudgetPublishersToEncryptSnsMessages"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "budgets.amazonaws.com", "events.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_kms_key" "main" {
  description             = "ScholarChain ${var.environment} data key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.key.json
}

resource "aws_kms_alias" "main" {
  name          = "alias/${var.name_prefix}"
  target_key_id = aws_kms_key.main.key_id
}

# Secret declared by NAME only. Terraform never sees or stores a value: a human
# sets the API-key digest JSON out of band after apply. The services read it by
# name (SCHOLARCHAIN_SVC_KEY_SECRET_NAME).
resource "aws_secretsmanager_secret" "api_keys" {
  #checkov:skip=CKV2_AWS_57:The secret holds API-key digests set and rotated by a human out of band; automatic rotation does not apply
  name                    = "scholarchain/${var.environment}/api-keys"
  description             = "Digests of the API keys accepted by the ScholarChain services. Value set by a human, never by Terraform."
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = var.secret_recovery_window_days
}
