import { listen } from "@tauri-apps/api/event";
import { useEffect, useState } from "react";
import { core } from "@/shared/api/core";

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

export function useRealtimeConnected(): boolean {
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let disposed = false;
    void core.realtimeStatus().then((status) => !disposed && setConnected(status));
    const unlisten = listen<{ connected: boolean }>("realtime://status", (event) =>
      setConnected(event.payload.connected),
    );
    return () => {
      disposed = true;
      void unlisten.then((stop) => stop());
    };
  }, []);

  return connected;
}
