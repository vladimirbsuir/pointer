import { api } from "@/shared/api/client";

export async function uploadImage(sessionId: string, file: Blob) {
  const { data, response } = await api().POST("/api/v1/sessions/{sessionId}/attachments", {
    params: { path: { sessionId }, header: { "Content-Type": file.type } },
    body: file as unknown as string,
    bodySerializer: (body: unknown) => body as Blob,
  });
  if (!response.ok || !data) {
    throw { code: "attachment_upload_failed", message: "", status: response.status };
  }
  return (data as { id: string }).id;
}
