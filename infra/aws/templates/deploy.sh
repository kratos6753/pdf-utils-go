#!/bin/bash
# Runs on the instance: refresh .env from SSM, fetch the compose file, pull
# images and (re)start the stack. Idempotent; concurrent runs are serialized.
set -euo pipefail
exec 9>/var/lock/pdfutils-deploy.lock
flock 9

source /etc/pdfutils.conf
export AWS_DEFAULT_REGION=$REGION
cd /opt/pdfutils

env_tmp=$(mktemp)
trap 'rm -f "$env_tmp"' EXIT
aws ssm get-parameters-by-path --path "$SSM_PREFIX/env/" --recursive --with-decryption \
  --query 'Parameters[].[Name,Value]' --output text |
  while IFS=$'\t' read -r name value; do printf '%s=%s\n' "${name##*/}" "$value"; done >"$env_tmp"
tag=$(aws ssm get-parameter --name "$SSM_PREFIX/image_tag" --query Parameter.Value --output text)
echo "IMAGE_TAG=$tag" >>"$env_tmp"
install -m 600 "$env_tmp" .env

aws s3 cp "s3://$OPS_BUCKET/deploy/docker-compose.yml" docker-compose.yml --only-show-errors

registry=$(sed -n 's/^REGISTRY=//p' .env)
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null

docker compose pull --quiet
docker compose up -d --remove-orphans --wait --wait-timeout 300
docker image prune -af --filter "until=168h" >/dev/null || true
echo "Deployed $tag"
