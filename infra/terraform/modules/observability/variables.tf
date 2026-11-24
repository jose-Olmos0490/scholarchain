variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "alarm_topic_arn" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "service_names" {
  description = "Logical service key to ECS service name."
  type        = map(string)
}

variable "alb_arn_suffix" {
  type = string
}

variable "target_group_arn_suffixes" {
  description = "Service key to target group ARN suffix (services behind the ALB)."
  type        = map(string)
}

variable "embedding_queue_name" {
  type = string
}

variable "embedding_dlq_name" {
  type = string
}

variable "table_names" {
  type = map(string)
}

variable "neptune_enabled" {
  type = bool
}

variable "neptune_cluster_identifier" {
  type    = string
  default = ""
}

variable "p95_latency_threshold_seconds" {
  description = "Alarm threshold for the ALB target response time p95."
  type        = number
  default     = 5
}

variable "http_5xx_threshold" {
  description = "Target 5xx responses per 5 minutes that raise the alarm."
  type        = number
  default     = 5
}
