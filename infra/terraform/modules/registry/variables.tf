variable "name_prefix" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "services" {
  description = "Service image names (one repository each)."
  type        = list(string)
  default     = ["query-api", "graph-evidence-api", "embedding-worker"]
}

variable "keep_images" {
  type    = number
  default = 20
}
