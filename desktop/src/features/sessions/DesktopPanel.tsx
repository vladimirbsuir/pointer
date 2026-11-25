import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { MonitorOffIcon, RefreshCwIcon, XIcon } from "lucide-react";
import type { Item } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { useAttachmentUrl } from "./attachment-url";
import { collectScreenshots, desktopSummary, isDesktopTool } from "./desktop-calls";
import { useDesktopView, type DesktopViewState } from "./use-desktop-view";

type DesktopPanelProps = {
  sessionId: string;
  items: Item[];
  displayStarts: number;
  onClose: () => void;
};

export function DesktopPanel({ sessionId, items, displayStarts, onClose }: DesktopPanelProps) {
  const { t } = useTranslation();
  const [screen, setScreen] = useState<HTMLDivElement | null>(null);
  const { state, reconnect } = useDesktopView(sessionId, screen, displayStarts);
  const lastScreenshot = useMemo(() => collectScreenshots(t, items).at(-1) ?? null, [items, t]);
  const lastAction = useMemo(() => {
    const calls = items.filter(
      (item) => item.type === "tool_call" && isDesktopTool(String(item.toolName ?? "")),
    );
    const call = calls.at(-1);
    return call ? desktopSummary(t, call) : null;
  }, [items, t]);
  const live = state.kind === "live";

  return (
    <aside className="flex w-[min(40rem,55%)] shrink-0 flex-col border-l bg-background">
      <div className="flex items-center justify-between border-b px-4 py-2">
        <h3 className="text-sm font-medium">{t("desktopView.title")}</h3>
        <Button
          size="icon-sm"
          variant="ghost"
          onClick={onClose}
          aria-label={t("desktopView.close")}
        >
          <XIcon />
        </Button>
      </div>
      <div className="relative flex min-h-0 flex-1 items-center justify-center bg-muted/40 p-2">
        <div
          ref={setScreen}
          data-testid="desktop-screen"
          className={cn("pointer-events-none size-full", !live && "invisible")}
        />
        {!live && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 p-4 text-center">
            <Placeholder
              sessionId={sessionId}
              state={state}
              screenshot={lastScreenshot?.attachmentId ?? null}
            />
            {(state.kind === "failed" || state.kind === "slow" || state.kind === "paused") && (
              <Button size="sm" variant="outline" onClick={reconnect}>
                <RefreshCwIcon />
                {t("desktopView.reconnect")}
              </Button>
            )}
          </div>
        )}
      </div>
      <div className="flex items-center gap-2 border-t px-4 py-1.5 text-xs text-muted-foreground tabular-nums">
        {live && (
          <span>
            {state.width}×{state.height}
          </span>
        )}
        <span className={cn(live && "text-emerald-600 dark:text-emerald-400")}>
          {t(`desktopView.state.${state.kind}`)}
        </span>
        {lastAction && <span className="min-w-0 truncate">· {lastAction}</span>}
      </div>
    </aside>
  );
}

const NOTES = {
  connecting: "desktopView.note.offline",
  live: "desktopView.note.offline",
  offline: "desktopView.note.offline",
  failed: "desktopView.note.failed",
  busy: "desktopView.note.busy",
  slow: "desktopView.note.slow",
  paused: "desktopView.note.paused",
} as const;

function Placeholder({
  sessionId,
  state,
  screenshot,
}: {
  sessionId: string;
  state: DesktopViewState;
  screenshot: string | null;
}) {
  const { t } = useTranslation();
  if (state.kind === "connecting") {
    return (
      <span className="size-6 animate-spin rounded-full border-2 border-muted-foreground/40 border-t-transparent" />
    );
  }
  if (state.kind === "busy") {
    return <p className="text-sm text-muted-foreground">{t("desktopView.busy")}</p>;
  }
  if (!screenshot) {
    return (
      <>
        <MonitorOffIcon className="size-6 text-muted-foreground" />
        <p className="text-sm text-muted-foreground">
          {state.kind === "offline" ? t("desktopView.notStarted") : t(NOTES[state.kind])}
        </p>
      </>
    );
  }
  return (
    <>
      <LastScreenshot sessionId={sessionId} attachmentId={screenshot} />
      <p className="text-sm text-muted-foreground">{t(NOTES[state.kind])}</p>
    </>
  );
}

function LastScreenshot({ sessionId, attachmentId }: { sessionId: string; attachmentId: string }) {
  const { url } = useAttachmentUrl(sessionId, attachmentId);
  return url ? (
    <img
      src={url}
      alt=""
      className="max-h-[70%] max-w-full rounded border object-contain opacity-50"
    />
  ) : null;
}
