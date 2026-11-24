output "alb_dns_name" {
  description = "Public entry point of the services (HTTPS when certificate_arn is set)."
  value       = module.compute.alb_dns_name
}

output "listener_configured" {
  value = module.compute.listener_configured
}

output "ecs_cluster_name" {
  value = module.compute.cluster_name
}

output "ecs_service_names" {
  value = module.compute.service_names
}

output "ecr_repository_urls" {
  value = module.registry.repository_urls
}

output "bucket_names" {
  value = module.datalake.bucket_names
}

output "vector_bucket_name" {
  value = module.vectors.vector_bucket_name
}

output "dynamodb_table_names" {
  value = module.dynamodb.table_names
}

output "embedding_queue_url" {
  value = module.messaging.embedding_queue_url
}

output "neptune_endpoint" {
  value = module.neptune.endpoint
}

output "neptune_loader_role_arn" {
  value = module.neptune.loader_role_arn
}

output "api_keys_secret_name" {
  description = "Secret the humans must populate after apply (name only; Terraform holds no value)."
  value       = module.security.api_keys_secret_name
}

output "deployer_role_arn" {
  value = module.iam.deployer_role_arn
}

output "pipeline_state_machine_arn" {
  value = module.pipeline.state_machine_arn
}

output "emr_cluster_id" {
  value = module.emr.cluster_id
}

output "alarm_topic_arn" {
  value = module.foundation.alarm_topic_arn
}

output "service_environment_keys" {
  description = "Names (never values) of the environment variables each service receives, for review against the code's configuration contract."
  value       = { for k, v in local.service_environment_raw : k => sort(keys(v)) }
}
