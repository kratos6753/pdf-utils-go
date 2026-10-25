data "aws_route53_zone" "main" {
  count        = local.manage_dns ? 1 : 0
  name         = var.route53_zone
  private_zone = false
}

resource "aws_route53_record" "app" {
  count   = local.manage_dns ? 1 : 0
  zone_id = data.aws_route53_zone.main[0].zone_id
  name    = var.domain
  type    = "A"
  ttl     = 300
  records = [aws_eip.app.public_ip]
}
