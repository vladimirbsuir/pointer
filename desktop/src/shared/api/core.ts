import { invoke } from "@tauri-apps/api/core";
import { toAppError, type Me } from "./types";

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invoke<T>(command, args);
  } catch (error) {
    throw toAppError(error);
  }
}

export const core = {
  appConfig: () => call<{ apiUrl: string }>("app_config"),
  restore: () => call<Me | null>("auth_restore"),
  signIn: (email: string, password: string) => call<Me>("auth_sign_in", { email, password }),
  register: (email: string, password: string, displayName: string, locale: string) =>
    call<Me>("auth_register", { email, password, displayName, locale }),
  signInWithGitHub: (locale: string) => call<Me>("auth_sign_in_with_github", { locale }),
  linkGitHub: () => call<Me>("auth_link_github"),
  accessToken: () => call<string>("auth_access_token"),
  signOut: () => call<void>("auth_sign_out"),
  subscribe: (sessionId: string, afterSeq: number) =>
    call<void>("realtime_subscribe", { sessionId, afterSeq }),
  unsubscribe: (sessionId: string) => call<void>("realtime_unsubscribe", { sessionId }),
  realtimeStatus: () => call<boolean>("realtime_status"),
  localProjects: () => call<Record<string, string>>("projects_local"),
  linkProject: (projectId: string, path: string) => call<void>("project_link", { projectId, path }),
  unlinkProject: (projectId: string) => call<void>("project_unlink", { projectId }),
  projectFiles: (projectId: string, query: string) =>
    call<string[]>("project_files", { projectId, query }),
  readProjectFile: (projectId: string, path: string) =>
    call<string>("project_read_file", { projectId, path }),
};
