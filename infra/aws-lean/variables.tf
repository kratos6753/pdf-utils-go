variable "project" {
  type    = string
  default = "pdfutils"
}

variable "environment" {
  description = "Name suffix; distinct from the full stack's \"prod\" so both can coexist in one account."
  type        = string
  default     = "lean"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

# --- DNS -----------------------------------------------------------------------

variable "domain" {
  description = "Public hostname of the app, e.g. pdf.example.com."
  type        = string
}

variable "route53_zone" {
  description = <<-EOT
    Existing Route 53 hosted zone containing `domain` (e.g. example.com).
    Set to "" to manage DNS elsewhere (e.g. Cloudflare's free plan) and save
    the $0.50/month hosted-zone fee: point an A record for `domain` at the
    `public_ip` output yourself.
  EOT
  type        = string
}

# --- Compute -------------------------------------------------------------------

variable "instance_type" {
  description = <<-EOT
    t2.micro (1 vCPU, 1 GiB) is free-tier eligible. Off the free tier, t4g.micro
    (arm64) and t3.micro are cheaper for the same memory; images are built for
    whichever architecture the type uses.
  EOT
  type        = string
  default     = "t2.micro"
}

variable "root_volume_size_gb" {
  description = "OS + swap. The free tier covers 30 GB of EBS in total."
  type        = number
  default     = 10
}

variable "data_volume_size_gb" {
  description = "Postgres, Redis, TLS certificates and Docker images; survives instance replacement."
  type        = number
  default     = 10
}

variable "swap_size_gb" {
  description = "Swap absorbs Ghostscript memory spikes on a 1 GiB instance."
  type        = number
  default     = 2
}

variable "worker_concurrency" {
  description = "Jobs processed in parallel. Keep at 1 on 1 GiB of RAM."
  type        = number
  default     = 1
}

# --- Container registry (Docker Hub) -------------------------------------------

variable "dockerhub_username" {
  type = string
}

variable "dockerhub_token" {
  description = "Docker Hub personal access token with Read, Write, Delete scope, needed to create the repository (set via TF_VAR_dockerhub_token)."
  type        = string
  sensitive   = true
}

variable "dockerhub_repository" {
  description = "One private repository holds both images, tagged api-* and web-* (fits the free plan)."
  type        = string
  default     = "pdfutils"
}

# --- Email (Gmail SMTP) --------------------------------------------------------

variable "smtp_username" {
  description = <<-EOT
    Gmail address that sends verification/reset emails, e.g. you@gmail.com.
    Empty disables email (links are only written to the server log).
  EOT
  type        = string
  default     = ""
}

variable "smtp_password" {
  description = "16-character Google app password (not your Gmail password); set via TF_VAR_smtp_password."
  type        = string
  default     = ""
  sensitive   = true
  validation {
    condition     = var.smtp_username == "" || nonsensitive(var.smtp_password != "")
    error_message = "smtp_username is set but smtp_password is empty: export TF_VAR_smtp_password=<Google app password> in this shell (or clear smtp_username to disable email)."
  }
}

variable "smtp_host" {
  description = "Any STARTTLS SMTP server works; Gmail is the default."
  type        = string
  default     = "smtp.gmail.com:587"
}

variable "mail_from_name" {
  type    = string
  default = "PDF Utils"
}

# --- App settings --------------------------------------------------------------

variable "google_client_id" {
  description = "Web OAuth client id. Leave empty to hide Google sign-in."
  type        = string
  default     = ""
}

variable "google_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "admin_initial_password" {
  description = "Password for the first /admin account (changed on first sign-in). Empty uses the public default from the code, so set it before the first deploy."
  type        = string
  default     = ""
  sensitive   = true
}

variable "google_mobile_client_ids" {
  type    = list(string)
  default = []
}

variable "history_glacier_after_days" {
  type    = number
  default = 30
}

# --- Optional extras (off by default to save cost) -----------------------------

variable "enable_db_backups" {
  description = "Nightly pg_dump to S3. Costs cents per month; strongly recommended once you have real users."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  type    = number
  default = 14
}

variable "alert_email" {
  description = "Monthly budget alert recipient (AWS Budgets is free for 2 budgets). Empty disables."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  type    = number
  default = 15
}

variable "github_repository" {
  description = "\"owner/repo\" to deploy on every push to main via GitHub Actions. Empty disables CI/CD."
  type        = string
  default     = ""
}

variable "create_github_oidc_provider" {
  description = "Set false if the account already has the GitHub OIDC provider (e.g. created by infra/aws)."
  type        = bool
  default     = true
}
