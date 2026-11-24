variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "bucket_arns" {
  description = "Map of data-lake area to bucket ARN (raw, curated, graph, rag, artifacts, logs)."
  type        = map(string)
}

variable "table_arns" {
  description = "Map of logical DynamoDB table key to ARN."
  type        = map(string)
}

variable "vector_bucket_arn" {
  type = string
}

variable "vector_index_arns" {
  type = map(string)
}

variable "embedding_queue_arn" {
  type = string
}

variable "embedding_dlq_arn" {
  type = string
}

variable "api_keys_secret_arn" {
  type = string
}

variable "log_group_arns" {
  type = map(string)
}

variable "ecr_repository_arns" {
  type = map(string)
}

variable "ecs_cluster_name" {
  description = "Name of the ECS cluster (the compute module builds the same name)."
  type        = string
}

variable "service_names" {
  description = "ECS service names the deployment role may update."
  type        = list(string)
}

variable "neptune_enabled" {
  type = bool
}

variable "neptune_cluster_resource_id" {
  description = "Neptune cluster resource ID; ignored when neptune_enabled is false."
  type        = string
  default     = ""
}

variable "neptune_loader_role_arn" {
  type    = string
  default = ""
}

variable "embedding_model_id" {
  type = string
}

variable "synthesis_model_id" {
  type    = string
  default = ""
}

variable "verification_model_id" {
  type    = string
  default = ""
}

variable "emr_enabled" {
  type = bool
}

variable "emr_cluster_arn" {
  description = "ARN of the EMR cluster the Step Functions role may drive; ignored when emr_enabled is false."
  type        = string
  default     = ""
}

variable "deployer_principal_arns" {
  description = "IAM principals allowed to assume the application-deployment role. Empty creates no deployment role."
  type        = list(string)
  default     = []
}
