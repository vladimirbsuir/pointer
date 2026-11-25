import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronLeftIcon, ChevronRightIcon, ImageOffIcon } from "lucide-react";
import { Button } from "@/shared/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/shared/ui/dialog";
import { useAttachmentUrl } from "./attachment-url";
import type { Mark, Screenshot } from "./desktop-calls";

type Gallery = { open: (attachmentId: string) => void };

const GalleryContext = createContext<Gallery | null>(null);

export function ScreenshotGallery({
  sessionId,
  screenshots,
  children,
}: {
  sessionId: string;
  screenshots: Screenshot[];
  children: React.ReactNode;
}) {
  const [openId, setOpenId] = useState<string | null>(null);
  const gallery = useMemo(() => ({ open: setOpenId }), []);
  const index = screenshots.findIndex((shot) => shot.attachmentId === openId);
  const current = index >= 0 ? screenshots[index] : null;
  const sameTurn = useMemo(
    () => (current ? screenshots.filter((shot) => shot.turnId === current.turnId) : []),
    [screenshots, current],
  );
  const position = current ? sameTurn.indexOf(current) : -1;

  const step = useCallback(
    (delta: number) => {
      const next = sameTurn[position + delta];
      if (next) {
        setOpenId(next.attachmentId);
      }
    },
    [sameTurn, position],
  );

  return (
    <GalleryContext.Provider value={gallery}>
      {children}
      <Dialog open={current !== null} onOpenChange={(open) => !open && setOpenId(null)}>
        {current && (
          <DialogContent
            className="w-[min(1320px,calc(100vw-2rem))] gap-2 sm:max-w-[min(1320px,calc(100vw-2rem))]"
            onKeyDown={(event) => {
              if (event.key === "ArrowLeft") {
                step(-1);
              } else if (event.key === "ArrowRight") {
                step(1);
              }
            }}
          >
            <DialogTitle className="truncate pr-8 font-mono text-xs font-normal text-muted-foreground">
              {current.label}
              {sameTurn.length > 1 && ` · ${position + 1}/${sameTurn.length}`}
            </DialogTitle>
            <div className="relative flex items-center justify-center">
              <MarkedImage sessionId={sessionId} shot={current} maxHeight="calc(100vh - 8rem)" />
              {position > 0 && (
                <Button
                  variant="secondary"
                  size="icon-sm"
                  className="absolute left-2"
                  onClick={() => step(-1)}
                >
                  <ChevronLeftIcon />
                </Button>
              )}
              {position < sameTurn.length - 1 && (
                <Button
                  variant="secondary"
                  size="icon-sm"
                  className="absolute right-2"
                  onClick={() => step(1)}
                >
                  <ChevronRightIcon />
                </Button>
              )}
            </div>
          </DialogContent>
        )}
      </Dialog>
    </GalleryContext.Provider>
  );
}

export function DesktopThumbnail({
  sessionId,
  shot,
}: {
  sessionId: string;
  shot: Pick<Screenshot, "attachmentId" | "width" | "height" | "marks">;
}) {
  const { t } = useTranslation();
  const gallery = useContext(GalleryContext);
  return (
    <button
      type="button"
      className="block w-full cursor-zoom-in"
      title={t("desktop.openScreenshot")}
      onClick={() => gallery?.open(shot.attachmentId)}
    >
      <MarkedImage sessionId={sessionId} shot={shot} maxHeight="12rem" />
    </button>
  );
}

function MarkedImage({
  sessionId,
  shot,
  maxHeight,
}: {
  sessionId: string;
  shot: Pick<Screenshot, "attachmentId" | "width" | "height" | "marks">;
  maxHeight: string;
}) {
  const { url, isError } = useAttachmentUrl(sessionId, shot.attachmentId);
  const box = {
    aspectRatio: `${shot.width} / ${shot.height}`,
    width: `min(100%, calc(${maxHeight} * ${shot.width} / ${shot.height}))`,
  };
  if (isError) {
    return (
      <span
        className="flex items-center justify-center rounded-md border bg-muted text-muted-foreground"
        style={box}
      >
        <ImageOffIcon className="size-5" />
      </span>
    );
  }
  return (
    <span className="relative block overflow-hidden rounded-md border bg-muted" style={box}>
      {url ? (
        <img src={url} alt="" className="absolute inset-0 size-full" />
      ) : (
        <span className="absolute inset-0 animate-pulse bg-muted" />
      )}
      {url && shot.marks.length > 0 && (
        <svg
          className="pointer-events-none absolute inset-0 size-full"
          viewBox={`0 0 ${shot.width} ${shot.height}`}
          preserveAspectRatio="none"
        >
          <Marks marks={shot.marks} scale={Math.max(shot.width, shot.height) / 100} />
        </svg>
      )}
    </span>
  );
}

function Marks({ marks, scale }: { marks: Mark[]; scale: number }) {
  const [first, second] = marks;
  if (!first) {
    return null;
  }
  return (
    <g fill="none" stroke="rgb(239 68 68)" strokeWidth={scale * 0.35}>
      {second && (
        <line x1={first.x} y1={first.y} x2={second.x} y2={second.y} strokeDasharray={scale} />
      )}
      {marks.map((mark, index) => (
        <g key={index}>
          <circle cx={mark.x} cy={mark.y} r={scale * 1.6} />
          <circle cx={mark.x} cy={mark.y} r={scale * 0.4} fill="rgb(239 68 68)" />
        </g>
      ))}
    </g>
  );
}
