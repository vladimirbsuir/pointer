import { emit } from "@tauri-apps/api/event";
import { mockIPC } from "@tauri-apps/api/mocks";
import { executeInBrowser, readFileInBrowser, suggestFilesInBrowser } from "./browser-executor";

const apiUrl = import.meta.env.VITE_POINTER_API_URL ?? "http://localhost:8080";
const refreshKey = "pointer.dev.refreshToken";
const deviceKey = "pointer.dev.deviceId";
const projectsKey = "pointer.dev.projects";

type AuthResponse = { accessToken: string; refreshToken: string; user: unknown };

let accessToken: string | null = null;
let socket: WebSocket | null = null;
let connected = false;
const subscriptions = new Map<string, number>();

function deviceId() {
  let id = localStorage.getItem(deviceKey);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(deviceKey, id);
  }
  return id;
}

function device() {
  return {
    id: deviceId(),
    name: "Browser preview",
    platform: { os: "browser" },
    clientVersion: "dev",
  };
}

function localProjects(): Record<string, string> {
  return JSON.parse(localStorage.getItem(projectsKey) ?? "{}");
}

function saveLocalProjects(projects: Record<string, string>) {
  localStorage.setItem(projectsKey, JSON.stringify(projects));
  send("device.projects_changed", { projects: Object.keys(projects) });
}

async function request<T>(path: string, body: unknown, token?: string): Promise<T> {
  const response = await fetch(`${apiUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  }).catch((error: unknown) => {
    throw { code: "network_error", message: String(error) };
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw {
      code: problem.code ?? `http_${response.status}`,
      message: problem.detail ?? "",
      status: response.status,
    };
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

function accept(response: AuthResponse) {
  localStorage.setItem(refreshKey, response.refreshToken);
  accessToken = response.accessToken;
  connect();
  return response.user;
}

let pendingRefresh: Promise<unknown> | null = null;

function refresh() {
  pendingRefresh ??= (async () => {
    const token = localStorage.getItem(refreshKey);
    if (!token) {
      throw { code: "signed_out", message: "Not signed in", status: 401 };
    }
    return accept(await request<AuthResponse>("/api/v1/auth/refresh", { refreshToken: token }));
  })().finally(() => {
    pendingRefresh = null;
  });
  return pendingRefresh;
}

function send(type: string, payload: unknown) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type, id: crypto.randomUUID(), replyTo: null, payload }));
  }
}

function setConnected(value: boolean) {
  connected = value;
  void emit("realtime://status", { connected: value });
}

function connect() {
  if (socket || !accessToken) {
    return;
  }
  const url = `${apiUrl.replace(/^http/, "ws")}/ws?access_token=${encodeURIComponent(accessToken)}`;
  const ws = new WebSocket(url);
  socket = ws;
  ws.onopen = () =>
    send("hello", {
      protocolVersion: 1,
      deviceId: deviceId(),
      deviceName: "Browser preview",
      clientVersion: "dev",
      capabilities: ["viewer", "executor"],
      projects: Object.keys(localProjects()),
    });
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "welcome") {
      setConnected(true);
      subscriptions.forEach((afterSeq, sessionId) =>
        send("session.subscribe", { sessionId, afterSeq }),
      );
      return;
    }
    if (message.type === "exec.request") {
      const request = message.payload;
      setTimeout(() => {
        const outcome = executeInBrowser(
          request.operation,
          request.arguments ?? {},
          Boolean(request.dryRun),
        );
        send("exec.result", { callId: request.callId, ...outcome });
      }, 150);
      return;
    }
    if (message.type === "session.event") {
      const { sessionId, seq } = message.payload;
      if (subscriptions.has(sessionId) && seq > (subscriptions.get(sessionId) ?? 0)) {
        subscriptions.set(sessionId, seq);
      }
    }
    void emit("realtime://message", message);
  };
  ws.onclose = () => {
    socket = null;
    setConnected(false);
    if (accessToken) {
      setTimeout(connect, 2000);
    }
  };
}

export function installBrowserCore() {
  mockIPC(
    async (command, payload) => {
      const args = (payload ?? {}) as Record<string, unknown>;
      switch (command) {
        case "app_config":
          return { apiUrl };
        case "auth_restore":
          if (!localStorage.getItem(refreshKey)) {
            return null;
          }
          try {
            return await refresh();
          } catch (error) {
            if ((error as { status?: number }).status === 401) {
              localStorage.removeItem(refreshKey);
              return null;
            }
            throw error;
          }
        case "auth_sign_in":
          return accept(
            await request<AuthResponse>("/api/v1/auth/login", {
              email: args.email,
              password: args.password,
              device: device(),
            }),
          );
        case "auth_register":
          return accept(
            await request<AuthResponse>("/api/v1/auth/register", {
              email: args.email,
              password: args.password,
              displayName: args.displayName,
              locale: args.locale,
              device: device(),
            }),
          );
        case "auth_access_token":
          if (!accessToken) {
            await refresh();
          }
          return accessToken;
        case "auth_sign_out": {
          const token = localStorage.getItem(refreshKey);
          if (token) {
            await request("/api/v1/auth/logout", { refreshToken: token }).catch(() => undefined);
          }
          localStorage.removeItem(refreshKey);
          accessToken = null;
          socket?.close();
          subscriptions.clear();
          return null;
        }
        case "realtime_subscribe":
          subscriptions.set(String(args.sessionId), Number(args.afterSeq));
          send("session.subscribe", { sessionId: args.sessionId, afterSeq: args.afterSeq });
          return null;
        case "realtime_unsubscribe":
          subscriptions.delete(String(args.sessionId));
          send("session.unsubscribe", { sessionId: args.sessionId });
          return null;
        case "realtime_status":
          return connected;
        case "projects_local":
          return localProjects();
        case "project_link":
          saveLocalProjects({ ...localProjects(), [String(args.projectId)]: String(args.path) });
          return null;
        case "project_unlink": {
          const projects = localProjects();
          delete projects[String(args.projectId)];
          saveLocalProjects(projects);
          return null;
        }
        case "project_files":
          return suggestFilesInBrowser(String(args.query ?? "")).slice(0, 30);
        case "project_read_file":
          return readFileInBrowser(String(args.path));
        case "plugin:dialog|open":
          return window.prompt("Folder path", "C:\\Projects\\demo");
        default:
          throw {
            code: "browser_open_failed",
            message: `${command} is not available in a browser`,
          };
      }
    },
    { shouldMockEvents: true },
  );
}
