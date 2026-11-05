import { api, unwrap } from "@/shared/api/client";
import { core } from "@/shared/api/core";

export type FileSource = { sessionId: string; projectId: string; remote: boolean };

export async function suggestFiles(source: FileSource, query: string) {
  if (!source.remote) {
    return core.projectFiles(source.projectId, query);
  }
  const { paths } = await unwrap(
    api().GET("/api/v1/sessions/{sessionId}/remote/files", {
      params: { path: { sessionId: source.sessionId }, query: { query } },
    }),
  );
  return paths ?? [];
}

export async function readFile(source: FileSource, path: string) {
  if (!source.remote) {
    return core.readProjectFile(source.projectId, path);
  }
  const { content } = await unwrap(
    api().GET("/api/v1/sessions/{sessionId}/remote/file", {
      params: { path: { sessionId: source.sessionId }, query: { path } },
    }),
  );
  return content ?? "";
}
