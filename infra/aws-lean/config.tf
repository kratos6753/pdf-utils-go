# App settings in SSM Parameter Store (standard tier: free). The server's
# deploy script turns <prefix>/env/* into the containers' .env file.

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "postgres" {
  length  = 32
  special = false
}

locals {
  email_enabled = var.smtp_username != ""

  env_plain = merge(
    {
      DOMAIN             = var.domain
      S3_REGION          = var.region
      S3_BUCKET          = aws_s3_bucket.main.bucket
      IMAGE_REPO         = local.image_repo
      WORKER_CONCURRENCY = tostring(var.worker_concurrency)
    },
    local.email_enabled ? {
      SMTP_ADDR = var.smtp_host
      SMTP_USER = var.smtp_username
      # Gmail rewrites other From addresses to the account, so send as it.
      MAIL_FROM = "${var.mail_from_name} <${var.smtp_username}>"
    } : {},
    var.google_client_id != "" ? { GOOGLE_CLIENT_ID = var.google_client_id } : {},
    length(var.google_mobile_client_ids) > 0 ? { GOOGLE_MOBILE_CLIENT_IDS = join(",", var.google_mobile_client_ids) } : {},
  )

  env_secret_keys = concat(
    ["JWT_SECRET", "POSTGRES_PASSWORD"],
    local.email_enabled ? ["SMTP_PASSWORD"] : [],
    nonsensitive(var.google_client_secret != "") ? ["GOOGLE_CLIENT_SECRET"] : [],
    nonsensitive(var.admin_initial_password != "") ? ["ADMIN_INITIAL_PASSWORD"] : [],
  )
  env_secret = {
    JWT_SECRET             = random_password.jwt.result
    POSTGRES_PASSWORD      = random_password.postgres.result
    SMTP_PASSWORD          = var.smtp_password
    GOOGLE_CLIENT_SECRET   = var.google_client_secret
    ADMIN_INITIAL_PASSWORD = var.admin_initial_password
  }

  env_hash = sha256(jsonencode([
    local.env_plain,
    { for k in local.env_secret_keys : k => sha256(local.env_secret[k]) },
  ]))
}

resource "aws_ssm_parameter" "env_plain" {
  for_each = local.env_plain
  name     = "${local.ssm_prefix}/env/${each.key}"
  type     = "String"
  value    = each.value
}

resource "aws_ssm_parameter" "env_secret" {
  for_each = toset(local.env_secret_keys)
  name     = "${local.ssm_prefix}/env/${each.key}"
  type     = "SecureString"
  value    = local.env_secret[each.key]
}

# Registry credentials live outside env/ so they never reach the containers.
resource "aws_ssm_parameter" "registry_username" {
  name  = "${local.ssm_prefix}/registry/username"
  type  = "String"
  value = var.dockerhub_username
}

resource "aws_ssm_parameter" "registry_token" {
  name  = "${local.ssm_prefix}/registry/token"
  type  = "SecureString"
  value = var.dockerhub_token
}

# Deployed image tag. Terraform sets the first value; `make deploy` and CI
# update it afterwards, so later changes are ignored here.
resource "aws_ssm_parameter" "image_tag" {
  name  = "${local.ssm_prefix}/image_tag"
  type  = "String"
  value = local.image_tag
  lifecycle { ignore_changes = [value] }
}
