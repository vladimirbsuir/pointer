# 0017. Server deployment

Status: Accepted.

## Context

M7 moves the backend from the developer's machine to a cloud server with TLS, including remote execution ([ADR 0016](0016-remote-execution-in-containers.md)).
The target server is a small VPS: 1 vCPU, about 1 GB of RAM with a 2 GB swap file, under 9 GB of disk, Ubuntu 24.04.
It already runs other services behind a system Caddy that owns ports 80 and 443.
The backend is a single instance ([02-architecture.md](../02-architecture.md#42-agent-loop)) and starts session containers through the Docker Engine API.

Open questions were how the backend is packaged and delivered, how TLS is terminated, how much of the Docker API the backend gets, how runner containers are networked, and how many containers such a server can run.

## Decision

- **Docker Compose on one host.** `infra/deploy/compose.yml` runs PostgreSQL with pgvector, the backend, a Docker socket proxy and, in the optional `proxy` profile, Caddy. Secrets and settings live in `infra/deploy/.env` on the server, created from `.env.example`; the JWT secret, the encryption key and the database password are generated on the server.
- **Images from GHCR.** The `Images` workflow builds `pointer-backend` and two variants of `pointer-runner` on every push to `main` that touches them, and publishes them to `ghcr.io/<owner>/` with the tags `latest` (backend and the full runner), `slim` (the small runner) and the commit SHA. The server only pulls; `deploy.sh` pulls the images and recreates the stack. Building on the server is out of the question with 1 vCPU and the Rust build of the runner.
- **TLS at a reverse proxy.** The backend listens on plain HTTP bound to `127.0.0.1:8080`. On a host that already has a reverse proxy, a site block forwards the domain to that port; on a clean host the `proxy` profile starts Caddy, which gets a Let's Encrypt certificate for `POINTER_DOMAIN`. WebSocket upgrades pass through without extra configuration. Without an own domain, an `sslip.io` name that resolves to the server address is used.
- **Docker access through a socket proxy.** The backend never mounts the Docker socket. It talks to `tecnativa/docker-socket-proxy` over `tcp://docker-proxy:2375` on an internal network. The proxy allows only `/_ping`, `/version`, `/containers` and `/volumes`; images, networks, exec, build, swarm and system calls are denied.
- **A separate network for runners.** Session containers join the `pointer-runners` bridge network. On it they reach only the backend (as `http://backend:8080`) and the internet for git and package downloads. PostgreSQL and the socket proxy are on internal networks that runners are not attached to.
- **A limit on running containers.** `POINTER_RUNNER_MAX_RUNNING` caps the containers that are starting, cloning or ready. A session that needs a container when the limit is reached first stops the least recently active container whose session is idle; if all of them are busy, its workspace waits in the new `queued` status and retries every two seconds until the prepare timeout, then fails with a message. Per-container CPU and memory come from `POINTER_RUNNER_CPUS` and `POINTER_RUNNER_MEMORY`.
- **A slim runner for small servers.** The runner Dockerfile has two targets: `slim` (git, Python, ripgrep, about 340 MB) and `full` (adds a C toolchain, Node.js, a JDK, Go and Rust, about 2.8 GB). A server chooses one with `POINTER_RUNNER_IMAGE`.
- **Sizing for the VPS.** The backend runs with the serial collector, C1 only and a 256 MB heap; PostgreSQL with small buffers; one runner at a time with one CPU and 512 MB of memory. Container logs are rotated by Docker (`json-file`, 10 MB).

## Consequences

- The backend is reachable over HTTPS and WSS; the desktop app is built with `POINTER_API_URL` pointing at the server.
- A fresh server needs root only once: install Docker, add the deploy user to the `docker` group and configure the reverse proxy. Everything else runs as that user.
- The socket proxy filters paths, not request bodies: a compromised backend could still create a privileged container. The proxy narrows the API surface; the backend remains trusted.
- On the reference VPS only one session runs on the server at a time, and agent commands that need a toolchain missing in the slim image install it in the session. A larger server raises the limit and switches to the full image.
- Runner containers on the same network can reach each other; with one container at a time this does not matter, and inter-container isolation can be added with a network per session.

## Rejected alternatives

- **Mounting the Docker socket into the backend.** Gives the backend full root-equivalent control of the host.
- **Docker-in-Docker or rootless Docker.** More isolation, but heavier on a 1 GB server and, for rootless Docker on Ubuntu 24.04, an AppArmor change anyway.
- **Building images on the server.** The Rust build of the runner does not fit 1 vCPU and 1 GB.
- **Rejecting sessions when the limit is reached.** Stopping an idle container first serves the common case of one active user with several old sessions.
- **Kubernetes or a managed container service.** Out of proportion for a single-instance backend and one server.
