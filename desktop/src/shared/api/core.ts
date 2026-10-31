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
};
