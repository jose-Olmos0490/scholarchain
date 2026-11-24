variable "enabled" {
  description = "Created only when the EMR cluster exists."
  type        = bool
}

variable "name_prefix" {
  type = string
}

variable "role_arn" {
  type = string
}

variable "cluster_id" {
  type = string
}

variable "log_group_arn" {
  type = string
}

variable "step_timeout_seconds" {
  description = "Hard limit for each EMR step."
  type        = number
  default     = 7200
}
