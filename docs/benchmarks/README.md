# Benchmarks

Measurements for the thesis. Each page describes its setup, results and threats to validity; the scripts live in [`infra/bench/`](../../infra/bench/).

| Page | Feature | Script |
|---|---|---|
| [rag.md](rag.md) | Hybrid code search ([F2](../features/f2-hybrid-search.md)) | `infra/bench/rag/` |
| [memory.md](memory.md) | Agent memory ([F4](../features/f4-agent-memory.md)) | `infra/bench/memory/run.py` |
| [routing.md](routing.md) | Model routing ([F3](../features/f3-model-routing.md)) | `infra/bench/routing/run.py` |
| [cache.md](cache.md) | Cache-aware context ([F5](../features/f5-cache-aware-context.md)) | `infra/bench/cache/run.py` |
| [escalation.md](escalation.md) | Trajectory-aware escalation ([F6](../features/f6-trajectory-escalation.md)) | `infra/bench/escalation/` |
| [desktop.md](desktop.md) | GUI testing sandbox ([F7](../features/f7-gui-sandbox/README.md)) | `infra/bench/desktop/` |

## Running the agent benchmarks locally

The agent benchmarks drive real sessions through the REST API, so they need the backend, an account with model providers and an executor device that runs the tools on this repository. Without the desktop app the device is the headless [`infra/bench/device`](../../infra/bench/device/) binary: it signs in as the benchmark account, announces the project and executes every `exec.request` with the shared `pointer-executor` crate, exactly like the desktop core.

1. Start the stack and the backend. An empty Telegram token keeps a local run from polling the bot:

   ```bash
   docker compose -f infra/docker-compose.yml up -d
   cd backend && POINTER_TELEGRAM_BOT_TOKEN= ./gradlew bootRun
   ```

   Backend properties for an experiment are passed as arguments, for example `./gradlew bootRun --args='--pointer.context.mask-block=1'`.

2. Create a benchmark account once. It registers a user with a project and stores the credentials in `infra/bench/account.local.json`, which git ignores. Providers with their encrypted keys can be copied from another local account, so that no key has to be entered again:

   ```bash
   python infra/bench/account.py create --copy-providers-from <local account email>
   ```

   Without `--copy-providers-from`, add providers through the API or the desktop app.

3. Load the account into the shell and start the device:

   ```bash
   eval "$(python infra/bench/account.py env)"
   cargo run --release --manifest-path infra/bench/device/Cargo.toml
   ```

   In PowerShell use `python infra/bench/account.py env --shell powershell | Invoke-Expression`. The device prints `bench-device: connected` and reconnects on its own when the backend restarts. It executes in `POINTER_WORKSPACE`, by default the root of this repository, with no worktree.

4. Run a benchmark from a second shell with the same environment, for example:

   ```bash
   eval "$(python infra/bench/account.py env)"
   python infra/bench/cache/run.py --model <model key> --clear-memories --label nomem
   ```

   Sessions are created in Ask mode, so the agent cannot change files; the scripts save their raw results in a git-ignored `results/` folder next to them.

## Notes

- The same model key can exist at several providers, one of them paid (TokenHarbor lists `deepseek-v4.1-flash` next to `deepseek-v4.1-flash:free`, and Experiential Labs has `deepseek-v4.1-flash` too). The cache script refuses an ambiguous key and asks for `--provider <name>`.
- Free provider plans have request and token limits per minute or day; a run that hits one shows failed turns with the provider's message in `turns.error`. The scripts report failed turns instead of retrying them.
- Memory benchmarks need an embedding model in the account settings (`--embedding-model <key>` in the cache script sets it); the backend calls it directly, so it must not be marked as a local provider.
- The benchmark account is a normal account: its sessions appear in the desktop app when you sign in with it, which is the quickest way to look at a run.

## Live check of the desktop app

Checks of the real Tauri app that tests cannot cover (reconnects, close codes, dropped events, command execution on Windows) use the proxy, fake model and WebView driver in [`infra/bench/live/`](../../infra/bench/live/README.md).
