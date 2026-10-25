resource "aws_route53_zone" "main" {
  count = var.create_route53_zone ? 1 : 0
  name  = var.route53_zone
}

data "aws_route53_zone" "main" {
  count        = var.create_route53_zone ? 0 : 1
  name         = var.route53_zone
  private_zone = false
}

locals {
  zone_id = var.create_route53_zone ? aws_route53_zone.main[0].zone_id : data.aws_route53_zone.main[0].zone_id
}

resource "aws_route53_record" "app" {
  zone_id = local.zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = [aws_eip.app.public_ip]
}

# Only Let's Encrypt (used by Caddy) may issue certificates for the domain.
resource "aws_route53_record" "caa" {
  zone_id = local.zone_id
  name    = var.domain
  type    = "CAA"
  ttl     = 3600
  records = ["0 issue \"letsencrypt.org\""]
}
