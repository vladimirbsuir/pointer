import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useChangedFiles } from "./use-changed-files";
import { toast } from "sonner";
import { structuredPatch } from "diff";
import { ChevronRightIcon, RotateCcwIcon, XIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { toAppError, type AppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { DiffView } from "./DiffView";

export type ChangedFile = {
  path: string;
  changeKind: "created" | "modified" | "deleted";
  linesAdded: number;
  linesRemoved: number;
  changeCount: number;
  reverted: boolean;
  lastChangedAt: string;
};

type FileDiff = {
  path: string;
  before: string | null;
  after: string | null;
  unavailable: boolean;
};

function unifiedDiff(diff: FileDiff) {
  const patch = structuredPatch(diff.path, diff.path, diff.before ?? "", diff.after ?? "", "", "", {
    context: 3,
  });
  return patch.hunks
    .flatMap((hunk) => [
      `@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`,
      ...hunk.lines,
    ])
    .join("\n");
}

type ChangesPanelProps = {
  sessionId: string;
  version: number;
  busy: boolean;
  onClose: () => void;
};

export function ChangesPanel({ sessionId, version, busy, onClose }: ChangesPanelProps) {
  const { t } = useTranslation();
  const { data: files } = useChangedFiles(sessionId, version);

  return (
    <aside className="flex w-[min(26rem,45%)] shrink-0 flex-col border-l bg-background">
      <div className="flex items-center justify-between border-b px-4 py-2">
        <h3 className="text-sm font-medium">{t("changes.title")}</h3>
        <Button size="icon-sm" variant="ghost" onClick={onClose} aria-label={t("changes.close")}>
          <XIcon />
        </Button>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {files?.length === 0 && (
          <p className="p-4 text-center text-sm text-muted-foreground">{t("changes.empty")}</p>
        )}
        {files?.map((file) => (
          <ChangedFileRow
            key={file.path}
            sessionId={sessionId}
            file={file}
            version={version}
            busy={busy}
          />
        ))}
      </div>
    </aside>
  );
}

type RowProps = { sessionId: string; file: ChangedFile; version: number; busy: boolean };

function ChangedFileRow({ sessionId, file, version, busy }: RowProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState<"revert" | "force" | null>(null);
  const [reverting, setReverting] = useState(false);
  const name = file.path.split("/").pop() ?? file.path;
  const folder = file.path.slice(0, file.path.length - name.length);

  const diff = useQuery({
    queryKey: ["change", sessionId, file.path, version],
    enabled: open,
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/sessions/{sessionId}/changes/file", {
          params: { path: { sessionId }, query: { path: file.path } },
        }),
      )) as FileDiff,
  });

  const revert = async (force: boolean) => {
    setReverting(true);
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/changes/revert", {
          params: { path: { sessionId } },
          body: { path: file.path, force },
        }),
      );
      setConfirming(null);
      toast.success(t("changes.revertedToast", { path: file.path }));
      await queryClient.invalidateQueries({ queryKey: ["changes", sessionId] });
    } catch (error) {
      const appError: AppError = toAppError(error);
      if (appError.code === "file_modified" && !force) {
        setConfirming("force");
      } else {
        setConfirming(null);
        toast.error(errorMessage(t, appError));
      }
    } finally {
      setReverting(false);
    }
  };

  return (
    <div className="rounded-md">
      <div className="group flex items-center gap-1 rounded-md px-2 py-1.5 text-sm hover:bg-muted/60">
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          onClick={() => setOpen((value) => !value)}
        >
          <ChevronRightIcon
            className={cn("size-3.5 shrink-0 transition-transform", open && "rotate-90")}
          />
          <span className={cn("truncate", file.reverted && "text-muted-foreground line-through")}>
            <span className="font-medium">{name}</span>
            <span className="ml-1.5 text-xs text-muted-foreground">{folder}</span>
          </span>
        </button>
        {file.changeKind !== "modified" && (
          <span className="text-xs text-muted-foreground">{t(`changes.${file.changeKind}`)}</span>
        )}
        <span className="text-xs text-emerald-600 tabular-nums dark:text-emerald-400">
          +{file.linesAdded}
        </span>
        <span className="text-xs text-red-600 tabular-nums dark:text-red-400">
          −{file.linesRemoved}
        </span>
        {file.reverted ? (
          <span className="ml-1 text-xs text-muted-foreground">{t("changes.reverted")}</span>
        ) : (
          <Button
            size="icon-xs"
            variant="ghost"
            disabled={busy || reverting}
            title={t("changes.revert")}
            aria-label={t("changes.revert")}
            onClick={() => setConfirming(confirming ? null : "revert")}
          >
            <RotateCcwIcon />
          </Button>
        )}
      </div>
      {confirming && (
        <div className="mx-2 mb-2 flex items-center justify-between gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
          <span>
            {t(confirming === "force" ? "changes.revertForce" : "changes.revertConfirm", {
              path: file.path,
            })}
          </span>
          <div className="flex shrink-0 gap-1">
            <Button size="xs" variant="ghost" onClick={() => setConfirming(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              size="xs"
              variant="destructive"
              disabled={reverting}
              onClick={() => void revert(confirming === "force")}
            >
              {t("changes.revert")}
            </Button>
          </div>
        </div>
      )}
      {open && diff.data && (
        <div className="px-2 pb-2">
          {diff.data.unavailable ? (
            <p className="text-xs text-muted-foreground">{t("changes.unavailable")}</p>
          ) : (
            <DiffView diff={unifiedDiff(diff.data)} />
          )}
        </div>
      )}
    </div>
  );
}
