import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { LogOutIcon, MessageSquarePlusIcon, SettingsIcon } from "lucide-react";
import { useAuthStore } from "@/features/auth/auth-store";
import { ConversationView } from "@/features/sessions/ConversationView";
import { SettingsDialog } from "@/features/settings/SettingsDialog";
import { ProjectsSidebar } from "@/features/workspace/ProjectsSidebar";
import { createSession } from "@/features/workspace/create-session";
import { useWorkspace } from "@/features/workspace/workspace-store";
import { queryKeys } from "@/shared/api/queries";
import { toAppError, type Project } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { notify } from "@/shared/lib/notify";
import { onRealtimeMessage, useRealtimeConnected } from "@/shared/realtime/realtime";
import { toast } from "sonner";
import { cn } from "@/shared/lib/utils";
import { Avatar, AvatarFallback } from "@/shared/ui/avatar";
import { Button } from "@/shared/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/shared/ui/dropdown-menu";

const BUSY_STATUSES = ["running", "waiting_for_approval", "waiting_for_executor"];

function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("");
}

export function AppShell() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const user = useAuthStore((state) => state.user);
  const signOut = useAuthStore((state) => state.signOut);
  const activeSessionId = useWorkspace((state) => state.activeSessionId);
  const connected = useRealtimeConnected();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const statuses = useRef(new Map<string, string>());

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.ctrlKey || event.altKey || event.shiftKey) {
        return;
      }
      if (event.key === ",") {
        event.preventDefault();
        setSettingsOpen(true);
      }
      const projectId =
        useWorkspace.getState().activeProjectId ??
        queryClient.getQueryData<Project[]>(queryKeys.projects)?.[0]?.id;
      if (event.key.toLowerCase() === "n" && projectId) {
        event.preventDefault();
        createSession(projectId, queryClient).catch((error: unknown) =>
          toast.error(errorMessage(t, toAppError(error))),
        );
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [queryClient, t]);

  useEffect(
    () =>
      onRealtimeMessage((message) => {
        if (message.type === "sessions.changed" && message.payload.change === "status") {
          const sessionId = String(message.payload.sessionId);
          const status = String(message.payload.status);
          const title = String(message.payload.title ?? "");
          const previous = statuses.current.get(sessionId);
          statuses.current.set(sessionId, status);
          const wasBusy = previous !== undefined && BUSY_STATUSES.includes(previous);
          if (!document.hasFocus()) {
            if (status === "waiting_for_approval") {
              void notify(t("notifications.approval"), title);
            } else if (wasBusy && status === "idle") {
              void notify(t("notifications.finished"), title);
            } else if (wasBusy && status === "error") {
              void notify(t("notifications.failed"), title);
            }
          }
        }
        if (message.type === "sessions.changed") {
          const projectId = String(message.payload.projectId);
          void queryClient.invalidateQueries({ queryKey: queryKeys.sessions(projectId) });
          if (
            message.payload.change === "deleted" &&
            message.payload.sessionId === activeSessionId
          ) {
            useWorkspace.getState().closeSession();
          }
        }
      }),
    [queryClient, activeSessionId, t],
  );

  if (!user) {
    return null;
  }

  return (
    <div className="flex h-screen">
      <aside className="flex w-64 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground">
        <div className="flex items-center justify-between px-4 py-3">
          <span className="text-lg font-semibold tracking-tight">{t("app.name")}</span>
          <span
            className={cn(
              "size-2 rounded-full",
              connected ? "bg-emerald-500" : "bg-muted-foreground/40",
            )}
            title={connected ? t("shell.connected") : t("shell.disconnected")}
          />
        </div>
        <ProjectsSidebar />
        <div className="border-t p-2">
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<Button variant="ghost" className="h-auto w-full justify-start gap-2 p-2" />}
            >
              <Avatar size="sm">
                <AvatarFallback>{initials(user.displayName)}</AvatarFallback>
              </Avatar>
              <span className="truncate text-sm">{user.displayName}</span>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" className="w-56">
              <DropdownMenuGroup>
                <DropdownMenuLabel className="truncate">{user.email}</DropdownMenuLabel>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => setSettingsOpen(true)}>
                <SettingsIcon />
                {t("shell.settings")}
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => void signOut()}>
                <LogOutIcon />
                {t("shell.signOut")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>
      <main className="flex min-w-0 flex-1 flex-col">
        {activeSessionId ? (
          <ConversationView key={activeSessionId} sessionId={activeSessionId} />
        ) : (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
            <MessageSquarePlusIcon className="size-10 text-muted-foreground/60" />
            <h2 className="text-2xl font-semibold tracking-tight">
              {t("shell.welcome", { name: user.displayName })}
            </h2>
            <p className="max-w-md text-muted-foreground">{t("shell.welcomeDescription")}</p>
          </div>
        )}
      </main>
      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
    </div>
  );
}
