import { useTranslation } from "react-i18next";
import { Button } from "@/shared/ui/button";
import { changeLanguage as persistLanguage, languages, type Language } from "@/shared/i18n";
import { themePreferences, useThemeStore } from "@/shared/lib/theme";

type AppearanceSwitcherProps = {
  compact?: boolean;
  onLanguageChange?: (language: Language) => void;
};

export function AppearanceSwitcher({ compact = false, onLanguageChange }: AppearanceSwitcherProps) {
  const { t, i18n } = useTranslation();
  const preference = useThemeStore((state) => state.preference);
  const setPreference = useThemeStore((state) => state.setPreference);
  const size = compact ? "sm" : "default";

  const changeLanguage = (language: Language) => {
    void persistLanguage(language);
    onLanguageChange?.(language);
  };

  return (
    <div className={compact ? "flex flex-wrap items-center gap-4" : "flex flex-col gap-6"}>
      <OptionGroup label={t("settings.language")} compact={compact}>
        {languages.map((language) => (
          <Button
            key={language}
            size={size}
            variant={i18n.resolvedLanguage === language ? "secondary" : "ghost"}
            onClick={() => changeLanguage(language)}
          >
            {t(`languages.${language}`)}
          </Button>
        ))}
      </OptionGroup>
      <OptionGroup label={t("settings.theme")} compact={compact}>
        {themePreferences.map((theme) => (
          <Button
            key={theme}
            size={size}
            variant={preference === theme ? "secondary" : "ghost"}
            onClick={() => setPreference(theme)}
          >
            {t(`themes.${theme}`)}
          </Button>
        ))}
      </OptionGroup>
    </div>
  );
}

function OptionGroup({
  label,
  compact,
  children,
}: {
  label: string;
  compact: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      role="group"
      aria-label={label}
      className={compact ? "flex items-center gap-1" : "flex flex-col gap-2"}
    >
      {!compact && <span className="text-sm font-medium">{label}</span>}
      <div className="flex gap-1">{children}</div>
    </div>
  );
}
