#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: sudo ./host-setup.sh --user NAME [--domain DOMAIN] [--small-server]

Prepares an Ubuntu or Debian host for the Pointer stack. Safe to run again.

  --user NAME       account that runs deploy.sh; added to the docker group
  --domain DOMAIN   adds a site block for DOMAIN to an installed system Caddy
                    (without a system Caddy, use the bundled one: init-env.sh --bundled-proxy)
  --small-server    tuning for about 1 GB of RAM: 2 GB swap file if there is no swap,
                    swappiness 60, journal limited to 50 MB, apt cache and unused snapd removed,
                    services that a VPS does not need disabled
EOF
}

deploy_user=""
domain=""
small_server=false

while [ $# -gt 0 ]; do
  case "$1" in
    --user) deploy_user="${2:?--user needs a value}"; shift 2 ;;
    --domain) domain="${2:?--domain needs a value}"; shift 2 ;;
    --small-server) small_server=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root, for example with sudo." >&2
  exit 1
fi
if [ -z "$deploy_user" ] || ! id "$deploy_user" >/dev/null 2>&1; then
  echo "Pass an existing account with --user." >&2
  exit 1
fi

step() {
  printf '\n==> %s\n' "$1"
}

install_docker() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    echo "Docker $(docker version --format '{{.Server.Version}}') with Compose is already installed"
    return
  fi
  . /etc/os-release
  apt-get update
  apt-get install -y ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
}

configure_docker_logs() {
  local config=/etc/docker/daemon.json
  if [ -f "$config" ]; then
    echo "$config exists, left unchanged"
    return
  fi
  mkdir -p /etc/docker
  printf '{\n  "log-driver": "json-file",\n  "log-opts": {"max-size": "10m", "max-file": "3"}\n}\n' > "$config"
  systemctl restart docker
}

add_caddy_site() {
  local caddyfile=/etc/caddy/Caddyfile
  if ! command -v caddy >/dev/null 2>&1 || [ ! -f "$caddyfile" ]; then
    echo "No system Caddy found. Use the bundled proxy: ./init-env.sh --bundled-proxy"
    return
  fi
  if grep -q "^${domain} {" "$caddyfile"; then
    echo "$domain is already in $caddyfile"
    return
  fi
  cp "$caddyfile" "${caddyfile}.bak.$(date +%Y%m%d%H%M%S)"
  printf '\n%s {\n\treverse_proxy 127.0.0.1:8080\n}\n' "$domain" >> "$caddyfile"
  caddy validate --config "$caddyfile" --adapter caddyfile
  systemctl reload caddy
}

ensure_swap() {
  if [ -n "$(swapon --show --noheadings)" ]; then
    echo "Swap is already enabled"
    return
  fi
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
}

tune_small_server() {
  ensure_swap
  echo 'vm.swappiness=60' > /etc/sysctl.d/99-pointer-swappiness.conf
  sysctl --system >/dev/null
  mkdir -p /etc/systemd/journald.conf.d
  printf '[Journal]\nSystemMaxUse=50M\n' > /etc/systemd/journald.conf.d/pointer-size.conf
  systemctl restart systemd-journald
  journalctl --vacuum-size=50M >/dev/null
  apt-get clean
  if command -v snap >/dev/null 2>&1 && [ "$(snap list 2>/dev/null | wc -l)" -le 1 ]; then
    apt-get purge -y snapd
  fi
  apt-get autoremove --purge -y
  for unit in multipathd.service multipathd.socket fwupd.service ModemManager.service udisks2.service packagekit.service; do
    if systemctl list-unit-files "$unit" --no-legend 2>/dev/null | grep -q .; then
      systemctl disable --now "$unit" >/dev/null 2>&1 || true
      echo "Disabled $unit"
    fi
  done
}

step "Docker"
install_docker
configure_docker_logs

step "Access for $deploy_user"
usermod -aG docker "$deploy_user"
echo "$deploy_user is in the docker group; a new login session picks it up"

if [ -n "$domain" ]; then
  step "Reverse proxy for $domain"
  add_caddy_site
fi

if [ "$small_server" = true ]; then
  step "Small server tuning"
  tune_small_server
fi

step "Done"
df -h / | tail -1
free -m | head -2
