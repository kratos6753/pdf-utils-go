# App configuration lives in SSM Parameter Store under <prefix>/env/<NAME>.
# The instance's deploy script turns that path into the containers' .env file,
# so changing a value here and applying redeploys with the new setting.

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "postgres" {
  length  = 32
  special = false # used inside a connection URL
}

locals {
  env_plain = {
    DOMAIN                   = var.domain
    AWS_REGION               = var.region
    S3_REGION                = var.region
    S3_BUCKET                = aws_s3_bucket.files.bucket
    REGISTRY                 = local.registry
    LOG_GROUP                = aws_cloudwatch_log_group.app.name
    SMTP_ADDR                = "email-smtp.${var.region}.amazonaws.com:587"
    SMTP_USER                = aws_iam_access_key.ses_smtp.id
    MAIL_FROM                = "${var.mail_from_name} <no-reply@${var.domain}>"
    ANON_RETENTION           = "${var.anon_retention_hours}h"
    WORKER_CONCURRENCY       = tostring(var.worker_concurrency)
    WORKER_REPLICAS          = tostring(var.worker_replicas)
    GOOGLE_CLIENT_ID         = var.google_client_id
    GOOGLE_MOBILE_CLIENT_IDS = join(",", var.google_mobile_client_ids)
  }
  env_secret = {
    JWT_SECRET             = random_password.jwt.result
    POSTGRES_PASSWORD      = random_password.postgres.result
    SMTP_PASSWORD          = aws_iam_access_key.ses_smtp.ses_smtp_password_v4
    GOOGLE_CLIENT_SECRET   = var.google_client_secret
    ADMIN_INITIAL_PASSWORD = var.admin_initial_password
  }
  # SSM rejects empty values, so optional settings are only stored when set.
  env_plain_keys = concat(
    [for k in keys(local.env_plain) : k if !startswith(k, "GOOGLE_")],
    var.google_client_id != "" ? ["GOOGLE_CLIENT_ID"] : [],
    length(var.google_mobile_client_ids) > 0 ? ["GOOGLE_MOBILE_CLIENT_IDS"] : [],
  )
  env_secret_keys = concat(
    ["JWT_SECRET", "POSTGRES_PASSWORD", "SMTP_PASSWORD"],
    nonsensitive(var.google_client_secret != "") ? ["GOOGLE_CLIENT_SECRET"] : [], # whether it is set is not secret
    nonsensitive(var.admin_initial_password != "") ? ["ADMIN_INITIAL_PASSWORD"] : [],
  )

  # Redeploy whenever any setting changes.
  env_hash = sha256(jsonencode([
    { for k in local.env_plain_keys : k => local.env_plain[k] },
    { for k in local.env_secret_keys : k => sha256(local.env_secret[k]) },
  ]))
}

resource "aws_ssm_parameter" "env_plain" {
  for_each = toset(local.env_plain_keys)
  name     = "${local.ssm_prefix}/env/${each.key}"
  type     = "String"
  value    = local.env_plain[each.key]
}

resource "aws_ssm_parameter" "env_secret" {
  for_each = toset(local.env_secret_keys)
  name     = "${local.ssm_prefix}/env/${each.key}"
  type     = "SecureString"
  value    = local.env_secret[each.key]
}

# The image tag currently deployed. Terraform sets the first value; after that
# `make deploy` and CI update it, so Terraform ignores later changes.
resource "aws_ssm_parameter" "image_tag" {
  name  = "${local.ssm_prefix}/image_tag"
  type  = "String"
  value = local.image_tag
  lifecycle { ignore_changes = [value] }
}
