# Optional CI/CD: GitHub Actions pushes to Docker Hub and deploys over SSM,
# authenticating to AWS through OIDC (no stored AWS keys).

locals {
  github_enabled = var.github_repository != ""
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = local.github_enabled && var.create_github_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = local.github_enabled && !var.create_github_oidc_provider ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

# Jobs that use an environment get it, not the branch, in their OIDC subject,
# and the ci.yml deploy jobs run in "production". Repos with immutable subjects
# use "owner@id/repo@id" in place of "owner/repo", so trust both forms.
data "github_repository" "this" {
  count     = local.github_enabled ? 1 : 0
  full_name = var.github_repository
}

data "github_user" "owner" {
  count    = local.github_enabled ? 1 : 0
  username = local.github_owner
}

locals {
  github_oidc_subjects = !local.github_enabled ? [] : [
    "repo:${var.github_repository}:environment:production",
    "repo:${local.github_owner}@${data.github_user.owner[0].id}/${local.github_repo}@${data.github_repository.this[0].repo_id}:environment:production",
  ]
}

data "aws_iam_policy_document" "github_assume" {
  count = local.github_enabled ? 1 : 0
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type = "Federated"
      identifiers = [var.create_github_oidc_provider
        ? aws_iam_openid_connect_provider.github[0].arn
      : data.aws_iam_openid_connect_provider.github[0].arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.github_oidc_subjects
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  count              = local.github_enabled ? 1 : 0
  name               = "${local.name}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume[0].json
}

data "aws_iam_policy_document" "github_deploy" {
  count = local.github_enabled ? 1 : 0
  statement {
    actions   = ["ssm:PutParameter"]
    resources = [aws_ssm_parameter.image_tag.arn]
  }
  statement {
    actions   = ["ssm:SendCommand"]
    resources = [aws_instance.app.arn, "arn:aws:ssm:${var.region}::document/AWS-RunShellScript"]
  }
  statement {
    actions   = ["ssm:GetCommandInvocation", "ssm:DescribeInstanceInformation"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  count  = local.github_enabled ? 1 : 0
  name   = "deploy"
  role   = aws_iam_role.github_deploy[0].id
  policy = data.aws_iam_policy_document.github_deploy[0].json
}

resource "github_actions_variable" "deploy" {
  for_each = local.github_enabled ? {
    AWS_DEPLOY_ROLE_ARN = aws_iam_role.github_deploy[0].arn
    AWS_REGION          = var.region
    INSTANCE_ID         = aws_instance.app.id
    SSM_PREFIX          = local.ssm_prefix
    APP_URL             = "https://${var.domain}"
    IMAGE_PLATFORM      = local.platform
    DOCKERHUB_REPO      = local.image_repo
    DOCKERHUB_USERNAME  = var.dockerhub_username
  } : {}
  repository    = local.github_repo
  variable_name = each.key
  value         = each.value
}

resource "github_actions_secret" "dockerhub_token" {
  count       = local.github_enabled ? 1 : 0
  repository  = local.github_repo
  secret_name = "DOCKERHUB_TOKEN"
  value       = var.dockerhub_token
}
