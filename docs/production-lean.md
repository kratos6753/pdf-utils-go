# Lean production setup (AWS, lowest cost)

`infra/aws-lean` is a stripped-down alternative to the [full stack](production.md). It runs the same app, managed entirely by Terraform, on the smallest viable footprint: **about $14/month on demand, or under $1/month while on the AWS free tier.**

It's a separate Terraform stack with its own state, so you can run it instead of, or alongside, `infra/aws`.

## What's different from the full stack

| | Full (`infra/aws`) | Lean (`infra/aws-lean`) |
|---|---|---|
| Server | t4g.medium (2 vCPU, 4 GiB) | **t2.micro** (1 vCPU, 1 GiB, free-tier eligible) + 2 GiB swap |
| Workers | 2 × 3 parallel jobs | 1 × 1 job (queue absorbs bursts) |
| Image registry | Amazon ECR | **Docker Hub**: one private repo, tags `api-*` / `web-*` (free plan) |
| Email | Amazon SES + DKIM/SPF/DMARC records | **Gmail SMTP** with an app password (free) |
| Logs | CloudWatch Logs | **On the server**, rotated (10 MB × 3 per container) |
| Backups | Nightly pg_dump + daily EBS snapshots | **Off** by default; optional nightly pg_dump (`enable_db_backups`) |
| Storage | Files bucket + ops bucket | One bucket (prefixes `tmp/ users/ deploy/ backups/`) |
| DNS | Route 53 incl. CAA/mail records | Route 53 A record, or **none** (use any free DNS) |
| CPU credits | Unlimited (burst billed) | Standard (never billed; throttles when exhausted) |

Kept because they cost nothing: a dedicated VPC with an S3 gateway endpoint, SSM Session Manager (no SSH), Parameter Store (standard tier), the EC2 auto-recover alarm (within the 10 free alarms), the budget alert (2 budgets are free), Glacier Instant Retrieval for old history, and GitHub OIDC deploys.

**Does it fit in 1 GiB?** Measured with this exact compose file: the containers use about 170 MiB idle and peaked at 235 MiB while converting ten 3.6 MB scans into a 37 MB PDF, compressing it, rasterizing it to JPGs (111 MB zip) and merging. Amazon Linux, Docker and the SSM agent add roughly 250–300 MiB. There's headroom, and swap covers Ghostscript spikes on unusually large files.

## Monthly cost (us-east-1, on-demand)

| Item | Cost |
|---|---|
| t2.micro (730 h) | ~$8.50 |
| EBS gp3, 10 GB root + 10 GB data | ~$1.60 |
| Public IPv4 address | ~$3.65 |
| Route 53 hosted zone (skip with `route53_zone = ""`) | $0.50 |
| S3 (Standard $0.023/GB, Glacier IR $0.004/GB), first 100 GB/month egress free | cents at low volume |
| Docker Hub, Gmail, SSM, Session Manager, budget, alarm | $0 |
| **Total** | **≈ $14/month** |

**Free tier:** on accounts created before 15 July 2025, the 12-month free tier covers 750 h/month of t2.micro, 30 GB of EBS and 750 h of public IPv4. That brings the bill to under $1/month for the first year. Newer accounts get the AWS Free plan's credits instead.

**Cheaper still (outside the free tier):** `instance_type = "t4g.micro"` (arm64, ~$6.10) or `"t3.micro"` (~$7.60) have the same 1 GiB of memory at a lower price than t2.micro. The stack builds images for the right architecture automatically.

**Why Gmail and not SES?** SES itself is cheap (about $0.10 per 1,000 emails). It requires DNS records and an AWS review before it can email arbitrary users, though, and you asked to avoid it. Gmail is free and needs neither. The trade-offs: about 500 recipients per day, and mail is sent *from* your Gmail address. Switching later means setting `smtp_host`, `smtp_username` and `smtp_password` to any other SMTP provider.

## One-time setup outside AWS

These need a human, once. Terraform does everything else.

1. **Docker Hub:** create an account (free), then go to *Account settings → Personal access tokens → Generate*. Choose **Read & Write** access. Terraform creates the private repository with this token, and the server pulls with it.
2. **Gmail (optional, for verification and reset emails):** turn on 2-Step Verification for the Google account, then create an app password at https://myaccount.google.com/apppasswords. Skip this and emails are only written to the server log. Signing up still works; the "verify your email" banner just can't be cleared.
3. **Domain:** best in Route 53 (zero manual steps). If it's elsewhere, set `route53_zone = ""` and create an A record pointing at the `public_ip` output at your DNS provider.

## Deploy

Prerequisites on your machine: AWS CLI (signed in), Terraform ≥ 1.10, Docker. For CI/CD you also need `gh auth login`.

```sh
make infra-bootstrap                           # once per AWS account (shared with the full stack)

cp infra/aws-lean/terraform.tfvars.example infra/aws-lean/terraform.tfvars
$EDITOR infra/aws-lean/terraform.tfvars        # domain, route53_zone, dockerhub_username, smtp_username…
export TF_VAR_dockerhub_token=dckr_pat_...     # secrets via env, never in the file
export TF_VAR_smtp_password=abcdabcdabcdabcd

make infra-init  STACK=aws-lean
make infra-apply STACK=aws-lean
```

`infra-apply` cross-compiles the images for the instance's architecture (about 2 minutes on Apple Silicon for x86), pushes them to your private Docker Hub repo, creates the AWS resources, boots the server and waits for the deploy. Caddy then gets the TLS certificate.

All other commands take the same `STACK=aws-lean` switch:

| Task | Command |
|---|---|
| Deploy the current checkout | `make deploy STACK=aws-lean` |
| Container status + memory | `make status STACK=aws-lean` |
| Recent logs | `make logs STACK=aws-lean` (last 200 lines; for live logs, `make ssh` then `cd /opt/pdfutils && docker compose logs -f`) |
| Shell | `make ssh STACK=aws-lean` |
| Backup now | `make backup STACK=aws-lean` |

Tip: put `STACK ?= aws-lean` at the top of the Makefile (or `export STACK=aws-lean` in your shell) if this is the only stack you use.

**CI/CD:** set `github_repository` and every push to `main` builds, pushes to Docker Hub and deploys. Terraform writes the repo variables and the `DOCKERHUB_TOKEN` secret.

## Backups (read this)

With `enable_db_backups = false` (the default) **nothing backs up the database**. If the data volume is lost, accounts and history are gone. User files in S3 are durable regardless.

Turning on `enable_db_backups = true` adds a nightly `pg_dump` to S3, kept 14 days, for a few cents a month. Strongly recommended once real users sign up. The setting applies at server creation, so with an existing server, run:

```sh
terraform -chdir=infra/aws-lean apply -replace=aws_instance.app
```

Replacing the server is safe: the database lives on the separate data volume. Restoring works the same as in [production.md](production.md#operations), using the single bucket's `backups/postgres/` prefix.

## Limits to keep in mind

- **CPU credits:** t2.micro earns enough credits for roughly 10% sustained CPU. Long bursts of heavy jobs (e.g. constant compression) use up the balance, and then the instance throttles to that baseline. This is never billed. The `make status` memory line and the EC2 console's *CPUCreditBalance* show where you stand. If it's chronic, move to `t4g.small` or the full stack.
- **One job at a time:** jobs queue rather than run in parallel. Users see "Waiting in line…" during bursts.
- **Logs** live only on the server, rotated at about 30 MB per container. Nothing is kept after the server is replaced.
- **Docker Hub pulls:** authenticated free accounts have hourly pull limits that are far above what deploys use.

## Moving to the full stack later

The two stacks don't share resources, so you can apply `infra/aws` and then migrate data:

1. `make backup STACK=aws-lean`.
2. Restore that dump into the new server.
3. Copy the files with `aws s3 sync s3://<lean bucket>/users/ s3://<full files bucket>/users/`.
4. Repoint the domain.
5. `terraform -chdir=infra/aws-lean destroy` (remove `prevent_destroy` on the data volume first).
