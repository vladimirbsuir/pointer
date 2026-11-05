import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { isTauri } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
import { toast } from "sonner";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queries";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { Button } from "@/shared/ui/button";

const LINK_POLL_MS = 2000;

async function openExternal(url: string) {
  if (isTauri()) {
    await openUrl(url);
  } else {
    window.open(url, "_blank", "noopener");
  }
}

export function TelegramSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [pendingUrl, setPendingUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { data: status } = useQuery({
    queryKey: queryKeys.telegram,
    queryFn: async () => unwrap(api().GET("/api/v1/telegram")),
    refetchInterval: (query) => (pendingUrl && !query.state.data?.linked ? LINK_POLL_MS : false),
  });
  const waiting = pendingUrl !== null && status?.linked === false;
  const justLinked = pendingUrl !== null && status?.linked === true;

  useEffect(() => {
    if (justLinked) {
      toast.success(t("settings.telegramLinkedToast"));
    }
  }, [justLinked, t]);

  if (!status) {
    return null;
  }

  const link = async () => {
    setBusy(true);
    try {
      const { url } = await unwrap(api().POST("/api/v1/telegram/link"));
      if (!url) {
        return;
      }
      setPendingUrl(url);
      await openExternal(url);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setBusy(false);
    }
  };

  const unlink = async () => {
    setBusy(true);
    try {
      await unwrap(api().DELETE("/api/v1/telegram/link"));
      setPendingUrl(null);
      await queryClient.invalidateQueries({ queryKey: queryKeys.telegram });
      toast.success(t("settings.telegramUnlinkedToast"));
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setBusy(false);
    }
  };

  const linkedLabel = status.telegramUsername
    ? t("settings.telegramLinked", { name: `@${status.telegramUsername}` })
    : t("settings.telegramLinkedNoUsername");

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-sm font-medium">{t("settings.telegram")}</p>
          <p className="text-sm text-muted-foreground">
            {!status.enabled
              ? t("settings.telegramDisabled")
              : status.linked
                ? linkedLabel
                : t("settings.telegramNotLinked")}
          </p>
        </div>
        {status.enabled &&
          (status.linked ? (
            <Button variant="outline" disabled={busy} onClick={() => void unlink()}>
              {t("settings.unlinkTelegram")}
            </Button>
          ) : (
            <Button variant="outline" disabled={busy} onClick={() => void link()}>
              {t("settings.linkTelegram")}
            </Button>
          ))}
      </div>
      {status.enabled && !status.linked && (
        <p className="text-sm text-muted-foreground">
          {waiting ? t("settings.telegramWaiting") : t("settings.telegramDescription")}
        </p>
      )}
      {waiting && pendingUrl && (
        <Button
          variant="link"
          className="self-start px-0"
          onClick={() => void openExternal(pendingUrl)}
        >
          {t("settings.telegramOpenLink")}
        </Button>
      )}
    </div>
  );
}
