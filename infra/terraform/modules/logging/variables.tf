variable "name_prefix" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "retention_days" {
  description = "Log retention. Must be a value CloudWatch Logs accepts (for example 14, 30, 90)."
  type        = number
  default     = 30
}
