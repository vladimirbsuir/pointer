# 0027. Root in runner containers and a repository proxy

Status: Accepted. Amends [ADR 0016](0016-remote-execution-in-containers.md).

## Context

F7 ([GUI testing sandbox](../features/f7-gui-sandbox/README.md)) lets the agent run the application it works on inside the session container and drive it on a virtual display. Applications under test need system packages the image cannot predict (a library a build links, a database, a toolkit), so the agent has to install them with `apt-get`. [ADR 0016](0016-remote-execution-in-containers.md) runs the container as the non-root user `pointer` with every capability dropped, which makes that impossible.

Root inside the container changes what a secret in the container is worth. Every secret there becomes readable by the agent and by any instruction injected through a web page or a repository file. The one valuable secret was the user's GitHub token with the `repo` scope over all their repositories: it reached the runner in the arguments of `pointer.repo_prepare` and `pointer.repo_publish` and was passed to git through the environment. That was already weaker than ADR 0016 states, because git hooks inherit that environment and the runner's `PATH` starts with a directory the agent can write.

Containers created before a change keep their old configuration until they are removed, and a session container can live for weeks.

## Decision

- **Commands run as root with a reduced capability set (GUI-13).** The runner starts every container with `User: 0`, `CapDrop: ALL` and adds back `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `FSETID`, `SETUID`, `SETGID`, `KILL` and `NET_BIND_SERVICE`, the set `dpkg`, `apt-get`, `runuser` and ordinary process management need. `no-new-privileges` and Docker's default seccomp profile stay. The image installs `sudo` for scripts that call it and keeps the `pointer` user for programs that refuse to run as root. A system-wide `safe.directory '*'` lets git use checkouts that belong to uid 1000 in volumes created earlier.
- **Docker's init process is PID 1.** Containers run with `Init: true`, so processes orphaned by GUI applications, forking daemons and killed process groups are reaped instead of filling the process limit.
- **Shared memory is set for every container** (`pointer.container.shm-size`, 512 MB by default), because browsers and Electron fail with Docker's 64 MB.
- **The GitHub token never enters the container.** The backend serves a repository proxy for git's smart HTTP protocol under `/api/v1/runner/git/{owner}/{name}.git`. The runner clones from and pushes to it with its own short-lived access token as a Bearer header. The proxy accepts only a token whose device is the runner of a session whose workspace has that repository (anything else is `404`), adds the user's token as Basic authentication, and streams requests and answers in both directions without buffering. For `git-receive-pack` it reads the command list first (at most 64 KB, gunzipped when the body is gzip-encoded) and forwards the push only when every command updates `refs/heads/<session branch>` and none deletes it; otherwise nothing is forwarded and git receives a normal "remote rejected" report with the reason (or `403` when the client did not ask for a report). Signed pushes are refused. Redirects are not followed.
- **The runner keeps the header in one place.** On every token refresh the runner writes the header, scoped to the proxy URL, into `/etc/pointer/git-auth.conf`, which the image's system git configuration includes. Its own clone and push and the agent's `git fetch` in `run_command` use it alike; a runner started without that file passes the header in the environment of its own git processes only. `pointer.repo_prepare` resets `origin` to the proxy URL on every start, so checkouts cloned from GitHub directly before this change switch over.
- **Outdated containers are recreated.** Every container carries the label `dev.pointer.spec`, a hash of the image name, user, capabilities, security options, init, shared memory, process limit, memory, CPUs, network and the names of its environment values. When the backend starts a workspace whose container has another hash, or was created from another image ID than the configured image now resolves to, it removes the container and creates it again on the same volume. The checkout survives; installed packages and running processes do not, as after any restart.
- **The `gui` image target.** `infra/runner/Dockerfile` gets a target `gui` on top of `full` with Xvfb, openbox, xdotool, scrot, xclip, D-Bus, the accessibility bridge, x11vnc and websockify, fonts, Chromium with the `pointer-chromium` wrapper, GTK and WebKitGTK with the Tauri build prerequisites, Tk and the full Java runtime. A runner whose `PATH` has `Xvfb` and `xdotool` reports the capability `desktop` and performs the `desktop_*` operations ([03-protocol.md](../03-protocol.md) section 7.3).

## Consequences

- The agent can install system packages and run services inside the container, which makes GUI applications and their dependencies testable without a project-specific image.
- An injected instruction can do anything inside the container, as before, plus everything root can do there. It cannot read the GitHub token, push to another branch or another repository, delete the session branch or push tags. What it gets from the runner's credentials is what it already had: read access to the repository and writes to the session branch.
- Without user namespace remapping, root in the container is root on the host after a kernel escape. The dropped capabilities, `no-new-privileges` and the seccomp profile narrow that surface; remapping is a daemon-wide setting of the shared production host and is left as a later hardening step.
- Git traffic of server sessions passes through the backend. It streams and adds no buffering, but a large clone costs backend bandwidth and a thread for its duration.
- After deploying this change every existing session container is recreated on its next start; packages installed in it are lost once.

## Rejected alternatives

- **Keep the non-root user and preinstall more packages.** No image can predict what an application needs; the agent would fail on the first missing library.
- **Rootless tricks inside the container (fakeroot, proot).** They break `dpkg` maintainer scripts and services in ways that look like bugs in the application under test.
- **A GitHub App installation token scoped to one repository.** Narrower than the user's token, but still a secret in the container that allows writes to every branch, and it needs an App the user has to install. The proxy enforces the branch rule, which no GitHub token can express.
- **A credential helper that asks the backend for the user's token.** Simpler than a proxy, but hands the token to root in the container, which is exactly what has to stop.
