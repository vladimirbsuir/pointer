import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  BanIcon,
  CheckIcon,
  ChevronRightIcon,
  ClockIcon,
  FilePenIcon,
  FileTextIcon,
  FolderIcon,
  GlobeIcon,
  Loader2Icon,
  SearchIcon,
  SquareTerminalIcon,
  PlugIcon,
  WrenchIcon,
  XIcon,
} from "lucide-react";
import type { Item } from "@/shared/api/types";
import { cn } from "@/shared/lib/utils";
import { ApprovalPanel } from "./ApprovalPanel";
import { DiffView } from "./DiffView";

type ToolCall = Item & {
  toolName?: string;
  toolStatus?: string;
  arguments?: Record<string, unknown>;
  result?: Record<string, unknown>;
  error?: { code?: string; message?: string };
  output?: string;
  liveOutput?: string;
  approvalId?: string;
  approvalPreview?: Record<string, unknown>;
  fileChanges?: { path: string; linesAdded: number; linesRemoved: number }[];
};

const icons: Record<string, typeof WrenchIcon> = {
  read_file: FileTextIcon,
  list_directory: FolderIcon,
  glob: SearchIcon,
  grep: SearchIcon,
  write_file: FilePenIcon,
  edit_file: FilePenIcon,
  run_command: SquareTerminalIcon,
  web_fetch: GlobeIcon,
};

function argument(call: ToolCall, name: string) {
  const value = call.arguments?.[name];
  return typeof value === "string" ? value : "";
}

export function ToolCallCard({ item, sessionId }: { item: Item; sessionId: string }) {
  const { t } = useTranslation();
  const call = item as ToolCall;
  const [open, setOpen] = useState(false);
  const toolName = call.toolName ?? "";
  const mcpParts = toolName.startsWith("mcp__") ? toolName.slice(5).split("__") : null;
  const Icon = icons[toolName] ?? (mcpParts ? PlugIcon : WrenchIcon);
  const status = call.toolStatus ?? "pending";
  const awaitingApproval =
    status === "awaiting_approval" && call.approvalId && call.approvalPreview;

  const summary = (() => {
    switch (toolName) {
      case "read_file":
      case "list_directory":
      case "write_file":
      case "edit_file":
        return t(`tools.${toolName}`, { target: argument(call, "path") || "." });
      case "glob":
      case "grep":
        return t(`tools.${toolName}`, { target: argument(call, "pattern") });
      case "run_command":
        return t("tools.run_command", { target: argument(call, "command") });
      case "web_fetch":
        return t("tools.web_fetch", { target: argument(call, "url") });
      default:
        return mcpParts
          ? t("tools.mcp", { server: mcpParts[0], tool: mcpParts.slice(1).join("__") })
          : t("tools.other", { name: toolName });
    }
  })();

  const detail = (() => {
    const result = call.result ?? {};
    if (status !== "succeeded") {
      return null;
    }
    switch (toolName) {
      case "read_file":
        return t("tools.lines", { count: Number(result.totalLines ?? 0) });
      case "glob":
        return t("tools.files", { count: (result.paths as unknown[] | undefined)?.length ?? 0 });
      case "grep":
        return t("tools.matches", {
          count: (result.matches as unknown[] | undefined)?.length ?? 0,
        });
      case "list_directory":
        return t("tools.entries", {
          count: (result.entries as unknown[] | undefined)?.length ?? 0,
        });
      case "run_command":
        return t("tools.exitCode", { code: String(result.exitCode ?? "?") });
      case "web_fetch":
        return t("tools.httpStatus", { code: String(result.status ?? "?") });
      default: {
        const change = call.fileChanges?.[0];
        return change ? `+${change.linesAdded} −${change.linesRemoved}` : null;
      }
    }
  })();

  const output =
    call.output ??
    call.liveOutput ??
    (call.result?.output as string | undefined) ??
    (call.result?.content as string | undefined);
  const diff = call.approvalPreview?.unifiedDiff as string | undefined;
  const hasDetails = Boolean(output || diff || call.error?.message || call.arguments);

  return (
    <div className="rounded-lg border text-sm">
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
        onClick={() => setOpen(!open)}
        disabled={!hasDetails}
      >
        <StatusIcon status={status} />
        <Icon className="size-4 shrink-0 text-muted-foreground" />
        <span className="min-w-0 flex-1 truncate font-mono text-xs">{summary}</span>
        {detail && <span className="shrink-0 text-xs text-muted-foreground">{detail}</span>}
        {hasDetails && (
          <ChevronRightIcon
            className={cn(
              "size-3.5 shrink-0 text-muted-foreground transition-transform",
              open && "rotate-90",
            )}
          />
        )}
      </button>
      {awaitingApproval && (
        <div className="border-t px-3 py-3">
          <ApprovalPanel
            sessionId={sessionId}
            approvalId={call.approvalId!}
            preview={call.approvalPreview!}
          />
        </div>
      )}
      {open && !awaitingApproval && (
        <div className="flex flex-col gap-2 border-t px-3 py-2">
          {call.error?.message && <p className="text-xs text-destructive">{call.error.message}</p>}
          {diff && <DiffView diff={diff} />}
          {output && (
            <pre className="max-h-72 overflow-auto rounded-md bg-muted/40 p-2 font-mono text-xs whitespace-pre-wrap">
              {output}
            </pre>
          )}
          {!diff && !output && call.arguments && (
            <pre className="overflow-auto rounded-md bg-muted/40 p-2 font-mono text-xs">
              {JSON.stringify(call.arguments, null, 2)}
            </pre>
          )}
        </div>
      )}
      {!open && status === "running" && call.liveOutput && (
        <pre className="max-h-32 overflow-hidden border-t px-3 py-2 font-mono text-xs whitespace-pre-wrap text-muted-foreground">
          {call.liveOutput.split("\n").slice(-6).join("\n")}
        </pre>
      )}
    </div>
  );
}

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case "running":
    case "pending":
      return <Loader2Icon className="size-3.5 shrink-0 animate-spin text-muted-foreground" />;
    case "awaiting_approval":
      return <ClockIcon className="size-3.5 shrink-0 text-amber-500" />;
    case "succeeded":
      return <CheckIcon className="size-3.5 shrink-0 text-emerald-500" />;
    case "denied":
      return <BanIcon className="size-3.5 shrink-0 text-muted-foreground" />;
    default:
      return <XIcon className="size-3.5 shrink-0 text-destructive" />;
  }
}
