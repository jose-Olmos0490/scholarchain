output "vector_bucket_name" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "vector_bucket_arn" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_names" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_name }
}

output "index_arns" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}
