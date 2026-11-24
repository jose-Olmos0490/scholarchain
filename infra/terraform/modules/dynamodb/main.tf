# Every table here is read from the code (modules/storage/dynamo/*.scala and core
# DynamoTablesConfig). All items use string keys `pk` and `sk`; the code uses no
# global or local secondary index, so none is created. Names follow the code
# default <tablePrefix>-<environment>-<table>.
#
# pitr: audit and ledger tables (the audit trail of an answer is not disposable).
# ttl:  only `querySession` carries `expiresAt` (sessions, idempotency markers and
#       jobs expire; AUDIT items in the same table carry no expiresAt).

locals {
  tables = merge(
    {
      run_ledger = {
        suffix = "run-ledger"
        pitr   = true
        ttl    = false
        note   = "run audit ledger"
      }
      entity_link_cache = {
        suffix = "entity-link-cache"
        pitr   = false
        ttl    = false
        note   = "regenerable adjudication cache"
      }
      query_session = {
        suffix = "query-session"
        pitr   = true
        ttl    = true
        note   = "sessions and idempotency markers and jobs and audit items"
      }
      evidence_bundle = {
        suffix = "evidence-bundle"
        pitr   = true
        ttl    = false
        note   = "evidence items and bundles"
      }
      artifact_registry = {
        suffix = "artifact-registry"
        pitr   = true
        ttl    = false
        note   = "artifact version ledger"
      }
      graph_read_model = {
        suffix = "graph-read-model"
        pitr   = false
        ttl    = false
        note   = "key-addressed graph read model: reloadable from Spark outputs"
      }
    },
    var.enable_researcher_metric_cache ? {
      researcher_metric_cache = {
        suffix = "researcher-metric-cache"
        pitr   = false
        ttl    = false
        note   = "optional regenerable metrics cache"
      }
    } : {}
  )
}

resource "aws_dynamodb_table" "this" {
  #checkov:skip=CKV_AWS_28:PITR is enabled on the run-ledger, query-session, evidence-bundle and artifact-registry tables (dynamic value checkov cannot evaluate). Accepted trade-off: entity_link_cache, graph_read_model and researcher_metric_cache have PITR disabled because they are regenerable from pipeline outputs
  for_each = local.tables

  name                        = "${var.table_prefix}-${var.environment}-${each.value.suffix}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  range_key                   = "sk"
  deletion_protection_enabled = var.deletion_protection
  table_class                 = "STANDARD"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }

  point_in_time_recovery {
    enabled = each.value.pitr
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [1] : []

    content {
      attribute_name = "expiresAt"
      enabled        = true
    }
  }

  tags = { Purpose = each.value.note }

  lifecycle {
    # DynamoDB tag values allow only letters, digits, spaces and _ . : / = + - @
    precondition {
      condition     = can(regex("^[A-Za-z0-9 _.:/=+@-]*$", each.value.note))
      error_message = "Table note for ${each.key} becomes a DynamoDB tag value and may contain only letters, digits, spaces and _ . : / = + - @."
    }
  }
}
