import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { BrainIcon, ChevronRightIcon, CircleAlertIcon, FileTextIcon } from "lucide-react";
import type { Item } from "@/shared/api/types";
import { Markdown } from "./Markdown";

type MessageListProps = {
  items: Item[];
  running: boolean;
  turnError: { code: string; message: string } | null;
};

const STICK_TO_BOTTOM_PX = 120;

export function MessageList({ items, running, turnError }: MessageListProps) {
  const { t } = useTranslation();
  const container = useRef<HTMLDivElement>(null);
  const stick = useRef(true);
  const visible = items.filter((item) =>
    ["user_message", "assistant_message", "reasoning"].includes(item.type),
  );
  const lastText = visible.at(-1)?.text?.length ?? 0;

  useEffect(() => {
    const element = container.current;
    if (element && stick.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [visible.length, lastText, running, turnError]);

  const waitingForFirstToken = running && visible.at(-1)?.type === "user_message";

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
          <MessageItem key={item.id} item={item} />
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
              <p className="font-medium">{t("chat.turnFailed")}</p>
              <p className="text-muted-foreground">{turnError.message || turnError.code}</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function MessageItem({ item }: { item: Item }) {
  const { t } = useTranslation();
  const text = item.text ?? "";

  if (item.type === "user_message") {
    const attachments = (item.attachments as { path: string }[] | undefined) ?? [];
    return (
      <div className="flex flex-col items-end gap-1">
        {attachments.map((attachment) => (
          <span
            key={attachment.path}
            className="flex items-center gap-1 text-xs text-muted-foreground"
          >
            <FileTextIcon className="size-3" />
            {attachment.path}
          </span>
        ))}
        <div className="max-w-[85%] rounded-2xl bg-muted px-4 py-2.5 text-sm whitespace-pre-wrap">
          {text}
        </div>
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
