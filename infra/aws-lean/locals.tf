locals {
  name         = "${var.project}-${var.environment}"
  ssm_prefix   = "/${var.project}/${var.environment}"
  image_repo   = "${var.dockerhub_username}/${var.dockerhub_repository}"
  manage_dns   = var.route53_zone != ""
  github_owner = var.github_repository == "" ? null : split("/", var.github_repository)[0]
  github_repo  = var.github_repository == "" ? null : split("/", var.github_repository)[1]
  repo_root    = abspath("${path.module}/../..")
}
