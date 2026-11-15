import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronRightIcon, TelescopeIcon } from "lucide-react";
import type { Item } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { Markdown } from "./Markdown";
import { ToolCallCard, ToolStatusIcon } from "./ToolCallCard";

type ExploreCall = Item & {
  toolStatus?: string;
  arguments?: { task?: unknown };
  result?: { report?: unknown };
  error?: { message?: string };
};

type ExploreCardProps = {
  item: Item;
  nested: Item[];
  sessionId: string;
};

export function ExploreCard({ item, nested, sessionId }: ExploreCardProps) {
  const { t } = useTranslation();
  const call = item as ExploreCall;
  const [opened, setOpened] = useState(false);
  const status = call.toolStatus ?? "pending";
  const needsApproval = nested.some((child) => child.toolStatus === "awaiting_approval");
  const open = opened || needsApproval;
  const task = typeof call.arguments?.task === "string" ? call.arguments.task : "";
  const report = typeof call.result?.report === "string" ? call.result.report : "";

  return (
    <div className="rounded-lg border text-sm">
      <button
        type="button"
        className="flex w-full min-w-0 items-center gap-2 px-3 py-2 text-left"
        aria-expanded={open}
        onClick={() => setOpened(!open)}
      >
        <ToolStatusIcon status={status} />
        <TelescopeIcon className="size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate text-xs">
          {t("tools.explore", { target: task })}
        </span>
        <span className="shrink-0 text-xs text-muted-foreground">
          {t("tools.exploreCalls", { count: nested.length })}
        </span>
        <ChevronRightIcon
          className={cn(
            "size-3.5 shrink-0 text-muted-foreground transition-transform",
            open && "rotate-90",
          )}
        />
      </button>
      {open && (
        <div className="flex flex-col gap-2 border-t px-3 py-2">
          <p className="text-xs whitespace-pre-wrap text-muted-foreground">{task}</p>
          {nested.length > 0 && (
            <div className="flex flex-col gap-1.5 border-l-2 pl-3">
              {nested.map((child) => (
                <ToolCallCard key={child.id} item={child} sessionId={sessionId} />
              ))}
            </div>
          )}
          {call.error?.message && <p className="text-xs text-destructive">{call.error.message}</p>}
          {report && (
            <div className="rounded-md bg-muted/40 p-2 text-xs">
              <Markdown text={report} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
