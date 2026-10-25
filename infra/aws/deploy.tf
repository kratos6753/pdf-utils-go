# Builds the app images from this checkout and rolls them out, so a single
# `terraform apply` takes the stack from nothing to running. Requires Docker
# and the AWS CLI where Terraform runs.

locals {
  platform = local.arch == "arm64" ? "linux/arm64" : "linux/amd64"

  source_files = sort(concat(
    [for f in fileset(local.repo_root, "backend/**") : f if !strcontains(f, "/.") && !endswith(f, "_test.go")],
    [for f in fileset(local.repo_root, "frontend/{src,public}/**") : f],
    ["frontend/package.json", "frontend/package-lock.json", "frontend/index.html", "frontend/vite.config.ts",
      "frontend/tsconfig.json", "frontend/tsconfig.app.json", "frontend/tsconfig.node.json",
    "deploy/Caddyfile", "deploy/web.Dockerfile"],
  ))
  # Content hash: identical source => identical tag => nothing to rebuild.
  image_tag = "tf-${substr(sha256(join("", [for f in local.source_files : filesha256("${local.repo_root}/${f}")])), 0, 12)}"
}

resource "null_resource" "images" {
  triggers = {
    tag      = local.image_tag
    registry = local.registry
  }

  provisioner "local-exec" {
    command = "${path.module}/../scripts/build-and-push.sh"
    environment = {
      REGISTRY = local.registry
      PROJECT  = var.project
      TAG      = local.image_tag
      PLATFORM = local.platform
      REGION   = var.region
    }
  }

  depends_on = [aws_ecr_repository.app]
}

# Rolls out new images, configuration or deploy files to the running instance.
resource "null_resource" "deploy" {
  triggers = {
    tag      = local.image_tag
    env      = local.env_hash
    compose  = aws_s3_object.compose.etag
    script   = aws_s3_object.deploy_script.etag
    instance = aws_instance.app.id
  }

  provisioner "local-exec" {
    command = "${path.module}/../scripts/deploy-remote.sh"
    environment = {
      INSTANCE_ID = aws_instance.app.id
      REGION      = var.region
      SSM_PREFIX  = local.ssm_prefix
      TAG         = local.image_tag
    }
  }

  depends_on = [null_resource.images, aws_volume_attachment.data, aws_eip_association.app, aws_route53_record.app]
}
