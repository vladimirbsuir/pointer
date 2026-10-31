import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { PlusIcon, RefreshCwIcon, StarIcon, Trash2Icon, ZapIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useProviders, useSettings } from "@/shared/api/queries";
import { toAppError, type Provider } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Field, FieldGroup, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";

type Preset = { name: string; baseUrl: string; isLocal: boolean; needsKey: boolean };

const presets: Preset[] = [
  { name: "OpenAI", baseUrl: "https://api.openai.com/v1", isLocal: false, needsKey: true },
  { name: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", isLocal: false, needsKey: true },
  { name: "Ollama", baseUrl: "http://localhost:11434/v1", isLocal: true, needsKey: false },
  { name: "LM Studio", baseUrl: "http://localhost:1234/v1", isLocal: true, needsKey: false },
];

export function ProvidersSection() {
  const { t } = useTranslation();
  const { data: providers } = useProviders();
  const [adding, setAdding] = useState<Preset | null>(null);

  return (
    <div className="flex flex-col gap-4">
      {providers?.length === 0 && !adding && (
        <p className="text-sm text-muted-foreground">{t("providers.empty")}</p>
      )}
      {providers?.map((provider) => (
        <ProviderCard key={provider.id} provider={provider} />
      ))}
      {adding ? (
        <AddProviderForm preset={adding} onDone={() => setAdding(null)} />
      ) : (
        <div className="flex flex-wrap gap-2">
          {presets.map((preset) => (
            <Button key={preset.name} variant="outline" size="sm" onClick={() => setAdding(preset)}>
              <PlusIcon />
              {preset.name}
            </Button>
          ))}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setAdding({ name: "", baseUrl: "", isLocal: false, needsKey: true })}
          >
            <PlusIcon />
            {t("providers.custom")}
          </Button>
        </div>
      )}
    </div>
  );
}

function AddProviderForm({ preset, onDone }: { preset: Preset; onDone: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [name, setName] = useState(preset.name);
  const [baseUrl, setBaseUrl] = useState(preset.baseUrl);
  const [apiKey, setApiKey] = useState("");
  const [isLocal, setIsLocal] = useState(preset.isLocal);
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    try {
      const provider = (await unwrap(
        api().POST("/api/v1/providers", {
          body: {
            name: name.trim(),
            baseUrl: baseUrl.trim(),
            apiKey: apiKey || undefined,
            isLocal,
          },
        }),
      )) as Provider;
      await unwrap(
        api().POST("/api/v1/providers/{providerId}/models/refresh", {
          params: { path: { providerId: provider.id } },
        }),
      ).catch((error: unknown) => toast.warning(errorMessage(t, toAppError(error))));
      await queryClient.invalidateQueries({ queryKey: queryKeys.providers });
      onDone();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={(event) => void submit(event)} className="rounded-lg border p-4">
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor="provider-name">{t("providers.name")}</FieldLabel>
          <Input
            id="provider-name"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="provider-url">{t("providers.baseUrl")}</FieldLabel>
          <Input
            id="provider-url"
            required
            placeholder="https://api.example.com/v1"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="provider-key">{t("providers.apiKey")}</FieldLabel>
          <Input
            id="provider-key"
            type="password"
            autoComplete="off"
            required={preset.needsKey && !isLocal}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
          />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <Switch checked={isLocal} onCheckedChange={setIsLocal} />
          {t("providers.local")}
        </label>
        <div className="flex gap-2">
          <Button type="submit" disabled={saving}>
            {t("providers.add")}
          </Button>
          <Button type="button" variant="ghost" onClick={onDone}>
            {t("common.cancel")}
          </Button>
        </div>
      </FieldGroup>
    </form>
  );
}

function ProviderCard({ provider }: { provider: Provider }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: settings } = useSettings();
  const [busy, setBusy] = useState(false);
  const path = { params: { path: { providerId: provider.id } } };

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: queryKeys.providers });
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setBusy(false);
    }
  };

  const test = () =>
    run(async () => {
      const result = await unwrap(api().POST("/api/v1/providers/{providerId}/test", path));
      if (result.ok) {
        toast.success(t("providers.testOk", { count: result.modelCount }));
      } else {
        toast.error(
          result.errorMessage ||
            errorMessage(t, { code: result.errorCode ?? "unknown", message: "" }),
        );
      }
    });

  const setDefault = (modelId: string) =>
    run(async () => {
      await unwrap(api().PUT("/api/v1/me/settings", { body: { defaultModelId: modelId } }));
      await queryClient.invalidateQueries({ queryKey: queryKeys.settings });
    });

  return (
    <div className="rounded-lg border">
      <div className="flex items-center justify-between gap-2 border-b px-4 py-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium">{provider.name}</span>
            {provider.isLocal && <Badge variant="secondary">{t("providers.localBadge")}</Badge>}
          </div>
          <p className="truncate text-xs text-muted-foreground">
            {provider.baseUrl}
            {provider.hasApiKey && ` · ••••${provider.apiKeyHint ?? ""}`}
          </p>
        </div>
        <div className="flex shrink-0 gap-1">
          <Button
            variant="ghost"
            size="icon-sm"
            disabled={busy}
            onClick={() => void test()}
            title={t("providers.test")}
          >
            <ZapIcon />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            disabled={busy}
            title={t("providers.refresh")}
            onClick={() =>
              void run(() =>
                unwrap(api().POST("/api/v1/providers/{providerId}/models/refresh", path)),
              )
            }
          >
            <RefreshCwIcon className={cn(busy && "animate-spin")} />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            disabled={busy}
            title={t("providers.delete")}
            onClick={() =>
              void run(() => unwrap(api().DELETE("/api/v1/providers/{providerId}", path)))
            }
          >
            <Trash2Icon />
          </Button>
        </div>
      </div>
      <ul className="max-h-56 overflow-y-auto py-1">
        {provider.models.length === 0 && (
          <li className="px-4 py-2 text-sm text-muted-foreground">{t("providers.noModels")}</li>
        )}
        {provider.models.map((model) => {
          const isDefault = settings?.defaultModelId === model.id;
          return (
            <li key={model.id} className="flex items-center gap-2 px-4 py-1.5 text-sm">
              <Switch
                size="sm"
                checked={model.enabled}
                onCheckedChange={(enabled) =>
                  void run(() =>
                    unwrap(
                      api().PATCH("/api/v1/providers/{providerId}/models/{modelId}", {
                        params: { path: { providerId: provider.id, modelId: model.id } },
                        body: { enabled },
                      }),
                    ),
                  )
                }
              />
              <span
                className={cn("min-w-0 flex-1 truncate", !model.enabled && "text-muted-foreground")}
              >
                {model.displayName}
              </span>
              <Button
                variant="ghost"
                size="icon-xs"
                disabled={busy || isDefault || !model.enabled}
                title={isDefault ? t("providers.isDefault") : t("providers.makeDefault")}
                onClick={() => void setDefault(model.id)}
              >
                <StarIcon className={cn(isDefault && "fill-current text-amber-500")} />
              </Button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
