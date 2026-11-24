# One bounded EMR cluster for the Hadoop and Spark jobs, behind enable_emr.
#
# Capacity: 1 primary + core nodes only. There is NO task (Spot) instance group.
# Spot task capacity was left out on purpose: a reclaimed Spot node loses shuffle
# output, and the Hadoop and Spark steps here have no checkpoint/restart story beyond
# re-running the whole step. On-demand core nodes only, bounded by managed scaling.
#
# Termination: auto_termination_policy ends the cluster after idle_timeout_seconds
# without a running step. The cluster is kept alive between steps
# (keep_job_flow_alive_when_no_steps) because Step Functions adds steps to it.
#
# Encryption: S3 data via EMRFS SSE-KMS, local disks via EBS encryption with the
# environment key. In-transit: Spark network crypto and IO encryption, Hadoop RPC
# privacy and HDFS data-transfer encryption are enabled below. MapReduce shuffle TLS
# and Tez/Hive TLS need a certificate bundle in S3 and are NOT configured: a known gap,
# mitigated by node security groups that admit only cluster-internal traffic.

locals {
  env_exports = var.job_environment

  configurations = [
    {
      Classification = "hadoop-env"
      Configurations = [{ Classification = "export", Properties = local.env_exports }]
    },
    {
      Classification = "spark-env"
      Configurations = [{ Classification = "export", Properties = local.env_exports }]
    },
    {
      Classification = "spark-defaults"
      Properties = merge(
        {
          "spark.authenticate"           = "true"
          "spark.network.crypto.enabled" = "true"
          "spark.io.encryption.enabled"  = "true"
        },
        { for k, v in local.env_exports : "spark.yarn.appMasterEnv.${k}" => v },
        { for k, v in local.env_exports : "spark.executorEnv.${k}" => v },
      )
    },
    {
      Classification = "core-site"
      Properties     = { "hadoop.rpc.protection" = "privacy" }
    },
    {
      Classification = "hdfs-site"
      Properties     = { "dfs.encrypt.data.transfer" = "true" }
    },
  ]
}

resource "aws_emr_security_configuration" "this" {
  count = var.enabled ? 1 : 0

  name = "${var.name_prefix}-emr"

  configuration = jsonencode({
    EncryptionConfiguration = {
      EnableInTransitEncryption = false
      EnableAtRestEncryption    = true
      AtRestEncryptionConfiguration = {
        S3EncryptionConfiguration = {
          EncryptionMode = "SSE-KMS"
          AwsKmsKey      = var.kms_key_arn
        }
        LocalDiskEncryptionConfiguration = {
          EncryptionKeyProviderType = "AwsKms"
          AwsKmsKey                 = var.kms_key_arn
          EnableEbsEncryption       = true
        }
      }
    }
  })
}

resource "aws_emr_cluster" "this" {
  #checkov:skip=CKV2_AWS_7:EMR security groups admit only cluster-internal traffic; egress is NAT-bound HTTPS (see network module)
  count = var.enabled ? 1 : 0

  name                              = "${var.name_prefix}-pipeline"
  release_label                     = var.release_label
  applications                      = var.applications
  service_role                      = var.service_role_arn
  security_configuration            = aws_emr_security_configuration.this[0].name
  log_uri                           = var.log_uri
  termination_protection            = false
  keep_job_flow_alive_when_no_steps = true
  step_concurrency_level            = 1
  configurations_json               = jsonencode(local.configurations)

  ec2_attributes {
    subnet_id                         = var.subnet_id
    instance_profile                  = var.instance_profile_arn
    emr_managed_master_security_group = var.master_security_group_id
    emr_managed_slave_security_group  = var.core_security_group_id
    service_access_security_group     = var.service_security_group_id
  }

  master_instance_group {
    instance_type  = var.master_instance_type
    instance_count = 1
  }

  core_instance_group {
    instance_type  = var.core_instance_type
    instance_count = var.core_instance_count
  }

  auto_termination_policy {
    idle_timeout = var.idle_timeout_seconds
  }

  lifecycle {
    # Steps are added by Step Functions, not by Terraform.
    ignore_changes = [step]
  }
}

resource "aws_emr_managed_scaling_policy" "this" {
  count = var.enabled ? 1 : 0

  cluster_id = aws_emr_cluster.this[0].id

  compute_limits {
    unit_type                       = "Instances"
    minimum_capacity_units          = var.scaling_min_units
    maximum_capacity_units          = var.scaling_max_units
    maximum_core_capacity_units     = var.scaling_max_core_units
    maximum_ondemand_capacity_units = var.scaling_max_units
  }
}
