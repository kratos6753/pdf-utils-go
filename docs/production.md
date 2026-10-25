# Production setup (AWS, fully managed by Terraform)

> Looking to minimize cost? See the [lean stack](production-lean.md) (t2.micro, Docker Hub, Gmail SMTP, about $14/month).

Every AWS resource is defined in `infra/`. A single `make infra-apply` builds the app images from your checkout and creates and deploys everything. You don't need to click anything in the AWS console.

## What gets created

```
                     Route 53 (A, CAA, DKIM, SPF, DMARC records)
                                    │
 users / mobile apps ──HTTPS──▶ Elastic IP ──▶ EC2 t4g.medium (Amazon Linux 2023, Docker)
                                                ├─ Caddy: TLS (Let's Encrypt), web app, /v1 proxy
                                                ├─ API ─┬─ Postgres ┐  on an encrypted EBS data volume
                                                ├─ 2× worker        ├─ Redis    ┘  (daily snapshots)
                                                │       └────────── S3 files bucket (lifecycle → Glacier IR)
                                                └─ logs ──▶ CloudWatch Logs
 SES (verification and reset email) · ECR (images) · SSM Parameter Store (config and secrets)
 S3 ops bucket (deploy files, nightly pg_dump backups) · optional GitHub Actions OIDC deploy role
```

| Area | Resources |
|---|---|
| Network | VPC, public subnet, internet gateway, S3 gateway endpoint, security group (80/443 only; **no SSH**) |
| Compute | EC2 instance with IMDSv2 and an instance role (no keys on disk), Elastic IP, encrypted gp3 data volume, auto-recover/reboot alarms |
| Storage | Files bucket (private, encrypted, CORS for the domain). `tmp/` (anonymous) expires after 1 day; `users/` (history) moves to **Glacier Instant Retrieval** after 30 days. Ops bucket holds deploy files and 30 days of DB backups |
| Backups | Nightly `pg_dump` to S3 (systemd timer) and daily EBS snapshots kept for 7 days (DLM) |
| Config | Generated JWT secret and DB password, SMTP credentials and app settings in SSM Parameter Store (secrets as SecureString) |
| Email | SES domain identity with DKIM, custom MAIL FROM (SPF) and DMARC records. A production-access request is filed automatically |
| Images | ECR repos `pdfutils/api` and `pdfutils/web` (immutable tags, scan on push, keep the last 15) |
| DNS | A record → Elastic IP, CAA restricting certificates to Let's Encrypt; optionally the hosted zone itself |
| CI/CD (optional) | GitHub OIDC provider and deploy role, plus repository variables used by `.github/workflows/ci.yml` |
| Cost guard (optional) | Monthly AWS budget with email alerts |

**Approximate cost** (us-east-1, light traffic): EC2 t4g.medium ~$25, EBS 60 GB ~$5, public IPv4 ~$3.60, snapshots, S3, Route 53 and CloudWatch ~$3–5. About **$35–40/month**, plus S3 storage and transfer as usage grows.

## Prerequisites (on the machine you deploy from)

- **AWS CLI v2** signed in to the target account with admin rights (`aws configure` or `aws sso login`). Check with `aws sts get-caller-identity`.
- **Terraform ≥ 1.10**: `brew install terraform`
- **Docker** (images are built locally on the first apply)
- **GitHub CLI** signed in (`gh auth login`), only if you want CI/CD
- **Session Manager plugin**, only for `make ssh`: `brew install --cask session-manager-plugin`
- **A domain.** The easiest setup is one registered in (or moved to) **Route 53**: then nothing at all is manual. If your domain lives at another registrar, set `create_route53_zone = true` and copy the four `name_servers` from the Terraform output to your registrar (a one-time step there).

## First deployment

```sh
# 1. One time: create the encrypted, versioned S3 bucket that holds Terraform state.
#    (Writes infra/aws/backend.hcl for the next step.)
make infra-bootstrap

# 2. Configure.
cp infra/aws/terraform.tfvars.example infra/aws/terraform.tfvars
$EDITOR infra/aws/terraform.tfvars          # domain, route53_zone, alert_email, github_repository…

# 3. Create everything and deploy the current checkout.
make infra-init
make infra-apply                             # review the plan, type "yes"
```

`make infra-apply` builds and pushes the images, creates the infrastructure, boots the server (which installs Docker, mounts the data volume, and pulls config and images), and waits until the deploy reports success. It takes about 10 minutes the first time. Caddy then gets a TLS certificate as soon as DNS resolves, which is usually immediate with Route 53.

Check it:

```sh
terraform -chdir=infra/aws output url        # → https://your-domain
make status                                  # containers on the server
make logs                                    # live app logs
```

## Deploying changes

Pick one:

- **Push to `main`** (if `github_repository` is set). CI runs the tests, then the `deploy` job assumes the AWS role via OIDC (no stored keys), builds the images, and rolls them out.
- **`make deploy`** from your machine builds images tagged with the git commit and rolls them out. It changes no infrastructure.
- **`make infra-apply`** applies infrastructure or config changes. It also redeploys if the app source or any setting changed.

A deploy pulls the new images and restarts containers with `docker compose up --wait`. Database migrations run automatically when the API starts. Expect a few seconds of downtime per deploy, which is fine for a single-server setup.

**Rolling back:** run `git checkout <good-commit> && make deploy`. Old images stay in ECR (the last 15 are kept).

## Changing configuration

All app settings are Terraform variables (see `infra/aws/variables.tf`). Edit `terraform.tfvars` and run `make infra-apply`. Terraform updates SSM Parameter Store and redeploys automatically.

Pass secrets as environment variables instead of writing them to the file:

```sh
export TF_VAR_google_client_secret='…'
make infra-apply
```

Plan limits (tasks per hour, file size, storage) live in the database `plans` table, not in Terraform. See the main README.

## Google sign-in (optional)

Google doesn't provide an API to create standard OAuth clients, so this is the one step done in a web console:

1. Go to https://console.cloud.google.com/apis/credentials, then **Create credentials → OAuth client ID → Web application**.
2. Under **Authorized redirect URIs**, add the value of `terraform -chdir=infra/aws output google_redirect_uri`.
3. Put the client id in `terraform.tfvars` (`google_client_id`), export `TF_VAR_google_client_secret`, then run `make infra-apply`.
4. For the mobile apps, create iOS and Android OAuth clients and add their ids to `google_mobile_client_ids`.

Until this is set, the app simply hides the Google button.

## Email (SES)

Terraform verifies the domain (DKIM, SPF and DMARC records go into Route 53) and files the request to leave the SES sandbox. **AWS reviews that request, usually within 24 hours.** Until it's approved, SES only delivers to verified addresses, so new users won't receive verification emails. Check the status with:

```sh
aws sesv2 get-account --query '{production:ProductionAccessEnabled,review:Details.ReviewDetails.Status}'
```

## Operations

| Task | Command |
|---|---|
| Shell on the server | `make ssh` (via SSM; no SSH keys or open port 22) |
| Tail logs | `make logs` (CloudWatch group `/pdfutils/prod/app`, 30-day retention) |
| Container status | `make status` |
| Back up the DB now | `make backup` (nightly at 03:00 UTC otherwise) |
| List backups | `aws s3 ls s3://$(terraform -chdir=infra/aws output -raw ops_bucket)/backups/postgres/ --recursive` |

**Restoring the database** from a `pg_dump` backup:

```sh
make ssh
sudo -i
cd /opt/pdfutils
source /etc/pdfutils.conf
aws s3 cp s3://$OPS_BUCKET/backups/postgres/<path>.dump /tmp/db.dump
docker compose stop api worker
docker compose exec -T postgres pg_restore -U pdfutils -d pdfutils --clean --if-exists < /tmp/db.dump
docker compose start api worker
```

To recover the whole volume instead, create a volume from one of the daily EBS snapshots (tagged `pdfutils-prod-data`).

**Server replacement** is safe: Postgres, Redis, TLS certificates and Docker data all live on the separate data volume. That volume has `prevent_destroy` set, so Terraform refuses to delete it by accident. The root disk is disposable.

**Scaling up:** change `instance_type` (e.g. `t4g.large`) or `worker_replicas` / `worker_concurrency`, then run `make infra-apply`. If you outgrow one server, the next step is moving Postgres to RDS and Redis to ElastiCache, then running API and workers on ECS. The app needs no code changes for that, only different `DATABASE_URL` and `REDIS_ADDR` values.

## Tearing down

```sh
# The data volume is protected. Remove `prevent_destroy` in infra/aws/compute.tf first
# if you really want to delete the database.
terraform -chdir=infra/aws destroy
```

S3 buckets that still contain objects must be emptied first. The state bucket from `infra-bootstrap` is also protected. Delete it by hand if you are completely done.

## Non-AWS alternative

`deploy/docker-compose.yml` runs the same stack on any VPS with Docker, using S3 or Cloudflare R2 for storage. It's configured by hand through `deploy/.env`. See the comments in that file.
