import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { PencilIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useSlashCommands } from "@/shared/api/queries";
import { toAppError, type SlashCommand } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Textarea } from "@/shared/ui/textarea";

const NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,39}$/;

type Draft = { id: string | null; name: string; description: string; prompt: string };

const emptyDraft: Draft = { id: null, name: "", description: "", prompt: "" };

export function CommandsSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: commands } = useSlashCommands();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.commands });

  const save = async (value: Draft) => {
    setSaving(true);
    const body = { name: value.name, description: value.description, prompt: value.prompt };
    try {
      await unwrap(
        value.id
          ? api().PUT("/api/v1/commands/{commandId}", {
              params: { path: { commandId: value.id } },
              body,
            })
          : api().POST("/api/v1/commands", { body }),
      );
      await refresh();
      setDraft(null);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (command: SlashCommand) => {
    try {
      await unwrap(
        api().DELETE("/api/v1/commands/{commandId}", {
          params: { path: { commandId: command.id } },
        }),
      );
      await refresh();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{t("commands.description")}</p>
      {commands?.length === 0 && !draft && (
        <p className="text-sm text-muted-foreground">{t("commands.empty")}</p>
      )}
      <ul className="flex flex-col divide-y rounded-md border empty:hidden">
        {commands?.map((command) => (
          <li key={command.id} className="flex items-start gap-3 px-3 py-2">
            <div className="min-w-0 flex-1">
              <p className="font-mono text-sm">/{command.name}</p>
              <p className="truncate text-xs text-muted-foreground">
                {command.description || command.prompt}
              </p>
            </div>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t("commands.edit")}
              onClick={() =>
                setDraft({
                  id: command.id,
                  name: command.name,
                  description: command.description,
                  prompt: command.prompt,
                })
              }
            >
              <PencilIcon />
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t("commands.delete")}
              onClick={() => void remove(command)}
            >
              <Trash2Icon />
            </Button>
          </li>
        ))}
      </ul>
      {draft ? (
        <CommandForm
          draft={draft}
          saving={saving}
          onChange={setDraft}
          onCancel={() => setDraft(null)}
          onSave={(value) => void save(value)}
        />
      ) : (
        <Button variant="outline" className="self-start" onClick={() => setDraft(emptyDraft)}>
          <PlusIcon />
          {t("commands.add")}
        </Button>
      )}
    </div>
  );
}

function CommandForm({
  draft,
  saving,
  onChange,
  onCancel,
  onSave,
}: {
  draft: Draft;
  saving: boolean;
  onChange: (draft: Draft) => void;
  onCancel: () => void;
  onSave: (draft: Draft) => void;
}) {
  const { t } = useTranslation();
  const nameValid = NAME_PATTERN.test(draft.name);
  const valid = nameValid && draft.prompt.trim().length > 0;

  return (
    <div className="flex flex-col gap-3 rounded-md border p-3">
      <Field>
        <FieldLabel htmlFor="command-name">{t("commands.name")}</FieldLabel>
        <Input
          id="command-name"
          value={draft.name}
          maxLength={40}
          placeholder="review"
          className="font-mono"
          aria-invalid={draft.name.length > 0 && !nameValid}
          onChange={(event) => onChange({ ...draft, name: event.target.value.toLowerCase() })}
        />
        <FieldDescription>{t("commands.nameHint")}</FieldDescription>
      </Field>
      <Field>
        <FieldLabel htmlFor="command-description">{t("commands.descriptionLabel")}</FieldLabel>
        <Input
          id="command-description"
          value={draft.description}
          maxLength={200}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
        />
      </Field>
      <Field>
        <FieldLabel htmlFor="command-prompt">{t("commands.prompt")}</FieldLabel>
        <Textarea
          id="command-prompt"
          value={draft.prompt}
          rows={6}
          maxLength={20_000}
          className="font-mono text-xs"
          placeholder={t("commands.promptPlaceholder")}
          onChange={(event) => onChange({ ...draft, prompt: event.target.value })}
        />
        <FieldDescription>{t("commands.promptHint")}</FieldDescription>
      </Field>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button disabled={!valid || saving} onClick={() => onSave(draft)}>
          {t("common.save")}
        </Button>
      </div>
    </div>
  );
}
