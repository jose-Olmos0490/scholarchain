variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "git_sha" {
  description = "Git SHA used as the image tag of every service (ECR tags are immutable)."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "app_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  type = string
}

variable "services_security_group_id" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arns" {
  type = map(string)
}

variable "repository_urls" {
  type = map(string)
}

variable "log_group_names" {
  type = map(string)
}

variable "logs_bucket_name" {
  description = "Bucket that receives the ALB access logs under alb/."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Null means no HTTPS listener."
  type        = string
  default     = null
}

variable "allow_plaintext_http" {
  description = "Dev-only escape hatch: when there is no certificate, forward port 80 to the services. API bearer tokens then cross the internet unencrypted. Never true for a persistent environment."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "ALB deletion protection."
  type        = bool
}

variable "namespace_name" {
  description = "Cloud Map private DNS namespace, for example dev.scholarchain.internal."
  type        = string
}

variable "services" {
  description = "Deployable services. alb_paths empty means the service has no load balancer (the embedding worker)."
  type = map(object({
    port                  = number
    cpu                   = number
    memory                = number
    min_capacity          = number
    max_capacity          = number
    initial_desired_count = number
    alb_paths             = list(string)
    alb_priority          = number
    environment           = map(string)
  }))
}

variable "cpu_target_percent" {
  description = "Target-tracking CPU utilization for the API services."
  type        = number
  default     = 60
}

variable "worker_backlog_per_task" {
  description = "Worker scale-out adds two tasks instead of one when more than this many SQS messages are visible."
  type        = number
  default     = 5
}

variable "embedding_queue_name" {
  type = string
}
