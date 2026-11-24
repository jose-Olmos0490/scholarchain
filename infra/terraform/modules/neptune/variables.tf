variable "enabled" {
  description = "enable_neptune toggle. False creates nothing."
  type        = bool
}

variable "name_prefix" {
  type = string
}

variable "data_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "graph_bucket_arn" {
  description = "Bucket the Neptune loader role may read."
  type        = string
}

variable "rag_bucket_arn" {
  description = "Bucket that holds the neptune-export/ prefix when auxiliary areas live in the rag bucket (as the services configure them)."
  type        = string
}

variable "neptune_export_prefix" {
  description = "Value of SCHOLARCHAIN_S3_PREFIX_NEPTUNE_EXPORT (relative, ends with /)."
  type        = string
  default     = "neptune-export/"
}

variable "min_ncu" {
  description = "Minimum Neptune Serverless capacity units. Neptune does not pause: this is billed around the clock."
  type        = number
  default     = 1
}

variable "max_ncu" {
  description = "Maximum Neptune Serverless capacity units (the cost ceiling)."
  type        = number
  default     = 4
}

variable "engine_version" {
  description = "UNVERIFIED default: confirm with describe-db-engine-versions before the first apply."
  type        = string
  default     = "1.4.0.0"
}

variable "parameter_group_family" {
  description = "Must match engine_version. UNVERIFIED default."
  type        = string
  default     = "neptune1.4"
}

variable "backup_retention_days" {
  type    = number
  default = 7
}

variable "deletion_protection" {
  description = "True for every persistent environment, false for dev."
  type        = bool
}

variable "query_timeout_ms" {
  description = "Server-side query timeout; keep at or above NEPTUNE_QUERY_TIMEOUT_MS."
  type        = number
  default     = 20000
}
