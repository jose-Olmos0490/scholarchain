output "enabled" {
  value = var.enabled
}

output "endpoint" {
  description = "Cluster (writer) endpoint host; empty when disabled."
  value       = try(aws_neptune_cluster.this[0].endpoint, "")
}

output "port" {
  value = try(aws_neptune_cluster.this[0].port, 8182)
}

output "cluster_resource_id" {
  description = "Resource ID used in neptune-db IAM resource ARNs; empty when disabled."
  value       = try(aws_neptune_cluster.this[0].cluster_resource_id, "")
}

output "cluster_arn" {
  value = try(aws_neptune_cluster.this[0].arn, "")
}

output "cluster_identifier" {
  value = try(aws_neptune_cluster.this[0].cluster_identifier, "")
}

output "loader_role_arn" {
  description = "Role Neptune assumes during a bulk load (NEPTUNE_LOADER_ROLE_ARN)."
  value       = try(aws_iam_role.loader[0].arn, "")
}
