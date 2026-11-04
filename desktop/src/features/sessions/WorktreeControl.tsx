import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { revealItemInDir } from "@tauri-apps/plugin-opener";
import { FolderOpenIcon, GitBranchIcon, GitMergeIcon, Trash2Icon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { toAppError, type Session, type WorktreeStatus } from "@/shared/api/types";
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
import { Input } from "@/shared/ui/input";

type WorktreeControlProps = {
  session: Session;
  busy: boolean;
  onChanged: (session: Session) => void;
};

export function WorktreeControl({ session, busy, onChanged }: WorktreeControlProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const worktree = session.worktree;

  if (worktree?.state !== "active") {
    return null;
  }

  return (
    <>
      <Button
        size="xs"
        variant="outline"
        className="max-w-48 shrink-0 font-mono"
        title={t("worktree.hint", { branch: worktree.branch, base: worktree.baseBranch })}
        onClick={() => setOpen(true)}
      >
        <GitBranchIcon />
        <span className="truncate">{worktree.branch}</span>
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-lg">
          {open && (
            <WorktreeDialogBody
              session={session}
              busy={busy}
              onDone={(updated) => {
                setOpen(false);
                onChanged(updated);
              }}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function WorktreeDialogBody({
  session,
  busy,
  onDone,
}: {
  session: Session;
  busy: boolean;
  onDone: (session: Session) => void;
}) {
  const { t } = useTranslation();
  const [message, setMessage] = useState(session.title);
  const [pending, setPending] = useState<"merge" | "discard" | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const status = useQuery({
    queryKey: ["worktree", session.id],
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/sessions/{sessionId}/worktree", {
          params: { path: { sessionId: session.id } },
        }),
      )) as unknown as WorktreeStatus,
    retry: false,
  });
  const worktree = session.worktree!;

  const run = async (action: "merge" | "discard") => {
    setPending(action);
    try {
      const updated = (await unwrap(
        action === "merge"
          ? api().POST("/api/v1/sessions/{sessionId}/worktree/merge", {
              params: { path: { sessionId: session.id } },
              body: { message },
            })
          : api().POST("/api/v1/sessions/{sessionId}/worktree/discard", {
              params: { path: { sessionId: session.id } },
            }),
      )) as Session;
      toast.success(
        t(action === "merge" ? "worktree.merged" : "worktree.discarded", {
          base: worktree.baseBranch,
        }),
      );
      onDone(updated);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      void status.refetch();
    } finally {
      setPending(null);
      setConfirmDiscard(false);
    }
  };

  const reveal = async (path: string) => {
    try {
      await revealItemInDir(path);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const data = status.data;
  const nothingToMerge =
    data !== undefined && data.commitsAhead === 0 && data.uncommittedFiles.length === 0;

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <GitBranchIcon className="size-4" />
          <span className="font-mono">{worktree.branch}</span>
        </DialogTitle>
        <DialogDescription>
          {t("worktree.description", { base: worktree.baseBranch })}
        </DialogDescription>
      </DialogHeader>
      <div className="flex flex-col gap-3 text-sm">
        {status.isPending && <p className="text-muted-foreground">{t("worktree.loading")}</p>}
        {status.error && (
          <p className="text-destructive">{errorMessage(t, toAppError(status.error))}</p>
        )}
        {data && (
          <>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 truncate rounded bg-muted px-2 py-1 text-xs">
                {data.path}
              </code>
              <Button
                size="icon-sm"
                variant="ghost"
                title={t("worktree.reveal")}
                aria-label={t("worktree.reveal")}
                onClick={() => void reveal(data.path)}
              >
                <FolderOpenIcon />
              </Button>
            </div>
            <p className="text-muted-foreground">
              {t("worktree.summary", {
                commits: data.commitsAhead,
                files: data.uncommittedFiles.length,
              })}
            </p>
            {data.uncommittedFiles.length > 0 && (
              <ul className="max-h-32 overflow-y-auto rounded border px-2 py-1 font-mono text-xs">
                {data.uncommittedFiles.map((file) => (
                  <li key={file} className="truncate">
                    {file}
                  </li>
                ))}
              </ul>
            )}
            {!data.baseCheckedOut && (
              <p className="rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
                {t("worktree.baseNotCheckedOut", { base: worktree.baseBranch })}
              </p>
            )}
            {data.uncommittedFiles.length > 0 && (
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                {t("worktree.commitMessage")}
                <Input
                  value={message}
                  maxLength={500}
                  onChange={(event) => setMessage(event.target.value)}
                />
              </label>
            )}
          </>
        )}
        {busy && <p className="text-xs text-muted-foreground">{t("worktree.busy")}</p>}
      </div>
      <DialogFooter>
        {confirmDiscard ? (
          <>
            <span className="mr-auto self-center text-xs">{t("worktree.confirmDiscard")}</span>
            <Button variant="ghost" onClick={() => setConfirmDiscard(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={pending !== null}
              onClick={() => void run("discard")}
            >
              <Trash2Icon />
              {t("worktree.discard")}
            </Button>
          </>
        ) : (
          <>
            <Button
              variant="outline"
              disabled={busy || pending !== null}
              onClick={() => setConfirmDiscard(true)}
            >
              <Trash2Icon />
              {t("worktree.discard")}
            </Button>
            <Button
              disabled={busy || pending !== null || !data || !data.baseCheckedOut}
              onClick={() => void run("merge")}
            >
              <GitMergeIcon />
              {nothingToMerge
                ? t("worktree.close")
                : t("worktree.merge", { base: worktree.baseBranch })}
            </Button>
          </>
        )}
      </DialogFooter>
    </>
  );
}
