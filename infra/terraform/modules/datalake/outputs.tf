output "bucket_names" {
  description = "Map of area to bucket name."
  value       = local.bucket_names
}

output "bucket_arns" {
  description = "Map of area to bucket ARN."
  value       = { for k, b in aws_s3_bucket.this : k => b.arn }
}
