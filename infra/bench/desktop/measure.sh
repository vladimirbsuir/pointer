#!/usr/bin/env bash
set -euo pipefail

image="${1:-pointer-runner:gui}"
bench="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
  bench="$(cygpath -w "$bench")"
fi

echo "image_size_mb=$(docker image inspect "$image" --format '{{.Size}}' | awk '{ printf "%.0f", $1 / 1048576 }')"
MSYS_NO_PATHCONV=1 docker run --rm --init --user 0 --shm-size 512m --cpus 2 --memory 4g --pids-limit 4096   -v "$bench:/bench:ro" --entrypoint bash "$image" /bench/measure-inner.sh
