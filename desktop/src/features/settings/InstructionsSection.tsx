import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useSettings } from "@/shared/api/queries";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Textarea } from "@/shared/ui/textarea";

export function InstructionsSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: settings } = useSettings();
  const [draft, setDraft] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const saved = settings?.globalInstructions ?? "";
  const text = draft ?? saved;

  const save = async () => {
    setSaving(true);
    try {
      await unwrap(api().PUT("/api/v1/me/settings", { body: { globalInstructions: text } }));
      await queryClient.invalidateQueries({ queryKey: queryKeys.settings });
      setDraft(null);
      toast.success(t("settings.instructionsSaved"));
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <Field>
        <FieldLabel htmlFor="global-instructions">{t("settings.instructions")}</FieldLabel>
        <FieldDescription>{t("settings.instructionsDescription")}</FieldDescription>
        <Textarea
          id="global-instructions"
          value={text}
          onChange={(event) => setDraft(event.target.value)}
          placeholder={t("settings.instructionsPlaceholder")}
          rows={10}
          className="font-mono text-xs"
        />
      </Field>
      <div className="flex justify-end">
        <Button disabled={saving || text === saved} onClick={() => void save()}>
          {t("common.save")}
        </Button>
      </div>
    </div>
  );
}
