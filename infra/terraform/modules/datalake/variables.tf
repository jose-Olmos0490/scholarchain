variable "name_prefix" {
  type = string
}

variable "account_id" {
  description = "Account ID, used only to make global bucket names unique."
  type        = string
}

variable "region" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "intermediate_expiration_days" {
  description = "Days after which objects under intermediate/ and tmp/ prefixes expire (curated and graph buckets)."
  type        = number
  default     = 30
}

variable "noncurrent_version_expiration_days" {
  type    = number
  default = 30
}

variable "logs_expiration_days" {
  type    = number
  default = 90
}
