import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  CheckIcon,
  PencilIcon,
  PinIcon,
  PinOffIcon,
  PlusIcon,
  Trash2Icon,
  Undo2Icon,
  XIcon,
} from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { useMemories } from "@/shared/api/queries";
import { undoMemory } from "./memory-api";
import { toAppError, type Memory, type MemoryKind } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Textarea } from "@/shared/ui/textarea";

const NAME_PATTERN = /^[a-z0-9][a-z0-9-]{1,63}$/;
const KINDS: MemoryKind[] = ["user", "feedback", "project", "reference"];

type Draft = {
  id: string | null;
  kind: MemoryKind;
  name: string;
  description: string;
  content: string;
};

export function MemoryList({ projectId }: { projectId: string | null }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: memories } = useMemories(projectId, "active");
  const { data: suggested } = useMemories(projectId, "suggested");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [lastDeleted, setLastDeleted] = useState<Memory | null>(null);
  const [saving, setSaving] = useState(false);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["memories"] });

  const run = async (action: () => Promise<unknown>) => {
    try {
      await action();
      await refresh();
      return true;
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      return false;
    }
  };

  const save = async (value: Draft) => {
    setSaving(true);
    const saved = await run(() =>
      unwrap(
        value.id
          ? api().PATCH("/api/v1/memories/{memoryId}", {
              params: { path: { memoryId: value.id } },
              body: { kind: value.kind, description: value.description, content: value.content },
            })
          : api().POST("/api/v1/memories", {
              body: {
                projectId: projectId ?? undefined,
                kind: value.kind,
                name: value.name,
                description: value.description,
                content: value.content,
              },
            }),
      ),
    );
    setSaving(false);
    if (saved) {
      setDraft(null);
    }
  };

  const togglePin = (memory: Memory) =>
    void run(() =>
      unwrap(
        api().PATCH("/api/v1/memories/{memoryId}", {
          params: { path: { memoryId: memory.id } },
          body: { pinned: !memory.pinned },
        }),
      ),
    );

  const remove = async (memory: Memory) => {
    if (
      await run(() =>
        unwrap(
          api().DELETE("/api/v1/memories/{memoryId}", {
            params: { path: { memoryId: memory.id } },
          }),
        ),
      )
    ) {
      setLastDeleted(memory);
    }
  };

  const undoDelete = async (memory: Memory) => {
    if (await run(() => undoMemory(memory.id))) {
      setLastDeleted(null);
    }
  };

  const decide = (memory: Memory, decision: "accept" | "dismiss") =>
    void run(() =>
      unwrap(
        decision === "accept"
          ? api().POST("/api/v1/memories/{memoryId}/accept", {
              params: { path: { memoryId: memory.id } },
            })
          : api().POST("/api/v1/memories/{memoryId}/dismiss", {
              params: { path: { memoryId: memory.id } },
            }),
      ),
    );

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        {projectId ? t("memory.projectDescription") : t("memory.userDescription")}
      </p>
      {suggested && suggested.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-sm font-medium">
            {t("memory.suggested", { count: suggested.length })}
          </p>
          <ul className="flex flex-col divide-y rounded-md border border-dashed">
            {suggested.map((memory) => (
              <li key={memory.id} className="flex items-start gap-3 px-3 py-2">
                <MemorySummary memory={memory} expanded />
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("memory.accept")}
                  onClick={() => decide(memory, "accept")}
                >
                  <CheckIcon />
                </Button>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={t("memory.dismiss")}
                  onClick={() => decide(memory, "dismiss")}
                >
                  <XIcon />
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {memories?.length === 0 && !draft && (
        <p className="text-sm text-muted-foreground">{t("memory.empty")}</p>
      )}
      {lastDeleted && (
        <div className="flex items-center gap-2 rounded-md bg-muted/50 px-3 py-2 text-sm">
          <span className="min-w-0 flex-1 truncate">
            {t("memory.deleted", { name: lastDeleted.name })}
          </span>
          <Button size="sm" variant="ghost" onClick={() => void undoDelete(lastDeleted)}>
            <Undo2Icon />
            {t("memory.undo")}
          </Button>
        </div>
      )}
      <ul className="flex flex-col divide-y rounded-md border empty:hidden">
        {memories?.map((memory) => (
          <li key={memory.id} className="flex items-start gap-2 px-3 py-2">
            <MemorySummary memory={memory} />
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={memory.pinned ? t("memory.unpin") : t("memory.pin")}
              title={memory.pinned ? t("memory.unpin") : t("memory.pin")}
              onClick={() => togglePin(memory)}
            >
              {memory.pinned ? <PinOffIcon /> : <PinIcon />}
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t("memory.edit")}
              onClick={() =>
                setDraft({
                  id: memory.id,
                  kind: memory.kind,
                  name: memory.name,
                  description: memory.description,
                  content: memory.content,
                })
              }
            >
              <PencilIcon />
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={t("memory.delete")}
              onClick={() => void remove(memory)}
            >
              <Trash2Icon />
            </Button>
          </li>
        ))}
      </ul>
      {draft ? (
        <MemoryForm
          draft={draft}
          saving={saving}
          onChange={setDraft}
          onCancel={() => setDraft(null)}
          onSave={(value) => void save(value)}
        />
      ) : (
        <Button
          variant="outline"
          className="self-start"
          onClick={() =>
            setDraft({
              id: null,
              kind: projectId ? "project" : "user",
              name: "",
              description: "",
              content: "",
            })
          }
        >
          <PlusIcon />
          {t("memory.add")}
        </Button>
      )}
    </div>
  );
}

function MemorySummary({ memory, expanded = false }: { memory: Memory; expanded?: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(expanded);

  return (
    <button
      type="button"
      className="min-w-0 flex-1 text-left"
      onClick={() => setOpen(!open)}
      aria-expanded={open}
    >
      <div className="flex items-center gap-2">
        <span className="truncate font-mono text-sm">{memory.name}</span>
        <Badge variant="secondary">{t(`memory.kinds.${memory.kind}`)}</Badge>
        {memory.pinned && <PinIcon className="size-3 text-muted-foreground" />}
      </div>
      <p
        className={
          open ? "text-xs text-muted-foreground" : "truncate text-xs text-muted-foreground"
        }
      >
        {memory.description}
      </p>
      {open && <p className="mt-1 text-xs whitespace-pre-wrap">{memory.content}</p>}
    </button>
  );
}

function MemoryForm({
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
  const valid = nameValid && draft.description.trim().length > 0 && draft.content.trim().length > 0;

  return (
    <div className="flex flex-col gap-3 rounded-md border p-3">
      <div className="flex flex-wrap gap-3">
        <Field className="min-w-48 flex-1">
          <FieldLabel htmlFor="memory-name">{t("memory.name")}</FieldLabel>
          <Input
            id="memory-name"
            value={draft.name}
            maxLength={64}
            placeholder="use-pnpm"
            className="font-mono"
            disabled={draft.id !== null}
            aria-invalid={draft.name.length > 0 && !nameValid}
            onChange={(event) => onChange({ ...draft, name: event.target.value })}
          />
        </Field>
        <Field className="w-auto">
          <FieldLabel>{t("memory.kind")}</FieldLabel>
          <div className="flex flex-wrap gap-1">
            {KINDS.map((kind) => (
              <Button
                key={kind}
                size="sm"
                variant={draft.kind === kind ? "secondary" : "ghost"}
                aria-pressed={draft.kind === kind}
                onClick={() => onChange({ ...draft, kind })}
              >
                {t(`memory.kinds.${kind}`)}
              </Button>
            ))}
          </div>
        </Field>
      </div>
      <Field>
        <FieldLabel htmlFor="memory-description">{t("memory.descriptionLabel")}</FieldLabel>
        <Input
          id="memory-description"
          value={draft.description}
          maxLength={200}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
        />
        <FieldDescription>{t("memory.descriptionHint")}</FieldDescription>
      </Field>
      <Field>
        <FieldLabel htmlFor="memory-content">{t("memory.content")}</FieldLabel>
        <Textarea
          id="memory-content"
          value={draft.content}
          maxLength={4000}
          rows={4}
          onChange={(event) => onChange({ ...draft, content: event.target.value })}
        />
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
