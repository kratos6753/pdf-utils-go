# One bucket, split by prefix:
#   tmp/      anonymous files (expire after 1 day; the app deletes them after 2h)
#   users/    signed-in users' history (to Glacier Instant Retrieval after N days)
#   deploy/   compose file and scripts the server pulls on each deploy
#   backups/  nightly pg_dump, when enable_db_backups = true

resource "aws_s3_bucket" "main" {
  bucket = "${local.name}-${data.aws_caller_identity.me.account_id}"
}

resource "aws_s3_bucket_public_access_block" "main" {
  bucket                  = aws_s3_bucket.main.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "main" {
  bucket = aws_s3_bucket.main.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "main" {
  bucket = aws_s3_bucket.main.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" } # SSE-S3: free
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "main" {
  bucket = aws_s3_bucket.main.id

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
        prefix                   = "users/"
        object_size_greater_than = 131072 # Glacier IR bills objects under 128 KB as 128 KB
      }
    }
    transition {
      days          = var.history_glacier_after_days
      storage_class = "GLACIER_IR"
    }
  }

  rule {
    id     = "expire-db-backups"
    status = "Enabled"
    filter { prefix = "backups/" }
    expiration { days = var.backup_retention_days }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}

resource "aws_s3_bucket_cors_configuration" "main" {
  bucket = aws_s3_bucket.main.id
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["https://${var.domain}"]
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_object" "deploy" {
  for_each = toset(["docker-compose.yml", "deploy.sh", "backup.sh"])
  bucket   = aws_s3_bucket.main.id
  key      = "deploy/${each.key}"
  source   = "${path.module}/templates/${each.key}"
  etag     = filemd5("${path.module}/templates/${each.key}")
}

# --- Docker Hub ----------------------------------------------------------------

resource "docker_hub_repository" "app" {
  namespace   = var.dockerhub_username
  name        = var.dockerhub_repository
  description = "${var.project} images (api-* and web-* tags)"
  private     = true
}
