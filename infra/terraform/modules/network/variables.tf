variable "name_prefix" {
  type = string
}

variable "region" {
  type = string
}

variable "kms_key_arn" {
  description = "Key that encrypts the VPC flow-log group."
  type        = string
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "availability_zones" {
  description = "Exactly two Availability Zones."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Exactly two Availability Zones are required."
  }
}

variable "alb_ingress_cidrs" {
  description = "CIDR blocks allowed to reach the ALB on 80 and 443. Required, no default. 0.0.0.0/0 and ::/0 are rejected."
  type        = list(string)

  validation {
    condition     = length(var.alb_ingress_cidrs) > 0 && alltrue([for c in var.alb_ingress_cidrs : can(cidrnetmask(c)) && !contains(["0.0.0.0/0", "::/0"], c)])
    error_message = "alb_ingress_cidrs must be a non-empty list of valid IPv4 CIDRs and must not contain 0.0.0.0/0 or ::/0."
  }
}

variable "service_ports" {
  description = "Container ports the ALB may reach on the services security group."
  type        = list(number)
  default     = [8080, 8081]
}

variable "neptune_port" {
  type    = number
  default = 8182
}

variable "flow_log_retention_days" {
  type    = number
  default = 30
}
