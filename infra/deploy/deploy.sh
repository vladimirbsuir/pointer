#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
set -a
. ./.env
set +a
docker compose pull
docker pull "${POINTER_RUNNER_IMAGE}"
docker compose up -d --remove-orphans
docker image prune -f
