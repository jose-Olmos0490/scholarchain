# VPC across two AZs. One NAT gateway only (cost-bounded): the three service
# tasks and the EMR cluster reach AWS APIs (Bedrock, S3 Vectors, SQS, Secrets
# Manager, ECR) through it, and the services reach the external model APIs
# (Anthropic, Voyage AI) the same way, on 443 only. A single NAT is a single point of failure for egress
# and for cross-AZ data charges; accepted for the course environment and
# documented in infra/terraform/README.md. S3 and DynamoDB use gateway endpoints
# so their traffic never touches the NAT.

locals {
  # /20 slices of the VPC CIDR: public 0-1, app 2-3, data 4-5.
  public_cidrs = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i)]
  app_cidrs    = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i + 2)]
  data_cidrs   = [for i in range(2) : cidrsubnet(var.vpc_cidr, 4, i + 4)]

  # Tag required by the AWS managed EMR service policy (AmazonEMRServicePolicy_v2).
  emr_managed_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name_prefix}-vpc" }
}

# The default security group is emptied so nothing can use it by accident.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-default-unused" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-igw" }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = local.public_cidrs[count.index]
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-public-${count.index}", Tier = "public-alb-only" }
}

resource "aws_subnet" "app" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = local.app_cidrs[count.index]

  tags = merge({ Name = "${var.name_prefix}-app-${count.index}", Tier = "private-app" }, local.emr_managed_tag)
}

resource "aws_subnet" "data" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = local.data_cidrs[count.index]

  tags = { Name = "${var.name_prefix}-data-${count.index}", Tier = "private-data" }
}

resource "aws_eip" "nat" {
  domain = "vpc"

  tags = { Name = "${var.name_prefix}-nat" }
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id

  tags = { Name = "${var.name_prefix}-nat" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table" "app" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = { Name = "${var.name_prefix}-app" }
}

# Data subnets have no default route: Neptune never needs the internet.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-data" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "app" {
  count          = 2
  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app.id
}

resource "aws_route_table_association" "data" {
  count          = 2
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.app.id, aws_route_table.data.id]

  tags = { Name = "${var.name_prefix}-s3" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.app.id, aws_route_table.data.id]

  tags = { Name = "${var.name_prefix}-dynamodb" }
}

# ---- flow logs -------------------------------------------------------------

resource "aws_cloudwatch_log_group" "flow" {
  #checkov:skip=CKV_AWS_338:Accepted trade-off: retention is the flow_log_retention_days variable (default 30 days) to bound cost
  name              = "/${replace(var.name_prefix, "-", "/")}/vpc-flow"
  retention_in_days = var.flow_log_retention_days
  kms_key_id        = var.kms_key_arn
}

data "aws_iam_policy_document" "flow_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "flow_permissions" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.flow.arn}:*"]
  }
}

resource "aws_iam_role" "flow" {
  name               = "${var.name_prefix}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_trust.json
}

resource "aws_iam_role_policy" "flow" {
  name   = "write-flow-logs"
  role   = aws_iam_role.flow.id
  policy = data.aws_iam_policy_document.flow_permissions.json
}

resource "aws_flow_log" "vpc" {
  vpc_id          = aws_vpc.main.id
  traffic_type    = "ALL"
  log_destination = aws_cloudwatch_log_group.flow.arn
  iam_role_arn    = aws_iam_role.flow.arn
}

# ---- security groups, chained by role: ALB -> services -> Neptune -------------

resource "aws_security_group" "alb" {
  #checkov:skip=CKV2_AWS_5:Attached to the ALB in module compute via a module variable; checkov does not follow module boundaries
  name        = "${var.name_prefix}-alb"
  description = "Application load balancer: 80 and 443 in, service ports out."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_security_group" "services" {
  #checkov:skip=CKV2_AWS_5:Attached to ECS services in module compute via a module variable
  name        = "${var.name_prefix}-services"
  description = "ECS service tasks: ALB in, AWS APIs and Neptune out."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-services" }
}

resource "aws_security_group" "neptune" {
  #checkov:skip=CKV2_AWS_5:Attached to the Neptune cluster in module neptune via a module variable; neptune can be toggled off
  name        = "${var.name_prefix}-neptune"
  description = "Neptune: only the services and loader security groups."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-neptune" }
}

resource "aws_security_group" "loader" {
  #checkov:skip=CKV2_AWS_5:Attached to loader tasks launched at runtime; no Terraform resource consumes it
  name        = "${var.name_prefix}-loader"
  description = "One-off loader tasks that start Neptune bulk loads."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-loader" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(var.alb_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "HTTP (redirect or dev-only forward) from the allowed CIDR"
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each          = toset(var.alb_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the allowed CIDR"
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_services" {
  for_each                     = toset([for p in var.service_ports : tostring(p)])
  security_group_id            = aws_security_group.alb.id
  description                  = "ALB to service port ${each.value}"
  referenced_security_group_id = aws_security_group.services.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "services_from_alb" {
  for_each                     = toset([for p in var.service_ports : tostring(p)])
  security_group_id            = aws_security_group.services.id
  description                  = "Service port ${each.value} from the ALB"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "services_self" {
  for_each                     = toset([for p in var.service_ports : tostring(p)])
  security_group_id            = aws_security_group.services.id
  description                  = "Service-to-service on ${each.value} (Cloud Map discovery)"
  referenced_security_group_id = aws_security_group.services.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "services_https" {
  security_group_id = aws_security_group.services.id
  description       = "HTTPS to AWS APIs through the NAT gateway"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "services_to_neptune" {
  security_group_id            = aws_security_group.services.id
  description                  = "Neptune queries"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "services_self" {
  for_each                     = toset([for p in var.service_ports : tostring(p)])
  security_group_id            = aws_security_group.services.id
  description                  = "Service-to-service on ${each.value}"
  referenced_security_group_id = aws_security_group.services.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_services" {
  security_group_id            = aws_security_group.neptune.id
  description                  = "Neptune from the services"
  referenced_security_group_id = aws_security_group.services.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_loader" {
  security_group_id            = aws_security_group.neptune.id
  description                  = "Neptune from the loader tasks"
  referenced_security_group_id = aws_security_group.loader.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "loader_to_neptune" {
  security_group_id            = aws_security_group.loader.id
  description                  = "Loader to Neptune"
  referenced_security_group_id = aws_security_group.neptune.id
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "loader_https" {
  security_group_id = aws_security_group.loader.id
  description       = "HTTPS to AWS APIs through the NAT gateway"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# ---- EMR: own security groups so the cluster can live in a private subnet -----

resource "aws_security_group" "emr_master" {
  #checkov:skip=CKV2_AWS_5:Attached to the EMR cluster in module emr via a module variable; EMR can be toggled off
  name                   = "${var.name_prefix}-emr-master"
  description            = "EMR primary node"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-master" }, local.emr_managed_tag)
}

resource "aws_security_group" "emr_core" {
  #checkov:skip=CKV2_AWS_5:Attached to the EMR cluster in module emr via a module variable; EMR can be toggled off
  name                   = "${var.name_prefix}-emr-core"
  description            = "EMR core and task nodes"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-core" }, local.emr_managed_tag)
}

resource "aws_security_group" "emr_service" {
  #checkov:skip=CKV2_AWS_5:Attached to the EMR cluster in module emr via a module variable; EMR can be toggled off
  name                   = "${var.name_prefix}-emr-service-access"
  description            = "EMR service access (private subnet)"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-service-access" }, local.emr_managed_tag)
}

locals {
  emr_node_sgs = {
    master = aws_security_group.emr_master.id
    core   = aws_security_group.emr_core.id
  }
}

# All traffic between the cluster nodes (required by Hadoop and Spark).
resource "aws_vpc_security_group_ingress_rule" "emr_nodes" {
  for_each                     = { for pair in setproduct(keys(local.emr_node_sgs), keys(local.emr_node_sgs)) : "${pair[0]}-from-${pair[1]}" => pair }
  security_group_id            = local.emr_node_sgs[each.value[0]]
  description                  = "Cluster-internal traffic"
  referenced_security_group_id = local.emr_node_sgs[each.value[1]]
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "emr_nodes_internal" {
  for_each                     = { for pair in setproduct(keys(local.emr_node_sgs), keys(local.emr_node_sgs)) : "${pair[0]}-to-${pair[1]}" => pair }
  security_group_id            = local.emr_node_sgs[each.value[0]]
  description                  = "Cluster-internal traffic"
  referenced_security_group_id = local.emr_node_sgs[each.value[1]]
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "emr_nodes_https" {
  for_each          = local.emr_node_sgs
  security_group_id = each.value
  description       = "HTTPS to AWS APIs and package mirrors through the NAT gateway"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "emr_master_from_service" {
  security_group_id            = aws_security_group.emr_master.id
  description                  = "EMR service access to the primary node"
  referenced_security_group_id = aws_security_group.emr_service.id
  from_port                    = 8443
  to_port                      = 8443
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "emr_service_from_master" {
  security_group_id            = aws_security_group.emr_service.id
  description                  = "Primary node to EMR service access"
  referenced_security_group_id = aws_security_group.emr_master.id
  from_port                    = 9443
  to_port                      = 9443
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "emr_service_to_nodes" {
  for_each                     = local.emr_node_sgs
  security_group_id            = aws_security_group.emr_service.id
  description                  = "EMR service access to the ${each.key} nodes"
  referenced_security_group_id = each.value
  from_port                    = 8443
  to_port                      = 8443
  ip_protocol                  = "tcp"
}
