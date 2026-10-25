# `terraform apply` builds images from this checkout, pushes them to Docker
# Hub and rolls them out. Requires Docker and the AWS CLI where it runs.

locals {
  source_files = sort(concat(
    [for f in fileset(local.repo_root, "backend/**") : f if !strcontains(f, "/.") && !endswith(f, "_test.go")],
    [for f in fileset(local.repo_root, "frontend/{src,public}/**") : f],
    ["frontend/package.json", "frontend/package-lock.json", "frontend/index.html", "frontend/vite.config.ts",
      "frontend/tsconfig.json", "frontend/tsconfig.app.json", "frontend/tsconfig.node.json",
    "deploy/Caddyfile", "deploy/web.Dockerfile"],
  ))
  image_tag = "tf-${substr(sha256(join("", [for f in local.source_files : filesha256("${local.repo_root}/${f}")])), 0, 12)}"
}

resource "null_resource" "images" {
  triggers = {
    tag      = local.image_tag
    repo     = local.image_repo
    platform = local.platform
  }

  provisioner "local-exec" {
    command = "${path.module}/../scripts/build-and-push-dockerhub.sh"
    environment = {
      IMAGE_REPO         = local.image_repo
      TAG                = local.image_tag
      PLATFORM           = local.platform
      DOCKERHUB_USERNAME = var.dockerhub_username
      DOCKERHUB_TOKEN    = var.dockerhub_token
    }
  }

  depends_on = [docker_hub_repository.app]
}

resource "null_resource" "deploy" {
  triggers = {
    tag      = local.image_tag
    env      = local.env_hash
    files    = join(",", [for o in aws_s3_object.deploy : o.etag])
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
