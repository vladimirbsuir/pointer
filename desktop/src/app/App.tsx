import { useTranslation } from "react-i18next";
import { changeLanguage, languages } from "@/shared/i18n";
import { useSystemTheme } from "@/shared/lib/use-system-theme";
import { Button } from "@/shared/ui/button";

export function App() {
  const { t, i18n } = useTranslation();
  useSystemTheme();

  return (
    <main className="flex h-screen flex-col items-center justify-center gap-6">
      <div className="text-center">
        <h1 className="text-4xl font-semibold tracking-tight">{t("app.name")}</h1>
        <p className="mt-2 text-muted-foreground">{t("app.tagline")}</p>
      </div>
      <div className="flex items-center gap-2" aria-label={t("settings.language")}>
        {languages.map((language) => (
          <Button
            key={language}
            variant={i18n.resolvedLanguage === language ? "default" : "outline"}
            onClick={() => changeLanguage(language)}
          >
            {t(`languages.${language}`)}
          </Button>
        ))}
      </div>
    </main>
  );
}
