output "embedding_queue_url" {
  value = aws_sqs_queue.embedding.url
}

output "embedding_queue_arn" {
  value = aws_sqs_queue.embedding.arn
}

output "embedding_queue_name" {
  value = aws_sqs_queue.embedding.name
}

output "embedding_dlq_url" {
  value = aws_sqs_queue.embedding_dlq.url
}

output "embedding_dlq_arn" {
  value = aws_sqs_queue.embedding_dlq.arn
}

output "embedding_dlq_name" {
  value = aws_sqs_queue.embedding_dlq.name
}
