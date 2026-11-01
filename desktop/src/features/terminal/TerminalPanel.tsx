import { useState, type PointerEvent } from "react";
import { useTranslation } from "react-i18next";
import { SquareTerminalIcon, XIcon } from "lucide-react";
import { useWorkspace } from "@/features/workspace/workspace-store";
import { useProjects } from "@/shared/api/queries";
import { Button } from "@/shared/ui/button";
import { TerminalView } from "./TerminalView";

const MIN_HEIGHT = 120;
const DEFAULT_HEIGHT = 260;

export function TerminalPanel() {
  const { t } = useTranslation();
  const open = useWorkspace((state) => state.terminalOpen);
  const activeProjectId = useWorkspace((state) => state.activeProjectId);
  const { data: projects } = useProjects();
  const [height, setHeight] = useState(DEFAULT_HEIGHT);
  const [started, setStarted] = useState<string[]>([]);
  const projectId = activeProjectId ?? projects?.[0]?.id ?? null;
  const projectName = projects?.find((project) => project.id === projectId)?.name;

  if (open && projectId && !started.includes(projectId)) {
    setStarted([...started, projectId]);
  }

  const startResize = (event: PointerEvent<HTMLDivElement>) => {
    const startY = event.clientY;
    const startHeight = height;
    const maxHeight = window.innerHeight * 0.7;
    const onMove = (move: globalThis.PointerEvent) => {
      setHeight(Math.min(maxHeight, Math.max(MIN_HEIGHT, startHeight + startY - move.clientY)));
    };
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  if (started.length === 0) {
    return null;
  }

  return (
    <section
      className="flex shrink-0 flex-col border-t bg-background"
      style={{ height, display: open && projectId ? "flex" : "none" }}
    >
      <div
        role="separator"
        aria-orientation="horizontal"
        className="h-1 cursor-row-resize hover:bg-primary/30"
        onPointerDown={startResize}
      />
      <div className="flex items-center justify-between px-3 pb-1">
        <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <SquareTerminalIcon className="size-3.5" />
          {t("terminal.title")}
          {projectName && <span className="text-foreground">{projectName}</span>}
        </span>
        <Button
          size="icon-xs"
          variant="ghost"
          aria-label={t("changes.close")}
          onClick={() => useWorkspace.getState().setTerminalOpen(false)}
        >
          <XIcon />
        </Button>
      </div>
      <div className="min-h-0 flex-1">
        {started.map((id) => (
          <TerminalView key={id} projectId={id} visible={open && id === projectId} />
        ))}
      </div>
    </section>
  );
}
