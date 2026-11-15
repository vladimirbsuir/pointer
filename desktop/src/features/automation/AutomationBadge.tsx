import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys, useAutomationRuns } from "@/shared/api/queries";
import { openExternal } from "@/shared/lib/open-external";
import { Button } from "@/shared/ui/button";
import { StatusIcon } from "./AutomationRuns";

type AutomationBadgeProps = {
  projectId: string;
  sessionId: string;
  busy: boolean;
};

export function AutomationBadge({ projectId, sessionId, busy }: AutomationBadgeProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: runs } = useAutomationRuns(projectId, true);
  const run = runs?.find((candidate) => candidate.sessionId === sessionId);

  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.automationRuns(projectId) });
  }, [busy, projectId, queryClient]);

  if (!run) {
    return null;
  }
  const status =
    run.status === "failed" && run.failureReason
      ? t(`automation.reason.${run.failureReason}`)
      : t(`automation.status.${run.status}`);

  return (
    <Button
      size="xs"
      variant="outline"
      disabled={!run.issueUrl}
      title={`${t("automation.openIssue", { number: run.issueNumber })} · ${status}`}
      onClick={() => void openExternal(run.issueUrl ?? "")}
    >
      <StatusIcon status={run.status} />
      <span className="tabular-nums">
        {t("automation.sessionBadge", {
          number: run.issueNumber,
          done: run.attempts,
          max: run.maxAttempts,
        })}
      </span>
    </Button>
  );
}
