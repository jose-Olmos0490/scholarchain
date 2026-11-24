variable "enabled" {
  description = "enable_emr toggle. False (the default in every environment) creates no cluster."
  type        = bool
}

variable "name_prefix" {
  type = string
}

variable "release_label" {
  description = "EMR release. The build targets Spark 4.1.1 / Hadoop 3.4.2 / Scala 2.13 (project/Dependencies.scala: emr-spark-8.1.0). UNVERIFIED as an EC2 cluster release label; confirm with list-release-labels before enabling."
  type        = string
  default     = "emr-spark-8.1.0"
}

variable "applications" {
  type    = list(string)
  default = ["Hadoop", "Spark"]
}

variable "subnet_id" {
  description = "Private application subnet for the cluster."
  type        = string
}

variable "master_security_group_id" {
  type = string
}

variable "core_security_group_id" {
  type = string
}

variable "service_security_group_id" {
  type = string
}

variable "service_role_arn" {
  type = string
}

variable "instance_profile_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "log_uri" {
  description = "s3://<logs bucket>/emr/"
  type        = string
}

variable "master_instance_type" {
  type    = string
  default = "m5.xlarge"
}

variable "core_instance_type" {
  type    = string
  default = "m5.xlarge"
}

variable "core_instance_count" {
  description = "Initial core nodes. Managed scaling moves it inside the limits below."
  type        = number
  default     = 2
}

variable "scaling_min_units" {
  description = "Managed-scaling minimum (instances)."
  type        = number
  default     = 2
}

variable "scaling_max_units" {
  description = "Managed-scaling maximum (instances) - the cost ceiling."
  type        = number
  default     = 4
}

variable "scaling_max_core_units" {
  type    = number
  default = 4
}

variable "idle_timeout_seconds" {
  description = "The cluster terminates itself after this long with no running step (60 to 604800)."
  type        = number
  default     = 3600

  validation {
    condition     = var.idle_timeout_seconds >= 60 && var.idle_timeout_seconds <= 604800
    error_message = "idle_timeout_seconds must be 60..604800."
  }
}

variable "job_environment" {
  description = "Non-secret environment variables (the core configuration keys) exported to the Hadoop client and to the Spark application master and executors."
  type        = map(string)
  default     = {}
}
