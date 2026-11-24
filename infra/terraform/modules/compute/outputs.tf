output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "service_names" {
  value = { for k, s in aws_ecs_service.this : k => s.name }
}

output "task_definition_families" {
  value = { for k, t in aws_ecs_task_definition.this : k => t.family }
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "alb_arn_suffix" {
  value = aws_lb.this.arn_suffix
}

output "target_group_arn_suffixes" {
  value = { for k, t in aws_lb_target_group.this : k => t.arn_suffix }
}

output "listener_configured" {
  description = "False when neither a certificate nor allow_plaintext_http is set: the ALB then has no listener."
  value       = local.has_listener
}

output "namespace_name" {
  value = aws_service_discovery_private_dns_namespace.this.name
}
