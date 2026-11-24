variable "name_prefix" {
  description = "Resource name prefix, for example scholarchain-dev."
  type        = string
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "region" {
  description = "AWS Region of the key (used for the CloudWatch Logs service principal)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID that owns the key."
  type        = string
}

variable "secret_recovery_window_days" {
  description = "Recovery window for the Secrets Manager secret (7 to 30; 0 is not allowed)."
  type        = number
  default     = 7

  validation {
    condition     = var.secret_recovery_window_days >= 7 && var.secret_recovery_window_days <= 30
    error_message = "secret_recovery_window_days must be 7..30; immediate deletion is not allowed."
  }
}
