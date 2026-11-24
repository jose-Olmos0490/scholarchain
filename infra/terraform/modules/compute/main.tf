# ECS cluster, Fargate task definitions and services, ALB, Cloud Map and auto
# scaling for the three deployable entrypoints found in build.sbt (serviceImages):
#   query-api (8080), graph-evidence-api (8081), embedding-worker (8082, no routes).
#
# Rollout model: Terraform owns the baseline task definition (image tag =
# var.git_sha). scripts/deploy.sh registers a revision with a newer image tag and
# updates the service, so aws_ecs_service ignores task_definition and desired_count.
# Revisions are never deregistered by Terraform (skip_destroy) so a rollback target
# always exists.

locals {
  alb_services = { for k, v in var.services : k => v if length(v.alb_paths) > 0 }
  has_listener = var.certificate_arn != null || var.allow_plaintext_http
}

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]
}

# ---- Cloud Map ------------------------------------------------------------

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = var.namespace_name
  description = "ScholarChain service discovery"
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "this" {
  for_each = local.alb_services

  name = each.key

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  health_check_custom_config {}

  # AWS stores a computed failure_threshold for this block; ignoring it avoids a
  # forced replacement (ResourceInUse while instances are registered).
  lifecycle {
    ignore_changes = [health_check_custom_config]
  }
}

# ---- task definitions -----------------------------------------------------

resource "aws_ecs_task_definition" "this" {
  for_each = var.services

  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arns[each.key]
  skip_destroy             = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # The JVM writes java.io.tmpdir=/tmp; the root file system is read-only.
  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    {
      name                   = each.key
      image                  = "${var.repository_urls[each.key]}:${var.git_sha}"
      essential              = true
      readonlyRootFilesystem = true
      portMappings           = [{ containerPort = each.value.port, protocol = "tcp" }]
      mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
      environment            = [for k in sort(keys(each.value.environment)) : { name = k, value = each.value.environment[k] }]

      # docker/healthcheck.sh calls GET /healthz on HEALTH_PORT (set by the image).
      healthCheck = {
        command     = ["CMD", "/app/bin/healthcheck.sh"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = var.log_group_names[each.key]
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = each.key
        }
      }
    }
  ])
}

# ---- load balancer --------------------------------------------------------

resource "aws_lb" "this" {
  #checkov:skip=CKV2_AWS_20:HTTP to HTTPS redirect listener http_redirect exists whenever a certificate is configured; without one the ALB is dev-only
  #checkov:skip=CKV2_AWS_28:Accepted trade-off: no WAF (cost); ALB ingress is restricted to alb_ingress_cidrs and the app enforces API keys and rate limits
  name                       = "${var.name_prefix}-alb"
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [var.alb_security_group_id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  idle_timeout               = 60

  access_logs {
    bucket  = var.logs_bucket_name
    prefix  = "alb"
    enabled = true
  }
}

resource "aws_lb_target_group" "this" {
  #checkov:skip=CKV_AWS_378:Accepted gap: TLS ends at the ALB; ALB to task is HTTP inside the VPC, security-group chained
  for_each = local.alb_services

  name                 = trimsuffix(substr("${var.name_prefix}-${each.key}", 0, 32), "-")
  port                 = each.value.port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  # /readyz: traffic is sent only to tasks whose own dependencies answer. If a
  # dependency (for example Neptune) is down, every task leaves rotation; that is the
  # intended readiness semantics but also means a dependency outage is visible as 503.
  health_check {
    path                = "/readyz"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":{\"code\":\"NOT_FOUND\"}}"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener" "http_redirect" {
  count = var.certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "http_forward" {
  #checkov:skip=CKV_AWS_103:Dev-only plaintext listener, created only with allow_plaintext_http=true and no certificate; the HTTPS listener uses TLS 1.2+
  count = var.certificate_arn == null && var.allow_plaintext_http ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":{\"code\":\"NOT_FOUND\"}}"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each = local.has_listener ? local.alb_services : {}

  listener_arn = var.certificate_arn != null ? aws_lb_listener.https[0].arn : aws_lb_listener.http_forward[0].arn
  priority     = each.value.alb_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  condition {
    path_pattern {
      values = each.value.alb_paths
    }
  }
}

# ---- services -------------------------------------------------------------

resource "aws_ecs_service" "this" {
  for_each = var.services

  name                               = each.key
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.this[each.key].arn
  desired_count                      = each.value.initial_desired_count
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  propagate_tags                     = "SERVICE"
  health_check_grace_period_seconds  = length(each.value.alb_paths) > 0 && local.has_listener ? 120 : null

  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.services_security_group_id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Without a listener (no certificate and no dev HTTP override) the target group is
  # not attached to the load balancer and ECS would refuse to register against it.
  dynamic "load_balancer" {
    for_each = length(each.value.alb_paths) > 0 && local.has_listener ? [1] : []

    content {
      target_group_arn = aws_lb_target_group.this[each.key].arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }

  dynamic "service_registries" {
    for_each = length(each.value.alb_paths) > 0 ? [1] : []

    content {
      registry_arn = aws_service_discovery_service.this[each.key].arn
    }
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this, aws_lb_listener_rule.this]
}

# ---- auto scaling (bounded) --------------------------------------------------

resource "aws_appautoscaling_target" "this" {
  for_each = var.services

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[each.key].name}"
  min_capacity       = each.value.min_capacity
  max_capacity       = each.value.max_capacity
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each = local.alb_services

  name               = "${var.name_prefix}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this[each.key].service_namespace
  scalable_dimension = aws_appautoscaling_target.this[each.key].scalable_dimension
  resource_id        = aws_appautoscaling_target.this[each.key].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Embedding worker: scale on SQS backlog per running task (metric math, no custom
# publisher). Visible messages divided by running tasks; with zero tasks the divisor
# defaults to 1 so a backlog still triggers scale-out from zero.
resource "aws_appautoscaling_policy" "worker_backlog" {
  name               = "${var.name_prefix}-embedding-worker-backlog"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this["embedding-worker"].service_namespace
  scalable_dimension = aws_appautoscaling_target.this["embedding-worker"].scalable_dimension
  resource_id        = aws_appautoscaling_target.this["embedding-worker"].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.worker_backlog_per_task
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    customized_metric_specification {
      metrics {
        id          = "visible"
        return_data = false

        metric_stat {
          stat = "Average"

          metric {
            namespace   = "AWS/SQS"
            metric_name = "ApproximateNumberOfMessagesVisible"

            dimensions {
              name  = "QueueName"
              value = var.embedding_queue_name
            }
          }
        }
      }

      metrics {
        id          = "running"
        return_data = false

        metric_stat {
          stat = "Average"

          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"

            dimensions {
              name  = "ClusterName"
              value = aws_ecs_cluster.this.name
            }

            dimensions {
              name  = "ServiceName"
              value = "embedding-worker"
            }
          }
        }
      }

      metrics {
        id          = "backlog"
        label       = "BacklogPerTask"
        expression  = "visible / FILL(running, 1)"
        return_data = true
      }
    }
  }
}
