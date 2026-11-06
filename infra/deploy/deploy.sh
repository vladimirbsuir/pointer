#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./deploy.sh [up|stop|status|logs|backup]

  up       pull the images, including the runner image, and start or update the stack (default)
  stop     stop the stack and remove session containers; data volumes are kept
  status   containers, health, memory and disk
  logs     follow the backend log
  backup   write a compressed database dump to backups/
EOF
}

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "No .env here; create it with ./init-env.sh." >&2
  exit 1
fi
sed -i 's/\r$//; s/\xEF\xBB\xBF//g' .env
set -a
. ./.env
set +a

remove_session_containers() {
  docker ps -aq --filter label=dev.pointer.session | xargs -r docker rm -f >/dev/null
}

wait_until_healthy() {
  for _ in $(seq 1 80); do
    if curl -fs http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q UP; then
      echo "Backend is up: https://${POINTER_DOMAIN}"
      return
    fi
    sleep 3
  done
  echo "Backend did not become healthy; see ./deploy.sh logs" >&2
  exit 1
}

case "${1:-up}" in
  up)
    docker compose pull
    docker pull "$POINTER_RUNNER_IMAGE"
    docker compose up -d --remove-orphans
    docker image prune -f >/dev/null
    wait_until_healthy
    ;;
  stop)
    remove_session_containers
    docker compose down
    ;;
  status)
    docker compose ps
    docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.CPUPerc}}'
    free -m | head -2
    df -h / | tail -1
    ;;
  logs)
    docker compose logs -f --tail 200 backend
    ;;
  backup)
    mkdir -p backups
    file="backups/pointer-$(date +%Y%m%d-%H%M%S).sql.gz"
    docker compose exec -T postgres pg_dump -U pointer pointer | gzip > "$file"
    echo "Wrote $file"
    ;;
  -h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
