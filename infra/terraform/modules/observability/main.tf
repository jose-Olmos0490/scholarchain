# Dashboard and alarms. Every alarm notifies the shared SNS topic. The monthly budget
# alert lives in the foundation module (AWS Budgets, not CloudWatch).

locals {
  alb_dims = { LoadBalancer = var.alb_arn_suffix }

  # one widget row per concern
  ecs_task_metrics = [
    for k, n in var.service_names : ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.cluster_name, "ServiceName", n]
  ]
  ecs_cpu_metrics = [
    for k, n in var.service_names : ["AWS/ECS", "CPUUtilization", "ClusterName", var.cluster_name, "ServiceName", n]
  ]
  p95_metrics = [
    for k, t in var.target_group_arn_suffixes :
    ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", t, { stat = "p95", label = "${k} p95" }]
  ]
  http5xx_metrics = [
    for k, t in var.target_group_arn_suffixes :
    ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", t, { stat = "Sum", label = "${k} 5xx" }]
  ]
  ddb_throttle_metrics = concat([
    for k, n in var.table_names : [
      ["AWS/DynamoDB", "ReadThrottleEvents", "TableName", n, { stat = "Sum", label = "${k} read" }],
      ["AWS/DynamoDB", "WriteThrottleEvents", "TableName", n, { stat = "Sum", label = "${k} write" }],
    ]
  ]...)

  base_widgets = [
    {
      type       = "metric", x = 0, y = 0, width = 12, height = 6
      properties = { title = "ECS running tasks", region = var.region, view = "timeSeries", stat = "Average", period = 60, metrics = local.ecs_task_metrics }
    },
    {
      type       = "metric", x = 12, y = 0, width = 12, height = 6
      properties = { title = "ECS CPU utilization (percent)", region = var.region, view = "timeSeries", stat = "Average", period = 60, metrics = local.ecs_cpu_metrics }
    },
    {
      type       = "metric", x = 0, y = 6, width = 12, height = 6
      properties = { title = "ALB target response time p95 (seconds)", region = var.region, view = "timeSeries", period = 60, metrics = local.p95_metrics }
    },
    {
      type       = "metric", x = 12, y = 6, width = 12, height = 6
      properties = { title = "ALB target 5xx responses", region = var.region, view = "timeSeries", period = 60, metrics = local.http5xx_metrics }
    },
    {
      type = "metric", x = 0, y = 12, width = 12, height = 6
      properties = {
        title = "SQS depth and dead letters", region = var.region, view = "timeSeries", stat = "Maximum", period = 60
        metrics = [
          ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.embedding_queue_name, { label = "embedding visible" }],
          ["AWS/SQS", "ApproximateNumberOfMessagesNotVisible", "QueueName", var.embedding_queue_name, { label = "embedding in flight" }],
          ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.embedding_dlq_name, { label = "DLQ visible" }],
        ]
      }
    },
    {
      type       = "metric", x = 12, y = 12, width = 12, height = 6
      properties = { title = "DynamoDB throttled requests", region = var.region, view = "timeSeries", period = 60, metrics = local.ddb_throttle_metrics }
    },
  ]

  neptune_widgets = var.neptune_enabled ? [
    {
      type = "metric", x = 0, y = 18, width = 24, height = 6
      properties = {
        title = "Neptune serverless capacity and CPU", region = var.region, view = "timeSeries", stat = "Average", period = 60
        metrics = [
          ["AWS/Neptune", "ServerlessDatabaseCapacity", "DBClusterIdentifier", var.neptune_cluster_identifier],
          ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_identifier],
        ]
      }
    },
  ] : []
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = var.name_prefix
  dashboard_body = jsonencode({ widgets = concat(local.base_widgets, local.neptune_widgets) })
}

# ---- alarms -----------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  for_each = var.target_group_arn_suffixes

  alarm_name          = "${var.name_prefix}-${each.key}-5xx"
  alarm_description   = "Target 5xx responses from ${each.key}"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.http_5xx_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }
}

resource "aws_cloudwatch_metric_alarm" "target_p95" {
  for_each = var.target_group_arn_suffixes

  alarm_name          = "${var.name_prefix}-${each.key}-p95-latency"
  alarm_description   = "p95 target response time of ${each.key}"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  threshold           = var.p95_latency_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }
}

resource "aws_cloudwatch_metric_alarm" "no_healthy_targets" {
  for_each = var.target_group_arn_suffixes

  alarm_name          = "${var.name_prefix}-${each.key}-no-healthy-targets"
  alarm_description   = "No healthy target behind ${each.key} (also fires while no image has been deployed yet)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  alarm_name          = "${var.name_prefix}-embedding-dlq-not-empty"
  alarm_description   = "A message reached the embedding dead-letter queue"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  dimensions = {
    QueueName = var.embedding_dlq_name
  }
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_throttles" {
  for_each = var.table_names

  alarm_name          = "${var.name_prefix}-${each.key}-throttles"
  alarm_description   = "Throttled reads or writes on ${each.value}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  metric_query {
    id          = "total"
    expression  = "reads + writes"
    label       = "Throttled requests"
    return_data = true
  }

  metric_query {
    id = "reads"

    metric {
      namespace   = "AWS/DynamoDB"
      metric_name = "ReadThrottleEvents"
      period      = 300
      stat        = "Sum"

      dimensions = {
        TableName = each.value
      }
    }
  }

  metric_query {
    id = "writes"

    metric {
      namespace   = "AWS/DynamoDB"
      metric_name = "WriteThrottleEvents"
      period      = 300
      stat        = "Sum"

      dimensions = {
        TableName = each.value
      }
    }
  }
}
