import { useState } from "react";
import { useTranslation } from "react-i18next";
import { open } from "@tauri-apps/plugin-dialog";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  ChevronRightIcon,
  FolderIcon,
  FolderPlusIcon,
  GitBranchIcon,
  LinkIcon,
  Loader2Icon,
  PlusIcon,
} from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { core } from "@/shared/api/core";
import { queryKeys, useLocalProjects, useProjects, useSessions } from "@/shared/api/queries";
import { toAppError, type Project, type Session } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/shared/ui/dropdown-menu";
import { createSession } from "./create-session";
import { useWorkspace } from "./workspace-store";

function folderName(path: string) {
  return path.split(/[\\/]/).filter(Boolean).at(-1) ?? path;
}

async function pickFolder(): Promise<string | null> {
  const selected = await open({ directory: true, multiple: false });
  return typeof selected === "string" ? selected : null;
}

export function ProjectsSidebar() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: projects } = useProjects();
  const { data: localPaths } = useLocalProjects();
  const [adding, setAdding] = useState(false);

  const addProject = async () => {
    const path = await pickFolder();
    if (!path) {
      return;
    }
    setAdding(true);
    try {
      const project = (await unwrap(
        api().POST("/api/v1/projects", { body: { name: folderName(path) } }),
      )) as Project;
      await core.linkProject(project.id, path);
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects });
      await queryClient.invalidateQueries({ queryKey: queryKeys.localProjects });
      useWorkspace.getState().selectProject(project.id);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    } finally {
      setAdding(false);
    }
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center justify-between px-3 pb-1">
        <span className="text-xs font-medium text-muted-foreground uppercase">
          {t("shell.projects")}
        </span>
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={() => void addProject()}
          disabled={adding}
          aria-label={t("shell.addProject")}
        >
          <FolderPlusIcon />
        </Button>
      </div>
      <div className="flex-1 overflow-y-auto px-2">
        {projects?.length === 0 && (
          <div className="flex flex-col items-start gap-2 px-2 py-2">
            <p className="text-sm text-muted-foreground">{t("shell.noProjects")}</p>
            <Button size="sm" variant="outline" onClick={() => void addProject()}>
              <FolderPlusIcon />
              {t("shell.openFolder")}
            </Button>
          </div>
        )}
        {projects?.map((project) => (
          <ProjectNode key={project.id} project={project} path={localPaths?.[project.id] ?? null} />
        ))}
      </div>
    </div>
  );
}

function ProjectNode({ project, path }: { project: Project; path: string | null }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const activeProjectId = useWorkspace((state) => state.activeProjectId);
  const [expanded, setExpanded] = useState(true);
  const { data: sessions } = useSessions(project.id);

  const startSession = async (worktree = false) => {
    try {
      await createSession(project.id, queryClient, { worktree });
      setExpanded(true);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const linkFolder = async () => {
    const folder = await pickFolder();
    if (folder) {
      await core.linkProject(project.id, folder);
      await queryClient.invalidateQueries({ queryKey: queryKeys.localProjects });
    }
  };

  return (
    <div className="mb-1">
      <div
        className={cn(
          "group flex items-center gap-1 rounded-md px-1 py-1 text-sm hover:bg-sidebar-accent",
          activeProjectId === project.id && "font-medium",
        )}
      >
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          onClick={() => setExpanded(!expanded)}
          title={path ?? t("shell.notLinked")}
        >
          <ChevronRightIcon
            className={cn("size-3.5 shrink-0 transition-transform", expanded && "rotate-90")}
          />
          <FolderIcon className="size-4 shrink-0 text-muted-foreground" />
          <span className="truncate">{project.name}</span>
        </button>
        {!path && (
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={() => void linkFolder()}
            aria-label={t("shell.linkFolder")}
            title={t("shell.notLinked")}
          >
            <LinkIcon className="text-amber-500" />
          </Button>
        )}
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="ghost"
                size="icon-xs"
                className="opacity-0 group-hover:opacity-100 aria-expanded:opacity-100"
                aria-label={t("shell.newSession")}
              />
            }
          >
            <PlusIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuItem onClick={() => void startSession()}>
              <PlusIcon />
              {t("shell.newSession")}
            </DropdownMenuItem>
            <DropdownMenuItem disabled={!path} onClick={() => void startSession(true)}>
              <GitBranchIcon />
              {t("shell.newWorktreeSession")}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {expanded && (
        <div className="ml-4 flex flex-col">
          {sessions?.map((session) => (
            <SessionRow key={session.id} projectId={project.id} session={session} />
          ))}
          {sessions?.length === 0 && (
            <button
              type="button"
              onClick={() => void startSession()}
              className="px-2 py-1 text-left text-xs text-muted-foreground hover:text-foreground"
            >
              {t("shell.newSession")}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function SessionRow({ projectId, session }: { projectId: string; session: Session }) {
  const active = useWorkspace((state) => state.activeSessionId === session.id);

  return (
    <button
      type="button"
      onClick={() => useWorkspace.getState().openSession(projectId, session.id)}
      className={cn(
        "flex items-center gap-2 rounded-md px-2 py-1 text-left text-sm text-sidebar-foreground/80 hover:bg-sidebar-accent",
        active && "bg-sidebar-accent text-sidebar-foreground",
      )}
    >
      {session.worktree?.state === "active" && (
        <GitBranchIcon
          className="size-3.5 shrink-0 text-muted-foreground"
          aria-label={session.worktree.branch}
        />
      )}
      <span className="min-w-0 flex-1 truncate">{session.title}</span>
      {session.status === "running" && (
        <Loader2Icon className="size-3.5 shrink-0 animate-spin text-muted-foreground" />
      )}
      {session.status === "error" && (
        <span className="size-2 shrink-0 rounded-full bg-destructive" />
      )}
    </button>
  );
}
