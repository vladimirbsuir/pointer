import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import RFB from "@novnc/novnc";
import { api, problemToError } from "@/shared/api/client";
import { core } from "@/shared/api/core";
import type { AppError } from "@/shared/api/types";

export type DesktopStatus = {
  available: boolean;
  running: boolean;
  width: number | null;
  height: number | null;
};

type DesktopTicket = {
  ticket: string;
  expiresAt: string;
  path: string;
  password: string;
  width: number;
  height: number;
};

export type DesktopViewState =
  | { kind: "connecting" }
  | { kind: "live"; width: number; height: number }
  | { kind: "offline" }
  | { kind: "failed" }
  | { kind: "busy" }
  | { kind: "slow" }
  | { kind: "paused" };

const TOO_SLOW = 4408;
const TOO_MANY_VIEWERS = 4429;
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000];
const HIDDEN_GRACE_MS = 30_000;
const WAITING_STATES: DesktopViewState["kind"][] = ["offline", "failed"];

export function useDesktopStatus(sessionId: string, enabled: boolean, version: number) {
  return useQuery({
    queryKey: ["desktop-status", sessionId, version],
    queryFn: () => fetchStatus(sessionId),
    enabled,
    retry: false,
  });
}

async function fetchStatus(sessionId: string): Promise<DesktopStatus> {
  const { data, error, response } = await api().GET("/api/v1/sessions/{sessionId}/desktop", {
    params: { path: { sessionId } },
  });
  if (!response.ok) {
    throw problemToError(error, response.status);
  }
  return data as DesktopStatus;
}

async function requestTicket(sessionId: string): Promise<DesktopTicket> {
  const { data, error, response } = await api().POST(
    "/api/v1/sessions/{sessionId}/desktop/ticket",
    { params: { path: { sessionId } } },
  );
  if (!response.ok) {
    throw problemToError(error, response.status);
  }
  return data as DesktopTicket;
}

export function streamUrl(apiUrl: string, path: string, ticket: string) {
  const url = new URL(apiUrl);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = path;
  url.search = `?ticket=${encodeURIComponent(ticket)}`;
  return url.toString();
}

export function useDesktopView(
  sessionId: string,
  screen: HTMLElement | null,
  displayStarts: number,
) {
  const [state, setState] = useState<DesktopViewState>({ kind: "connecting" });
  const stateRef = useRef(state);
  const connection = useRef<{ rfb: RFB; socket: WebSocket } | null>(null);
  const attempt = useRef(0);
  const slowCloses = useRef(0);
  const timer = useRef<number | null>(null);
  const hiddenTimer = useRef<number | null>(null);
  const generation = useRef(0);
  const connectAgain = useRef<() => void>(() => {});

  const update = useCallback((next: DesktopViewState) => {
    stateRef.current = next;
    setState(next);
  }, []);

  const clearTimer = () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  };

  const disconnect = useCallback(() => {
    generation.current += 1;
    clearTimer();
    const current = connection.current;
    connection.current = null;
    if (current) {
      try {
        current.rfb.disconnect();
      } catch {
        current.socket.close();
      }
    }
  }, []);

  const connect = useCallback(async () => {
    if (!screen) {
      return;
    }
    disconnect();
    const own = generation.current;
    const stale = () => own !== generation.current;

    const retry = () => {
      if (stale()) {
        return;
      }
      const delay = RETRY_DELAYS_MS[attempt.current];
      if (delay === undefined) {
        update({ kind: "failed" });
        return;
      }
      attempt.current += 1;
      timer.current = window.setTimeout(() => connectAgain.current(), delay);
    };

    let ticket: DesktopTicket;
    let apiUrl: string;
    try {
      const status = await fetchStatus(sessionId);
      if (stale()) {
        return;
      }
      if (!status.available || !status.running) {
        update({ kind: "offline" });
        return;
      }
      [ticket, { apiUrl }] = await Promise.all([requestTicket(sessionId), core.appConfig()]);
    } catch (error) {
      if (stale()) {
        return;
      }
      const failure = error as AppError;
      if (failure.status === 429) {
        update({ kind: "busy" });
      } else if (
        failure.code === "desktop_not_running" ||
        failure.code === "container_not_running"
      ) {
        update({ kind: "offline" });
      } else {
        retry();
      }
      return;
    }
    if (stale()) {
      return;
    }

    const socket = new WebSocket(streamUrl(apiUrl, ticket.path, ticket.ticket), ["binary"]);
    const rfb = new RFB(screen, socket, {
      credentials: { password: ticket.password },
      shared: true,
    });
    rfb.viewOnly = true;
    rfb.scaleViewport = true;
    rfb.focusOnClick = false;
    rfb.showDotCursor = false;
    rfb.background = "transparent";
    connection.current = { rfb, socket };

    rfb.addEventListener("connect", () => {
      if (!stale()) {
        attempt.current = 0;
        update({ kind: "live", width: ticket.width, height: ticket.height });
      }
    });
    socket.addEventListener("close", (event) => {
      if (stale()) {
        return;
      }
      connection.current = null;
      if (event.code === TOO_MANY_VIEWERS) {
        update({ kind: "busy" });
      } else if (event.code === TOO_SLOW) {
        slowCloses.current += 1;
        if (slowCloses.current >= 2) {
          update({ kind: "slow" });
        } else {
          connectAgain.current();
        }
      } else {
        retry();
      }
    });
  }, [disconnect, screen, sessionId, update]);

  useEffect(() => {
    connectAgain.current = () => {
      update({ kind: "connecting" });
      void connect();
    };
  }, [connect, update]);

  const reconnect = useCallback(() => {
    attempt.current = 0;
    slowCloses.current = 0;
    void connect();
  }, [connect]);

  useEffect(() => {
    const start = window.setTimeout(reconnect, 0);
    return () => {
      window.clearTimeout(start);
      disconnect();
    };
  }, [reconnect, disconnect]);

  useEffect(() => {
    if (displayStarts === 0 || !WAITING_STATES.includes(stateRef.current.kind)) {
      return;
    }
    const start = window.setTimeout(reconnect, 0);
    return () => window.clearTimeout(start);
  }, [displayStarts, reconnect]);

  useEffect(() => {
    const clearHiddenTimer = () => {
      if (hiddenTimer.current !== null) {
        window.clearTimeout(hiddenTimer.current);
        hiddenTimer.current = null;
      }
    };
    const onVisibility = () => {
      clearHiddenTimer();
      if (document.hidden) {
        hiddenTimer.current = window.setTimeout(() => {
          hiddenTimer.current = null;
          disconnect();
          update({ kind: "paused" });
        }, HIDDEN_GRACE_MS);
      } else if (stateRef.current.kind === "paused") {
        reconnect();
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      clearHiddenTimer();
    };
  }, [disconnect, reconnect, update]);

  const retryNow = useCallback(() => {
    update({ kind: "connecting" });
    reconnect();
  }, [reconnect, update]);

  return { state, reconnect: retryNow };
}
