output "cluster_id" {
  value = try(aws_emr_cluster.this[0].id, "")
}

output "cluster_arn" {
  description = "Empty when disabled."
  value       = try(aws_emr_cluster.this[0].arn, "")
}
