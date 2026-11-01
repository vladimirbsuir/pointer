import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { PlusIcon, XIcon } from "lucide-react";
import { api, unwrap } from "@/shared/api/client";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";

type Policy = "allow" | "ask" | "deny";
type ToolPermission = { name: string; kind: string; defaultPolicy: Policy; policy: Policy | null };
type CommandRule = { prefix: string; policy: Policy };
type Permissions = { tools: ToolPermission[]; commandRules: CommandRule[] };

const policies: Policy[] = ["allow", "ask", "deny"];
const permissionsKey = ["permissions"] as const;

export function PermissionsSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: permissionsKey,
    queryFn: async () => (await unwrap(api().GET("/api/v1/permissions"))) as Permissions,
  });
  const [prefix, setPrefix] = useState("");
  const [rulePolicy, setRulePolicy] = useState<Policy>("allow");

  if (!data) {
    return null;
  }

  const save = async (next: Permissions) => {
    queryClient.setQueryData(permissionsKey, next);
    try {
      const saved = await unwrap(
        api().PUT("/api/v1/permissions", {
          body: {
            policies: Object.fromEntries(
              next.tools.filter((tool) => tool.policy).map((tool) => [tool.name, tool.policy!]),
            ),
            commandRules: next.commandRules,
          },
        }),
      );
      queryClient.setQueryData(permissionsKey, saved as Permissions);
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
      void queryClient.invalidateQueries({ queryKey: permissionsKey });
    }
  };

  const setToolPolicy = (name: string, policy: Policy) =>
    void save({
      ...data,
      tools: data.tools.map((tool) =>
        tool.name === name
          ? { ...tool, policy: policy === tool.defaultPolicy ? null : policy }
          : tool,
      ),
    });

  const addRule = () => {
    const trimmed = prefix.trim();
    if (!trimmed) {
      return;
    }
    setPrefix("");
    void save({
      ...data,
      commandRules: [
        ...data.commandRules.filter((rule) => rule.prefix !== trimmed),
        { prefix: trimmed, policy: rulePolicy },
      ],
    });
  };

  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">{t("permissions.tools")}</h3>
        <p className="text-xs text-muted-foreground">{t("permissions.toolsHint")}</p>
        <ul className="flex flex-col divide-y rounded-lg border">
          {data.tools.map((tool) => {
            const effective = tool.policy ?? tool.defaultPolicy;
            return (
              <li key={tool.name} className="flex items-center justify-between gap-2 px-3 py-2">
                <span className="font-mono text-xs">{tool.name}</span>
                <PolicySwitch
                  value={effective}
                  onChange={(policy) => setToolPolicy(tool.name, policy)}
                />
              </li>
            );
          })}
        </ul>
      </section>
      <section className="flex flex-col gap-2">
        <h3 className="text-sm font-medium">{t("permissions.commandRules")}</h3>
        <p className="text-xs text-muted-foreground">{t("permissions.commandRulesHint")}</p>
        {data.commandRules.length > 0 && (
          <ul className="flex flex-col divide-y rounded-lg border">
            {data.commandRules.map((rule) => (
              <li key={rule.prefix} className="flex items-center gap-2 px-3 py-2">
                <span className="min-w-0 flex-1 truncate font-mono text-xs">{rule.prefix}</span>
                <span className="text-xs text-muted-foreground">
                  {t(`permissions.policies.${rule.policy}`)}
                </span>
                <Button
                  variant="ghost"
                  size="icon-xs"
                  onClick={() =>
                    void save({
                      ...data,
                      commandRules: data.commandRules.filter(
                        (existing) => existing.prefix !== rule.prefix,
                      ),
                    })
                  }
                >
                  <XIcon />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex gap-2">
          <Input
            value={prefix}
            onChange={(event) => setPrefix(event.target.value)}
            onKeyDown={(event) => event.key === "Enter" && addRule()}
            placeholder="git status"
            className="h-8 font-mono text-xs"
          />
          <PolicySwitch value={rulePolicy} onChange={setRulePolicy} />
          <Button size="sm" variant="outline" onClick={addRule}>
            <PlusIcon />
          </Button>
        </div>
      </section>
    </div>
  );
}

function PolicySwitch({ value, onChange }: { value: Policy; onChange: (policy: Policy) => void }) {
  const { t } = useTranslation();
  return (
    <div role="radiogroup" className="flex shrink-0 gap-0.5 rounded-md bg-muted p-0.5">
      {policies.map((policy) => (
        <button
          key={policy}
          type="button"
          role="radio"
          aria-checked={value === policy}
          onClick={() => onChange(policy)}
          className={cn(
            "rounded px-2 py-0.5 text-xs transition-colors",
            value === policy
              ? "bg-background font-medium shadow-sm"
              : "text-muted-foreground hover:text-foreground",
          )}
        >
          {t(`permissions.policies.${policy}`)}
        </button>
      ))}
    </div>
  );
}
