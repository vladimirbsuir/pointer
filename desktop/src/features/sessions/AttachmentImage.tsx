import { useEffect, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { ImageOffIcon } from "lucide-react";
import { api } from "@/shared/api/client";

function useObjectUrl(blob: Blob | undefined) {
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(
    () => () => {
      if (url) {
        URL.revokeObjectURL(url);
      }
    },
    [url],
  );
  return url;
}

export function AttachmentImage({
  sessionId,
  attachmentId,
}: {
  sessionId: string;
  attachmentId: string;
}) {
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
      return data as unknown as Blob;
    },
    staleTime: Infinity,
  });
  const url = useObjectUrl(data);

  if (isError) {
    return (
      <span className="flex size-24 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
        <ImageOffIcon className="size-5" />
      </span>
    );
  }
  return url ? (
    <a href={url} target="_blank" rel="noreferrer">
      <img src={url} alt="" className="max-h-48 max-w-64 rounded-lg border object-contain" />
    </a>
  ) : (
    <span className="size-24 animate-pulse rounded-lg bg-muted" />
  );
}
