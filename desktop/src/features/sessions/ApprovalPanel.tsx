import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, unwrap } from "@/shared/api/client";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { DiffView } from "./DiffView";
import { ToolProposalPanel, type ToolProposalPreview } from "./ToolProposalPanel";

type ApprovalPanelProps = {
  sessionId: string;
  approvalId: string;
  preview: Record<string, unknown>;
};

export function ApprovalPanel({ sessionId, approvalId, preview }: ApprovalPanelProps) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [denying, setDenying] = useState(false);
  const [message, setMessage] = useState("");

  const resolve = async (decision: "approved" | "denied", scope: "once" | "session") => {
    setBusy(true);
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/approvals/{approvalId}", {
          params: { path: { sessionId, approvalId } },
          body: { decision, scope, message: message.trim() || undefined },
        }),
      );
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      setBusy(false);
    }
  };

  if (preview.kind === "tool_proposal") {
    return (
      <ToolProposalPanel
        sessionId={sessionId}
        approvalId={approvalId}
        preview={preview as unknown as ToolProposalPreview}
      />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs font-medium">{t("approval.title")}</p>
      {preview.kind === "diff" ? (
        <DiffView diff={String(preview.unifiedDiff ?? "")} />
      ) : preview.kind === "fetch" ? (
        <p className="overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs break-all">
          <span className="text-muted-foreground">GET </span>
          {String(preview.url ?? "")}
        </p>
      ) : preview.kind === "custom_tool" ? (
        <div className="overflow-hidden rounded-md border bg-muted/40 font-mono text-xs">
          <p className="border-b px-2 py-1 text-muted-foreground">
            {t("customTools.call", {
              name: String(preview.name),
              version: String(preview.version),
              interpreter: String(preview.interpreter),
            })}
          </p>
          <pre className="max-h-60 overflow-auto p-2">
            {JSON.stringify(preview.arguments ?? {}, null, 2)}
          </pre>
        </div>
      ) : preview.kind === "tool" ? (
        <div className="overflow-hidden rounded-md border bg-muted/40 font-mono text-xs">
          <p className="border-b px-2 py-1 text-muted-foreground">
            {t("tools.mcp", { server: String(preview.server), tool: String(preview.tool) })}
          </p>
          <pre className="max-h-60 overflow-auto p-2">
            {JSON.stringify(preview.arguments ?? {}, null, 2)}
          </pre>
        </div>
      ) : (
        <pre className="overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs">
          <span className="text-muted-foreground">
            {String(preview.workingDirectory ?? ".")} &gt;{" "}
          </span>
          {String(preview.command ?? "")}
        </pre>
      )}
      {denying ? (
        <div className="flex gap-2">
          <Input
            autoFocus
            value={message}
            onChange={(event) => setMessage(event.target.value)}
            placeholder={t("approval.denyPlaceholder")}
            className="h-8"
            onKeyDown={(event) => event.key === "Enter" && void resolve("denied", "once")}
          />
          <Button
            size="sm"
            variant="destructive"
            disabled={busy}
            onClick={() => void resolve("denied", "once")}
          >
            {t("approval.deny")}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setDenying(false)}>
            {t("common.cancel")}
          </Button>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" disabled={busy} onClick={() => void resolve("approved", "once")}>
            {t("approval.allowOnce")}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            disabled={busy}
            onClick={() => void resolve("approved", "session")}
          >
            {t("approval.allowSession")}
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDenying(true)}>
            {t("approval.deny")}
          </Button>
        </div>
      )}
    </div>
  );
}
