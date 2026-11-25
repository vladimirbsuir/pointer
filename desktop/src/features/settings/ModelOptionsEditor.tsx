import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "@/shared/api/client";
import { useProviders, useSettings } from "@/shared/api/queries";
import type { Model } from "@/shared/api/types";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";
import { FallbackListEditor } from "./FallbackListEditor";

type ModelOptionsEditorProps = {
  providerId: string;
  model: Model;
  onDone: (save: () => Promise<unknown>) => void;
  onCancel: () => void;
};

function parsePrice(value: string) {
  const trimmed = value.trim().replace(",", ".");
  if (trimmed === "") {
    return null;
  }
  const price = Number(trimmed);
  return Number.isFinite(price) && price >= 0 && price <= 100_000 ? price : undefined;
}

export function ModelOptionsEditor({
  providerId,
  model,
  onDone,
  onCancel,
}: ModelOptionsEditorProps) {
  const { t } = useTranslation();
  const [input, setInput] = useState(model.inputPricePerMtok?.toString() ?? "");
  const [cached, setCached] = useState(model.cachedInputPricePerMtok?.toString() ?? "");
  const [output, setOutput] = useState(model.outputPricePerMtok?.toString() ?? "");
  const [supportsVision, setSupportsVision] = useState(model.supportsVision);
  const [coordinateSystem, setCoordinateSystem] = useState(model.coordinateSystem);
  const [queryPrefix, setQueryPrefix] = useState(model.embeddingQueryPrefix ?? "");
  const [documentPrefix, setDocumentPrefix] = useState(model.embeddingDocumentPrefix ?? "");
  const [fallbacks, setFallbacks] = useState(model.fallbackModelIds);
  const { data: settings } = useSettings();
  const { data: providers } = useProviders();
  const showPrefixes =
    settings?.embeddingModelId === model.id ||
    Boolean(model.embeddingQueryPrefix) ||
    Boolean(model.embeddingDocumentPrefix);
  const inputPrice = parsePrice(input);
  const cachedPrice = parsePrice(cached);
  const outputPrice = parsePrice(output);
  const valid = inputPrice !== undefined && cachedPrice !== undefined && outputPrice !== undefined;
  const path = { params: { path: { providerId, modelId: model.id } } };

  const prefixes = showPrefixes
    ? { embeddingQueryPrefix: queryPrefix, embeddingDocumentPrefix: documentPrefix }
    : {};

  const fallbacksChanged =
    fallbacks.length !== model.fallbackModelIds.length ||
    fallbacks.some((id, index) => id !== model.fallbackModelIds[index]);

  const saveFallbacks = () =>
    unwrap(
      api().PUT("/api/v1/models/{modelId}/fallbacks", {
        params: { path: { modelId: model.id } },
        body: { modelIds: fallbacks },
      }),
    );

  const save = async () => {
    await saveOptions();
    if (fallbacksChanged) {
      await saveFallbacks();
    }
  };

  const saveOptions = () =>
    unwrap(
      api().PATCH("/api/v1/providers/{providerId}/models/{modelId}", {
        ...path,
        body:
          inputPrice === null && cachedPrice === null && outputPrice === null
            ? { clearPrices: true, supportsVision, coordinateSystem, ...prefixes }
            : {
                clearPrices: true,
                supportsVision,
                coordinateSystem,
                inputPricePerMtok: inputPrice ?? 0,
                outputPricePerMtok: outputPrice ?? 0,
                ...(cachedPrice === null ? {} : { cachedInputPricePerMtok: cachedPrice }),
                ...prefixes,
              },
      }),
    );

  return (
    <div className="flex flex-wrap items-end gap-2 px-4 pt-1 pb-2">
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("providers.inputPrice")}
        <Input
          value={input}
          inputMode="decimal"
          placeholder="0.00"
          className="h-8 w-28"
          aria-invalid={inputPrice === undefined}
          onChange={(event) => setInput(event.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("providers.cachedInputPrice")}
        <Input
          value={cached}
          inputMode="decimal"
          placeholder={input.trim() === "" ? "0.00" : input.trim()}
          className="h-8 w-28"
          aria-invalid={cachedPrice === undefined}
          onChange={(event) => setCached(event.target.value)}
        />
      </label>
      <label className="flex flex-col gap-1 text-xs text-muted-foreground">
        {t("providers.outputPrice")}
        <Input
          value={output}
          inputMode="decimal"
          placeholder="0.00"
          className="h-8 w-28"
          aria-invalid={outputPrice === undefined}
          onChange={(event) => setOutput(event.target.value)}
        />
      </label>
      <label className="flex h-8 items-center gap-1.5 text-xs text-muted-foreground">
        <Switch size="sm" checked={supportsVision} onCheckedChange={setSupportsVision} />
        {t("providers.supportsVision")}
      </label>
      <label
        className="flex flex-col gap-1 text-xs text-muted-foreground"
        title={t("providers.coordinatesHint")}
      >
        {t("providers.coordinates")}
        <select
          className="h-8 w-36 rounded-md border bg-background px-2 text-xs disabled:opacity-50"
          value={coordinateSystem}
          disabled={!supportsVision}
          onChange={(event) => setCoordinateSystem(event.target.value as typeof coordinateSystem)}
        >
          <option value="pixels">{t("providers.coordinatesPixels")}</option>
          <option value="normalized">{t("providers.coordinatesGrid")}</option>
        </select>
      </label>
      {showPrefixes && (
        <div className="flex w-full flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            {t("providers.queryPrefix")}
            <Input
              value={queryPrefix}
              maxLength={200}
              className="h-8 w-56 font-mono"
              onChange={(event) => setQueryPrefix(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            {t("providers.documentPrefix")}
            <Input
              value={documentPrefix}
              maxLength={200}
              className="h-8 w-56 font-mono"
              onChange={(event) => setDocumentPrefix(event.target.value)}
            />
          </label>
          <p className="w-full text-xs text-muted-foreground">{t("providers.prefixHint")}</p>
        </div>
      )}
      <FallbackListEditor
        model={model}
        providers={providers ?? []}
        value={fallbacks}
        onChange={setFallbacks}
      />
      <div className="ml-auto flex gap-1">
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button size="sm" disabled={!valid} onClick={() => onDone(save)}>
          {t("common.save")}
        </Button>
      </div>
    </div>
  );
}
