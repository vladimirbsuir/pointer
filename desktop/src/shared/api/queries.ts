import { QueryClient, useQuery } from "@tanstack/react-query";
import { api, unwrap } from "./client";
import { core } from "./core";
import type { Project, Provider, Session, SlashCommand } from "./types";

export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

export const queryKeys = {
  providers: ["providers"] as const,
  settings: ["settings"] as const,
  commands: ["commands"] as const,
  projects: ["projects"] as const,
  localProjects: ["localProjects"] as const,
  sessions: (projectId: string) => ["sessions", projectId] as const,
};

export function useProviders() {
  return useQuery({
    queryKey: queryKeys.providers,
    queryFn: async () => (await unwrap(api().GET("/api/v1/providers"))) as Provider[],
  });
}

export function useSettings() {
  return useQuery({
    queryKey: queryKeys.settings,
    queryFn: async () => {
      const data = await unwrap(api().GET("/api/v1/me/settings"));
      return {
        globalInstructions: data.globalInstructions ?? "",
        defaultModelId: data.defaultModelId ?? null,
      };
    },
  });
}

export function useSlashCommands() {
  return useQuery({
    queryKey: queryKeys.commands,
    queryFn: async () => (await unwrap(api().GET("/api/v1/commands"))) as SlashCommand[],
  });
}

export function useProjects() {
  return useQuery({
    queryKey: queryKeys.projects,
    queryFn: async () => (await unwrap(api().GET("/api/v1/projects"))) as Project[],
  });
}

export function useLocalProjects() {
  return useQuery({ queryKey: queryKeys.localProjects, queryFn: () => core.localProjects() });
}

export function useSessions(projectId: string) {
  return useQuery({
    queryKey: queryKeys.sessions(projectId),
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/projects/{projectId}/sessions", { params: { path: { projectId } } }),
      )) as Session[],
  });
}

export function enabledModels(providers: Provider[] | undefined) {
  return (providers ?? []).flatMap((provider) =>
    provider.models.filter((model) => model.enabled).map((model) => ({ provider, model })),
  );
}
