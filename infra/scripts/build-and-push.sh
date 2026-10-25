#!/usr/bin/env bash
# Builds the api and web images and pushes them to ECR (skipping tags that
# already exist). Used by Terraform, `make deploy` and CI.
#   REGISTRY PROJECT TAG PLATFORM REGION must be set.
set -euo pipefail
: "${REGISTRY:?}" "${PROJECT:?}" "${TAG:?}" "${PLATFORM:?}" "${REGION:?}"
root=$(cd "$(dirname "$0")/../.." && pwd)

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null

build() { # name, context, dockerfile
  local repo="$PROJECT/$1"
  if aws ecr describe-images --region "$REGION" --repository-name "$repo" --image-ids imageTag="$TAG" >/dev/null 2>&1; then
    echo "$repo:$TAG already in ECR, skipping build"
    return
  fi
  echo "Building $repo:$TAG for $PLATFORM"
  docker buildx build --platform "$PLATFORM" --provenance=false --push \
    -t "$REGISTRY/$repo:$TAG" -f "$3" "$2"
}

build api "$root/backend" "$root/backend/Dockerfile"
build web "$root" "$root/deploy/web.Dockerfile"
