import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  CircleCheckIcon,
  CircleSlashIcon,
  CircleXIcon,
  ClockIcon,
  GitPullRequestDraftIcon,
  GitPullRequestIcon,
  Loader2Icon,
  MessageSquareIcon,
  PlayIcon,
  SquareIcon,
} from "lucide-react";
import { formatCost } from "@/features/sessions/format-cost";
import { useWorkspace } from "@/features/workspace/workspace-store";
import { api, unwrap } from "@/shared/api/client";
import { isActiveRun, queryKeys, useAutomationRuns } from "@/shared/api/queries";
import { toAppError, type Automation, type AutomationRun, type Project } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { openExternal } from "@/shared/lib/open-external";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { formatDuration } from "./format-duration";

type AutomationRunsProps = {
  project: Project;
  automation: Automation;
  onOpenSession: () => void;
};

export function AutomationRuns({ project, automation, onOpenSession }: AutomationRunsProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: runs } = useAutomationRuns(project.id, true, true);
  const [issue, setIssue] = useState("");
  const [starting, setStarting] = useState(false);
  const issueNumber = Number(issue);
  const validIssue = Number.isInteger(issueNumber) && issueNumber > 0;
  const available = automation.configured && automation.enabled;

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: queryKeys.automationRuns(project.id) });
    await queryClient.invalidateQueries({ queryKey: queryKeys.sessions(project.id) });
    await queryClient.invalidateQueries({ queryKey: queryKeys.remoteSessions(project.id) });
  };

  const start = async (event: FormEvent) => {
    event.preventDefault();
    if (!validIssue) {
      return;
    }
    setStarting(true);
    try {
      await unwrap(
        api().POST("/api/v1/projects/{projectId}/automation/runs", {
          params: { path: { projectId: project.id } },
          body: { issueNumber },
        }),
      );
      setIssue("");
      toast.success(t("automation.started", { number: issueNumber }));
      await refresh();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setStarting(false);
    }
  };

  const cancel = async (run: AutomationRun) => {
    try {
      await unwrap(
        api().POST("/api/v1/projects/{projectId}/automation/runs/{runId}/cancel", {
          params: { path: { projectId: project.id, runId: run.id } },
        }),
      );
      await refresh();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const openSession = (run: AutomationRun) => {
    if (run.sessionId) {
      useWorkspace.getState().openSession(project.id, run.sessionId);
      onOpenSession();
    }
  };

  return (
    <div className="flex flex-col gap-4 pt-2">
      {!available && (
        <p className="text-sm text-muted-foreground">
          {automation.configured ? t("automation.disabled") : t("automation.notConfigured")}
        </p>
      )}
      <form className="flex items-center gap-2" onSubmit={(event) => void start(event)}>
        <Input
          type="number"
          min={1}
          value={issue}
          placeholder={t("automation.issueNumber")}
          aria-label={t("automation.issueNumber")}
          className="w-40"
          disabled={!available}
          onChange={(event) => setIssue(event.target.value)}
        />
        <Button type="submit" disabled={!available || !validIssue || starting}>
          <PlayIcon />
          {t("automation.run")}
        </Button>
      </form>
      {runs?.length === 0 && (
        <p className="text-sm text-muted-foreground">{t("automation.noRuns")}</p>
      )}
      {runs && runs.length > 0 && (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-xs text-muted-foreground">
              <th className="py-1.5 pr-3 font-medium">{t("automation.columns.issue")}</th>
              <th className="py-1.5 pr-3 font-medium">{t("automation.columns.status")}</th>
              <th className="py-1.5 pr-3 font-medium">{t("automation.columns.checks")}</th>
              <th className="py-1.5 pr-3 font-medium">{t("automation.columns.cost")}</th>
              <th className="py-1.5 pr-3 font-medium">{t("automation.columns.duration")}</th>
              <th className="py-1.5" />
            </tr>
          </thead>
          <tbody>
            {runs.map((run) => (
              <RunRow
                key={run.id}
                run={run}
                onOpenSession={() => openSession(run)}
                onCancel={() => void cancel(run)}
              />
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function RunRow({
  run,
  onOpenSession,
  onCancel,
}: {
  run: AutomationRun;
  onOpenSession: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const active = isActiveRun(run);
  const detail =
    run.status === "failed" && run.failureReason
      ? (run.error ?? t(`automation.reason.${run.failureReason}`))
      : t(`automation.trigger.${run.trigger}`);

  return (
    <tr className="border-b align-top last:border-0">
      <td className="max-w-56 py-2 pr-3">
        <button
          type="button"
          className="block max-w-full truncate text-left font-medium hover:underline disabled:no-underline"
          disabled={!run.issueUrl}
          title={t("automation.openIssue", { number: run.issueNumber })}
          onClick={() => void openExternal(run.issueUrl ?? "")}
        >
          #{run.issueNumber} {run.issueTitle}
        </button>
        <span className="block truncate text-xs text-muted-foreground" title={detail}>
          {detail}
        </span>
      </td>
      <td className="py-2 pr-3 whitespace-nowrap">
        <span className="inline-flex items-center gap-1.5">
          <StatusIcon status={run.status} />
          {t(`automation.status.${run.status}`)}
        </span>
      </td>
      <td className="py-2 pr-3 whitespace-nowrap tabular-nums">
        {t("automation.checks", { done: run.attempts, max: run.maxAttempts })}
      </td>
      <td className="py-2 pr-3 whitespace-nowrap tabular-nums">{formatCost(run.costUsd)}</td>
      <td className="py-2 pr-3 whitespace-nowrap tabular-nums">
        {formatDuration(run.startedAt, run.finishedAt)}
      </td>
      <td className="py-1.5">
        <div className="flex justify-end gap-0.5">
          {run.pullRequestUrl && (
            <Button
              variant="ghost"
              size="icon-xs"
              title={t(
                run.status === "succeeded" ? "automation.openPullRequest" : "automation.openDraft",
              )}
              aria-label={t(
                run.status === "succeeded" ? "automation.openPullRequest" : "automation.openDraft",
              )}
              onClick={() => void openExternal(run.pullRequestUrl ?? "")}
            >
              {run.status === "succeeded" ? <GitPullRequestIcon /> : <GitPullRequestDraftIcon />}
            </Button>
          )}
          <Button
            variant="ghost"
            size="icon-xs"
            disabled={!run.sessionId}
            title={t("automation.openSession")}
            aria-label={t("automation.openSession")}
            onClick={onOpenSession}
          >
            <MessageSquareIcon />
          </Button>
          {active && (
            <Button
              variant="ghost"
              size="icon-xs"
              title={t("automation.cancel")}
              aria-label={t("automation.cancel")}
              onClick={onCancel}
            >
              <SquareIcon />
            </Button>
          )}
        </div>
      </td>
    </tr>
  );
}

export function StatusIcon({ status }: { status: AutomationRun["status"] }) {
  switch (status) {
    case "queued":
      return <ClockIcon className="size-3.5 text-muted-foreground" />;
    case "running":
    case "checking":
      return <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />;
    case "succeeded":
      return <CircleCheckIcon className="size-3.5 text-emerald-600" />;
    case "failed":
      return <CircleXIcon className="size-3.5 text-destructive" />;
    default:
      return <CircleSlashIcon className="size-3.5 text-muted-foreground" />;
  }
}
