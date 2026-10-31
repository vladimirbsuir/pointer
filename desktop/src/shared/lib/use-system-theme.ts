import { useEffect } from "react";

const darkSchemeQuery = "(prefers-color-scheme: dark)";

export function useSystemTheme() {
  useEffect(() => {
    const media = window.matchMedia(darkSchemeQuery);
    const apply = () => document.documentElement.classList.toggle("dark", media.matches);
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, []);
}
