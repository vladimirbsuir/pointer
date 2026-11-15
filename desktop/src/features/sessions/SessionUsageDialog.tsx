import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, unwrap } from "@/shared/api/client";
import { toAppError, type Session, type SessionUsage } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { formatCost } from "./format-cost";

type SessionUsageDialogProps = {
  session: Session;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onBudgetChanged: (session: Session) => void;
};

export function SessionUsageDialog({
  session,
  open,
  onOpenChange,
  onBudgetChanged,
}: SessionUsageDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{t("usage.title")}</DialogTitle>
          <DialogDescription>{t("usage.description")}</DialogDescription>
        </DialogHeader>
        {open && <UsageBody session={session} onBudgetChanged={onBudgetChanged} />}
      </DialogContent>
    </Dialog>
  );
}

function UsageBody({
  session,
  onBudgetChanged,
}: {
  session: Session;
  onBudgetChanged: (session: Session) => void;
}) {
  const { t } = useTranslation();
  const { data: usage } = useQuery({
    queryKey: ["sessionUsage", session.id, session.lastSeq],
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/sessions/{sessionId}/usage", {
          params: { path: { sessionId: session.id } },
        }),
      )) as SessionUsage,
  });
  const [budget, setBudget] = useState(session.budgetUsd?.toString() ?? "");
  const [saving, setSaving] = useState(false);
  const parsed = budget.trim() === "" ? 0 : Number(budget.trim().replace(",", "."));
  const valid = Number.isFinite(parsed) && parsed >= 0 && parsed <= 100_000;
  const spent = usage?.estimatedCost ?? session.estimatedCost;
  const limit = session.budgetUsd;
  const percent = limit && spent !== null ? Math.min(100, Math.round((spent / limit) * 100)) : null;

  const saveBudget = async (value: number) => {
    setSaving(true);
    try {
      const updated = (await unwrap(
        api().PATCH("/api/v1/sessions/{sessionId}", {
          params: { path: { sessionId: session.id } },
          body: { budgetUsd: value },
        }),
      )) as Session;
      onBudgetChanged(updated);
      setBudget(updated.budgetUsd?.toString() ?? "");
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="flex flex-col gap-1.5">
        <p>
          {spent === null
            ? t("usage.noPrices")
            : limit
              ? t("usage.spentOfBudget", { spent: formatCost(spent), budget: formatCost(limit) })
              : t("usage.spent", { spent: formatCost(spent) })}
        </p>
        {percent !== null && (
          <div className="h-1.5 overflow-hidden rounded-full bg-muted">
            <div
              className={percent >= 100 ? "h-full bg-destructive" : "h-full bg-primary"}
              style={{ width: `${percent}%` }}
            />
          </div>
        )}
      </div>
      {usage && usage.entries.length > 0 && (
        <table className="w-full text-xs tabular-nums">
          <thead className="text-muted-foreground">
            <tr className="text-left">
              <th className="py-1 font-normal">{t("usage.model")}</th>
              <th className="py-1 font-normal">{t("usage.purpose")}</th>
              <th className="py-1 text-right font-normal">{t("usage.calls")}</th>
              <th className="py-1 text-right font-normal">{t("usage.tokens")}</th>
              <th className="py-1 text-right font-normal">{t("usage.cost")}</th>
            </tr>
          </thead>
          <tbody>
            {usage.entries.map((entry) => (
              <tr key={`${entry.modelId}-${entry.role}-${entry.purpose}`} className="border-t">
                <td className="max-w-40 truncate py-1">{entry.modelLabel}</td>
                <td className="py-1">{t(`usage.purposes.${entry.purpose}`)}</td>
                <td className="py-1 text-right">{entry.calls}</td>
                <td className="py-1 text-right">
                  {(entry.promptTokens + entry.completionTokens).toLocaleString()}
                </td>
                <td className="py-1 text-right">
                  {entry.costUsd === null ? "—" : formatCost(entry.costUsd)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Field>
        <FieldLabel htmlFor="session-budget">{t("usage.budget")}</FieldLabel>
        <div className="flex gap-2">
          <Input
            id="session-budget"
            value={budget}
            inputMode="decimal"
            placeholder="0.50"
            className="w-32"
            aria-invalid={!valid}
            onChange={(event) => setBudget(event.target.value)}
          />
          <Button disabled={!valid || saving} onClick={() => void saveBudget(parsed)}>
            {t("common.save")}
          </Button>
          {limit !== null && (
            <Button variant="ghost" disabled={saving} onClick={() => void saveBudget(0)}>
              {t("usage.clearBudget")}
            </Button>
          )}
        </div>
        <FieldDescription>{t("usage.budgetHint")}</FieldDescription>
      </Field>
    </div>
  );
}
