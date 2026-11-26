import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { BoxIcon, Loader2Icon, TriangleAlertIcon } from "lucide-react";
import { queryKeys } from "@/shared/api/queries";
import type { SandboxState, SandboxStatus } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { formatMegabytes } from "./sandbox-format";

const REFRESH_AFTER_TURN_MS = 1500;

type SandboxControlProps = {
  sessionId: string;
  sandbox: SandboxStatus;
  busy: boolean;
  compact: boolean;
};

export function SandboxControl({ sessionId, sandbox, busy, compact }: SandboxControlProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (busy) {
      return;
    }
    const timer = window.setTimeout(
      () => void queryClient.invalidateQueries({ queryKey: queryKeys.sandbox(sessionId) }),
      REFRESH_AFTER_TURN_MS,
    );
    return () => window.clearTimeout(timer);
  }, [busy, queryClient, sessionId]);

  const openDetails = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.sandbox(sessionId) });
    setOpen(true);
  };

  return (
    <>
      <Button
        size="xs"
        variant="outline"
        className="max-w-40 min-w-0 shrink"
        onClick={openDetails}
        title={t("sandbox.session.hint", { state: t(`sandbox.state.${sandbox.status}`) })}
        aria-label={t("sandbox.session.hint", { state: t(`sandbox.badge.${sandbox.status}`) })}
      >
        <SandboxIcon state={sandbox.status} />
        <span className={cn("truncate", compact && "sr-only")}>
          {t(`sandbox.badge.${sandbox.status}`)}
        </span>
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <BoxIcon className="size-4" />
              {t("sandbox.session.title")}
            </DialogTitle>
            <DialogDescription>{t("sandbox.session.description")}</DialogDescription>
          </DialogHeader>
          <SandboxDetails sandbox={sandbox} />
        </DialogContent>
      </Dialog>
    </>
  );
}

function SandboxIcon({ state }: { state: SandboxState }) {
  if (state === "queued" || state === "starting") {
    return <Loader2Icon className="animate-spin" />;
  }
  if (state === "failed") {
    return <TriangleAlertIcon className="text-destructive" />;
  }
  return <BoxIcon className={cn(state === "ready" && "text-emerald-600 dark:text-emerald-400")} />;
}

function SandboxDetails({ sandbox }: { sandbox: SandboxStatus }) {
  const { t, i18n } = useTranslation();
  const lastSync =
    sandbox.syncedAt === null
      ? t("sandbox.session.notSynced")
      : t("sandbox.session.syncSummary", {
          files: sandbox.syncedFiles ?? 0,
          size: formatMegabytes(sandbox.syncedBytes ?? 0, i18n.language),
          time: new Date(sandbox.syncedAt).toLocaleString(i18n.language),
        });

  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
        <dt className="text-muted-foreground">{t("sandbox.session.stateLabel")}</dt>
        <dd>{t(`sandbox.state.${sandbox.status}`)}</dd>
        {sandbox.workspace && (
          <>
            <dt className="text-muted-foreground">{t("sandbox.session.workspace")}</dt>
            <dd className="truncate font-mono">{sandbox.workspace}</dd>
          </>
        )}
        <dt className="text-muted-foreground">{t("sandbox.session.lastSync")}</dt>
        <dd>{lastSync}</dd>
      </dl>
      {sandbox.error && (
        <p className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs">
          {sandbox.error}
        </p>
      )}
      <p className="text-xs text-muted-foreground">{t("sandbox.session.privacy")}</p>
    </div>
  );
}
