import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./locales/en.json";
import ru from "./locales/ru.json";

export const resources = {
  en: { translation: en },
  ru: { translation: ru },
} as const;

export type Language = keyof typeof resources;

export const languages = Object.keys(resources) as Language[];

const languageStorageKey = "pointer.language";

function initialLanguage(): Language {
  const stored = localStorage.getItem(languageStorageKey);
  if (stored && stored in resources) {
    return stored as Language;
  }
  const system = navigator.language.split("-")[0];
  return system && system in resources ? (system as Language) : "en";
}

export function changeLanguage(language: Language) {
  localStorage.setItem(languageStorageKey, language);
  return i18n.changeLanguage(language);
}

i18n.use(initReactI18next).init({
  resources,
  lng: initialLanguage(),
  fallbackLng: "en",
  interpolation: { escapeValue: false },
});

export default i18n;
