import { Channel, invoke } from "@tauri-apps/api/core";
import { toAppError, type Me } from "./types";

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invoke<T>(command, args);
  } catch (error) {
    throw toAppError(error);
  }
}

export type TerminalEvent =
  { kind: "output"; data: string } | { kind: "exit"; code: number | null };

export type McpServerConfig = { name: string; enabled: boolean } & (
  | { transport: "stdio"; command: string; args: string[]; env: Record<string, string> }
  | { transport: "http"; url: string; headers: Record<string, string> }
);

export type McpServer = McpServerConfig & {
  status: "disabled" | "starting" | "running" | "failed";
  error: string | null;
  tools: string[];
};

export const core = {
  appConfig: () => call<{ apiUrl: string }>("app_config"),
  restore: () => call<Me | null>("auth_restore"),
  signIn: (email: string, password: string) => call<Me>("auth_sign_in", { email, password }),
  register: (email: string, password: string, displayName: string, locale: string) =>
    call<Me>("auth_register", { email, password, displayName, locale }),
  signInWithGitHub: (locale: string) => call<Me>("auth_sign_in_with_github", { locale }),
  linkGitHub: (repositories = false) => call<Me>("auth_link_github", { repositories }),
  accessToken: () => call<string>("auth_access_token"),
  signOut: () => call<void>("auth_sign_out"),
  subscribe: (sessionId: string, afterSeq: number) =>
    call<void>("realtime_subscribe", { sessionId, afterSeq }),
  unsubscribe: (sessionId: string) => call<void>("realtime_unsubscribe", { sessionId }),
  realtimeStatus: () => call<boolean>("realtime_status"),
  localProjects: () => call<Record<string, string>>("projects_local"),
  linkProject: (projectId: string, path: string) => call<void>("project_link", { projectId, path }),
  unlinkProject: (projectId: string) => call<void>("project_unlink", { projectId }),
  projectRepository: (projectId: string) =>
    call<string | null>("project_repository", { projectId }),
  projectFiles: (projectId: string, query: string) =>
    call<string[]>("project_files", { projectId, query }),
  readProjectFile: (projectId: string, path: string) =>
    call<string>("project_read_file", { projectId, path }),
  terminalOpen: (
    projectId: string,
    cols: number,
    rows: number,
    onEvent: (event: TerminalEvent) => void,
  ) => call<string>("terminal_open", { projectId, cols, rows, onEvent: new Channel(onEvent) }),
  terminalWrite: (id: string, data: string) => call<void>("terminal_write", { id, data }),
  terminalResize: (id: string, cols: number, rows: number) =>
    call<void>("terminal_resize", { id, cols, rows }),
  terminalClose: (id: string) => call<void>("terminal_close", { id }),
  mcpServers: () => call<McpServer[]>("mcp_servers"),
  mcpSave: (server: McpServerConfig, previousName: string | null) =>
    call<void>("mcp_save", { server, previousName }),
  mcpRemove: (name: string) => call<void>("mcp_remove", { name }),
  mcpRestart: (name: string) => call<void>("mcp_restart", { name }),
};
