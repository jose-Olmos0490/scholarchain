variable "name_prefix" {
  type = string
}

variable "kms_key_arn" {
  description = "Key that encrypts the alarm topic."
  type        = string
}

variable "budget_limit_usd" {
  description = "Monthly cost budget in USD. Alerts fire at 50 percent and 80 percent (actual) and 100 percent (forecast)."
  type        = number
  default     = 150
}

variable "budget_alert_emails" {
  description = "E-mail addresses notified by the budget and the alarm topic. Subscriptions need a human confirmation click."
  type        = list(string)
  default     = []
}
