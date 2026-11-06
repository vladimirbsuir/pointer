#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./init-env.sh --domain DOMAIN [--runner slim|full] [--max-running N] [--bundled-proxy] [--force]

Creates .env next to compose.yml with generated secrets. An existing .env is kept unless --force is given.

  --domain DOMAIN    public name of the backend, for example 203-0-113-7.sslip.io
  --runner VARIANT   runner image: slim (git, Python, about 340 MB, default) or full (all toolchains, about 2.8 GB)
  --max-running N    session containers running at once (default 1)
  --bundled-proxy    start the bundled Caddy on ports 80 and 443 for hosts without a reverse proxy
  --force            overwrite an existing .env; the database password changes too
EOF
}

domain=""
runner=slim
max_running=1
bundled_proxy=false
force=false

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) domain="${2:?--domain needs a value}"; shift 2 ;;
    --runner) runner="${2:?--runner needs a value}"; shift 2 ;;
    --max-running) max_running="${2:?--max-running needs a value}"; shift 2 ;;
    --bundled-proxy) bundled_proxy=true; shift ;;
    --force) force=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")"

if [ -z "$domain" ]; then
  echo "Pass the public name with --domain." >&2
  exit 1
fi
case "$runner" in
  slim) runner_image=ghcr.io/vladimirbsuir/pointer-runner:slim; runner_cpus=1; runner_memory=512MB ;;
  full) runner_image=ghcr.io/vladimirbsuir/pointer-runner:latest; runner_cpus=2; runner_memory=4GB ;;
  *) echo "--runner is slim or full." >&2; exit 2 ;;
esac
if [ -f .env ] && [ "$force" != true ]; then
  echo ".env already exists; edit it or pass --force." >&2
  exit 1
fi

umask 077
{
  echo "POINTER_DOMAIN=$domain"
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
  echo "POINTER_JWT_SECRET=$(openssl rand -hex 48)"
  echo "POINTER_ENCRYPTION_KEY=$(openssl rand -base64 32)"
  echo "POINTER_GITHUB_CLIENT_ID="
  echo "POINTER_GITHUB_CLIENT_SECRET="
  echo "POINTER_TELEGRAM_BOT_TOKEN="
  echo "POINTER_CONTAINER_ENABLED=true"
  echo "POINTER_RUNNER_IMAGE=$runner_image"
  echo "POINTER_RUNNER_MAX_RUNNING=$max_running"
  echo "POINTER_RUNNER_CPUS=$runner_cpus"
  echo "POINTER_RUNNER_MEMORY=$runner_memory"
  if [ "$bundled_proxy" = true ]; then
    echo "COMPOSE_PROFILES=proxy"
  fi
} > .env

echo "Created $(pwd)/.env"
echo "Next: put the GitHub OAuth App credentials into POINTER_GITHUB_CLIENT_ID and POINTER_GITHUB_CLIENT_SECRET, then run ./deploy.sh"
