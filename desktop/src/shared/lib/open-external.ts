import { isTauri } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";

export async function openExternal(url: string) {
  if (isTauri()) {
    await openUrl(url);
  } else {
    window.open(url, "_blank", "noopener");
  }
}
