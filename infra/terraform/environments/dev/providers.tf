# Local state for the course environment (documented limitation: see
# infra/terraform/README.md and backend.tf.example). Provider and Terraform
# versions are pinned in versions.tf.

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile

  # The provider refuses to run against any other account. Disabled only for an
  # offline plan, which can never be applied.
  allowed_account_ids = var.offline_plan ? null : [var.aws_account_id]

  skip_credentials_validation = var.offline_plan
  skip_requesting_account_id  = var.offline_plan
  skip_metadata_api_check     = var.offline_plan

  default_tags {
    tags = {
      course      = "cs441"
      project     = "scholarchain"
      environment = var.environment
      owner       = var.owner
      expiration  = var.expiration
    }
  }
}
