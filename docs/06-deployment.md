# Pointer: Deployment

How to run the backend on a Linux server with TLS and remote execution.
The design is described in [ADR 0017](adr/0017-server-deployment.md); this page is the procedure.

## 1. What runs where

| Piece | Where it comes from |
|---|---|
| `infra/deploy/compose.yml` | PostgreSQL with pgvector, the backend, the Docker socket proxy, optional Caddy |
| `ghcr.io/vladimirbsuir/pointer-backend:latest` | built by the `Images` workflow on every push to `main` |
| `ghcr.io/vladimirbsuir/pointer-runner:slim` / `:latest` | the same workflow; `slim` has git and Python, `latest` all toolchains |
| `infra/deploy/.env` | created on the server by `init-env.sh`, never committed |

Scripts in `infra/deploy`:

| Script | Runs as | Purpose |
|---|---|---|
| `host-setup.sh` | root, once | installs Docker, adds the deploy user to the `docker` group, adds a site to a system Caddy, optional small-server tuning |
| `init-env.sh` | deploy user, once | creates `.env` with generated secrets |
| `deploy.sh` | deploy user | `up`, `stop`, `status`, `logs`, `backup`; first strips Windows line endings and byte order marks from `.env` |

All scripts print their options with `--help` and are safe to run again.

## 2. Requirements

- Ubuntu 22.04+ or Debian 12+ with `git`, `curl`, `openssl`, a user with `sudo`.
- At least 1 vCPU, 1 GB of RAM with swap and 3 GB of free disk for the slim runner. The full runner needs about 3 GB more disk and 4 GB of RAM per running session.
- A DNS name pointing at the server. Without an own domain, `<ip-with-dashes>.sslip.io` resolves to the address, for example `46-183-21-5.sslip.io`.
- Ports 80 and 443 open for the certificate and HTTPS.
- A GitHub OAuth App with the callback URL `http://127.0.0.1/callback`; the one used for local development works, since the callback is a loopback address.

## 3. First deployment

On the server:

```bash
git clone --depth 1 https://github.com/vladimirbsuir/pointer.git ~/pointer
cd ~/pointer/infra/deploy
```

Prepare the host. With a system Caddy already serving other sites:

```bash
sudo ./host-setup.sh --user "$USER" --domain 46-183-21-5.sslip.io --small-server
```

Without `--small-server` on servers with more than 2 GB of RAM. Log out and in again so that the `docker` group applies.

Create the settings. Add `--bundled-proxy` when the host has no reverse proxy of its own; the bundled Caddy then takes ports 80 and 443:

```bash
./init-env.sh --domain 46-183-21-5.sslip.io --runner slim --max-running 1
nano .env
```

In `.env`, fill `POINTER_GITHUB_CLIENT_ID` and `POINTER_GITHUB_CLIENT_SECRET`. `POINTER_TELEGRAM_BOT_TOKEN` is optional; a bot can be polled by one backend only, so a token used by a local backend must not be reused here.

Start:

```bash
./deploy.sh
```

The script pulls the images, starts the stack and waits until `/actuator/health` is `UP`. The backend needs about 25 seconds on 1 vCPU.

## 4. Desktop app

The backend address is compiled into the desktop app. Build the installer against the server:

```powershell
cd desktop
$env:POINTER_API_URL = "https://46-183-21-5.sslip.io"
pnpm tauri build
```

The installer is in `desktop/src-tauri/target/release/bundle/`.

## 5. Operations

| Task | Command in `~/pointer/infra/deploy` |
|---|---|
| Update to the latest images and scripts | `git pull && ./deploy.sh` |
| Stop (data volumes stay) | `./deploy.sh stop` |
| State, memory, disk | `./deploy.sh status` |
| Backend log | `./deploy.sh logs` |
| Database dump to `backups/` | `./deploy.sh backup` |
| Restore a dump | `gunzip -c backups/<file>.sql.gz \| docker compose exec -T postgres psql -U pointer pointer` |
| Remove everything including data | `./deploy.sh stop && docker volume rm pointer-server_postgres-data` and `docker volume ls -q --filter label=dev.pointer.session \| xargs -r docker volume rm` |

`.env` holds the encryption key of provider API keys and GitHub tokens. Losing it makes stored keys unreadable; keep a copy of it with the backups.

## 6. Checks after a deployment

```bash
curl -s https://46-183-21-5.sslip.io/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://46-183-21-5.sslip.io/ws
```

The first prints `"status":"UP"`, the second `401` (the WebSocket reaches the backend, which asks for a token).

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `502` from the proxy | The backend is stopped or still starting: `./deploy.sh status`, `./deploy.sh logs`. |
| `permission denied ... docker.sock` | The `docker` group needs a new login session after `host-setup.sh`. |
| A server session stays `queued` | The container limit is reached by busy sessions; raise `POINTER_RUNNER_MAX_RUNNING` if memory allows, then `./deploy.sh`. |
| `The runner image ... is not available` | `POINTER_RUNNER_IMAGE` was not pulled; `./deploy.sh` pulls it. |
