import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { isTauri } from "@tauri-apps/api/core";
import { App } from "@/app/App";
import { initApiClient } from "@/shared/api/client";
import "@/shared/i18n";
import "./index.css";

if (import.meta.env.DEV && !isTauri()) {
  const { installBrowserCore } = await import("@/dev/browser-core");
  installBrowserCore();
}

await initApiClient();

createRoot(document.getElementById("root") as HTMLElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
