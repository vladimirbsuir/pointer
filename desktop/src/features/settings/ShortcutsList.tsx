import { useTranslation } from "react-i18next";

const shortcuts = [
  { keys: ["Ctrl", "N"], action: "newSession" },
  { keys: ["Esc"], action: "stop" },
  { keys: ["Shift", "Tab"], action: "cycleMode" },
  { keys: ["@"], action: "mention" },
  { keys: ["Enter"], action: "send" },
  { keys: ["Ctrl", ","], action: "settings" },
] as const;

export function ShortcutsList() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-sm font-medium">{t("settings.shortcuts")}</h3>
      <dl className="flex flex-col gap-1.5 text-sm">
        {shortcuts.map(({ keys, action }) => (
          <div key={action} className="flex items-center justify-between gap-4">
            <dt className="text-muted-foreground">{t(`shortcuts.${action}`)}</dt>
            <dd className="flex gap-1">
              {keys.map((key) => (
                <kbd
                  key={key}
                  className="rounded border bg-muted px-1.5 py-0.5 font-mono text-[0.7rem]"
                >
                  {key}
                </kbd>
              ))}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
