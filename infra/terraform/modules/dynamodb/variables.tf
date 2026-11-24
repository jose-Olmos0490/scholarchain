variable "table_prefix" {
  description = "Value of SCHOLARCHAIN_DDB_TABLE_PREFIX (code default: scholarchain)."
  type        = string
  default     = "scholarchain"
}

variable "environment" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "deletion_protection" {
  description = "DynamoDB deletion protection. True for every persistent environment."
  type        = bool
}

variable "enable_researcher_metric_cache" {
  description = "Create the optional researcherMetricCache table (the code uses it only when its name is configured)."
  type        = bool
  default     = true
}
