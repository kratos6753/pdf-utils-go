# One-time setup: the S3 bucket that holds Terraform state for infra/aws.
# Its own state is local (terraform.tfstate in this directory); keep it, or
# re-import the bucket if it is lost.
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws   = { source = "hashicorp/aws", version = "~> 6.0" }
    local = { source = "hashicorp/local", version = "~> 2.5" }
  }
}

variable "region" {
  description = "AWS region for the state bucket (use the same region as the app)."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  type    = string
  default = "pdfutils"
}

provider "aws" {
  region = var.region
  default_tags { tags = { Project = var.project, ManagedBy = "terraform" } }
}

data "aws_caller_identity" "me" {}

resource "aws_s3_bucket" "state" {
  # Account id keeps the globally-unique name predictable and collision-free.
  bucket = "${var.project}-tfstate-${data.aws_caller_identity.me.account_id}"
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = 90 }
  }
}

# Backend settings for each stack, so `terraform init` needs no flags to type.
# infra/aws = full production stack, infra/aws-lean = low-cost stack.
locals {
  stacks = {
    "aws"      = "${var.project}/prod/terraform.tfstate"
    "aws-lean" = "${var.project}/lean/terraform.tfstate"
  }
}

resource "local_file" "backend" {
  for_each        = local.stacks
  filename        = "${path.module}/../${each.key}/backend.hcl"
  file_permission = "0644"
  content         = <<-EOT
    bucket       = "${aws_s3_bucket.state.bucket}"
    key          = "${each.value}"
    region       = "${var.region}"
    encrypt      = true
    use_lockfile = true
  EOT
}

output "state_bucket" {
  value = aws_s3_bucket.state.bucket
}
