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

output "provider_secret_names" {
  description = "Provider API-key secrets the human must populate before deploying (names only; Terraform holds no value)."
  value = {
    anthropic = module.security.anthropic_secret_name
    voyage    = module.security.voyage_secret_name
  }
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

# ---- inputs of experiments/manifests (names and URLs only, no secrets) --------

output "alb_url" {
  description = "SC_BASE_URL. Null when the ALB has no listener. With a certificate that does not cover the ALB DNS name, use the certificate's domain name instead."
  value       = module.compute.listener_configured ? "${var.certificate_arn != null ? "https" : "http"}://${module.compute.alb_dns_name}" : null
}

output "embedding_dlq_url" {
  description = "SC_EMBEDDING_DLQ_URL."
  value       = module.messaging.embedding_dlq_url
}

output "embedding_queue_name" {
  description = "SC_EMBEDDING_QUEUE_NAME."
  value       = module.messaging.embedding_queue_name
}

output "embedding_dlq_name" {
  description = "SC_EMBEDDING_DLQ_NAME."
  value       = module.messaging.embedding_dlq_name
}

output "run_ledger_table_name" {
  description = "SC_DDB_TABLE_RUN_LEDGER."
  value       = module.dynamodb.table_names["run_ledger"]
}

output "alb_arn_suffix" {
  description = "SC_ALB_ARN_SUFFIX."
  value       = module.compute.alb_arn_suffix
}

output "target_group_arn_suffixes" {
  description = "SC_TG_QUERY_API_ARN_SUFFIX is the query-api entry."
  value       = module.compute.target_group_arn_suffixes
}

output "neptune_cluster_identifier" {
  description = "SC_NEPTUNE_CLUSTER_ID."
  value       = module.neptune.cluster_identifier
}

output "log_group_names" {
  description = "SC_QUERY_API_LOG_GROUP and SC_WORKER_LOG_GROUP are the query-api and embedding-worker entries."
  value       = module.logging.log_group_names
}
