import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { listen } from "@tauri-apps/api/event";
import { toast } from "sonner";
import { PencilIcon, PlugIcon, RotateCwIcon, Trash2Icon } from "lucide-react";
import { core, type McpServer, type McpServerConfig } from "@/shared/api/core";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { cn } from "@/shared/lib/utils";
import { Button } from "@/shared/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/shared/ui/field";
import { Input } from "@/shared/ui/input";
import { Switch } from "@/shared/ui/switch";
import { Textarea } from "@/shared/ui/textarea";

const mcpQueryKey = ["mcpServers"] as const;

const statusColors: Record<McpServer["status"], string> = {
  running: "bg-emerald-500",
  starting: "bg-amber-500 animate-pulse",
  failed: "bg-destructive",
  disabled: "bg-muted-foreground/40",
};

function splitCommandLine(line: string) {
  const tokens = line.match(/"[^"]*"|\S+/g) ?? [];
  return tokens.map((token) => token.replace(/^"(.*)"$/, "$1"));
}

function joinCommandLine(command: string, args: string[]) {
  return [command, ...args].map((part) => (/\s/.test(part) ? `"${part}"` : part)).join(" ");
}

function parsePairs(text: string, separator: string) {
  return Object.fromEntries(
    text
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.includes(separator))
      .map((line) => {
        const index = line.indexOf(separator);
        return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
      }),
  );
}

function formatPairs(pairs: Record<string, string>, separator: string) {
  return Object.entries(pairs)
    .map(([key, value]) => `${key}${separator}${value}`)
    .join("\n");
}

function describeTarget(server: McpServerConfig) {
  return server.transport === "stdio" ? joinCommandLine(server.command, server.args) : server.url;
}

export function McpSection() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<McpServerConfig | "new" | null>(null);
  const { data: servers } = useQuery({ queryKey: mcpQueryKey, queryFn: () => core.mcpServers() });

  useEffect(() => {
    const unlisten = listen("mcp://changed", () => {
      void queryClient.invalidateQueries({ queryKey: mcpQueryKey });
    });
    return () => {
      void unlisten.then((stop) => stop());
    };
  }, [queryClient]);

  const run = async (action: () => Promise<unknown>) => {
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: mcpQueryKey });
    } catch (error) {
      toast.error(errorMessage(t, toAppError(error)));
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{t("mcp.description")}</p>
      {servers?.length === 0 && editing === null && (
        <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">
          {t("mcp.empty")}
        </p>
      )}
      <div className="flex flex-col gap-2">
        {servers?.map((server) =>
          editing !== "new" && editing?.name === server.name ? (
            <ServerForm
              key={server.name}
              initial={server}
              onCancel={() => setEditing(null)}
              onSave={(config) =>
                run(async () => {
                  await core.mcpSave(config, server.name);
                  setEditing(null);
                })
              }
            />
          ) : (
            <ServerRow
              key={server.name}
              server={server}
              onToggle={(enabled) =>
                void run(() => core.mcpSave({ ...server, enabled }, server.name))
              }
              onRestart={() => void run(() => core.mcpRestart(server.name))}
              onEdit={() => setEditing(server)}
              onRemove={() => void run(() => core.mcpRemove(server.name))}
            />
          ),
        )}
      </div>
      {editing === "new" ? (
        <ServerForm
          initial={null}
          onCancel={() => setEditing(null)}
          onSave={(config) =>
            run(async () => {
              await core.mcpSave(config, null);
              setEditing(null);
            })
          }
        />
      ) : (
        <div>
          <Button variant="outline" size="sm" onClick={() => setEditing("new")}>
            <PlugIcon />
            {t("mcp.add")}
          </Button>
        </div>
      )}
    </div>
  );
}

type ServerRowProps = {
  server: McpServer;
  onToggle: (enabled: boolean) => void;
  onRestart: () => void;
  onEdit: () => void;
  onRemove: () => void;
};

function ServerRow({ server, onToggle, onRestart, onEdit, onRemove }: ServerRowProps) {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border p-3 text-sm">
      <div className="flex items-center gap-2">
        <span className={cn("size-2 shrink-0 rounded-full", statusColors[server.status])} />
        <span className="font-medium">{server.name}</span>
        <span className="text-xs text-muted-foreground">{t(`mcp.status.${server.status}`)}</span>
        <div className="ml-auto flex items-center gap-1">
          <Switch size="sm" checked={server.enabled} onCheckedChange={onToggle} />
          <Button
            size="icon-xs"
            variant="ghost"
            onClick={onRestart}
            disabled={!server.enabled}
            aria-label={t("mcp.restart")}
            title={t("mcp.restart")}
          >
            <RotateCwIcon />
          </Button>
          <Button
            size="icon-xs"
            variant="ghost"
            onClick={onEdit}
            aria-label={t("mcp.edit")}
            title={t("mcp.edit")}
          >
            <PencilIcon />
          </Button>
          <Button
            size="icon-xs"
            variant="ghost"
            onClick={onRemove}
            aria-label={t("mcp.remove")}
            title={t("mcp.remove")}
          >
            <Trash2Icon />
          </Button>
        </div>
      </div>
      <p className="mt-1 truncate font-mono text-xs text-muted-foreground">
        {describeTarget(server)}
      </p>
      {server.status === "running" && (
        <p className="mt-1 text-xs text-muted-foreground">
          {server.tools.length > 0
            ? t("mcp.tools", { count: server.tools.length, names: server.tools.join(", ") })
            : t("mcp.noTools")}
        </p>
      )}
      {server.status === "failed" && server.error && (
        <pre className="mt-2 max-h-32 overflow-auto rounded-md bg-destructive/10 p-2 font-mono text-xs whitespace-pre-wrap text-destructive">
          {server.error}
        </pre>
      )}
    </div>
  );
}

type ServerFormProps = {
  initial: McpServerConfig | null;
  onCancel: () => void;
  onSave: (config: McpServerConfig) => Promise<void>;
};

function ServerForm({ initial, onCancel, onSave }: ServerFormProps) {
  const { t } = useTranslation();
  const [name, setName] = useState(initial?.name ?? "");
  const [transport, setTransport] = useState<McpServerConfig["transport"]>(
    initial?.transport ?? "stdio",
  );
  const [commandLine, setCommandLine] = useState(
    initial?.transport === "stdio" ? joinCommandLine(initial.command, initial.args) : "",
  );
  const [env, setEnv] = useState(
    initial?.transport === "stdio" ? formatPairs(initial.env, "=") : "",
  );
  const [url, setUrl] = useState(initial?.transport === "http" ? initial.url : "");
  const [headers, setHeaders] = useState(
    initial?.transport === "http" ? formatPairs(initial.headers, ": ") : "",
  );
  const [saving, setSaving] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const [command = "", ...args] = splitCommandLine(commandLine);
    const config: McpServerConfig =
      transport === "stdio"
        ? {
            name: name.trim(),
            enabled: initial?.enabled ?? true,
            transport,
            command,
            args,
            env: parsePairs(env, "="),
          }
        : {
            name: name.trim(),
            enabled: initial?.enabled ?? true,
            transport,
            url: url.trim(),
            headers: parsePairs(headers, ":"),
          };
    setSaving(true);
    await onSave(config);
    setSaving(false);
  };

  return (
    <form
      onSubmit={(event) => void submit(event)}
      className="flex flex-col gap-3 rounded-lg border p-3"
    >
      <Field>
        <FieldLabel htmlFor="mcp-name">{t("mcp.name")}</FieldLabel>
        <Input
          id="mcp-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="filesystem"
          required
        />
      </Field>
      <div role="radiogroup" aria-label={t("mcp.transport")} className="flex gap-1">
        {(["stdio", "http"] as const).map((option) => (
          <Button
            key={option}
            type="button"
            size="sm"
            role="radio"
            aria-checked={transport === option}
            variant={transport === option ? "secondary" : "ghost"}
            onClick={() => setTransport(option)}
          >
            {t(`mcp.${option}`)}
          </Button>
        ))}
      </div>
      {transport === "stdio" ? (
        <>
          <Field>
            <FieldLabel htmlFor="mcp-command">{t("mcp.command")}</FieldLabel>
            <Input
              id="mcp-command"
              value={commandLine}
              onChange={(event) => setCommandLine(event.target.value)}
              placeholder="npx -y @modelcontextprotocol/server-filesystem C:\Projects"
              className="font-mono text-xs"
              required
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="mcp-env">{t("mcp.env")}</FieldLabel>
            <FieldDescription>{t("mcp.envHint")}</FieldDescription>
            <Textarea
              id="mcp-env"
              value={env}
              onChange={(event) => setEnv(event.target.value)}
              placeholder="GITHUB_TOKEN=..."
              rows={2}
              className="font-mono text-xs"
            />
          </Field>
        </>
      ) : (
        <>
          <Field>
            <FieldLabel htmlFor="mcp-url">{t("mcp.url")}</FieldLabel>
            <Input
              id="mcp-url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://example.com/mcp"
              className="font-mono text-xs"
              required
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="mcp-headers">{t("mcp.headers")}</FieldLabel>
            <FieldDescription>{t("mcp.headersHint")}</FieldDescription>
            <Textarea
              id="mcp-headers"
              value={headers}
              onChange={(event) => setHeaders(event.target.value)}
              placeholder="Authorization: Bearer ..."
              rows={2}
              className="font-mono text-xs"
            />
          </Field>
        </>
      )}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={saving}>
          {t("common.save")}
        </Button>
      </div>
    </form>
  );
}
