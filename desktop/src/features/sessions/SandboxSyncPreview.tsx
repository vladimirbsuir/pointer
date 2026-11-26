import { useTranslation } from "react-i18next";
import { FolderUpIcon, ShieldAlertIcon } from "lucide-react";
import { formatMegabytes } from "./sandbox-format";

type SandboxSyncPreviewProps = {
  preview: Record<string, unknown>;
};

export function SandboxSyncPreview({ preview }: SandboxSyncPreviewProps) {
  const { t, i18n } = useTranslation();
  const files = Number(preview.files ?? 0);
  const bytes = Number(preview.bytes ?? 0);
  const excluded = Array.isArray(preview.excluded) ? preview.excluded.map(String) : [];
  const excludedCount = Number(preview.excludedCount ?? excluded.length);
  const hidden = excludedCount - excluded.length;
  const firstSync = preview.firstSync === true;

  return (
    <div className="flex flex-col gap-2 text-xs">
      {"files" in preview && (
        <p className="flex items-center gap-2 rounded-md border bg-muted/40 px-2 py-1.5">
          <FolderUpIcon className="size-3.5 shrink-0 text-muted-foreground" />
          {t("approval.sandboxSyncSize", {
            files,
            size: formatMegabytes(bytes, i18n.language),
          })}
        </p>
      )}
      {excludedCount > 0 && (
        <div className="rounded-md border bg-muted/40 px-2 py-1.5">
          <p className="text-muted-foreground">
            {t("approval.sandboxSyncExcluded", { count: excludedCount })}
          </p>
          <ul className="mt-1 max-h-28 overflow-auto font-mono">
            {excluded.map((path) => (
              <li key={path} className="truncate">
                {path}
              </li>
            ))}
          </ul>
          {hidden > 0 && (
            <p className="mt-1 text-muted-foreground">
              {t("approval.sandboxSyncMore", { count: hidden })}
            </p>
          )}
        </div>
      )}
      <p className="text-muted-foreground">{t("approval.sandboxSync")}</p>
      {firstSync && (
        <p className="flex gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-2 py-1.5">
          <ShieldAlertIcon className="mt-px size-3.5 shrink-0 text-amber-600 dark:text-amber-400" />
          {t("approval.sandboxSyncFirst")}
        </p>
      )}
    </div>
  );
}
