# Live check of the desktop app

Scripts that drive the real Tauri app against a local backend, for checks that unit and integration tests cannot cover: reconnects, close codes, dropped events and command execution on Windows. They were used for the live check of 2026-11-26.

| File | Purpose |
|---|---|
| `proxy.py` | TCP proxy on `127.0.0.1:8081` in front of the backend on `8080`. It passes HTTP through, parses WebSocket frames and logs them, and is controlled over HTTP on `8082`. |
| `fake_llm.py` | OpenAI-compatible streaming server on `127.0.0.1:18090`. A user message `RUN {json}` makes it call `run_command` with those arguments, a message containing `LONG` streams a slow 80-word answer, anything else gets a short answer. |
| `account.py` | Registers a fresh test account with the fake provider and a project, and stores it in `account.local.json` (ignored by git). |
| `cdp.py` | Runs JavaScript in the app's WebView over the Chrome DevTools Protocol and takes screenshots: `python cdp.py "<expression>"`, `python cdp.py file <script.js>`, `python cdp.py shot <file.png>`. |
| `signin.py`, `link.py`, `ui.py`, `signout.js` | Sign in with the test account, link `workspace/` to its project through the `project_link` command (no folder dialog), click by text, send a message, read the conversation, sign out. |
| `result_delivery.py` | Runs a 12-second command and cuts (`cut`) or silently stalls (`stall`) the connection while it runs, then prints when and how the result reached the turn. |

## Running

1. Start the database and the backend: `docker compose -f infra/docker-compose.yml up -d`, then `POINTER_TELEGRAM_BOT_TOKEN= ./gradlew bootRun` in `backend`.
2. Start `python proxy.py` and `python fake_llm.py` in this folder, then `python account.py` once.
3. Start the app through the proxy with the WebView debug port open. `POINTER_API_URL` is read at compile time, so the first start rebuilds the core:

   ```bash
   cd desktop
   POINTER_API_URL=http://127.0.0.1:8081 WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9222 pnpm tauri dev
   ```

4. `python signin.py`, `python link.py`, then `python ui.py click "New session"` and `python ui.py send "Hello"`.

Run the scripts with plain `python`, not `python -I`: `websocket-client` is installed in the user site-packages. With `PYTHONIOENCODING=utf-8` the Russian parts of the output print correctly in Git Bash.

## Proxy controls

| Request | Effect |
|---|---|
| `GET /drop?n=1` | Drops the next `n` `session.event` messages, to test re-subscribing after a gap in `seq`. |
| `GET /stall` | Stops forwarding in both directions on the current connection while keeping it open, like a network that went away silently. Client frames are still logged, marked `LOST IN STALL`. |
| `GET /cut?block=15` | Closes the current connection without a close frame and refuses new WebSocket connections for the given seconds. |
| `GET /close?code=4426` | Sends a close frame with the code to the app and closes the connection, to test `4401`, `4403`, `4409` and `4426`. |
| `GET /state` | Connections with their age, close codes, subscriptions and counts of events, pings and pongs. |
| `GET /log?n=200` | The latest log lines. |

## Caveats

- The dev app keeps its session in the Windows credential store under `dev.pointer.desktop`, shared with the developer's own sign-in. Signing in with the test account signs the developer out of the dev app.
- `link.py` adds `workspace/` to the app's local project map; remove it afterwards with `window.__TAURI_INTERNALS__.invoke('project_unlink', { projectId })` through `cdp.py`.
- Radix menus open only on real pointer input. `signout.js` dispatches pointer events, which works in the Tauri WebView but not always; the browser preview needs real clicks.
- Notifications of the dev build show as "Windows PowerShell" because the app is not installed and has no AppUserModelID.
