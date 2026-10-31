import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { AuthScreen } from "@/features/auth/AuthScreen";
import { useAuthStore } from "@/features/auth/auth-store";
import { AppShell } from "@/features/shell/AppShell";
import { queryClient } from "@/shared/api/queries";
import { useApplyTheme } from "@/shared/lib/theme";
import { QueryClientProvider } from "@tanstack/react-query";
import { Button } from "@/shared/ui/button";
import { Toaster } from "@/shared/ui/sonner";

export function App() {
  useApplyTheme();
  const status = useAuthStore((state) => state.status);
  const restore = useAuthStore((state) => state.restore);

  useEffect(() => {
    void restore();
  }, [restore]);

  return (
    <QueryClientProvider client={queryClient}>
      {status === "loading" && <LoadingScreen />}
      {status === "unavailable" && <UnavailableScreen onRetry={() => void restore()} />}
      {status === "signedOut" && <AuthScreen />}
      {status === "signedIn" && <AppShell />}
      <Toaster position="bottom-right" />
    </QueryClientProvider>
  );
}

function LoadingScreen() {
  const { t } = useTranslation();
  return (
    <main className="flex h-screen items-center justify-center text-sm text-muted-foreground">
      {t("auth.loading")}
    </main>
  );
}

function UnavailableScreen({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <main className="flex h-screen flex-col items-center justify-center gap-3 text-center">
      <h2 className="text-lg font-semibold">{t("auth.unavailableTitle")}</h2>
      <p className="text-sm text-muted-foreground">{t("auth.unavailableDescription")}</p>
      <Button variant="outline" onClick={onRetry}>
        {t("common.retry")}
      </Button>
    </main>
  );
}
