# CloudWatch log groups, created before the IAM roles so the roles can be scoped to
# their exact ARNs. All groups are encrypted with the environment key.

locals {
  groups = toset(["query-api", "graph-evidence-api", "embedding-worker", "pipeline", "emr-steps"])
}

resource "aws_cloudwatch_log_group" "this" {
  #checkov:skip=CKV_AWS_338:Accepted trade-off: retention is the retention_days variable (default 30 days) to bound cost
  for_each = local.groups

  name              = "/${replace(var.name_prefix, "-", "/")}/${each.key}"
  retention_in_days = var.retention_days
  kms_key_id        = var.kms_key_arn
}
