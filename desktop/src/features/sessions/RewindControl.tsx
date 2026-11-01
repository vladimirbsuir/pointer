import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Undo2Icon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";

type RewindControlProps = {
  sessionId: string;
  turnId: string;
  disabled: boolean;
  onRewound: (text: string) => void;
};

type Step = "closed" | "choose" | "force";

export function RewindControl({ sessionId, turnId, disabled, onRewound }: RewindControlProps) {
  const { t } = useTranslation();
  const [step, setStep] = useState<Step>("closed");
  const [busy, setBusy] = useState(false);

  const rewind = async (restoreFiles: boolean, force: boolean) => {
    setBusy(true);
    try {
      const result = (await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/rewind", {
          params: { path: { sessionId } },
          body: { turnId, restoreFiles, force },
        }),
      )) as { restoredFiles: string[]; text: string };
      setStep("closed");
      toast.success(
        result.restoredFiles.length > 0
          ? t("rewind.doneWithFiles", { count: result.restoredFiles.length })
          : t("rewind.done"),
      );
      onRewound(result.text);
    } catch (error) {
      const appError = toAppError(error);
      if (appError.code === "file_modified" && !force) {
        setStep("force");
      } else {
        setStep("closed");
        toast.error(errorMessage(t, appError));
      }
    } finally {
      setBusy(false);
    }
  };

  if (step === "closed") {
    return (
      <Button
        size="icon-xs"
        variant="ghost"
        disabled={disabled}
        title={t("rewind.action")}
        aria-label={t("rewind.action")}
        className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100"
        onClick={() => setStep("choose")}
      >
        <Undo2Icon />
      </Button>
    );
  }

  return (
    <div className="flex max-w-[85%] flex-col gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs">
      <span>{t(step === "force" ? "rewind.force" : "rewind.confirm")}</span>
      <div className="flex flex-wrap justify-end gap-1">
        <Button size="xs" variant="ghost" onClick={() => setStep("closed")}>
          {t("common.cancel")}
        </Button>
        {step === "choose" && (
          <Button
            size="xs"
            variant="outline"
            disabled={busy}
            onClick={() => void rewind(false, false)}
          >
            {t("rewind.conversationOnly")}
          </Button>
        )}
        <Button
          size="xs"
          variant="destructive"
          disabled={busy}
          onClick={() => void rewind(true, step === "force")}
        >
          {t(step === "force" ? "rewind.overwrite" : "rewind.withFiles")}
        </Button>
      </div>
    </div>
  );
}
