output "url" {
  value = "https://${var.domain}"
}

output "public_ip" {
  description = "Point an A record for the domain here if route53_zone is empty."
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  value = aws_instance.app.id
}

output "region" {
  value = var.region
}

output "ssm_prefix" {
  value = local.ssm_prefix
}

output "bucket" {
  value = aws_s3_bucket.main.bucket
}

output "image_repo" {
  value = local.image_repo
}

output "image_platform" {
  value = local.platform
}

output "image_tag_built" {
  value = local.image_tag
}

output "google_redirect_uri" {
  value = "https://${var.domain}/v1/auth/google/callback"
}

output "email_enabled" {
  value = local.email_enabled
}
