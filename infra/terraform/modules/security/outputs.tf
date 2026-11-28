output "kms_key_arn" {
  value = aws_kms_key.main.arn
}

output "kms_key_id" {
  value = aws_kms_key.main.key_id
}

output "api_keys_secret_arn" {
  value = aws_secretsmanager_secret.api_keys.arn
}

output "api_keys_secret_name" {
  value = aws_secretsmanager_secret.api_keys.name
}

output "anthropic_secret_arn" {
  value = aws_secretsmanager_secret.anthropic_api_key.arn
}

output "anthropic_secret_name" {
  value = aws_secretsmanager_secret.anthropic_api_key.name
}

output "voyage_secret_arn" {
  value = aws_secretsmanager_secret.voyage_api_key.arn
}

output "voyage_secret_name" {
  value = aws_secretsmanager_secret.voyage_api_key.name
}
