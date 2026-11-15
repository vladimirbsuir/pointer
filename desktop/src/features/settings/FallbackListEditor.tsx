import { useTranslation } from "react-i18next";
import { ArrowUpIcon, XIcon } from "lucide-react";
import type { Model, Provider } from "@/shared/api/types";
import { Button } from "@/shared/ui/button";

export const MAX_FALLBACKS = 5;

type FallbackListEditorProps = {
  model: Model;
  providers: Provider[];
  value: string[];
  onChange: (value: string[]) => void;
};

export function FallbackListEditor({ model, providers, value, onChange }: FallbackListEditorProps) {
  const { t } = useTranslation();
  const options = providers.flatMap((provider) =>
    provider.models
      .filter((candidate) => candidate.id !== model.id && candidate.enabled)
      .map((candidate) => ({
        id: candidate.id,
        label: `${candidate.displayName} · ${provider.name}`,
      })),
  );
  const known = providers.flatMap((provider) =>
    provider.models.map((candidate) => ({
      id: candidate.id,
      label: `${candidate.displayName} · ${provider.name}`,
    })),
  );
  const label = (id: string) => known.find((option) => option.id === id)?.label ?? id;
  const available = options.filter((option) => !value.includes(option.id));

  const moveUp = (index: number) => {
    const next = [...value];
    next.splice(index - 1, 0, ...next.splice(index, 1));
    onChange(next);
  };

  return (
    <div className="flex w-full flex-col gap-1.5 text-xs">
      <span className="text-muted-foreground">{t("providers.fallbacks")}</span>
      {value.length > 0 && (
        <ol className="flex flex-col gap-1">
          {value.map((id, index) => (
            <li key={id} className="flex items-center gap-1 rounded-md border px-2 py-1">
              <span className="w-4 text-muted-foreground tabular-nums">{index + 1}</span>
              <span className="min-w-0 flex-1 truncate">{label(id)}</span>
              <Button
                variant="ghost"
                size="icon-xs"
                disabled={index === 0}
                title={t("providers.fallbackMoveUp")}
                onClick={() => moveUp(index)}
              >
                <ArrowUpIcon />
              </Button>
              <Button
                variant="ghost"
                size="icon-xs"
                title={t("providers.fallbackRemove")}
                onClick={() => onChange(value.filter((other) => other !== id))}
              >
                <XIcon />
              </Button>
            </li>
          ))}
        </ol>
      )}
      {value.length < MAX_FALLBACKS && available.length > 0 && (
        <select
          aria-label={t("providers.fallbackAdd")}
          className="h-8 w-64 max-w-full rounded-md border bg-background px-2 text-xs"
          value=""
          onChange={(event) => onChange([...value, event.target.value])}
        >
          <option value="">{t("providers.fallbackAdd")}</option>
          {available.map((option) => (
            <option key={option.id} value={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      )}
      <p className="text-muted-foreground">{t("providers.fallbackHint")}</p>
    </div>
  );
}
