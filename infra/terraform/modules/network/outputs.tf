output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  value = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  value = aws_subnet.data[*].id
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "services_security_group_id" {
  value = aws_security_group.services.id
}

output "neptune_security_group_id" {
  value = aws_security_group.neptune.id
}

output "loader_security_group_id" {
  value = aws_security_group.loader.id
}

output "emr_master_security_group_id" {
  value = aws_security_group.emr_master.id
}

output "emr_core_security_group_id" {
  value = aws_security_group.emr_core.id
}

output "emr_service_security_group_id" {
  value = aws_security_group.emr_service.id
}

output "nat_gateway_id" {
  value = aws_nat_gateway.main.id
}
