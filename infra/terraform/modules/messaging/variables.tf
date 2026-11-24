variable "name_prefix" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "visibility_timeout_seconds" {
  description = "Must exceed the worker's maxIngestRunSeconds (900); the code default consumer visibility timeout is 1800."
  type        = number
  default     = 1800
}

variable "redrive_max_receive_count" {
  description = "SQS-side backstop; must exceed the worker's own maxReceiveCount (5)."
  type        = number
  default     = 10
}
