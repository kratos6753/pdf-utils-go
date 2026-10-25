#!/usr/bin/env bash
# Builds the api and web images and pushes them to one Docker Hub repository
# as api-TAG and web-TAG (skipping tags that already exist).
#   IMAGE_REPO (user/repo) TAG PLATFORM DOCKERHUB_USERNAME DOCKERHUB_TOKEN must be set.
set -euo pipefail
: "${IMAGE_REPO:?}" "${TAG:?}" "${PLATFORM:?}" "${DOCKERHUB_USERNAME:?}" "${DOCKERHUB_TOKEN:?}"
root=$(cd "$(dirname "$0")/../.." && pwd)

printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USERNAME" --password-stdin >/dev/null

build() { # component, context, dockerfile
  local ref="$IMAGE_REPO:$1-$TAG"
  if docker manifest inspect "$ref" >/dev/null 2>&1; then
    echo "$ref already on Docker Hub, skipping build"
    return
  fi
  echo "Building $ref for $PLATFORM"
  docker buildx build --platform "$PLATFORM" --provenance=false --push -t "$ref" -f "$3" "$2"
}

build api "$root/backend" "$root/backend/Dockerfile"
build web "$root" "$root/deploy/web.Dockerfile"
