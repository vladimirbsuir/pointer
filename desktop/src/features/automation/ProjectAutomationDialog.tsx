import { useState } from "react";
import { useTranslation } from "react-i18next";
import { BotIcon } from "lucide-react";
import { useAutomation, useRemoteStatus } from "@/shared/api/queries";
import type { Automation, Project } from "@/shared/api/types";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/shared/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/shared/ui/tabs";
import { AutomationRuns } from "./AutomationRuns";
import { AutomationSettings } from "./AutomationSettings";

type ProjectAutomationDialogProps = {
  project: Project;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function ProjectAutomationDialog({
  project,
  open,
  onOpenChange,
}: ProjectAutomationDialogProps) {
  const { t } = useTranslation();

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <BotIcon className="size-4" />
            {t("automation.title", { project: project.name })}
          </DialogTitle>
          <DialogDescription>{t("automation.description")}</DialogDescription>
        </DialogHeader>
        {open && <AutomationPanel project={project} onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function AutomationPanel({ project, onClose }: { project: Project; onClose: () => void }) {
  const { t } = useTranslation();
  const { data: remote } = useRemoteStatus();
  const { data: automation } = useAutomation(project.id, true);
  const blocker = !project.repository
    ? t("automation.noRepository")
    : remote && !remote.repositoryAccess
      ? t("automation.noAccess")
      : null;

  if (blocker) {
    return <p className="text-sm text-amber-600">{blocker}</p>;
  }
  if (!automation) {
    return null;
  }

  return (
    <AutomationTabs
      project={project}
      automation={automation}
      remoteEnabled={remote?.enabled ?? true}
      onClose={onClose}
    />
  );
}

function AutomationTabs({
  project,
  automation,
  remoteEnabled,
  onClose,
}: {
  project: Project;
  automation: Automation;
  remoteEnabled: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [tab, setTab] = useState(automation.configured ? "runs" : "settings");

  return (
    <Tabs value={tab} onValueChange={(value) => setTab(String(value))}>
      <TabsList>
        <TabsTrigger value="runs">{t("automation.tabs.runs")}</TabsTrigger>
        <TabsTrigger value="settings">{t("automation.tabs.settings")}</TabsTrigger>
      </TabsList>
      <TabsContent value="runs">
        {!remoteEnabled && (
          <p className="mb-3 text-sm text-amber-600">{t("automation.remoteDisabled")}</p>
        )}
        <AutomationRuns project={project} automation={automation} onOpenSession={onClose} />
      </TabsContent>
      <TabsContent value="settings">
        <AutomationSettings
          key={automation.updatedAt ?? "new"}
          project={project}
          automation={automation}
        />
      </TabsContent>
    </Tabs>
  );
}
