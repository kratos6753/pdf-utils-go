output "url" {
  value = "https://${var.domain}"
}

output "public_ip" {
  value = aws_eip.app.public_ip
}

output "instance_id" {
  value = aws_instance.app.id
}

output "region" {
  value = var.region
}

output "name_servers" {
  description = "Set these at your domain registrar (only when create_route53_zone = true)."
  value       = var.create_route53_zone ? aws_route53_zone.main[0].name_servers : null
}

output "files_bucket" {
  value = aws_s3_bucket.files.bucket
}

output "ops_bucket" {
  value = aws_s3_bucket.ops.bucket
}

output "ecr_registry" {
  value = local.registry
}

output "project" {
  value = var.project
}

output "ssm_prefix" {
  value = local.ssm_prefix
}

output "log_group" {
  value = aws_cloudwatch_log_group.app.name
}

output "image_tag_built" {
  description = "Image tag built from this checkout by the last apply."
  value       = local.image_tag
}

output "google_redirect_uri" {
  description = "Authorized redirect URI to register on the Google OAuth client."
  value       = "https://${var.domain}/v1/auth/google/callback"
}

output "github_deploy_role_arn" {
  value = local.github_enabled ? aws_iam_role.github_deploy[0].arn : null
}
