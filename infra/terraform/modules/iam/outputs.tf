output "execution_role_arn" {
  value = aws_iam_role.execution.arn
}

output "task_role_arns" {
  value = { for k, r in aws_iam_role.task : k => r.arn }
}

output "loader_role_arn" {
  value = aws_iam_role.loader.arn
}

output "emr_service_role_arn" {
  value = aws_iam_role.emr_service.arn
}

output "emr_service_role_name" {
  value = aws_iam_role.emr_service.name
}

output "emr_instance_profile_arn" {
  value = aws_iam_instance_profile.emr.arn
}

output "emr_instance_profile_name" {
  value = aws_iam_instance_profile.emr.name
}

output "sfn_role_arn" {
  value = try(aws_iam_role.sfn[0].arn, "")
}

output "deployer_role_arn" {
  value = try(aws_iam_role.deployer[0].arn, "")
}
