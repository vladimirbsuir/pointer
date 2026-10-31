import { useEffect } from "react";
import { create } from "zustand";

export type ThemePreference = "system" | "light" | "dark";

export const themePreferences: ThemePreference[] = ["system", "light", "dark"];

const storageKey = "pointer.theme";
const darkSchemeQuery = "(prefers-color-scheme: dark)";

function storedPreference(): ThemePreference {
  try {
    const value = localStorage.getItem(storageKey);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

function persistPreference(preference: ThemePreference): boolean {
  try {
    localStorage.setItem(storageKey, preference);
    return true;
  } catch {
    return false;
  }
}

type ThemeState = {
  preference: ThemePreference;
  resolved: "light" | "dark";
  setPreference: (preference: ThemePreference) => void;
  setResolved: (resolved: "light" | "dark") => void;
};

export const useThemeStore = create<ThemeState>((set) => ({
  preference: storedPreference(),
  resolved: "light",
  setPreference: (preference) => {
    persistPreference(preference);
    set({ preference });
  },
  setResolved: (resolved) => set({ resolved }),
}));

export function useApplyTheme() {
  const preference = useThemeStore((state) => state.preference);
  const setResolved = useThemeStore((state) => state.setResolved);

  useEffect(() => {
    const media = window.matchMedia(darkSchemeQuery);
    const apply = () => {
      const resolved = preference === "system" ? (media.matches ? "dark" : "light") : preference;
      document.documentElement.classList.toggle("dark", resolved === "dark");
      setResolved(resolved);
    };
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [preference, setResolved]);
}
