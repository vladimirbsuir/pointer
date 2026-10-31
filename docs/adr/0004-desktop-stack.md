# 0004. Desktop stack: Tauri, React, TypeScript

Status: Accepted

## Context

The desktop client needs native access (file system, processes, credential store, notifications, deep links) and a rich UI (streaming chat, Markdown, diffs, code viewer, terminal later). Target platform is Windows.

## Decision

- **Shell:** Tauri 2. Native functionality (executor, relay, credential storage) is implemented in Rust.
- **UI:** React with TypeScript, built with Vite.
- **Styling and components:** Tailwind CSS and shadcn/ui.
- **State:** Zustand for client state, TanStack Query for backend requests.
- **Internationalization:** i18next with one resource file per language (English and Russian initially).
- **Code and diff viewing:** Monaco Editor or CodeMirror 6, to be decided when the review UI is designed.
- **Terminal (MVP+):** xterm.js.

## Consequences

- Small installer and memory footprint compared to Electron, using the system WebView2.
- The React ecosystem covers every UI need with mature libraries.
- AI agents produce reliable React and TypeScript code, which matters for a project developed with an agent.
- Native code is in Rust, a third language next to Java and TypeScript.

## Rejected alternatives

- **Electron:** larger footprint, and the user chose Tauri.
- **Svelte or Vue:** viable, but a smaller ecosystem for the specific components needed and less reliable agent output.
