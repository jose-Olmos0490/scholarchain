# One ECR repository per deployable service image (docker/Dockerfile.service is
# built once per entrypoint). Tags are immutable: an image tag is a Git SHA and is
# never moved.

resource "aws_ecr_repository" "this" {
  for_each = toset(var.services)

  name                 = "${replace(var.name_prefix, "-", "/")}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the newest ${var.keep_images} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.keep_images
        }
        action = { type = "expire" }
      }
    ]
  })
}
