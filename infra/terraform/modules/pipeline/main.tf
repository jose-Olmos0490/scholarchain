# Bounded pipeline: Hadoop ingestion step, then Spark graph step, on the existing EMR
# cluster. The state machine is a thin sequencer; scripts/run-bounded-pipeline.sh
# builds the step argument lists (jar paths from the Git SHA, sample caps) and starts
# it. Each step has a hard timeout, no retry (a rerun is a new run), and any failure
# ends the execution in a Fail state.
#
# Entity resolution is NOT a step: the entity-resolution module has no cluster entry
# point (ResolveSample is a local tool). The Spark job reads match decisions from the
# configured matchDecisionsDir, so a human or a later phase must produce them first.

locals {
  hadoop_step = {
    Type     = "Task"
    Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
    Parameters = {
      ClusterId = var.cluster_id
      Step = {
        Name            = "scholarchain-hadoop-ingestion"
        ActionOnFailure = "CANCEL_AND_WAIT"
        HadoopJarStep = {
          Jar      = "command-runner.jar"
          "Args.$" = "$.hadoopStepArgs"
        }
      }
    }
    TimeoutSeconds = var.step_timeout_seconds
    ResultPath     = "$.hadoopResult"
    Next           = "SparkGraph"
    Catch = [{
      ErrorEquals = ["States.ALL"]
      ResultPath  = "$.error"
      Next        = "PipelineFailed"
    }]
  }

  spark_step = {
    Type     = "Task"
    Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
    Parameters = {
      ClusterId = var.cluster_id
      Step = {
        Name            = "scholarchain-spark-graph"
        ActionOnFailure = "CANCEL_AND_WAIT"
        HadoopJarStep = {
          Jar      = "command-runner.jar"
          "Args.$" = "$.sparkStepArgs"
        }
      }
    }
    TimeoutSeconds = var.step_timeout_seconds
    ResultPath     = "$.sparkResult"
    Next           = "PipelineSucceeded"
    Catch = [{
      ErrorEquals = ["States.ALL"]
      ResultPath  = "$.error"
      Next        = "PipelineFailed"
    }]
  }

  definition = {
    Comment = "ScholarChain bounded pipeline (Hadoop then Spark)"
    StartAt = "HadoopIngestion"
    States = {
      HadoopIngestion   = local.hadoop_step
      SparkGraph        = local.spark_step
      PipelineSucceeded = { Type = "Succeed" }
      PipelineFailed = {
        Type  = "Fail"
        Error = "PipelineStepFailed"
        Cause = "An EMR step failed or timed out; see the step logs under the logs bucket emr/ prefix."
      }
    }
  }
}

resource "aws_sfn_state_machine" "pipeline" {
  #checkov:skip=CKV_AWS_284:X-Ray tracing is not used; execution history is logged
  count = var.enabled ? 1 : 0

  name     = "${var.name_prefix}-bounded-pipeline"
  role_arn = var.role_arn
  type     = "STANDARD"

  definition = jsonencode(local.definition)

  logging_configuration {
    log_destination        = "${var.log_group_arn}:*"
    include_execution_data = true # inputs are S3 paths and a Git SHA only
    level                  = "ALL"
  }
}
