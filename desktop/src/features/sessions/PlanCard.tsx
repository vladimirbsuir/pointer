import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { CheckIcon, ListChecksIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { toAppError, type Item } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";

type PlanStep = { title: string; details?: string };

type PlanCardProps = {
  item: Item;
  sessionId: string;
  running: boolean;
};

export function PlanCard({ item, sessionId, running }: PlanCardProps) {
  const { t } = useTranslation();
  const [approving, setApproving] = useState(false);
  const steps = (item.steps as PlanStep[] | undefined) ?? [];
  const approval = String(item.approval ?? "pending") as "pending" | "approved" | "rejected";

  const approve = async () => {
    setApproving(true);
    try {
      await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/plans/{itemId}/approve", {
          params: { path: { sessionId, itemId: item.id } },
        }),
      );
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setApproving(false);
    }
  };

  return (
    <div
      className={cn(
        "rounded-xl border bg-card p-4 text-sm shadow-xs",
        approval === "rejected" && "opacity-60",
      )}
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 font-medium">
          <ListChecksIcon className="size-4 text-primary" />
          {t("plan.title")}
        </div>
        <Badge variant={approval === "approved" ? "default" : "secondary"}>
          {approval === "approved" && <CheckIcon />}
          {t(`plan.${approval}`)}
        </Badge>
      </div>
      {typeof item.summary === "string" && item.summary && (
        <p className="mb-3 text-muted-foreground">{item.summary}</p>
      )}
      <ol className="flex flex-col gap-2">
        {steps.map((step, index) => (
          <li key={index} className="flex gap-3">
            <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-medium tabular-nums">
              {index + 1}
            </span>
            <div className="min-w-0">
              <p className="font-medium">{step.title}</p>
              {step.details && <p className="text-muted-foreground">{step.details}</p>}
            </div>
          </li>
        ))}
      </ol>
      {approval === "pending" && (
        <div className="mt-4 flex items-center justify-between gap-3">
          <p className="text-xs text-muted-foreground">{t("plan.hint")}</p>
          <Button size="sm" disabled={running || approving} onClick={() => void approve()}>
            {t("plan.approve")}
          </Button>
        </div>
      )}
    </div>
  );
}
