import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ArrowUpIcon, FileTextIcon, SquareIcon, XIcon } from "lucide-react";
import { core } from "@/shared/api/core";
import type { SessionMode } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Textarea } from "@/shared/ui/textarea";

const modes: SessionMode[] = ["ask", "plan", "agent"];

const MENTION_PATTERN = /(^|\s)@([^\s@]*)$/;
const SUGGESTION_DELAY_MS = 120;
const VISIBLE_SUGGESTIONS = 8;

export type Attachment = { kind: "file"; path: string; content: string };

type Mention = { start: number; end: number; query: string };

type ComposerProps = {
  projectId: string;
  running: boolean;
  disabledReason: string | null;
  mode: SessionMode;
  onModeChange: (mode: SessionMode) => void;
  onSend: (text: string, attachments: Attachment[]) => Promise<boolean>;
  onStop: () => void;
};

function findMention(text: string, caret: number): Mention | null {
  const match = MENTION_PATTERN.exec(text.slice(0, caret));
  if (!match) {
    return null;
  }
  const query = match[2] ?? "";
  return { start: caret - query.length - 1, end: caret, query };
}

function nextMode(mode: SessionMode): SessionMode {
  return modes[(modes.indexOf(mode) + 1) % modes.length] ?? "agent";
}

export function Composer({
  projectId,
  running,
  disabledReason,
  mode,
  onModeChange,
  onSend,
  onStop,
}: ComposerProps) {
  const { t } = useTranslation();
  const textarea = useRef<HTMLTextAreaElement>(null);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [attached, setAttached] = useState<string[]>([]);
  const [mention, setMention] = useState<Mention | null>(null);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const canSend = text.trim().length > 0 && !sending && disabledReason === null;
  const mentionQuery = mention?.query ?? null;

  useEffect(() => {
    if (mentionQuery === null) {
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      core
        .projectFiles(projectId, mentionQuery)
        .then((paths) => {
          if (!cancelled) {
            setSuggestions(paths.slice(0, VISIBLE_SUGGESTIONS));
            setActiveIndex(0);
          }
        })
        .catch(() => {
          if (!cancelled) {
            setSuggestions([]);
          }
        });
    }, SUGGESTION_DELAY_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [projectId, mentionQuery]);

  const updateText = (value: string, caret: number) => {
    setText(value);
    setMention(findMention(value, caret));
  };

  const selectSuggestion = (path: string) => {
    if (!mention) {
      return;
    }
    const inserted = `@${path} `;
    const value = text.slice(0, mention.start) + inserted + text.slice(mention.end);
    const caret = mention.start + inserted.length;
    setText(value);
    setMention(null);
    setAttached((current) => (current.includes(path) ? current : [...current, path]));
    requestAnimationFrame(() => {
      textarea.current?.focus();
      textarea.current?.setSelectionRange(caret, caret);
    });
  };

  const readAttachments = async (message: string) => {
    const paths = attached.filter((path) => message.includes(`@${path}`));
    const attachments: Attachment[] = [];
    for (const path of paths) {
      try {
        attachments.push({
          kind: "file",
          path,
          content: await core.readProjectFile(projectId, path),
        });
      } catch {
        toast.error(t("chat.attachFailed", { path }));
        return null;
      }
    }
    return attachments;
  };

  const send = async () => {
    if (!canSend) {
      return;
    }
    setSending(true);
    const message = text.trim();
    const attachments = await readAttachments(message);
    const sent = attachments !== null && (await onSend(message, attachments));
    setSending(false);
    if (sent) {
      setText("");
      setAttached([]);
      setMention(null);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (mention && suggestions.length > 0) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const step = event.key === "ArrowDown" ? 1 : -1;
        setActiveIndex((index) => (index + step + suggestions.length) % suggestions.length);
        return;
      }
      if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        const path = suggestions[activeIndex];
        if (path) {
          selectSuggestion(path);
        }
        return;
      }
    }
    if (event.key === "Escape" && mention) {
      event.preventDefault();
      setMention(null);
      return;
    }
    if (event.key === "Tab" && event.shiftKey) {
      event.preventDefault();
      onModeChange(nextMode(mode));
      return;
    }
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send();
    }
  };

  return (
    <div className="mx-auto w-full max-w-3xl px-6 pb-6">
      <div className="relative rounded-2xl border bg-background shadow-sm focus-within:ring-2 focus-within:ring-ring/30">
        {mention && (
          <div className="absolute inset-x-2 bottom-full z-10 mb-2 overflow-hidden rounded-lg border bg-popover text-popover-foreground shadow-md">
            {suggestions.length === 0 ? (
              <p className="px-3 py-2 text-xs text-muted-foreground">{t("chat.noFiles")}</p>
            ) : (
              <ul role="listbox" aria-label={t("shortcuts.mention")}>
                {suggestions.map((path, index) => (
                  <li
                    key={path}
                    role="option"
                    aria-selected={index === activeIndex}
                    onMouseDown={(event) => {
                      event.preventDefault();
                      selectSuggestion(path);
                    }}
                    onMouseEnter={() => setActiveIndex(index)}
                    className={cn(
                      "flex cursor-pointer items-center gap-2 px-3 py-1.5 font-mono text-xs",
                      index === activeIndex && "bg-accent text-accent-foreground",
                    )}
                  >
                    <FileTextIcon className="size-3.5 shrink-0 text-muted-foreground" />
                    <span className="truncate">{path}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        {attached.length > 0 && (
          <div className="flex flex-wrap gap-1.5 px-3 pt-3">
            {attached.map((path) => (
              <span
                key={path}
                className="flex items-center gap-1 rounded-md bg-muted px-2 py-0.5 font-mono text-xs"
              >
                <FileTextIcon className="size-3" />
                {path}
                <button
                  type="button"
                  aria-label={t("changes.close")}
                  className="text-muted-foreground hover:text-foreground"
                  onClick={() => setAttached((current) => current.filter((item) => item !== path))}
                >
                  <XIcon className="size-3" />
                </button>
              </span>
            ))}
          </div>
        )}
        <Textarea
          ref={textarea}
          autoFocus
          value={text}
          onChange={(event) =>
            updateText(event.target.value, event.target.selectionStart ?? event.target.value.length)
          }
          onSelect={(event) =>
            setMention(findMention(text, event.currentTarget.selectionStart ?? text.length))
          }
          onBlur={() => setMention(null)}
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
