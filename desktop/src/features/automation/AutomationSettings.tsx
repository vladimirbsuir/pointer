import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { XIcon } from "lucide-react";
import { ModelPicker } from "@/features/sessions/ModelPicker";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queries";
import { toAppError, type Automation, type Project } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";

const BRANCH_PATTERN = /^[A-Za-z0-9._/-]*$/;
const MIN_ATTEMPTS = 1;
const MAX_ATTEMPTS = 10;

type AutomationSettingsProps = {
  project: Project;
  automation: Automation;
};

export function AutomationSettings({ project, automation }: AutomationSettingsProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [enabled, setEnabled] = useState(automation.configured ? automation.enabled : true);
  const [triggerLabel, setTriggerLabel] = useState(automation.triggerLabel);
  const [baseBranch, setBaseBranch] = useState(automation.baseBranch ?? "");
  const [modelId, setModelId] = useState(automation.modelId);
  const [checkCommand, setCheckCommand] = useState(automation.checkCommand ?? "");
  const [maxAttempts, setMaxAttempts] = useState(String(automation.maxAttempts));
  const [budget, setBudget] = useState(
    automation.budgetUsd === null ? "" : String(automation.budgetUsd),
  );
  const [escalation, setEscalation] = useState(automation.escalation);
  const [saving, setSaving] = useState(false);

  const attempts = Number(maxAttempts);
  const budgetValue = budget.trim() === "" ? 0 : Number(budget);
  const valid =
    triggerLabel.trim() !== "" &&
    !triggerLabel.includes(",") &&
    BRANCH_PATTERN.test(baseBranch.trim()) &&
    Number.isInteger(attempts) &&
    attempts >= MIN_ATTEMPTS &&
    attempts <= MAX_ATTEMPTS &&
    Number.isFinite(budgetValue) &&
    budgetValue >= 0;

  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid) {
      return;
    }
    setSaving(true);
    try {
      const saved = (await unwrap(
        api().PUT("/api/v1/projects/{projectId}/automation", {
          params: { path: { projectId: project.id } },
          body: {
            enabled,
            triggerLabel: triggerLabel.trim(),
            baseBranch: baseBranch.trim(),
            modelId: modelId ?? undefined,
            checkCommand: checkCommand.trim(),
            maxAttempts: attempts,
            budgetUsd: budgetValue,
            escalation,
          },
        }),
      )) as Automation;
      queryClient.setQueryData(queryKeys.automation(project.id), saved);
      toast.success(t("automation.saved"));
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="flex flex-col gap-4 pt-2" onSubmit={(event) => void save(event)}>
      <label className="flex items-start gap-3">
        <Switch checked={enabled} onCheckedChange={setEnabled} className="mt-0.5" />
        <span className="flex flex-col gap-0.5">
          <span className="text-sm font-medium">{t("automation.enabled")}</span>
          <span className="text-xs text-muted-foreground">{t("automation.enabledHint")}</span>
        </span>
      </label>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="automation-label">{t("automation.triggerLabel")}</FieldLabel>
          <Input
            id="automation-label"
            value={triggerLabel}
            maxLength={50}
            onChange={(event) => setTriggerLabel(event.target.value)}
            aria-invalid={triggerLabel.trim() === "" || triggerLabel.includes(",")}
          />
          <FieldDescription>{t("automation.triggerLabelHint")}</FieldDescription>
        </Field>
        <Field>
          <FieldLabel htmlFor="automation-branch">{t("automation.baseBranch")}</FieldLabel>
          <Input
            id="automation-branch"
            value={baseBranch}
            placeholder="main"
            onChange={(event) => setBaseBranch(event.target.value)}
            aria-invalid={!BRANCH_PATTERN.test(baseBranch.trim())}
          />
          <FieldDescription>{t("automation.baseBranchHint")}</FieldDescription>
        </Field>
      </div>
      <Field>
        <FieldLabel>{t("automation.model")}</FieldLabel>
        <div className="flex items-center gap-1">
          <ModelPicker value={modelId} onChange={setModelId} />
          {modelId !== null && (
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => setModelId(null)}
              title={t("automation.defaultModel")}
              aria-label={t("automation.defaultModel")}
            >
              <XIcon />
            </Button>
          )}
        </div>
        <FieldDescription>{t("automation.modelHint")}</FieldDescription>
      </Field>
      <Field>
        <FieldLabel htmlFor="automation-check">{t("automation.checkCommand")}</FieldLabel>
        <Input
          id="automation-check"
          value={checkCommand}
          placeholder="npm test"
          className="font-mono"
          maxLength={2000}
          onChange={(event) => setCheckCommand(event.target.value)}
        />
        <FieldDescription>{t("automation.checkCommandHint")}</FieldDescription>
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field>
          <FieldLabel htmlFor="automation-attempts">{t("automation.maxAttempts")}</FieldLabel>
          <Input
            id="automation-attempts"
            type="number"
            min={MIN_ATTEMPTS}
            max={MAX_ATTEMPTS}
            value={maxAttempts}
            onChange={(event) => setMaxAttempts(event.target.value)}
          />
          <FieldDescription>{t("automation.maxAttemptsHint")}</FieldDescription>
        </Field>
        <Field>
          <FieldLabel htmlFor="automation-budget">{t("automation.budget")}</FieldLabel>
          <Input
            id="automation-budget"
            type="number"
            min={0}
            step="0.01"
            value={budget}
            onChange={(event) => setBudget(event.target.value)}
          />
          <FieldDescription>{t("automation.budgetHint")}</FieldDescription>
        </Field>
      </div>
      <Field>
        <label className="flex items-center gap-2 text-sm font-medium">
          <Switch size="sm" checked={escalation} onCheckedChange={setEscalation} />
          {t("automation.escalation")}
        </label>
        <FieldDescription>{t("automation.escalationHint")}</FieldDescription>
      </Field>
      <div className="flex items-center justify-between gap-4">
        <p
          className={cn(
            "text-xs text-muted-foreground",
            (automation.webhookError ||
              (automation.configured && automation.webhook === "missing" && enabled)) &&
              "text-amber-600",
          )}
        >
          {automation.webhookError
            ? t("automation.webhook.failed", { error: automation.webhookError })
            : t(`automation.webhook.${automation.webhook}`)}
        </p>
        <Button type="submit" disabled={!valid || saving}>
          {t("common.save")}
        </Button>
      </div>
    </form>
  );
}
