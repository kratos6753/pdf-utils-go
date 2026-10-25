#!/bin/bash
# Runs on the instance: refresh .env from SSM, fetch the compose file, pull
# images from Docker Hub and (re)start the stack. Idempotent and serialized.
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

aws s3 cp "s3://$BUCKET/deploy/docker-compose.yml" docker-compose.yml --only-show-errors

user=$(aws ssm get-parameter --name "$SSM_PREFIX/registry/username" --query Parameter.Value --output text)
aws ssm get-parameter --name "$SSM_PREFIX/registry/token" --with-decryption --query Parameter.Value --output text |
  docker login --username "$user" --password-stdin >/dev/null

docker compose pull --quiet
docker compose up -d --remove-orphans --wait --wait-timeout 600
docker image prune -af >/dev/null || true # small disk: keep only images in use
echo "Deployed $tag"
