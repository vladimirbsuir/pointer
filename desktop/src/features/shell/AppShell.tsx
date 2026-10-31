import { useState } from "react";
import { useTranslation } from "react-i18next";
import { FolderOpenIcon, LogOutIcon, SettingsIcon } from "lucide-react";
import { useAuthStore } from "@/features/auth/auth-store";
import { SettingsDialog } from "@/features/settings/SettingsDialog";
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
  const user = useAuthStore((state) => state.user);
  const signOut = useAuthStore((state) => state.signOut);
  const [settingsOpen, setSettingsOpen] = useState(false);

  if (!user) {
    return null;
  }

  return (
    <div className="flex h-screen">
      <aside className="flex w-64 shrink-0 flex-col border-r bg-sidebar text-sidebar-foreground">
        <div className="px-4 py-4 text-lg font-semibold tracking-tight">{t("app.name")}</div>
        <div className="flex flex-1 flex-col gap-2 px-2">
          <p className="px-2 text-xs font-medium text-muted-foreground uppercase">
            {t("shell.projects")}
          </p>
          <p className="px-2 text-sm text-muted-foreground">{t("shell.noProjects")}</p>
        </div>
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
      <main className="flex flex-1 flex-col items-center justify-center gap-4 p-8 text-center">
        <h2 className="text-2xl font-semibold tracking-tight">
          {t("shell.welcome", { name: user.displayName })}
        </h2>
        <p className="max-w-md text-muted-foreground">{t("shell.welcomeDescription")}</p>
        <Button disabled>
          <FolderOpenIcon />
          {t("shell.openFolder")}
        </Button>
      </main>
      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
    </div>
  );
}
