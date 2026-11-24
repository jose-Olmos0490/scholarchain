# Queues the code uses: the embedding work queue and its dead-letter queue
# (EmbeddingWorkerMain: SCHOLARCHAIN_SVC_EMBEDDING_QUEUE_URL and _DLQ_URL).
# The worker copies a poisoned message to the DLQ itself after maxReceiveCount (5);
# the SQS redrive policy below (10 receives) is the backstop for a worker that dies
# mid-message. The code has no async-job queue: asynchronous query jobs are stored
# in DynamoDB and run in-process, so no further queue is created.

resource "aws_sqs_queue" "embedding_dlq" {
  name                      = "${var.name_prefix}-embedding-work-dlq"
  message_retention_seconds = 1209600 # 14 days
  kms_master_key_id         = var.kms_key_arn
}

resource "aws_sqs_queue" "embedding" {
  name                       = "${var.name_prefix}-embedding-work"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 20
  kms_master_key_id          = var.kms_key_arn

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.embedding_dlq.arn
    maxReceiveCount     = var.redrive_max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "embedding_dlq" {
  queue_url = aws_sqs_queue.embedding_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.embedding.arn]
  })
}

data "aws_iam_policy_document" "tls_only" {
  for_each = {
    embedding     = aws_sqs_queue.embedding.arn
    embedding_dlq = aws_sqs_queue.embedding_dlq.arn
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]

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
}

resource "aws_sqs_queue_policy" "embedding" {
  queue_url = aws_sqs_queue.embedding.id
  policy    = data.aws_iam_policy_document.tls_only["embedding"].json
}

resource "aws_sqs_queue_policy" "embedding_dlq" {
  queue_url = aws_sqs_queue.embedding_dlq.id
  policy    = data.aws_iam_policy_document.tls_only["embedding_dlq"].json
}
