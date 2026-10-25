terraform {
  required_version = ">= 1.10"

  # Settings come from backend.hcl, written by infra/bootstrap:
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 6.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
    null   = { source = "hashicorp/null", version = "~> 3.2" }
    github = { source = "integrations/github", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = var.project, Environment = var.environment, ManagedBy = "terraform" }
  }
}

# Only used when github_repository is set (reads GITHUB_TOKEN from the environment).
provider "github" {
  owner = local.github_owner
}

data "aws_caller_identity" "me" {}
data "aws_region" "current" {}
