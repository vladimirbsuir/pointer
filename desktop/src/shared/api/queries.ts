import { QueryClient, useQuery } from "@tanstack/react-query";
import { api, unwrap } from "./client";
import { core } from "./core";
import type {
  CustomTool,
  CustomToolVersion,
  ProjectIndex,
  Project,
  Provider,
  RemoteStatus,
  RemoteWorkspace,
  Session,
  SlashCommand,
} from "./types";

export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

export const queryKeys = {
  providers: ["providers"] as const,
  settings: ["settings"] as const,
  telegram: ["telegram"] as const,
  commands: ["commands"] as const,
  customTools: ["customTools"] as const,
  customToolVersions: (toolId: string) => ["customTools", toolId, "versions"] as const,
  projects: ["projects"] as const,
  localProjects: ["localProjects"] as const,
  sessions: (projectId: string) => ["sessions", projectId] as const,
  projectIndex: (projectId: string) => ["projectIndex", projectId] as const,
  remote: ["remote"] as const,
  remoteSessions: (projectId: string) => ["remoteSessions", projectId] as const,
  remoteWorkspace: (sessionId: string) => ["remoteWorkspace", sessionId] as const,
};

const TRANSITIONAL_REMOTE_STATUSES = new Set(["queued", "starting", "preparing"]);
const REMOTE_POLL_MS = 2500;

export function useRemoteStatus() {
  return useQuery({
    queryKey: queryKeys.remote,
    queryFn: async () => (await unwrap(api().GET("/api/v1/remote"))) as RemoteStatus,
  });
}

export function useRemoteSessions(projectId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.remoteSessions(projectId),
    enabled: enabled && projectId !== "",
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/projects/{projectId}/remote-sessions", {
          params: { path: { projectId } },
        }),
      )) as RemoteWorkspace[],
  });
}

export function useRemoteWorkspace(sessionId: string, enabled: boolean, sessionBusy: boolean) {
  return useQuery({
    queryKey: queryKeys.remoteWorkspace(sessionId),
    enabled,
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/sessions/{sessionId}/remote", { params: { path: { sessionId } } }),
      )) as RemoteWorkspace,
    refetchInterval: (query) =>
      sessionBusy || TRANSITIONAL_REMOTE_STATUSES.has(query.state.data?.status ?? "")
        ? REMOTE_POLL_MS
        : false,
  });
}

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
        embeddingModelId: data.embeddingModelId ?? null,
      };
    },
  });
}

export function useProjectIndex(projectId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.projectIndex(projectId),
    enabled,
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/projects/{projectId}/index", { params: { path: { projectId } } }),
      )) as ProjectIndex,
    refetchInterval: (query) => (query.state.data?.status === "indexing" ? 1000 : false),
  });
}

export function useSlashCommands() {
  return useQuery({
    queryKey: queryKeys.commands,
    queryFn: async () => (await unwrap(api().GET("/api/v1/commands"))) as SlashCommand[],
  });
}

export function useCustomTools() {
  return useQuery({
    queryKey: queryKeys.customTools,
    queryFn: async () => (await unwrap(api().GET("/api/v1/custom-tools"))) as CustomTool[],
  });
}

export function useCustomToolVersions(toolId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.customToolVersions(toolId),
    enabled,
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/custom-tools/{toolId}/versions", { params: { path: { toolId } } }),
      )) as CustomToolVersion[],
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
