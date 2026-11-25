import { ImageOffIcon } from "lucide-react";
import { useAttachmentUrl } from "./attachment-url";

async function openInNewTab(dataUrl: string) {
  const blob = await (await fetch(dataUrl)).blob();
  window.open(URL.createObjectURL(blob), "_blank", "noreferrer");
}

export function AttachmentImage({
  sessionId,
  attachmentId,
}: {
  sessionId: string;
  attachmentId: string;
}) {
  const { url, isError } = useAttachmentUrl(sessionId, attachmentId);

  if (isError) {
    return (
      <span className="flex size-24 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
        <ImageOffIcon className="size-5" />
      </span>
    );
  }
  return url ? (
    <button type="button" className="cursor-zoom-in" onClick={() => void openInNewTab(url)}>
      <img src={url} alt="" className="max-h-48 max-w-64 rounded-lg border object-contain" />
    </button>
  ) : (
    <span className="size-24 animate-pulse rounded-lg bg-muted" />
  );
}
