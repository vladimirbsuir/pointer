import { useQuery } from "@tanstack/react-query";
import { api } from "@/shared/api/client";

function readAsDataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("Could not read the image"));
    reader.readAsDataURL(blob);
  });
}

export function useAttachmentUrl(sessionId: string, attachmentId: string) {
  const { data, isError } = useQuery({
    queryKey: ["attachment", sessionId, attachmentId],
    queryFn: async () => {
      const { data, response } = await api().GET(
        "/api/v1/sessions/{sessionId}/attachments/{attachmentId}",
        { params: { path: { sessionId, attachmentId } }, parseAs: "blob" },
      );
      if (!response.ok || !data) {
        throw new Error(String(response.status));
      }
      return readAsDataUrl(data as unknown as Blob);
    },
    staleTime: Infinity,
  });
  return { url: data ?? null, isError };
}
