import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { FileDiffIcon, FoldVerticalIcon, SquareTerminalIcon } from "lucide-react";
import { useWorkspace } from "@/features/workspace/workspace-store";
import { api, unwrap } from "@/shared/api/client";
import { enabledModels, useProviders } from "@/shared/api/queries";
import { toAppError, type Session, type SessionMode } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Switch } from "@/shared/ui/switch";
import { ChangesPanel } from "./ChangesPanel";
import { useChangedFiles } from "./use-changed-files";
import { Composer, type Attachment } from "./Composer";
import { MessageList } from "./MessageList";
import { ModelPicker } from "./ModelPicker";
import { useConversation, type Usage } from "./use-conversation";
import { WorktreeControl } from "./WorktreeControl";

const BUSY_STATUSES = ["running", "waiting_for_approval", "waiting_for_executor"];

export function ConversationView({ sessionId }: { sessionId: string }) {
  const { t } = useTranslation();
  const { state, patchSession } = useConversation(sessionId);
  const { data: providers } = useProviders();
  const [changesOpen, setChangesOpen] = useState(false);
  const terminalOpen = useWorkspace((state) => state.terminalOpen);
  const [draft, setDraft] = useState({ key: 0, text: "" });
  const { data: changedFiles } = useChangedFiles(sessionId, state.changesVersion);
  const session = state.session;
  const running = session ? BUSY_STATUSES.includes(session.status) : false;

  useEffect(() => {
    if (!running) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (
        event.key === "Escape" &&
        !event.defaultPrevented &&
        !(event.target instanceof Element && event.target.closest(".xterm")) &&
        !document.querySelector("[role=dialog]")
      ) {
        void api().POST("/api/v1/sessions/{sessionId}/cancel", {
          params: { path: { sessionId } },
        });
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [running, sessionId]);

  if (state.loading) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground" />
    );
  }
  if (!session) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-muted-foreground">
        {state.loadError ? errorMessage(t, state.loadError) : null}
      </div>
    );
  }

  const models = enabledModels(providers);
  const currentModel = models.find(({ model }) => model.id === session.modelId)?.model;
  const disabledReason =
    models.length === 0
      ? t("chat.addProviderFirst")
      : session.modelId === null
        ? t("chat.chooseModelFirst")
        : null;
  const activeChanges = (changedFiles ?? []).filter((file) => !file.reverted).length;
  const canCompact =
    !running && session.modelId !== null && state.items.some((item) => item.turnId !== null);

  const updateSession = async (body: {
    modelId?: string;
    mode?: SessionMode;
    autoApprove?: boolean;
  }) => {
    try {
      const updated = (await unwrap(
        api().PATCH("/api/v1/sessions/{sessionId}", {
          params: { path: { sessionId } },
          body,
        }),
      )) as Session;
      patchSession({ ...updated, status: session.status });
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const send = async (text: string, attachments: Attachment[]) => {
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/messages", {
          params: { path: { sessionId }, header: { "Idempotency-Key": crypto.randomUUID() } },
          body: { text, attachments },
        }),
      );
      return true;
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      return false;
    }
  };

  const compact = async () => {
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/compact", { params: { path: { sessionId } } }),
      );
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const stop = () => {
    void api().POST("/api/v1/sessions/{sessionId}/cancel", { params: { path: { sessionId } } });
  };

  return (
    <div className="flex h-full min-w-0 flex-1">
      <div className="flex h-full min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-4 border-b px-4 py-2">
          <div className="flex min-w-0 items-center gap-2">
            <h2 className="truncate text-sm font-medium">{session.title}</h2>
            <WorktreeControl
              session={session}
              busy={running}
              onChanged={(updated) => patchSession({ ...updated, status: session.status })}
            />
          </div>
          <div className="flex items-center gap-2">
            <UsageIndicator
              usage={state.usage}
              contextWindow={state.usage?.contextWindow ?? currentModel?.contextWindow ?? null}
            />
            <Button
              size="icon-sm"
              variant="ghost"
              disabled={!canCompact}
              onClick={() => void compact()}
              title={t("chat.compactHint")}
              aria-label={t("chat.compact")}
            >
              <FoldVerticalIcon />
            </Button>
            <Button
              size="icon-sm"
              variant={terminalOpen ? "secondary" : "ghost"}
              onClick={() => useWorkspace.getState().setTerminalOpen(!terminalOpen)}
              title={t("terminal.toggle")}
              aria-label={t("terminal.toggle")}
            >
              <SquareTerminalIcon />
            </Button>
            <Button
              size="sm"
              variant={changesOpen ? "secondary" : "ghost"}
              onClick={() => setChangesOpen((open) => !open)}
            >
              <FileDiffIcon />
              <span className={cn(changesOpen && "sr-only")}>{t("chat.changes")}</span>
              {activeChanges > 0 && (
                <span className="rounded-full bg-primary px-1.5 text-[0.65rem] text-primary-foreground tabular-nums">
                  {activeChanges}
                </span>
              )}
            </Button>
            <label
              className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground"
              title={t("chat.autoApproveHint")}
            >
              <Switch
                size="sm"
                checked={session.autoApprove}
                onCheckedChange={(autoApprove) => void updateSession({ autoApprove })}
              />
              <span className={cn(changesOpen && "sr-only")}>{t("chat.autoApprove")}</span>
            </label>
            <ModelPicker
              value={session.modelId}
              onChange={(modelId) => void updateSession({ modelId })}
            />
          </div>
        </header>
        <MessageList
          sessionId={sessionId}
          items={state.items}
          running={running}
          turnError={state.turnError}
          onRewound={(text) => setDraft((current) => ({ key: current.key + 1, text }))}
        />
        {session.status === "waiting_for_executor" && (
          <div className="mx-auto mb-2 w-full max-w-3xl px-6">
            <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
              {t("chat.waitingForExecutor")}
            </div>
          </div>
        )}
        <Composer
          key={draft.key}
          initialText={draft.text}
          projectId={session.projectId}
          running={running}
          disabledReason={disabledReason}
          mode={session.mode}
          onModeChange={(mode) => void updateSession({ mode })}
          onSend={send}
          onStop={stop}
        />
      </div>
      {changesOpen && (
        <ChangesPanel
          sessionId={sessionId}
          version={state.changesVersion}
          busy={running}
          onClose={() => setChangesOpen(false)}
        />
      )}
    </div>
  );
}

function UsageIndicator({
  usage,
  contextWindow,
}: {
  usage: Usage | null;
  contextWindow: number | null;
}) {
  const { t } = useTranslation();
  if (!usage?.contextTokens) {
    return null;
  }
  const percent = contextWindow
    ? Math.min(100, Math.round((usage.contextTokens / contextWindow) * 100))
    : null;
  const context = contextWindow
    ? t("chat.contextUsage", { tokens: usage.contextTokens, window: contextWindow })
    : t("chat.contextUsageUnknown", { tokens: usage.contextTokens });
  const total = t("chat.sessionUsage", {
    input: usage.totalInputTokens,
    output: usage.totalOutputTokens,
  });
  const cost = usage.estimatedCost === null ? null : formatCost(usage.estimatedCost);

  return (
    <span
      className="flex shrink-0 items-center gap-1.5 text-xs whitespace-nowrap text-muted-foreground tabular-nums"
      title={[context, total, cost && t("chat.estimatedCost", { cost })].filter(Boolean).join("\n")}
    >
      {percent !== null && (
        <span className="h-1.5 w-12 overflow-hidden rounded-full bg-muted">
          <span
            className={cn("block h-full rounded-full bg-primary", percent >= 80 && "bg-amber-500")}
            style={{ width: `${percent}%` }}
          />
        </span>
      )}
      {percent !== null ? `${percent}%` : t("chat.tokens", { count: usage.contextTokens })}
      {cost && <span>· {cost}</span>}
    </span>
  );
}

function formatCost(cost: number) {
  const digits = cost >= 1 ? 2 : cost >= 0.01 ? 3 : 4;
  return `$${cost.toFixed(digits)}`;
}
