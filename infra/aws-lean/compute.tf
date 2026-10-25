data "aws_ec2_instance_type" "selected" {
  instance_type = var.instance_type
}

locals {
  arch     = contains(data.aws_ec2_instance_type.selected.supported_architectures, "arm64") ? "arm64" : "x86_64"
  platform = local.arch == "arm64" ? "linux/arm64" : "linux/amd64"
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${local.arch}"
}

# Database and Docker data live on their own small volume so replacing the
# instance (new AMI, different type) keeps everything.
resource "aws_ebs_volume" "data" {
  availability_zone = local.az
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true
  tags              = { Name = "${local.name}-data" }
  lifecycle { prevent_destroy = true }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    region         = var.region
    ssm_prefix     = local.ssm_prefix
    bucket         = aws_s3_bucket.main.bucket
    volume_id      = replace(aws_ebs_volume.data.id, "-", "")
    swap_gb        = var.swap_size_gb
    enable_backups = var.enable_db_backups
  })

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # containers use the instance role for S3
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size_gb
    encrypted   = true
  }

  # Standard credits: never billed for CPU bursts (throttles instead).
  credit_specification {
    cpu_credits = "standard"
  }

  tags = { Name = local.name }

  lifecycle {
    ignore_changes = [ami, user_data]
  }

  depends_on = [
    null_resource.images,
    aws_ssm_parameter.env_plain,
    aws_ssm_parameter.env_secret,
    aws_ssm_parameter.registry_token,
    aws_ssm_parameter.image_tag,
    aws_s3_object.deploy,
  ]
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.app.id
}

# A stable address for DNS. (Every public IPv4 address is billed the same,
# Elastic or not; the free tier covers one.)
resource "aws_eip" "app" {
  domain = "vpc"
  tags   = { Name = local.name }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

# EC2 status-check metrics are free, and so are the first 10 alarms.
resource "aws_cloudwatch_metric_alarm" "recover" {
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
