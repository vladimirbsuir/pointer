import type { QueryClient } from "@tanstack/react-query";
import { api, unwrap } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queries";
import type { Session } from "@/shared/api/types";
import { useWorkspace } from "./workspace-store";

export async function createSession(
  projectId: string,
  queryClient: QueryClient,
  options: { worktree?: boolean } = {},
) {
  let session = (await unwrap(
    api().POST("/api/v1/projects/{projectId}/sessions", {
      params: { path: { projectId } },
      body: {},
    }),
  )) as Session;
  if (options.worktree) {
    try {
      session = (await unwrap(
        api().POST("/api/v1/sessions/{sessionId}/worktree", {
          params: { path: { sessionId: session.id } },
        }),
      )) as Session;
    } catch (error) {
      await api().DELETE("/api/v1/sessions/{sessionId}", {
        params: { path: { sessionId: session.id } },
      });
      await queryClient.invalidateQueries({ queryKey: queryKeys.sessions(projectId) });
      throw error;
    }
  }
  await queryClient.invalidateQueries({ queryKey: queryKeys.sessions(projectId) });
  useWorkspace.getState().openSession(projectId, session.id);
  return session;
}
