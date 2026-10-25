# --- User files ---------------------------------------------------------------

resource "aws_s3_bucket" "files" {
  bucket = "${local.name}-files-${data.aws_caller_identity.me.account_id}"
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket                  = aws_s3_bucket.files.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
    bucket_key_enabled = true
  }
}

# Matches the key prefixes the app writes (see internal/service SaveFile):
#   tmp/    anonymous uploads and results, also deleted by the app's cleanup job
#   users/  signed-in users' history, kept until evicted by quota or deleted
resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    id     = "expire-anonymous-files"
    status = "Enabled"
    filter { prefix = "tmp/" }
    expiration { days = 1 }
  }

  rule {
    id     = "history-to-glacier-instant-retrieval"
    status = "Enabled"
    filter {
      and {
        prefix = "users/"
        # Glacier IR bills small objects as 128 KB; leave tiny files in Standard.
        object_size_greater_than = 131072
      }
    }
    transition {
      days          = var.history_glacier_after_days
      storage_class = "GLACIER_IR"
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}

# Browsers download results straight from S3 via presigned URLs, and the
# "continue with another tool" flow fetches them with fetch(), which needs CORS.
resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.id
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["https://${var.domain}"]
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

# --- Ops: deploy artifacts and database backups --------------------------------

resource "aws_s3_bucket" "ops" {
  bucket = "${local.name}-ops-${data.aws_caller_identity.me.account_id}"
}

resource "aws_s3_bucket_public_access_block" "ops" {
  bucket                  = aws_s3_bucket.ops.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    id     = "expire-db-backups"
    status = "Enabled"
    filter { prefix = "backups/" }
    expiration { days = var.backup_retention_days }
  }
}

# Files the instance pulls on every deploy.
resource "aws_s3_object" "compose" {
  bucket = aws_s3_bucket.ops.id
  key    = "deploy/docker-compose.yml"
  source = "${path.module}/templates/docker-compose.yml"
  etag   = filemd5("${path.module}/templates/docker-compose.yml")
}

resource "aws_s3_object" "deploy_script" {
  bucket = aws_s3_bucket.ops.id
  key    = "deploy/deploy.sh"
  source = "${path.module}/templates/deploy.sh"
  etag   = filemd5("${path.module}/templates/deploy.sh")
}

resource "aws_s3_object" "backup_script" {
  bucket = aws_s3_bucket.ops.id
  key    = "deploy/backup.sh"
  source = "${path.module}/templates/backup.sh"
  etag   = filemd5("${path.module}/templates/backup.sh")
}

# --- Container images ----------------------------------------------------------

resource "aws_ecr_repository" "app" {
  for_each             = toset(["api", "web"])
  name                 = "${var.project}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 15 most recent images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 15 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "app" {
  name              = local.log_group
  retention_in_days = 30
}
