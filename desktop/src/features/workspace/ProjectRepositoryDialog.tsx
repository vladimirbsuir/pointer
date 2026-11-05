import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { FolderGitIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { core } from "@/shared/api/core";
import { queryKeys } from "@/shared/api/queries";
import { toAppError, type Project } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";

const REPOSITORY_PATTERN = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;

type ProjectRepositoryDialogProps = {
  project: Project;
  linked: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ProjectRepositoryDialog({
  project,
  linked,
  open,
  onOpenChange,
}: ProjectRepositoryDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <FolderGitIcon className="size-4" />
            {t("repository.title", { project: project.name })}
          </DialogTitle>
          <DialogDescription>{t("repository.description")}</DialogDescription>
        </DialogHeader>
        {open && (
          <RepositoryForm project={project} linked={linked} onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function RepositoryForm({
  project,
  linked,
  onDone,
}: {
  project: Project;
  linked: boolean;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [value, setValue] = useState(project.repository ?? "");
  const [saving, setSaving] = useState(false);
  const trimmed = value.trim();
  const valid = trimmed === "" || REPOSITORY_PATTERN.test(trimmed);

  const detect = async () => {
    try {
      const detected = await core.projectRepository(project.id);
      if (detected) {
        setValue(detected);
      } else {
        toast.info(t("repository.notDetected"));
      }
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid) {
      return;
    }
    setSaving(true);
    try {
      await unwrap(
        api().PATCH("/api/v1/projects/{projectId}", {
          params: { path: { projectId: project.id } },
          body: { repository: trimmed },
        }),
      );
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects });
      onDone();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="flex flex-col gap-4" onSubmit={(event) => void save(event)}>
      <Field>
        <FieldLabel htmlFor="project-repository">{t("repository.label")}</FieldLabel>
        <div className="flex gap-2">
          <Input
            id="project-repository"
            value={value}
            placeholder="owner/name"
            onChange={(event) => setValue(event.target.value)}
            aria-invalid={!valid}
          />
          {linked && (
            <Button type="button" variant="outline" onClick={() => void detect()}>
              {t("repository.detect")}
            </Button>
          )}
        </div>
        <FieldDescription>{t("repository.hint")}</FieldDescription>
      </Field>
      <DialogFooter>
        <Button type="submit" disabled={!valid || saving}>
          {t("common.save")}
        </Button>
      </DialogFooter>
    </form>
  );
}
