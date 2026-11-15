import { api, unwrap } from "@/shared/api/client";

export function undoMemory(memoryId: string) {
  return unwrap(api().POST("/api/v1/memories/{memoryId}/undo", { params: { path: { memoryId } } }));
}
