import { useTranslation } from "react-i18next";
import { BrainIcon } from "lucide-react";
import type { Project } from "@/shared/api/types";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/shared/ui/dialog";
import { MemoryList } from "./MemoryList";

type ProjectMemoryDialogProps = {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ProjectMemoryDialog({ project, open, onOpenChange }: ProjectMemoryDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <BrainIcon className="size-4" />
            {t("memory.dialogTitle", { project: project.name })}
          </DialogTitle>
        </DialogHeader>
        {open && <MemoryList projectId={project.id} />}
      </DialogContent>
    </Dialog>
  );
}
