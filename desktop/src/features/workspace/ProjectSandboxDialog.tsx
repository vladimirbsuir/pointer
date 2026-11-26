import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { MonitorIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useRemoteStatus } from "@/shared/api/queries";
import { toAppError, type Project } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { Switch } from "@/shared/ui/switch";

type ProjectSandboxDialogProps = {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ProjectSandboxDialog({ project, open, onOpenChange }: ProjectSandboxDialogProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: remote } = useRemoteStatus();
  const [saving, setSaving] = useState(false);
  const available = remote?.enabled ?? false;

  const change = async (enabled: boolean) => {
    setSaving(true);
    try {
      await unwrap(
        api().PATCH("/api/v1/projects/{projectId}", {
          params: { path: { projectId: project.id } },
          body: { sandboxEnabled: enabled },
        }),
      );
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects });
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MonitorIcon className="size-4" />
            {t("sandbox.title", { project: project.name })}
          </DialogTitle>
          <DialogDescription>{t("sandbox.description")}</DialogDescription>
        </DialogHeader>
        <label className="flex items-start gap-3">
          <Switch
            checked={project.sandboxEnabled}
            disabled={saving || (!available && !project.sandboxEnabled)}
            onCheckedChange={(enabled) => void change(enabled)}
            className="mt-0.5"
          />
          <span className="flex flex-col gap-0.5">
            <span className="text-sm font-medium">{t("sandbox.enabled")}</span>
            <span className="text-xs text-muted-foreground">
              {available ? t("sandbox.enabledHint") : t("remote.disabled")}
            </span>
          </span>
        </label>
      </DialogContent>
    </Dialog>
  );
}
