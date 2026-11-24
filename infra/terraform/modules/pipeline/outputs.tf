output "state_machine_arn" {
  description = "Empty when disabled."
  value       = try(aws_sfn_state_machine.pipeline[0].arn, "")
}
