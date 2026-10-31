import { useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { ArrowUpIcon, SquareIcon } from "lucide-react";
import type { SessionMode } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Textarea } from "@/shared/ui/textarea";

const modes: SessionMode[] = ["ask", "plan", "agent"];

type ComposerProps = {
  running: boolean;
  disabledReason: string | null;
  mode: SessionMode;
  onModeChange: (mode: SessionMode) => void;
  onSend: (text: string) => Promise<boolean>;
  onStop: () => void;
};

export function Composer({
  running,
  disabledReason,
  mode,
  onModeChange,
  onSend,
  onStop,
}: ComposerProps) {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const canSend = text.trim().length > 0 && !sending && disabledReason === null;

  const send = async () => {
    if (!canSend) {
      return;
    }
    setSending(true);
    const sent = await onSend(text.trim());
    setSending(false);
    if (sent) {
      setText("");
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send();
    }
  };

  return (
    <div className="mx-auto w-full max-w-3xl px-6 pb-6">
      <div className="rounded-2xl border bg-background shadow-sm focus-within:ring-2 focus-within:ring-ring/30">
        <Textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={disabledReason ?? t("chat.placeholder")}
          rows={2}
          className="max-h-60 min-h-16 resize-none border-0 bg-transparent px-4 pt-3 shadow-none focus-visible:ring-0 dark:bg-transparent"
        />
        <div className="flex items-center justify-between px-2 pb-2">
          <div role="radiogroup" aria-label={t("chat.mode")} className="flex gap-0.5">
            {modes.map((option) => (
              <button
                key={option}
                type="button"
                role="radio"
                aria-checked={mode === option}
                onClick={() => onModeChange(option)}
                className={cn(
                  "rounded-md px-2 py-1 text-xs transition-colors",
                  mode === option
                    ? "bg-secondary font-medium text-secondary-foreground"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {t(`modes.${option}`)}
              </button>
            ))}
          </div>
          {running && text.trim().length === 0 ? (
            <Button size="icon-sm" variant="secondary" onClick={onStop} aria-label={t("chat.stop")}>
              <SquareIcon className="fill-current" />
            </Button>
          ) : (
            <Button
              size="icon-sm"
              disabled={!canSend}
              onClick={() => void send()}
              aria-label={t("chat.send")}
            >
              <ArrowUpIcon />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
