locals {
  name_prefix         = "scholarchain-${var.environment}"
  deletion_protection = var.protect_stateful
  azs                 = ["${var.aws_region}a", "${var.aws_region}b"]
  s3_prefixes = {
    reports        = "reports/"
    logs           = "logs/"
    neptune_export = "neptune-export/"
  }

  # With Voyage the code requires EMBEDDING_MODEL_ID to equal the Voyage model ID, and
  # both values are recorded as vector provenance: a Bedrock value must never leak through.
  active_embedding_model_id      = var.embedding_provider == "voyage" ? var.voyage_model_id : var.embedding_model_id
  active_embedding_model_version = var.embedding_provider == "voyage" ? var.voyage_model_version : var.embedding_model_version
}

# ---------------------------------------------------------------------------
# Foundation: key, secret names, alarm topic, budget
# ---------------------------------------------------------------------------

module "security" {
  source = "../../modules/security"

  name_prefix = local.name_prefix
  environment = var.environment
  region      = var.aws_region
  account_id  = var.aws_account_id
}

module "foundation" {
  source = "../../modules/foundation"

  name_prefix         = local.name_prefix
  kms_key_arn         = module.security.kms_key_arn
  budget_limit_usd    = var.budget_limit_usd
  budget_alert_emails = var.budget_alert_emails
}

module "network" {
  source = "../../modules/network"

  name_prefix        = local.name_prefix
  region             = var.aws_region
  kms_key_arn        = module.security.kms_key_arn
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.azs
  alb_ingress_cidrs  = var.alb_ingress_cidrs
}

module "datalake" {
  source = "../../modules/datalake"

  name_prefix = local.name_prefix
  account_id  = var.aws_account_id
  region      = var.aws_region
  kms_key_arn = module.security.kms_key_arn
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  environment         = var.environment
  kms_key_arn         = module.security.kms_key_arn
  deletion_protection = local.deletion_protection
}

module "vectors" {
  source = "../../modules/vectors"

  vector_bucket_name = "${local.name_prefix}-vectors-${var.aws_account_id}"
  dimension          = var.vector_dimension
  distance_metric    = var.vector_distance_metric
}

module "messaging" {
  source = "../../modules/messaging"

  name_prefix = local.name_prefix
  kms_key_arn = module.security.kms_key_arn
}

module "logging" {
  source = "../../modules/logging"

  name_prefix    = local.name_prefix
  kms_key_arn    = module.security.kms_key_arn
  retention_days = var.log_retention_days
}

module "neptune" {
  source = "../../modules/neptune"

  enabled                = var.enable_neptune
  name_prefix            = local.name_prefix
  data_subnet_ids        = module.network.data_subnet_ids
  security_group_id      = module.network.neptune_security_group_id
  kms_key_arn            = module.security.kms_key_arn
  graph_bucket_arn       = module.datalake.bucket_arns["graph"]
  rag_bucket_arn         = module.datalake.bucket_arns["rag"]
  neptune_export_prefix  = local.s3_prefixes.neptune_export
  min_ncu                = var.neptune_min_ncu
  max_ncu                = var.neptune_max_ncu
  engine_version         = var.neptune_engine_version
  parameter_group_family = var.neptune_parameter_group_family
  deletion_protection    = local.deletion_protection
  query_timeout_ms       = var.runtime_settings.neptune_query_timeout_ms
}

module "registry" {
  source = "../../modules/registry"

  name_prefix = local.name_prefix
  kms_key_arn = module.security.kms_key_arn
}

module "iam" {
  source = "../../modules/iam"

  name_prefix = local.name_prefix
  region      = var.aws_region
  account_id  = var.aws_account_id
  kms_key_arn = module.security.kms_key_arn

  bucket_arns         = module.datalake.bucket_arns
  table_arns          = module.dynamodb.table_arns
  vector_bucket_arn   = module.vectors.vector_bucket_arn
  vector_index_arns   = module.vectors.index_arns
  embedding_queue_arn = module.messaging.embedding_queue_arn
  embedding_dlq_arn   = module.messaging.embedding_dlq_arn
  api_keys_secret_arn = module.security.api_keys_secret_arn
  log_group_arns      = module.logging.log_group_arns
  ecr_repository_arns = module.registry.repository_arns

  ecs_cluster_name = local.name_prefix
  service_names    = keys(var.service_sizing)

  neptune_enabled             = var.enable_neptune
  neptune_cluster_resource_id = module.neptune.cluster_resource_id
  neptune_loader_role_arn     = module.neptune.loader_role_arn

  embedding_model_id    = var.embedding_model_id
  synthesis_model_id    = var.synthesis_model_id
  verification_model_id = var.verification_model_id

  llm_provider         = var.llm_provider
  embedding_provider   = var.embedding_provider
  anthropic_secret_arn = module.security.anthropic_secret_arn
  voyage_secret_arn    = module.security.voyage_secret_arn

  emr_enabled     = var.enable_emr
  emr_cluster_arn = module.emr.cluster_arn

  deployer_principal_arns = var.deployer_principal_arns
}

# ---------------------------------------------------------------------------
# Application environment, derived from the code's configuration contract
# ---------------------------------------------------------------------------

locals {
  neptune_endpoint = var.enable_neptune ? module.neptune.endpoint : "neptune-disabled.invalid"

  vector_index_env = {
    for logical, env_name in {
      researchers       = "RESEARCHERS"
      communities       = "COMMUNITIES"
      abstracts         = "ABSTRACTS"
      paths             = "PATHS"
      topicInstitutions = "TOPIC_INSTITUTIONS"
    } : "SCHOLARCHAIN_VECTOR_INDEX_${env_name}_TYPE" => "ENHANCED"
  }

  # Names of the five indexes (VECTOR_INDEX_*), plus dimension, metric and mode per index.
  vector_env = merge(
    {
      VECTOR_INDEX_RESEARCHERS        = module.vectors.index_names["researchers"]
      VECTOR_INDEX_COMMUNITIES        = module.vectors.index_names["communities"]
      VECTOR_INDEX_ABSTRACTS          = module.vectors.index_names["abstracts"]
      VECTOR_INDEX_PATHS              = module.vectors.index_names["paths"]
      VECTOR_INDEX_TOPIC_INSTITUTIONS = module.vectors.index_names["topicInstitutions"]
    },
    # The deployed indexes are ENHANCED (see modules/vectors); the code default is CLASSIC.
    local.vector_index_env,
    merge([
      for env_name in ["RESEARCHERS", "COMMUNITIES", "ABSTRACTS", "PATHS", "TOPIC_INSTITUTIONS"] : {
        "SCHOLARCHAIN_VECTOR_INDEX_${env_name}_DIMENSION"       = tostring(var.vector_dimension)
        "SCHOLARCHAIN_VECTOR_INDEX_${env_name}_DISTANCE_METRIC" = var.vector_distance_metric
      }
    ]...),
  )

  table_env = merge(
    {
      SCHOLARCHAIN_DDB_TABLE_PREFIX            = "scholarchain"
      SCHOLARCHAIN_DDB_TABLE_RUN_LEDGER        = module.dynamodb.table_names["run_ledger"]
      SCHOLARCHAIN_DDB_TABLE_ENTITY_LINK_CACHE = module.dynamodb.table_names["entity_link_cache"]
      SCHOLARCHAIN_DDB_TABLE_QUERY_SESSION     = module.dynamodb.table_names["query_session"]
      SCHOLARCHAIN_DDB_TABLE_EVIDENCE_BUNDLE   = module.dynamodb.table_names["evidence_bundle"]
      SCHOLARCHAIN_DDB_TABLE_ARTIFACT_REGISTRY = module.dynamodb.table_names["artifact_registry"]
    },
    # graphReadModel has no environment variable: the code derives <prefix>-<env>-graph-read-model,
    # which is exactly the table name created by modules/dynamodb.
    contains(keys(module.dynamodb.table_names), "researcher_metric_cache") ? {
      SCHOLARCHAIN_DDB_TABLE_RESEARCHER_METRIC_CACHE = module.dynamodb.table_names["researcher_metric_cache"]
    } : {},
  )

  common_env = merge(
    {
      SCHOLARCHAIN_ENV            = var.environment
      AWS_REGION                  = var.aws_region
      SCHOLARCHAIN_AWS_ACCOUNT_ID = var.aws_account_id

      RAW_BUCKET     = module.datalake.bucket_names["raw"]
      CURATED_BUCKET = module.datalake.bucket_names["curated"]
      GRAPH_BUCKET   = module.datalake.bucket_names["graph"]
      RAG_BUCKET     = module.datalake.bucket_names["rag"]
      VECTOR_BUCKET  = module.vectors.vector_bucket_name

      NEPTUNE_ENDPOINT         = local.neptune_endpoint
      NEPTUNE_PORT             = tostring(module.neptune.port)
      NEPTUNE_QUERY_TIMEOUT_MS = tostring(var.runtime_settings.neptune_query_timeout_ms)

      SCHOLARCHAIN_S3_PREFIX_REPORTS        = local.s3_prefixes.reports
      SCHOLARCHAIN_S3_PREFIX_LOGS           = local.s3_prefixes.logs
      SCHOLARCHAIN_S3_PREFIX_NEPTUNE_EXPORT = local.s3_prefixes.neptune_export

      SCHOLARCHAIN_QUERY_SESSION_TTL_DAYS = tostring(var.runtime_settings.query_session_ttl_days)

      SCHOLARCHAIN_RETRIEVAL_GRAPH_LIMIT    = tostring(var.runtime_settings.graph_limit)
      SCHOLARCHAIN_RETRIEVAL_MAX_PATH_DEPTH = tostring(var.runtime_settings.max_path_depth)
      EMBEDDING_MODEL_ID                    = local.active_embedding_model_id
    },
    local.vector_env,
    local.table_env,
  )

  api_env = {
    SCHOLARCHAIN_RETRIEVAL_VECTOR_LIMIT          = tostring(var.runtime_settings.vector_limit)
    SCHOLARCHAIN_RETRIEVAL_CONTEXT_TOKEN_BUDGET  = tostring(var.runtime_settings.context_token_budget)
    SCHOLARCHAIN_RETRIEVAL_FUSION_POLICY_VERSION = var.runtime_settings.fusion_policy_version
    SCHOLARCHAIN_OPS_SYNCHRONOUS_DEADLINE_MS     = tostring(var.runtime_settings.synchronous_deadline_ms)
    SCHOLARCHAIN_OPS_MAX_WORKFLOW_RETRIES        = tostring(var.runtime_settings.max_workflow_retries)
    SCHOLARCHAIN_OPS_MAX_CONCURRENT_LLM_CALLS    = tostring(var.runtime_settings.max_concurrent_llm_calls)

    # The secret is referenced by NAME; the value is read by the task role at run time.
    SCHOLARCHAIN_SVC_KEY_SECRET_NAME = module.security.api_keys_secret_name
    # One ALB hop appends X-Forwarded-For (api-contract.md, failed-authentication limits).
    SCHOLARCHAIN_SVC_TRUSTED_PROXY_HOPS = "1"
  }

  # Model providers. Secrets are referenced by NAME; the task role reads the value.
  llm_env = merge(
    { SCHOLARCHAIN_LLM_PROVIDER = var.llm_provider },
    {
      for k, v in {
        SCHOLARCHAIN_ANTHROPIC_MODEL_ID            = var.anthropic_model_id
        SCHOLARCHAIN_ANTHROPIC_BASE_URL            = var.anthropic_base_url
        SCHOLARCHAIN_ANTHROPIC_VERSION             = var.anthropic_api_version
        SCHOLARCHAIN_ANTHROPIC_CONNECT_TIMEOUT_MS  = tostring(var.anthropic_settings.connect_timeout_ms)
        SCHOLARCHAIN_ANTHROPIC_REQUEST_TIMEOUT_MS  = tostring(var.anthropic_settings.request_timeout_ms)
        SCHOLARCHAIN_ANTHROPIC_MAX_RETRIES         = tostring(var.anthropic_settings.max_retries)
        SCHOLARCHAIN_SVC_ANTHROPIC_KEY_SECRET_NAME = module.security.anthropic_secret_name
      } : k => v if var.llm_provider == "anthropic"
    },
  )

  # The query API and the embedding worker both embed; deadlines differ by use.
  embedding_use = {
    "query-api" = {
      request_timeout_ms = var.voyage_settings.query_request_timeout_ms
      max_retries        = var.voyage_settings.query_max_retries
    }
    "embedding-worker" = {
      request_timeout_ms = var.voyage_settings.ingest_request_timeout_ms
      max_retries        = var.voyage_settings.ingest_max_retries
    }
  }

  embedding_env = {
    for service, use in local.embedding_use : service => merge(
      {
        SCHOLARCHAIN_EMBEDDING_PROVIDER  = var.embedding_provider
        EMBEDDING_MODEL_VERSION          = local.active_embedding_model_version
        SCHOLARCHAIN_EMBEDDING_NORMALIZE = tostring(var.runtime_settings.embedding_normalize)
      },
      {
        for k, v in {
          SCHOLARCHAIN_VOYAGE_MODEL_ID = var.voyage_model_id
          SCHOLARCHAIN_VOYAGE_BASE_URL = var.voyage_base_url
          # One source for the dimension: it must equal every vector index dimension.
          SCHOLARCHAIN_VOYAGE_OUTPUT_DIMENSION    = tostring(var.vector_dimension)
          SCHOLARCHAIN_VOYAGE_BATCH_SIZE          = tostring(var.voyage_settings.batch_size)
          SCHOLARCHAIN_VOYAGE_CONNECT_TIMEOUT_MS  = tostring(var.voyage_settings.connect_timeout_ms)
          SCHOLARCHAIN_VOYAGE_REQUEST_TIMEOUT_MS  = tostring(use.request_timeout_ms)
          SCHOLARCHAIN_VOYAGE_MAX_RETRIES         = tostring(use.max_retries)
          SCHOLARCHAIN_SVC_VOYAGE_KEY_SECRET_NAME = module.security.voyage_secret_name
        } : k => v if var.embedding_provider == "voyage"
      },
    )
  }

  # Values the operator has not set are left out, so the application reports the missing
  # key by name instead of failing on an empty string. Built from variables only, so the
  # key set is known at plan time.
  optional_query_env = {
    for k, v in {
      SYNTHESIS_MODEL_ID        = var.llm_provider == "bedrock" ? var.synthesis_model_id : ""
      VERIFICATION_MODEL_ID     = var.llm_provider == "bedrock" ? var.verification_model_id : ""
      SCHOLARCHAIN_SVC_RUN_ID   = var.serving_run_id
      SCHOLARCHAIN_SVC_SNAPSHOT = var.serving_snapshot
    } : k => v if v != ""
  }

  service_environment_raw = {
    "query-api" = merge(local.common_env, local.api_env, local.optional_query_env, local.llm_env, local.embedding_env["query-api"], {
      SCHOLARCHAIN_RAG_LLM_MAX_RETRIES = tostring(var.runtime_settings.llm_max_retries)
      # dockerStageContexts copies prompts/ to the image payload (payload/ -> /app/).
      SCHOLARCHAIN_SVC_PROMPTS_DIR = "/app/prompts"
    })

    "graph-evidence-api" = merge(local.common_env, local.api_env)

    "embedding-worker" = merge(local.common_env, local.embedding_env["embedding-worker"], {
      SCHOLARCHAIN_SVC_EMBEDDING_QUEUE_URL = module.messaging.embedding_queue_url
      SCHOLARCHAIN_SVC_EMBEDDING_DLQ_URL   = module.messaging.embedding_dlq_url

      SCHOLARCHAIN_VECTOR_INGEST_RATE_LIMIT_PER_SECOND = tostring(var.runtime_settings.ingest_rate_limit_per_sec)
      SCHOLARCHAIN_VECTOR_INGEST_MAX_TOPIC_IDS         = tostring(var.runtime_settings.ingest_max_topic_ids)
      SCHOLARCHAIN_VECTOR_INGEST_MAX_INSTITUTION_IDS   = tostring(var.runtime_settings.ingest_max_institution_ids)
      SCHOLARCHAIN_VECTOR_INGEST_LEDGER_SEQUENCE       = tostring(var.runtime_settings.ingest_ledger_sequence)
    })
  }

  service_routes = {
    "query-api" = {
      port         = 8080
      alb_paths    = ["/v1/query", "/v1/jobs", "/v1/jobs/*", "/v1/explain/*", "/healthz", "/readyz"]
      alb_priority = 10
    }
    "graph-evidence-api" = {
      port         = 8081
      alb_paths    = ["/v1/graph/*", "/v1/evidence/*"]
      alb_priority = 20
    }
    "embedding-worker" = {
      port         = 8082
      alb_paths    = []
      alb_priority = 0
    }
  }

  services = {
    for k, v in local.service_routes : k => merge(v, var.service_sizing[k], {
      environment = local.service_environment_raw[k]
    })
  }
}

module "compute" {
  source = "../../modules/compute"

  name_prefix = local.name_prefix
  region      = var.aws_region
  git_sha     = var.git_sha

  vpc_id                     = module.network.vpc_id
  public_subnet_ids          = module.network.public_subnet_ids
  app_subnet_ids             = module.network.app_subnet_ids
  alb_security_group_id      = module.network.alb_security_group_id
  services_security_group_id = module.network.services_security_group_id

  execution_role_arn = module.iam.execution_role_arn
  task_role_arns     = module.iam.task_role_arns
  repository_urls    = module.registry.repository_urls
  log_group_names    = module.logging.log_group_names
  logs_bucket_name   = module.datalake.bucket_names["logs"]

  certificate_arn      = var.certificate_arn
  allow_plaintext_http = var.allow_plaintext_http
  deletion_protection  = local.deletion_protection

  namespace_name          = "${var.environment}.scholarchain.internal"
  services                = local.services
  worker_backlog_per_task = var.worker_backlog_per_task
  embedding_queue_name    = module.messaging.embedding_queue_name
}

module "emr" {
  source = "../../modules/emr"

  enabled                   = var.enable_emr
  name_prefix               = local.name_prefix
  release_label             = var.emr_release_label
  subnet_id                 = module.network.app_subnet_ids[0]
  master_security_group_id  = module.network.emr_master_security_group_id
  core_security_group_id    = module.network.emr_core_security_group_id
  service_security_group_id = module.network.emr_service_security_group_id
  service_role_arn          = module.iam.emr_service_role_arn
  instance_profile_arn      = module.iam.emr_instance_profile_arn
  kms_key_arn               = module.security.kms_key_arn
  log_uri                   = "s3://${module.datalake.bucket_names["logs"]}/emr/"
  core_instance_count       = var.emr_core_instance_count
  scaling_min_units         = var.emr_scaling_min_units
  scaling_max_units         = var.emr_scaling_max_units
  scaling_max_core_units    = var.emr_scaling_max_units
  idle_timeout_seconds      = var.emr_idle_timeout_seconds

  job_environment = merge(
    {
      SCHOLARCHAIN_ENV            = var.environment
      AWS_REGION                  = var.aws_region
      SCHOLARCHAIN_AWS_ACCOUNT_ID = var.aws_account_id
      RAW_BUCKET                  = module.datalake.bucket_names["raw"]
      CURATED_BUCKET              = module.datalake.bucket_names["curated"]
      GRAPH_BUCKET                = module.datalake.bucket_names["graph"]
      RAG_BUCKET                  = module.datalake.bucket_names["rag"]
      VECTOR_BUCKET               = module.vectors.vector_bucket_name
      NEPTUNE_ENDPOINT            = local.neptune_endpoint
      NEPTUNE_PORT                = tostring(module.neptune.port)
      NEPTUNE_QUERY_TIMEOUT_MS    = tostring(var.runtime_settings.neptune_query_timeout_ms)
    },
    local.vector_env,
    var.pipeline_environment,
  )
}

module "pipeline" {
  source = "../../modules/pipeline"

  enabled       = var.enable_emr
  name_prefix   = local.name_prefix
  role_arn      = module.iam.sfn_role_arn
  cluster_id    = module.emr.cluster_id
  log_group_arn = module.logging.log_group_arns["pipeline"]
}

module "observability" {
  source = "../../modules/observability"

  name_prefix                = local.name_prefix
  region                     = var.aws_region
  alarm_topic_arn            = module.foundation.alarm_topic_arn
  cluster_name               = module.compute.cluster_name
  service_names              = module.compute.service_names
  alb_arn_suffix             = module.compute.alb_arn_suffix
  target_group_arn_suffixes  = module.compute.target_group_arn_suffixes
  embedding_queue_name       = module.messaging.embedding_queue_name
  embedding_dlq_name         = module.messaging.embedding_dlq_name
  table_names                = module.dynamodb.table_names
  neptune_enabled            = var.enable_neptune
  neptune_cluster_identifier = module.neptune.cluster_identifier
}
