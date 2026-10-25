# Latest Amazon Linux 2023 for the instance's architecture. Later AMI releases
# do not replace the running instance (see ignore_changes).
data "aws_ec2_instance_type" "selected" {
  instance_type = var.instance_type
}

locals {
  arch = contains(data.aws_ec2_instance_type.selected.supported_architectures, "arm64") ? "arm64" : "x86_64"
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${local.arch}"
}

resource "aws_ebs_volume" "data" {
  availability_zone = local.az
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true
  tags              = { Name = "${local.name}-data", Snapshot = "${local.name}-data" }
  lifecycle { prevent_destroy = true } # holds the database
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name
  monitoring             = false

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    region     = var.region
    ssm_prefix = local.ssm_prefix
    ops_bucket = aws_s3_bucket.ops.bucket
    volume_id  = replace(aws_ebs_volume.data.id, "-", "")
  })

  metadata_options {
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 2          # let containers reach the instance role
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  credit_specification {
    cpu_credits = "unlimited" # bursts during heavy jobs instead of throttling
  }

  tags = { Name = local.name }

  lifecycle {
    ignore_changes = [ami, user_data]
  }

  # The first boot pulls images and config, so they must exist beforehand.
  depends_on = [
    null_resource.images,
    aws_ssm_parameter.env_plain,
    aws_ssm_parameter.env_secret,
    aws_ssm_parameter.image_tag,
    aws_s3_object.compose,
    aws_s3_object.deploy_script,
    aws_s3_object.backup_script,
  ]
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id
}

resource "aws_eip" "app" {
  domain = "vpc"
  tags   = { Name = local.name }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

# Recover automatically onto healthy hardware if AWS-side checks fail, and
# reboot if the OS stops responding.
resource "aws_cloudwatch_metric_alarm" "system_recover" {
  alarm_name          = "${local.name}-system-check"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = ["arn:aws:automate:${var.region}:ec2:recover"]
}

resource "aws_cloudwatch_metric_alarm" "instance_reboot" {
  alarm_name          = "${local.name}-instance-check"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = ["arn:aws:automate:${var.region}:ec2:reboot"]
}

# Daily snapshots of the data volume, in addition to nightly pg_dump to S3.
resource "aws_dlm_lifecycle_policy" "data" {
  description        = "${local.name} data volume snapshots"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    target_tags    = { Snapshot = "${local.name}-data" }

    schedule {
      name = "daily"
      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["04:00"]
      }
      retain_rule { count = 7 }
      copy_tags = true
    }
  }
}
