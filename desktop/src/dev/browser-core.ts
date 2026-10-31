import { mockIPC } from "@tauri-apps/api/mocks";

const apiUrl = import.meta.env.VITE_POINTER_API_URL ?? "http://localhost:8080";
const refreshKey = "pointer.dev.refreshToken";
const deviceKey = "pointer.dev.deviceId";

type AuthResponse = { accessToken: string; refreshToken: string; user: unknown };

let accessToken: string | null = null;

function device() {
  let id = localStorage.getItem(deviceKey);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(deviceKey, id);
  }
  return { id, name: "Browser preview", platform: { os: "browser" }, clientVersion: "dev" };
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

export function installBrowserCore() {
  mockIPC(async (command, payload) => {
    const args = (payload ?? {}) as Record<string, string>;
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
        return null;
      }
      default:
        throw { code: "browser_open_failed", message: `${command} is not available in a browser` };
    }
  });
}
