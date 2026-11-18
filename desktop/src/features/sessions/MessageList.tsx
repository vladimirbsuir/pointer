import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import {
  BrainIcon,
  CheckIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  ClockIcon,
  FileTextIcon,
  FoldVerticalIcon,
  LightbulbIcon,
  ShuffleIcon,
  TrendingUpIcon,
} from "lucide-react";
import type { Item } from "@/shared/api/types";
import { AttachmentImage } from "./AttachmentImage";
import { ExploreCard } from "./ExploreCard";
import { Markdown } from "./Markdown";
import { PlanCard } from "./PlanCard";
import { RewindControl } from "./RewindControl";
import { ToolCallCard } from "./ToolCallCard";

type MessageListProps = {
  sessionId: string;
  items: Item[];
  running: boolean;
  turnError: { code: string; message: string } | null;
  onRewound: (text: string) => void;
};

const STICK_TO_BOTTOM_PX = 120;
const NO_ITEMS: Item[] = [];

export function MessageList({ sessionId, items, running, turnError, onRewound }: MessageListProps) {
  const { t } = useTranslation();
  const container = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const visible = items.filter(
    (item) =>
      !item.parentItemId &&
      [
        "user_message",
        "assistant_message",
        "reasoning",
        "tool_call",
        "plan",
        "compaction_summary",
        "model_switched",
        "model_escalated",
        "model_nudged",
      ].includes(item.type),
  );
  const lastText = visible.at(-1)?.text?.length ?? 0;

  useEffect(() => {
    const element = container.current;
    if (element && stick.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [visible.length, lastText, running, turnError]);

  const last = visible.at(-1);
  const waitingForFirstToken =
    running &&
    last?.status !== "in_progress" &&
    !(
      last?.type === "tool_call" &&
      ["running", "awaiting_approval", "pending"].includes(String(last.toolStatus))
    );

  return (
    <div
      ref={container}
      className="flex-1 overflow-y-auto"
      onScroll={(event) => {
        const element = event.currentTarget;
        stick.current =
          element.scrollHeight - element.scrollTop - element.clientHeight < STICK_TO_BOTTOM_PX;
      }}
    >
      <div className="mx-auto flex max-w-3xl flex-col gap-6 px-6 py-8">
        {visible.length === 0 && !running && (
          <p className="text-center text-sm text-muted-foreground">{t("chat.empty")}</p>
        )}
        {visible.map((item) => (
          <MessageItem
            key={item.id}
            item={item}
            nested={
              item.toolName === "explore"
                ? items.filter((other) => other.parentItemId === item.id)
                : NO_ITEMS
            }
            sessionId={sessionId}
            running={running}
            onRewound={onRewound}
          />
        ))}
        {waitingForFirstToken && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <span className="size-2 animate-pulse rounded-full bg-primary" />
            {t("chat.thinking")}
          </div>
        )}
        {turnError && (
          <div className="flex gap-2 rounded-lg border border-destructive/40 bg-destructive/10 p-3 text-sm">
            <CircleAlertIcon className="mt-0.5 size-4 shrink-0 text-destructive" />
            <div>
              {turnError.code === "budget_exceeded" ? (
                <>
                  <p className="font-medium">{t("chat.budgetExceeded")}</p>
                  <p className="text-muted-foreground">{turnError.message}</p>
                </>
              ) : turnError.code === "interrupted" ? (
                <>
                  <p className="font-medium">{t("chat.turnInterrupted")}</p>
                  <p className="text-muted-foreground">{t("chat.turnInterruptedHint")}</p>
                </>
              ) : (
                <>
                  <p className="font-medium">{t("chat.turnFailed")}</p>
                  <p className="text-muted-foreground">{turnError.message || turnError.code}</p>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

type MessageItemProps = {
  item: Item;
  nested: Item[];
  sessionId: string;
  running: boolean;
  onRewound: (text: string) => void;
};

function MessageItem({ item, nested, sessionId, running, onRewound }: MessageItemProps) {
  const { t } = useTranslation();
  const text = item.text ?? "";

  if (item.type === "tool_call" && item.toolName === "explore") {
    return <ExploreCard item={item} nested={nested} sessionId={sessionId} />;
  }

  if (item.type === "tool_call") {
    return <ToolCallCard item={item} sessionId={sessionId} />;
  }

  if (item.type === "plan") {
    return <PlanCard item={item} sessionId={sessionId} running={running} />;
  }

  if (item.type === "compaction_summary") {
    if (item.status === "failed") {
      return null;
    }
    const compactedKey = item.trigger === "soft" ? "chat.compactedSoft" : "chat.compacted";
    return (
      <details className="group rounded-lg border border-dashed px-3 py-2 text-sm">
        <summary className="flex cursor-pointer list-none items-center gap-2 text-muted-foreground select-none">
          <FoldVerticalIcon className="size-3.5" />
          {item.status === "in_progress"
            ? t("chat.compacting")
            : t(compactedKey, { count: Number(item.replacedItemCount ?? 0) })}
          <ChevronRightIcon className="ml-auto size-3.5 transition-transform group-open:rotate-90" />
        </summary>
        <div className="mt-2 border-t pt-2">
          <Markdown text={text} />
        </div>
      </details>
    );
  }

  if (item.type === "model_switched") {
    return (
      <div
        className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground"
        title={String(item.reason ?? "")}
      >
        <ShuffleIcon className="size-3.5 shrink-0" />
        <span className="min-w-0 truncate">
          {t("chat.modelSwitched", { from: String(item.from ?? ""), to: String(item.to ?? "") })}
        </span>
      </div>
    );
  }

  if (item.type === "model_nudged") {
    const signals = Array.isArray(item.signals) ? (item.signals as string[]) : [];
    return (
      <div
        className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground"
        title={String(item.text ?? "")}
      >
        <LightbulbIcon className="size-3.5 shrink-0" />
        <span className="min-w-0 truncate">
          {t("chat.modelNudged", {
            model: String(item.model ?? ""),
            signals: signals.map((signal) => t(`chat.signals.${signal}`, signal)).join(", "),
          })}
        </span>
      </div>
    );
  }

  if (item.type === "model_escalated") {
    const signals = Array.isArray(item.signals) ? (item.signals as string[]) : [];
    const values = { from: String(item.from ?? ""), to: String(item.to ?? "") };
    return (
      <div
        className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground"
        title={signals.map((signal) => t(`chat.signals.${signal}`, signal)).join(", ")}
      >
        <TrendingUpIcon className="size-3.5 shrink-0" />
        <span className="min-w-0 truncate">
          {item.reason === "context"
            ? t("chat.modelEscalatedContext", values)
            : t("chat.modelEscalated", {
                ...values,
                signals: signals.map((signal) => t(`chat.signals.${signal}`, signal)).join(", "),
              })}
        </span>
      </div>
    );
  }

  if (item.type === "user_message" && item.planItemId) {
    return (
      <div className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground">
        <CheckIcon className="size-3.5" />
        {t("chat.planApprovedMessage")}
      </div>
    );
  }

  if (item.type === "user_message") {
    const attachments =
      (item.attachments as { kind?: string; path?: string; attachmentId?: string }[] | undefined) ??
      [];
    const images = attachments.filter((attachment) => attachment.kind === "image");
    return (
      <div className="group flex flex-col items-end gap-1">
        {attachments
          .filter((attachment) => attachment.kind !== "image")
          .map((attachment) => (
            <span
              key={attachment.path}
              className="flex items-center gap-1 text-xs text-muted-foreground"
            >
              <FileTextIcon className="size-3" />
              {attachment.path}
            </span>
          ))}
        {images.length > 0 && (
          <div className="flex max-w-[85%] flex-wrap justify-end gap-2">
            {images.map((image) => (
              <AttachmentImage
                key={image.attachmentId}
                sessionId={sessionId}
                attachmentId={image.attachmentId!}
              />
            ))}
          </div>
        )}
        <div className="flex max-w-[85%] items-start gap-1">
          {item.turnId !== null && (
            <RewindControl
              sessionId={sessionId}
              turnId={item.turnId}
              disabled={running}
              onRewound={onRewound}
            />
          )}
          <div className="rounded-2xl bg-muted px-4 py-2.5 text-sm whitespace-pre-wrap">{text}</div>
        </div>
        {item.turnId === null && (
          <span className="flex items-center gap-1 text-xs text-muted-foreground">
            <ClockIcon className="size-3" />
            {t("chat.queued")}
          </span>
        )}
      </div>
    );
  }

  if (item.type === "reasoning") {
    return (
      <details className="group text-sm text-muted-foreground">
        <summary className="flex cursor-pointer list-none items-center gap-1.5 select-none">
          <ChevronRightIcon className="size-3.5 transition-transform group-open:rotate-90" />
          <BrainIcon className="size-3.5" />
          {item.status === "in_progress" ? t("chat.reasoningLive") : t("chat.reasoning")}
        </summary>
        <div className="mt-2 border-l-2 pl-3 whitespace-pre-wrap">{text}</div>
      </details>
    );
  }

  return (
    <div className="text-sm">
      <Markdown text={text} />
      {item.status === "in_progress" && (
        <span className="ml-0.5 inline-block h-4 w-1.5 animate-pulse bg-foreground/60 align-middle" />
      )}
      {item.status === "cancelled" && (
        <p className="mt-1 text-xs text-muted-foreground">{t("chat.stopped")}</p>
      )}
    </div>
  );
}
