# Transactional email (verification, password reset) through Amazon SES SMTP.

resource "aws_sesv2_email_identity" "domain" {
  email_identity = var.domain
}

resource "aws_route53_record" "dkim" {
  count   = 3 # Easy DKIM always issues three tokens
  zone_id = local.zone_id
  name    = "${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}._domainkey.${var.domain}"
  type    = "CNAME"
  ttl     = 1800
  records = ["${aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens[count.index]}.dkim.amazonses.com"]
}

# Custom MAIL FROM so SPF aligns with the domain (better inbox placement).
resource "aws_sesv2_email_identity_mail_from_attributes" "domain" {
  email_identity         = aws_sesv2_email_identity.domain.email_identity
  mail_from_domain       = "mail.${var.domain}"
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
}

resource "aws_route53_record" "mail_from_mx" {
  zone_id = local.zone_id
  name    = "mail.${var.domain}"
  type    = "MX"
  ttl     = 1800
  records = ["10 feedback-smtp.${var.region}.amazonses.com"]
}

resource "aws_route53_record" "mail_from_spf" {
  zone_id = local.zone_id
  name    = "mail.${var.domain}"
  type    = "TXT"
  ttl     = 1800
  records = ["v=spf1 include:amazonses.com ~all"]
}

resource "aws_route53_record" "dmarc" {
  zone_id = local.zone_id
  name    = "_dmarc.${var.domain}"
  type    = "TXT"
  ttl     = 1800
  records = ["v=DMARC1; p=none"] # monitoring only; tighten once mail flows cleanly
}

# SES SMTP needs IAM user credentials; the SMTP password is derived from the key.
resource "aws_iam_user" "ses_smtp" {
  name = "${local.name}-ses-smtp"
}

resource "aws_iam_user_policy" "ses_smtp" {
  name = "send-email"
  user = aws_iam_user.ses_smtp.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ses:SendRawEmail", "ses:SendEmail"]
      Resource = "*"
      Condition = {
        StringLike = { "ses:FromAddress" = "*@${var.domain}" }
      }
    }]
  })
}

resource "aws_iam_access_key" "ses_smtp" {
  user = aws_iam_user.ses_smtp.name
}

# New accounts start in the SES sandbox (mail only to verified addresses).
# This files the production-access request; AWS reviews it (usually < 24h).
resource "null_resource" "ses_production_access" {
  count    = var.request_ses_production_access ? 1 : 0
  triggers = { domain = var.domain }

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    command     = <<-EOT
      set -euo pipefail
      enabled=$(aws sesv2 get-account --region ${var.region} --query ProductionAccessEnabled --output text)
      if [ "$enabled" = "True" ]; then echo "SES production access already enabled"; exit 0; fi
      aws sesv2 put-account-details --region ${var.region} \
        --production-access-enabled \
        --mail-type TRANSACTIONAL \
        --website-url "https://${var.domain}" \
        --contact-language EN \
        --use-case-description "Transactional email for ${var.domain}, an online PDF tools service: account email verification and password reset links sent only to users who sign up. No marketing email. Bounces and complaints are handled via SES." \
        || echo "WARNING: could not file the SES production access request (it may already be pending)."
    EOT
  }
}
