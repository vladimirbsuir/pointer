import { listen } from "@tauri-apps/api/event";
import { useEffect, useState } from "react";
import { core, type RealtimeStatus } from "@/shared/api/core";

export type RealtimeMessage = {
  type: string;
  id: string;
  replyTo: string | null;
  payload: Record<string, unknown>;
};

type Handler = (message: RealtimeMessage) => void;

const handlers = new Set<Handler>();
let listening = false;

async function ensureListening() {
  if (listening) {
    return;
  }
  listening = true;
  await listen<RealtimeMessage>("realtime://message", (event) => {
    handlers.forEach((handler) => handler(event.payload));
  });
}

export function onRealtimeMessage(handler: Handler): () => void {
  handlers.add(handler);
  void ensureListening();
  return () => handlers.delete(handler);
}

export const ACCESS_REVOKED_CLOSE_CODE = 4403;
export const UNSUPPORTED_VERSION_CLOSE_CODE = 4426;

const disconnected: RealtimeStatus = { connected: false, closeCode: null };

export function useRealtimeStatus(): RealtimeStatus {
  const [status, setStatus] = useState(disconnected);

  useEffect(() => {
    let disposed = false;
    void core.realtimeStatus().then((current) => !disposed && setStatus(current));
    const unlisten = listen<RealtimeStatus>("realtime://status", (event) =>
      setStatus(event.payload),
    );
    return () => {
      disposed = true;
      void unlisten.then((stop) => stop());
    };
  }, []);

  return status;
}
