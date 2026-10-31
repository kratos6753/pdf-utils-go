variable "project" {
  description = "Name prefix for every resource."
  type        = string
  default     = "pdfutils"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

# --- DNS -----------------------------------------------------------------------

variable "domain" {
  description = "Public hostname of the app, e.g. pdf.example.com or example.com."
  type        = string
}

variable "route53_zone" {
  description = "Route 53 hosted zone that contains `domain`, e.g. example.com."
  type        = string
}

variable "create_route53_zone" {
  description = <<-EOT
    true: create the hosted zone (then point your registrar at the `name_servers` output).
    false: use an existing zone, e.g. one Route 53 created when you registered the domain.
  EOT
  type        = bool
  default     = false
}

# --- Compute -------------------------------------------------------------------

variable "instance_type" {
  description = "Graviton (arm64) instance. t4g.medium = 2 vCPU / 4 GiB, enough for API, 2 workers, Postgres and Redis."
  type        = string
  default     = "t4g.medium"
}

variable "data_volume_size_gb" {
  description = "Persistent EBS volume for Postgres, Redis, TLS certificates and Docker images."
  type        = number
  default     = 40
}

variable "worker_replicas" {
  type    = number
  default = 2
}

variable "worker_concurrency" {
  description = "Jobs processed in parallel per worker container."
  type        = number
  default     = 3
}

# --- App settings --------------------------------------------------------------

variable "mail_from_name" {
  type    = string
  default = "PDF Utils"
}

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
  description = "iOS/Android OAuth client ids accepted for mobile sign-in."
  type        = list(string)
  default     = []
}

variable "anon_retention_hours" {
  type    = number
  default = 2
}

variable "history_glacier_after_days" {
  description = "Move users' history files to S3 Glacier Instant Retrieval after this many days."
  type        = number
  default     = 30
}

# --- Email ---------------------------------------------------------------------

variable "request_ses_production_access" {
  description = <<-EOT
    Ask AWS to move SES out of the sandbox (sandboxed accounts can only email
    verified addresses). AWS reviews the request, usually within a day.
  EOT
  type        = bool
  default     = true
}

# --- Ops -----------------------------------------------------------------------

variable "alert_email" {
  description = "Receives budget alerts. Empty disables the budget."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  type    = number
  default = 60
}

variable "backup_retention_days" {
  type    = number
  default = 30
}

# --- CI/CD (optional) ----------------------------------------------------------

variable "github_repository" {
  description = <<-EOT
    "owner/repo" to deploy from GitHub Actions on every push to main. Terraform
    creates the OIDC deploy role and writes the repository variables the workflow
    needs (export GITHUB_TOKEN, e.g. `export GITHUB_TOKEN=$(gh auth token)`).
    Empty disables CI/CD; deploy with `make deploy` instead.
  EOT
  type        = string
  default     = ""
}

variable "create_github_oidc_provider" {
  description = "Set false if the account already has the token.actions.githubusercontent.com OIDC provider."
  type        = bool
  default     = true
}
