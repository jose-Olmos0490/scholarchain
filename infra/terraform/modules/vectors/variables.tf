variable "vector_bucket_name" {
  description = "Vector bucket name (3-63 lowercase characters)."
  type        = string
}

variable "index_names" {
  description = "Logical key to index name. Keys match core VectorIndexConfig: researchers, communities, abstracts, paths, topicInstitutions."
  type        = map(string)
  default = {
    researchers       = "researcher-profile-v1"
    communities       = "collaboration-community-v1"
    abstracts         = "paper-abstract-v1"
    paths             = "collaboration-path-v1"
    topicInstitutions = "topic-institution-summary-v1"
  }
}

variable "dimension" {
  description = "Embedding dimension of every index. Code default is 1024 (SCHOLARCHAIN_VECTOR_INDEX_*_DIMENSION)."
  type        = number
  default     = 1024

  validation {
    condition     = var.dimension >= 1 && var.dimension <= 4096
    error_message = "S3 Vectors supports dimensions 1..4096."
  }
}

variable "distance_metric" {
  description = "cosine or euclidean. Code default is cosine."
  type        = string
  default     = "cosine"

  validation {
    condition     = contains(["cosine", "euclidean"], var.distance_metric)
    error_message = "distance_metric must be cosine or euclidean."
  }
}
