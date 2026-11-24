# S3 Vectors bucket and the five indexes the code configures
# (core VectorIndexConfig -> VECTOR_INDEX_*). Dimension and metric come from the
# code's embedding settings (core reference.conf: 1024, cosine). The non-filterable
# keys are exactly IndexDefinition.NonFilterableMetadataKeys in
# modules/storage/.../VectorTypes.scala: S3VectorsIndex.describe reads them back and
# VectorWriter refuses to write when the deployed index differs.
#
# Limitations of aws provider 6.74.0 that matter here:
#   * aws_s3vectors_index has no index_mode argument. Vector buckets created on or
#     after 2026-09-30 default to ENHANCED and cannot hold CLASSIC indexes, so the
#     services must run with SCHOLARCHAIN_VECTOR_INDEX_<NAME>_TYPE=ENHANCED (the
#     code default is CLASSIC). The compute module sets that variable.
#   * The bucket uses the default SSE-S3 encryption (no CMK): the S3 Vectors
#     service key-policy requirements were not verified from documentation.

locals {
  non_filterable_keys = [
    "textS3Uri",
    "textHash",
    "embeddingModel",
    "embeddingModelVersion",
    "runId",
    "schemaVersion",
  ]
}

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = var.vector_bucket_name
  force_destroy      = false
}

resource "aws_s3vectors_index" "this" {
  for_each = var.index_names

  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  index_name         = each.value
  data_type          = "float32"
  dimension          = var.dimension
  distance_metric    = var.distance_metric

  metadata_configuration {
    non_filterable_metadata_keys = local.non_filterable_keys
  }
}
