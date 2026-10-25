# Run `make help` for the list of targets.
SHELL := /bin/bash
# GitHub provider credentials for CI/CD wiring (only used if github_repository is set).
export GITHUB_TOKEN ?= $(shell gh auth token 2>/dev/null)

.PHONY: help dev dev-down test e2e infra-bootstrap infra-init infra-plan infra-apply deploy ssh logs status backup

help: ## Show targets
	@grep -hE '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

# --- Development ---------------------------------------------------------------

dev: ## Run everything locally (infra in Docker + API + worker + web)
	scripts/dev.sh

dev-down: ## Stop local infra containers (data is kept)
	docker compose -f deploy/docker-compose.dev.yml down

test: ## Unit tests, lint and build
	cd backend && gofmt -l . | (! grep .) && go vet ./... && go test ./...
	cd frontend && npm run lint && npm run build

e2e: ## API + browser end-to-end tests (needs `make dev` running)
	API=http://localhost:5173 scripts/e2e.sh
	cd frontend && npx playwright test

# --- Production (AWS) ----------------------------------------------------------
# Two independent stacks; pick one with STACK (default: the full stack):
#   STACK=aws       full production (docs/production.md)
#   STACK=aws-lean  low-cost production (docs/production-lean.md)
STACK ?= aws
TF    := terraform -chdir=infra/$(STACK)
out    = $(shell $(TF) output -raw $(1) 2>/dev/null)
remote = INSTANCE_ID=$(call out,instance_id) REGION=$(call out,region) infra/scripts/run-remote.sh

infra-bootstrap: ## One time: create the Terraform state bucket (shared by both stacks)
	terraform -chdir=infra/bootstrap init -input=false
	terraform -chdir=infra/bootstrap apply

infra-init: ## Initialize Terraform for STACK against the remote state
	$(TF) init -input=false -backend-config=backend.hcl

infra-plan: ## Show what infra changes would be applied
	$(TF) plan

infra-apply: ## Create/update all resources for STACK and deploy the current checkout
	$(TF) apply

deploy: ## Build images from this checkout and roll them out (no infra changes)
	@set -euo pipefail; \
	tag="git-$$(git rev-parse --short=12 HEAD 2>/dev/null || echo none)"; \
	if [ -n "$$(git status --porcelain 2>/dev/null)" ]; then tag="$$tag-dirty-$$(date +%s)"; fi; \
	export TAG="$$tag" REGION=$(call out,region) INSTANCE_ID=$(call out,instance_id) SSM_PREFIX=$(call out,ssm_prefix); \
	if [ "$(STACK)" = "aws-lean" ]; then \
	  export IMAGE_REPO=$(call out,image_repo) PLATFORM=$(call out,image_platform); \
	  export DOCKERHUB_USERNAME=$$(aws ssm get-parameter --region "$$REGION" --name "$$SSM_PREFIX/registry/username" --query Parameter.Value --output text); \
	  export DOCKERHUB_TOKEN=$$(aws ssm get-parameter --region "$$REGION" --name "$$SSM_PREFIX/registry/token" --with-decryption --query Parameter.Value --output text); \
	  infra/scripts/build-and-push-dockerhub.sh; \
	else \
	  export REGISTRY=$(call out,ecr_registry) PROJECT=$(call out,project); \
	  export PLATFORM=$$(aws ec2 describe-instances --region "$$REGION" --instance-ids "$$INSTANCE_ID" \
	         --query 'Reservations[0].Instances[0].Architecture' --output text | sed 's/^x86_64$$/amd64/; s/^/linux\//'); \
	  infra/scripts/build-and-push.sh; \
	fi; \
	infra/scripts/deploy-remote.sh

ssh: ## Shell on the server via SSM (needs session-manager-plugin)
	aws ssm start-session --region $(call out,region) --target $(call out,instance_id)

logs: ## App logs (full stack: live from CloudWatch; lean: last 200 lines per container)
	@if [ "$(STACK)" = "aws-lean" ]; then \
	  $(remote) "cd /opt/pdfutils && docker compose logs --no-color --tail 200"; \
	else \
	  aws logs tail $(call out,log_group) --region $(call out,region) --follow --format short; \
	fi

status: ## Show running containers and memory on the server
	@$(remote) "cd /opt/pdfutils && docker compose ps && free -m"

backup: ## Take a Postgres backup to S3 now
	@$(remote) "/opt/pdfutils/backup.sh"
