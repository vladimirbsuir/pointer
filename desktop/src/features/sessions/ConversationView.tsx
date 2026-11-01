import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, unwrap } from "@/shared/api/client";
import { enabledModels, useProviders } from "@/shared/api/queries";
import { toAppError, type Session, type SessionMode } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Switch } from "@/shared/ui/switch";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";
import { ModelPicker } from "./ModelPicker";
import { useConversation } from "./use-conversation";

export function ConversationView({ sessionId }: { sessionId: string }) {
  const { t } = useTranslation();
  const { state, patchSession } = useConversation(sessionId);
  const { data: providers } = useProviders();
  const session = state.session;

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

  const running = ["running", "waiting_for_approval", "waiting_for_executor"].includes(
    session.status,
  );
  const hasModels = enabledModels(providers).length > 0;
  const disabledReason = !hasModels
    ? t("chat.addProviderFirst")
    : session.modelId === null
      ? t("chat.chooseModelFirst")
      : null;

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

  const send = async (text: string) => {
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/messages", {
          params: { path: { sessionId }, header: { "Idempotency-Key": crypto.randomUUID() } },
          body: { text },
        }),
      );
      return true;
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      return false;
    }
  };

  const stop = () => {
    void api().POST("/api/v1/sessions/{sessionId}/cancel", { params: { path: { sessionId } } });
  };

  const usageLabel = state.usage?.contextTokens
    ? state.usage.contextWindow
      ? `${Math.min(100, Math.round((state.usage.contextTokens / state.usage.contextWindow) * 100))}%`
      : t("chat.tokens", { count: state.usage.contextTokens })
    : null;

  return (
    <div className="flex h-full flex-1 flex-col">
      <header className="flex items-center justify-between gap-4 border-b px-4 py-2">
        <h2 className="truncate text-sm font-medium">{session.title}</h2>
        <div className="flex items-center gap-2">
          {usageLabel !== null && (
            <span
              className="text-xs text-muted-foreground tabular-nums"
              title={t("chat.contextUsage", {
                tokens: state.usage?.contextTokens,
                window: state.usage?.contextWindow,
              })}
            >
              {usageLabel}
            </span>
          )}
          <label
            className="flex items-center gap-1.5 text-xs text-muted-foreground"
            title={t("chat.autoApproveHint")}
          >
            <Switch
              size="sm"
              checked={session.autoApprove}
              onCheckedChange={(autoApprove) => void updateSession({ autoApprove })}
            />
            {t("chat.autoApprove")}
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
      />
      {session.status === "waiting_for_executor" && (
        <div className="mx-auto mb-2 w-full max-w-3xl px-6">
          <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
            {t("chat.waitingForExecutor")}
          </div>
        </div>
      )}
      <Composer
        running={running}
        disabledReason={disabledReason}
        mode={session.mode}
        onModeChange={(mode) => void updateSession({ mode })}
        onSend={send}
        onStop={stop}
      />
    </div>
  );
}
