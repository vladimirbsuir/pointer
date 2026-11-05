import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  CloudAlertIcon,
  CloudIcon,
  ExternalLinkIcon,
  GitPullRequestCreateIcon,
  GitPullRequestIcon,
  Loader2Icon,
} from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys, useRemoteWorkspace } from "@/shared/api/queries";
import { toAppError, type RemoteWorkspace } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { openExternal } from "@/shared/lib/open-external";
import { Button } from "@/shared/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";

const PUSH_REFRESH_DELAYS_MS = [1500, 5000];

type RemoteControlProps = {
  sessionId: string;
  busy: boolean;
};

export function RemoteControl({ sessionId, busy }: RemoteControlProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const { data: workspace } = useRemoteWorkspace(sessionId, true, busy);

  useEffect(() => {
    if (busy) {
      return;
    }
    const timers = PUSH_REFRESH_DELAYS_MS.map((delay) =>
      window.setTimeout(
        () =>
          void queryClient.invalidateQueries({ queryKey: queryKeys.remoteWorkspace(sessionId) }),
        delay,
      ),
    );
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [busy, queryClient, sessionId]);

  if (!workspace) {
    return null;
  }

  return (
    <>
      <Button
        size="xs"
        variant="outline"
        className="max-w-64"
        onClick={() => setOpen(true)}
        title={t(`remote.status.${workspace.status}`)}
      >
        <StatusIcon workspace={workspace} />
        <span className="truncate">{workspace.branch}</span>
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <CloudIcon className="size-4" />
              {t("remote.title")}
            </DialogTitle>
            <DialogDescription>{t("remote.description")}</DialogDescription>
          </DialogHeader>
          <RemoteDetails workspace={workspace} sessionId={sessionId} busy={busy} />
        </DialogContent>
      </Dialog>
    </>
  );
}

function StatusIcon({ workspace }: { workspace: RemoteWorkspace }) {
  if (workspace.status === "starting" || workspace.status === "preparing") {
    return <Loader2Icon className="animate-spin" />;
  }
  if (workspace.status === "failed" || workspace.error) {
    return <CloudAlertIcon className="text-destructive" />;
  }
  return <CloudIcon />;
}

function RemoteDetails({
  workspace,
  sessionId,
  busy,
}: {
  workspace: RemoteWorkspace;
  sessionId: string;
  busy: boolean;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [opening, setOpening] = useState(false);

  const openPullRequest = async () => {
    setOpening(true);
    try {
      const { url } = await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/remote/pull-request", {
          params: { path: { sessionId } },
        }),
      );
      await queryClient.invalidateQueries({ queryKey: queryKeys.remoteWorkspace(sessionId) });
      if (url) {
        await openExternal(url);
      }
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setOpening(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        <dt className="text-muted-foreground">{t("remote.repository")}</dt>
        <dd className="truncate font-mono">{workspace.repository}</dd>
        <dt className="text-muted-foreground">{t("remote.branch")}</dt>
        <dd className="truncate font-mono">
          {workspace.branch}
          {workspace.baseBranch && (
            <span className="text-muted-foreground"> ← {workspace.baseBranch}</span>
          )}
        </dd>
        <dt className="text-muted-foreground">{t("remote.statusLabel")}</dt>
        <dd>{t(`remote.status.${workspace.status}`)}</dd>
        <dt className="text-muted-foreground">{t("remote.lastPush")}</dt>
        <dd className="font-mono">
          {workspace.pushedCommit ? workspace.pushedCommit.slice(0, 7) : t("remote.notPushed")}
        </dd>
      </dl>
      {workspace.error && (
        <p className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs">
          {workspace.error}
        </p>
      )}
      <p className="text-xs text-muted-foreground">{t("remote.pushHint")}</p>
      <DialogFooter>
        {workspace.branchUrl && (
          <Button variant="outline" onClick={() => void openExternal(workspace.branchUrl ?? "")}>
            <ExternalLinkIcon />
            {t("remote.openBranch")}
          </Button>
        )}
        {workspace.pullRequestUrl ? (
          <Button onClick={() => void openExternal(workspace.pullRequestUrl ?? "")}>
            <GitPullRequestIcon />
            {t("remote.openPullRequest")}
          </Button>
        ) : (
          <Button
            disabled={!workspace.pushedCommit || busy || opening}
            onClick={() => void openPullRequest()}
          >
            <GitPullRequestCreateIcon />
            {t("remote.createPullRequest")}
          </Button>
        )}
      </DialogFooter>
    </div>
  );
}
