import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { DatabaseZapIcon, Loader2Icon, RefreshCwIcon, Trash2Icon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useProjectIndex, useProviders, useSettings } from "@/shared/api/queries";
import { toAppError, type Project, type ProjectIndex } from "@/shared/api/types";
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

type ProjectIndexDialogProps = {
  project: Project;
  linked: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ProjectIndexDialog({
  project,
  linked,
  open,
  onOpenChange,
}: ProjectIndexDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <DatabaseZapIcon className="size-4" />
            {t("index.title", { project: project.name })}
          </DialogTitle>
          <DialogDescription>{t("index.description")}</DialogDescription>
        </DialogHeader>
        {open && <IndexBody project={project} linked={linked} />}
      </DialogContent>
    </Dialog>
  );
}

function IndexBody({ project, linked }: { project: Project; linked: boolean }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { data: index, error } = useProjectIndex(project.id);
  const { data: settings } = useSettings();
  const { data: providers } = useProviders();
  const [pending, setPending] = useState(false);
  const embeddingModel = providers
    ?.flatMap((provider) => provider.models)
    .find((model) => model.id === settings?.embeddingModelId);
  const indexing = index?.status === "indexing";
  const modelChanged =
    index?.status !== "none" &&
    Boolean(settings?.embeddingModelId) &&
    index?.embeddingModelId !== settings?.embeddingModelId;

  const run = async (action: () => Promise<unknown>) => {
    setPending(true);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: queryKeys.projectIndex(project.id) });
    } catch (actionError) {
      toast.error(errorMessage(t, toAppError(actionError)));
    } finally {
      setPending(false);
    }
  };

  const start = (rebuild: boolean) =>
    run(() =>
      unwrap(
        api().POST("/api/v1/projects/{projectId}/index", {
          params: { path: { projectId: project.id } },
          body: { rebuild },
        }),
      ),
    );

  const remove = () =>
    run(() =>
      unwrap(
        api().DELETE("/api/v1/projects/{projectId}/index", {
          params: { path: { projectId: project.id } },
        }),
      ),
    );

  return (
    <>
      <div className="flex flex-col gap-3 text-sm">
        {error && <p className="text-destructive">{errorMessage(t, toAppError(error))}</p>}
        {index && <IndexSummary index={index} locale={i18n.language} />}
        <p className="text-muted-foreground">
          {embeddingModel
            ? t("index.model", { model: embeddingModel.displayName })
            : t("index.noModel")}
        </p>
        {modelChanged && <p className="text-amber-600">{t("index.modelChanged")}</p>}
        {!linked && <p className="text-amber-600">{t("index.notLinked")}</p>}
      </div>
      <DialogFooter>
        {index && index.status !== "none" && (
          <Button variant="ghost" disabled={pending || indexing} onClick={() => void remove()}>
            <Trash2Icon />
            {t("index.delete")}
          </Button>
        )}
        {index && index.status !== "none" && (
          <Button
            variant="outline"
            disabled={pending || indexing || !linked || !embeddingModel}
            onClick={() => void start(true)}
          >
            {t("index.rebuild")}
          </Button>
        )}
        <Button
          disabled={pending || indexing || !linked || !embeddingModel}
          onClick={() => void start(false)}
        >
          {indexing ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
          {index?.status === "none" ? t("index.build") : t("index.update")}
        </Button>
      </DialogFooter>
    </>
  );
}

function IndexSummary({ index, locale }: { index: ProjectIndex; locale: string }) {
  const { t } = useTranslation();

  switch (index.status) {
    case "none":
      return <p>{t("index.none")}</p>;
    case "indexing":
      return (
        <div className="flex flex-col gap-1.5">
          <p>{t("index.progress", { done: index.filesDone, total: index.filesTotal })}</p>
          <div className="h-1.5 overflow-hidden rounded-full bg-muted">
            <div
              className="h-full bg-primary transition-all"
              style={{
                width: `${index.filesTotal ? (index.filesDone / index.filesTotal) * 100 : 0}%`,
              }}
            />
          </div>
        </div>
      );
    default:
      return (
        <div className="flex flex-col gap-1">
          <p>{t("index.stats", { files: index.fileCount, chunks: index.chunkCount })}</p>
          {index.indexedAt && (
            <p className="text-muted-foreground">
              {t("index.indexedAt", {
                time: new Date(index.indexedAt).toLocaleString(locale),
              })}
            </p>
          )}
          {index.status === "failed" && (
            <p className="text-destructive">{index.error ?? t("errors.unknown")}</p>
          )}
        </div>
      );
  }
}
