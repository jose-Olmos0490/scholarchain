# ---------------------------------------------------------------------------
# Identity of the deployment
# ---------------------------------------------------------------------------

variable "aws_region" {
  description = "AWS Region."
  type        = string
  default     = "us-east-1"
}

variable "aws_account_id" {
  description = "AWS account ID. Never hardcoded: supply it with TF_VAR_aws_account_id or -var. The provider refuses any other account (allowed_account_ids)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be a 12-digit AWS account ID."
  }
}

variable "aws_profile" {
  description = "Named AWS CLI profile used by Terraform. Null uses the default credential chain."
  type        = string
  default     = null
}

variable "offline_plan" {
  description = "Plan without AWS credentials (CI or review machines). Disables credential validation and the account check. Never use for apply."
  type        = bool
  default     = false
}

variable "environment" {
  description = "Environment name. dev is the only non-persistent environment; any other name gets deletion protection."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.environment))
    error_message = "environment must be 2-10 lowercase letters and digits, starting with a letter."
  }
}

variable "owner" {
  description = "Value of the owner tag. scripts/plan.sh refuses CHANGE_ME."
  type        = string
  default     = "CHANGE_ME"
}

variable "expiration" {
  description = "Value of the expiration tag (YYYY-MM-DD): the date after which the environment should be destroyed."
  type        = string
  default     = "2027-01-31"

  validation {
    condition     = can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", var.expiration))
    error_message = "expiration must be YYYY-MM-DD."
  }
}

variable "git_sha" {
  description = "Git SHA of the commit being deployed; the image tag of every service."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{7,40}$", var.git_sha))
    error_message = "git_sha must be 7 to 40 lowercase hex characters."
  }
}

# ---------------------------------------------------------------------------
# Cost and safety
# ---------------------------------------------------------------------------

variable "budget_limit_usd" {
  description = "Monthly AWS Budgets limit. Roughly 230 USD per month with Neptune on, 115 without."
  type        = number
  default     = 250
}

variable "budget_alert_emails" {
  description = "E-mail addresses for the budget and alarm notifications."
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "enable_emr" {
  description = "Create the EMR cluster and the Step Functions pipeline. Off by default: an idle cluster costs money."
  type        = bool
  default     = false
}

variable "enable_neptune" {
  description = "Create Neptune Serverless. Off by default: the minimum capacity is billed around the clock. The services are not ready (/readyz fails) without Neptune."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Network and edge
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "alb_ingress_cidrs" {
  description = "CIDR blocks that may reach the ALB on 80 and 443. Required, no default: set to your own public IP /32. 0.0.0.0/0 and ::/0 are rejected."
  type        = list(string)

  validation {
    condition     = length(var.alb_ingress_cidrs) > 0 && alltrue([for c in var.alb_ingress_cidrs : can(cidrnetmask(c)) && !contains(["0.0.0.0/0", "::/0"], c)])
    error_message = "alb_ingress_cidrs must be a non-empty list of valid IPv4 CIDRs and must not contain 0.0.0.0/0 or ::/0."
  }
}

variable "protect_stateful" {
  description = "Deletion protection for stateful resources (Neptune, DynamoDB tables, ALB), independent of the environment name. When true Neptune also takes a final snapshot on delete. Tearing down requires a reviewed apply with protect_stateful=false first."
  type        = bool
  default     = true
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the HTTPS listener. Null: no HTTPS listener."
  type        = string
  default     = null
}

variable "allow_plaintext_http" {
  description = "Dev only. With no certificate, forward HTTP on port 80 to the services (bearer tokens travel unencrypted). Ignored when certificate_arn is set."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Neptune and EMR sizing (small, bounded defaults)
# ---------------------------------------------------------------------------

variable "neptune_min_ncu" {
  type    = number
  default = 1
}

variable "neptune_max_ncu" {
  type    = number
  default = 4
}

variable "neptune_engine_version" {
  type    = string
  default = "1.4.0.0"
}

variable "neptune_parameter_group_family" {
  type    = string
  default = "neptune1.4"
}

variable "emr_release_label" {
  type    = string
  default = "emr-spark-8.1.0"
}

variable "emr_core_instance_count" {
  type    = number
  default = 2
}

variable "emr_scaling_min_units" {
  type    = number
  default = 2
}

variable "emr_scaling_max_units" {
  type    = number
  default = 4
}

variable "emr_idle_timeout_seconds" {
  type    = number
  default = 3600
}

variable "pipeline_environment" {
  description = "Non-secret core configuration keys (graph windows, ER thresholds, bridge and community parameters, Hadoop and Spark settings) exported to the EMR jobs. Their values are application decisions with no default in the code; see terraform.tfvars.example."
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------------
# Services (bounded)
# ---------------------------------------------------------------------------

variable "service_sizing" {
  description = "Fargate size and scaling bounds per service. initial_desired_count 0 keeps services idle until the first image is pushed by scripts/deploy.sh."
  type = map(object({
    cpu                   = number
    memory                = number
    min_capacity          = number
    max_capacity          = number
    initial_desired_count = number
  }))
  default = {
    "query-api" = {
      cpu                   = 1024
      memory                = 2048
      min_capacity          = 1
      max_capacity          = 3
      initial_desired_count = 0
    }
    "graph-evidence-api" = {
      cpu                   = 512
      memory                = 1024
      min_capacity          = 1
      max_capacity          = 2
      initial_desired_count = 0
    }
    "embedding-worker" = {
      cpu                   = 512
      memory                = 1024
      min_capacity          = 0
      max_capacity          = 2
      initial_desired_count = 0
    }
  }
}

variable "deployer_principal_arns" {
  description = "IAM principals (for example the architect's SSO role) that may assume the application-deployment role. Empty creates no role."
  type        = list(string)
  default     = []
}

# ---------------------------------------------------------------------------
# Application configuration that the code requires but does not default
# ---------------------------------------------------------------------------

variable "embedding_model_id" {
  description = "EMBEDDING_MODEL_ID. The Bedrock embedder sends {inputText, dimensions, normalize}, the Titan Text Embeddings V2 request shape."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "embedding_model_version" {
  description = "EMBEDDING_MODEL_VERSION: free-form label stored with every vector."
  type        = string
  default     = "titan-embed-text-v2"
}

variable "synthesis_model_id" {
  description = "SYNTHESIS_MODEL_ID (Bedrock model or inference profile ID). Empty: the variable is not set and the query API refuses to start."
  type        = string
  default     = ""
}

variable "verification_model_id" {
  description = "VERIFICATION_MODEL_ID. Empty: the variable is not set and the query API refuses to start."
  type        = string
  default     = ""
}

variable "vector_dimension" {
  type    = number
  default = 1024
}

variable "vector_distance_metric" {
  type    = string
  default = "cosine"
}

variable "serving_run_id" {
  description = "SCHOLARCHAIN_SVC_RUN_ID: the pipeline run the query API serves. Empty until a run exists."
  type        = string
  default     = ""
}

variable "serving_snapshot" {
  description = "SCHOLARCHAIN_SVC_SNAPSHOT: the dataset snapshot label the query API serves."
  type        = string
  default     = ""
}

variable "runtime_settings" {
  description = "Required runtime keys without a code default. Values here are ASSUMPTIONS for review by the rag-engineer and service-engineer (graph limit 25, path depth 4 and vector limit 8 mirror the test fixtures)."
  type = object({
    graph_limit                = number
    vector_limit               = number
    max_path_depth             = number
    context_token_budget       = number
    fusion_policy_version      = string
    synchronous_deadline_ms    = number
    max_workflow_retries       = number
    max_concurrent_llm_calls   = number
    neptune_query_timeout_ms   = number
    query_session_ttl_days     = number
    embedding_normalize        = bool
    ingest_rate_limit_per_sec  = number
    ingest_max_topic_ids       = number
    ingest_max_institution_ids = number
    ingest_ledger_sequence     = number
  })
  default = {
    graph_limit                = 25
    vector_limit               = 8
    max_path_depth             = 4
    context_token_budget       = 6000
    fusion_policy_version      = "fusion-policy-v1"
    synchronous_deadline_ms    = 20000
    max_workflow_retries       = 2
    max_concurrent_llm_calls   = 5
    neptune_query_timeout_ms   = 20000
    query_session_ttl_days     = 7
    embedding_normalize        = true
    ingest_rate_limit_per_sec  = 5
    ingest_max_topic_ids       = 20
    ingest_max_institution_ids = 20
    ingest_ledger_sequence     = 0
  }
}

variable "worker_backlog_per_task" {
  type    = number
  default = 5
}
