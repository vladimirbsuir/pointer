import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "@/shared/api/client";
import type { Model } from "@/shared/api/types";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";

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
  const [output, setOutput] = useState(model.outputPricePerMtok?.toString() ?? "");
  const [supportsVision, setSupportsVision] = useState(model.supportsVision);
  const inputPrice = parsePrice(input);
  const outputPrice = parsePrice(output);
  const valid = inputPrice !== undefined && outputPrice !== undefined;
  const path = { params: { path: { providerId, modelId: model.id } } };

  const save = () =>
    unwrap(
      api().PATCH("/api/v1/providers/{providerId}/models/{modelId}", {
        ...path,
        body:
          inputPrice === null && outputPrice === null
            ? { clearPrices: true, supportsVision }
            : {
                clearPrices: true,
                supportsVision,
                inputPricePerMtok: inputPrice ?? 0,
                outputPricePerMtok: outputPrice ?? 0,
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
