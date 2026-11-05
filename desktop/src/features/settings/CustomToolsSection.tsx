import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ChevronRightIcon, RotateCcwIcon, Trash2Icon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import {
  queryKeys,
  useCustomToolVersions,
  useCustomTools,
  useProjects,
} from "@/shared/api/queries";
import { toAppError, type CustomTool } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Switch } from "@/shared/ui/switch";

type CallPolicy = CustomTool["defaultPolicy"];

const policies: CallPolicy[] = ["allow", "ask", "deny"];

export function CustomToolsSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data: tools } = useCustomTools();
  const { data: projects } = useProjects();

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.customTools });

  const update = async (
    tool: CustomTool,
    changes: { defaultPolicy?: CallPolicy; enabled?: boolean; modes?: string[] },
  ) => {
    try {
      await unwrap(
        api().PATCH("/api/v1/custom-tools/{toolId}", {
          params: { path: { toolId: tool.id } },
          body: changes,
        }),
      );
      await refresh();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const remove = async (tool: CustomTool) => {
    try {
      await unwrap(
        api().DELETE("/api/v1/custom-tools/{toolId}", { params: { path: { toolId: tool.id } } }),
      );
      await refresh();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  const scopeLabel = (tool: CustomTool) =>
    tool.scope === "user"
      ? t("customTools.scopes.user")
      : (projects?.find((project) => project.id === tool.projectId)?.name ??
        t("customTools.scopes.project"));

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{t("customTools.description")}</p>
      {tools?.length === 0 && (
        <p className="text-sm text-muted-foreground">{t("customTools.empty")}</p>
      )}
      <ul className="flex flex-col divide-y rounded-md border empty:hidden">
        {tools?.map((tool) => (
          <CustomToolRow
            key={tool.id}
            tool={tool}
            scopeLabel={scopeLabel(tool)}
            onUpdate={(changes) => void update(tool, changes)}
            onRemove={() => void remove(tool)}
            onRestored={() => void refresh()}
          />
        ))}
      </ul>
    </div>
  );
}

function CustomToolRow({
  tool,
  scopeLabel,
  onUpdate,
  onRemove,
  onRestored,
}: {
  tool: CustomTool;
  scopeLabel: string;
  onUpdate: (changes: { defaultPolicy?: CallPolicy; enabled?: boolean; modes?: string[] }) => void;
  onRemove: () => void;
  onRestored: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const readModes = tool.modes.includes("ask") || tool.modes.includes("plan");

  return (
    <li className="flex flex-col gap-2 px-3 py-2">
      <div className="flex items-start gap-3">
        <button
          type="button"
          aria-label={t("customTools.versions")}
          className="mt-0.5 text-muted-foreground"
          onClick={() => setOpen(!open)}
        >
          <ChevronRightIcon className={cn("size-4 transition-transform", open && "rotate-90")} />
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className={cn("font-mono text-sm", !tool.enabled && "text-muted-foreground")}>
              {tool.name}
            </span>
            <Badge variant="secondary">v{tool.version}</Badge>
            <Badge variant="outline">{tool.interpreter}</Badge>
            <Badge variant="outline">{scopeLabel}</Badge>
          </div>
          <p className="line-clamp-2 text-xs text-muted-foreground">{tool.description}</p>
        </div>
        <Switch
          size="sm"
          checked={tool.enabled}
          aria-label={t("customTools.enabled")}
          onCheckedChange={(enabled) => onUpdate({ enabled })}
        />
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label={t("customTools.delete")}
          onClick={onRemove}
        >
          <Trash2Icon />
        </Button>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 pl-7 text-xs">
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground">{t("customTools.calls")}</span>
          <div role="radiogroup" className="flex gap-0.5 rounded-md bg-muted p-0.5">
            {policies.map((policy) => (
              <button
                key={policy}
                type="button"
                role="radio"
                aria-checked={tool.defaultPolicy === policy}
                onClick={() => onUpdate({ defaultPolicy: policy })}
                className={cn(
                  "rounded px-2 py-0.5 text-xs transition-colors",
                  tool.defaultPolicy === policy
                    ? "bg-background font-medium shadow-sm"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {t(`permissions.policies.${policy}`)}
              </button>
            ))}
          </div>
        </div>
        <label className="flex items-center gap-2">
          <span className="text-muted-foreground">{t("customTools.readModes")}</span>
          <Switch
            size="sm"
            checked={readModes}
            onCheckedChange={(checked) =>
              onUpdate({ modes: checked ? ["ask", "plan", "agent"] : ["agent"] })
            }
          />
        </label>
      </div>
      {open && <VersionList tool={tool} onRestored={onRestored} />}
    </li>
  );
}

function VersionList({ tool, onRestored }: { tool: CustomTool; onRestored: () => void }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { data: versions } = useCustomToolVersions(tool.id, true);
  const [shown, setShown] = useState<number | null>(tool.version);

  const restore = async (version: number) => {
    try {
      await unwrap(
        api().POST("/api/v1/custom-tools/{toolId}/versions/{version}/restore", {
          params: { path: { toolId: tool.id, version } },
        }),
      );
      await queryClient.invalidateQueries({ queryKey: queryKeys.customToolVersions(tool.id) });
      onRestored();
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  return (
    <div className="flex flex-col gap-1 pl-7">
      {versions?.map((version) => (
        <div key={version.version} className="flex flex-col gap-1">
          <div className="flex items-center gap-2 text-xs">
            <button
              type="button"
              className="font-mono hover:underline"
              onClick={() => setShown(shown === version.version ? null : version.version)}
            >
              v{version.version}
            </button>
            <span className="text-muted-foreground">
              {new Date(version.createdAt).toLocaleString(i18n.language)}
            </span>
            {version.current ? (
              <Badge variant="secondary">{t("customTools.current")}</Badge>
            ) : (
              <Button size="xs" variant="ghost" onClick={() => void restore(version.version)}>
                <RotateCcwIcon />
                {t("customTools.restore")}
              </Button>
            )}
          </div>
          {shown === version.version && (
            <pre className="max-h-60 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-xs">
              {version.body}
            </pre>
          )}
        </div>
      ))}
    </div>
  );
}
