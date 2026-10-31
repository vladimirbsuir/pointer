import { create } from "zustand";
import { core } from "@/shared/api/core";
import { toAppError, type AppError, type Me } from "@/shared/api/types";
import i18n, { changeLanguage, languages, type Language } from "@/shared/i18n";

type AuthStatus = "loading" | "signedOut" | "signedIn" | "unavailable";

type AuthState = {
  status: AuthStatus;
  user: Me | null;
  error: AppError | null;
  restore: () => Promise<void>;
  signIn: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  signInWithGitHub: () => Promise<void>;
  linkGitHub: () => Promise<void>;
  signOut: () => Promise<void>;
  setUser: (user: Me) => void;
};

function currentLanguage(): Language {
  const language = i18n.resolvedLanguage;
  return languages.find((candidate) => candidate === language) ?? "en";
}

function applyUserLanguage(user: Me) {
  const language = languages.find((candidate) => candidate === user.locale);
  if (language && language !== currentLanguage()) {
    void changeLanguage(language);
  }
}

export const useAuthStore = create<AuthState>((set) => {
  const signedIn = (user: Me) => {
    applyUserLanguage(user);
    set({ status: "signedIn", user, error: null });
  };

  return {
    status: "loading",
    user: null,
    error: null,

    restore: async () => {
      set({ status: "loading", error: null });
      try {
        const user = await core.restore();
        if (user) {
          signedIn(user);
        } else {
          set({ status: "signedOut", user: null });
        }
      } catch (error) {
        set({ status: "unavailable", error: toAppError(error) });
      }
    },

    signIn: async (email, password) => signedIn(await core.signIn(email, password)),

    register: async (email, password, displayName) =>
      signedIn(await core.register(email, password, displayName, currentLanguage())),

    signInWithGitHub: async () => signedIn(await core.signInWithGitHub(currentLanguage())),

    linkGitHub: async () => set({ user: await core.linkGitHub() }),

    signOut: async () => {
      await core.signOut();
      set({ status: "signedOut", user: null, error: null });
    },

    setUser: (user) => set({ user }),
  };
});
