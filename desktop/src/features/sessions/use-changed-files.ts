import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "@/shared/api/client";
import type { ChangedFile } from "./ChangesPanel";

export function useChangedFiles(sessionId: string, version: number) {
  return useQuery({
    queryKey: ["changes", sessionId, version],
    queryFn: async () =>
      (await unwrap(
        api().GET("/api/v1/sessions/{sessionId}/changes", { params: { path: { sessionId } } }),
      )) as ChangedFile[],
    placeholderData: (previous) => previous,
  });
}
