locals {
  name         = "${var.project}-${var.environment}"
  ssm_prefix   = "/${var.project}/${var.environment}"
  registry     = "${data.aws_caller_identity.me.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  log_group    = "/${var.project}/${var.environment}/app"
  github_owner = var.github_repository == "" ? null : split("/", var.github_repository)[0]
  github_repo  = var.github_repository == "" ? null : split("/", var.github_repository)[1]
  repo_root    = abspath("${path.module}/../..")
}
