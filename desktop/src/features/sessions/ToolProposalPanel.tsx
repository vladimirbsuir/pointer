import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { structuredPatch } from "diff";
import { api, unwrap } from "@/shared/api/client";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";
import { DiffView } from "./DiffView";

type ToolScope = "user" | "project";
type CallPolicy = "ask" | "allow";

export type ToolProposalPreview = {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
  interpreter: string;
  body: string;
  scope: ToolScope;
  scopeFixed: boolean;
  userScopeAvailable: boolean;
  version: number;
  defaultPolicy: CallPolicy;
  modes: string[];
  previous?: {
    version: number;
    description: string;
    parameters: Record<string, unknown>;
    interpreter: string;
    body: string;
  };
};

function unifiedDiff(before: string, after: string) {
  return structuredPatch("before", "after", before, after, "", "", { context: 3 })
    .hunks.flatMap((hunk) => [
      `@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`,
      ...hunk.lines,
    ])
    .join("\n");
}

export function ToolProposalPanel({
  sessionId,
  approvalId,
  preview,
}: {
  sessionId: string;
  approvalId: string;
  preview: ToolProposalPreview;
}) {
  const { t } = useTranslation();
  const [scope, setScope] = useState<ToolScope>(preview.scope);
  const [policy, setPolicy] = useState<CallPolicy>(
    preview.defaultPolicy === "allow" ? "allow" : "ask",
  );
  const [readModes, setReadModes] = useState(
    preview.modes.includes("ask") || preview.modes.includes("plan"),
  );
  const [showBody, setShowBody] = useState(!preview.previous);
  const [rejecting, setRejecting] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const previous = preview.previous;
  const schemaBefore = previous ? JSON.stringify(previous.parameters, null, 2) : "";
  const schemaAfter = JSON.stringify(preview.parameters, null, 2);

  const resolve = async (decision: "approved" | "denied") => {
    setBusy(true);
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/approvals/{approvalId}", {
          params: { path: { sessionId, approvalId } },
          body:
            decision === "approved"
              ? {
                  decision,
                  scope: "once",
                  tool: {
                    scope,
                    defaultPolicy: policy,
                    modes: readModes ? ["ask", "plan", "agent"] : ["agent"],
                  },
                }
              : { decision, scope: "once", message: message.trim() || undefined },
        }),
      );
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-xs font-medium">
          {previous ? t("customTools.newVersionTitle") : t("customTools.proposalTitle")}
        </p>
        <span className="font-mono text-xs">{preview.name}</span>
        <Badge variant="secondary">v{preview.version}</Badge>
        <Badge variant="outline">{preview.interpreter}</Badge>
      </div>
      <p className="text-xs whitespace-pre-wrap text-muted-foreground">{preview.description}</p>
      <p className="text-xs text-amber-600 dark:text-amber-400">{t("customTools.warning")}</p>

      {previous && (
        <section className="flex flex-col gap-1">
          <p className="text-xs text-muted-foreground">
            {t("customTools.changesSince", { version: previous.version })}
          </p>
          {previous.interpreter !== preview.interpreter && (
            <p className="font-mono text-xs">
              {previous.interpreter} → {preview.interpreter}
            </p>
          )}
          <DiffView diff={unifiedDiff(previous.body, preview.body)} />
          {schemaBefore !== schemaAfter && (
            <DiffView diff={unifiedDiff(schemaBefore, schemaAfter)} />
          )}
        </section>
      )}

      <button
        type="button"
        className="self-start text-xs text-muted-foreground underline-offset-2 hover:underline"
        onClick={() => setShowBody(!showBody)}
      >
        {showBody ? t("customTools.hideScript") : t("customTools.showScript")}
      </button>
      {showBody && (
        <>
          <pre className="max-h-80 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs">
            {preview.body}
          </pre>
          <pre className="max-h-40 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs">
            {schemaAfter}
          </pre>
        </>
      )}

      <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-2 text-xs">
        <span className="text-muted-foreground">{t("customTools.scope")}</span>
        <Segmented
          value={scope}
          options={[
            { value: "project", label: t("customTools.scopes.project") },
            {
              value: "user",
              label: t("customTools.scopes.user"),
              disabled: !preview.userScopeAvailable,
            },
          ]}
          disabled={preview.scopeFixed}
          onChange={(value) => setScope(value as ToolScope)}
        />
        <span className="text-muted-foreground">{t("customTools.calls")}</span>
        <Segmented
          value={policy}
          options={[
            { value: "ask", label: t("permissions.policies.ask") },
            { value: "allow", label: t("permissions.policies.allow") },
          ]}
          onChange={(value) => setPolicy(value as CallPolicy)}
        />
        <span className="text-muted-foreground">{t("customTools.readModes")}</span>
        <Switch size="sm" checked={readModes} onCheckedChange={setReadModes} />
      </div>

      {rejecting ? (
        <div className="flex gap-2">
          <Input
            autoFocus
            value={message}
            onChange={(event) => setMessage(event.target.value)}
            placeholder={t("approval.denyPlaceholder")}
            className="h-8"
            onKeyDown={(event) => event.key === "Enter" && void resolve("denied")}
          />
          <Button
            size="sm"
            variant="destructive"
            disabled={busy}
            onClick={() => void resolve("denied")}
          >
            {t("customTools.reject")}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
            {t("common.cancel")}
          </Button>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          <Button size="sm" disabled={busy} onClick={() => void resolve("approved")}>
            {previous ? t("customTools.approveVersion") : t("customTools.approve")}
          </Button>
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(true)}>
            {t("customTools.reject")}
          </Button>
        </div>
      )}
    </div>
  );
}

function Segmented({
  value,
  options,
  disabled,
  onChange,
}: {
  value: string;
  options: { value: string; label: string; disabled?: boolean }[];
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <div role="radiogroup" className="flex w-fit gap-0.5 rounded-md bg-muted p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={value === option.value}
          disabled={disabled || option.disabled}
          onClick={() => onChange(option.value)}
          className={cn(
            "rounded px-2 py-0.5 text-xs transition-colors disabled:opacity-50",
            value === option.value
              ? "bg-background font-medium shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
