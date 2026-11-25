import type { TFunction } from "i18next";
import type { Item } from "@/shared/api/types";

export type ScreenshotRef = {
  attachmentId: string;
  mediaType?: string;
  width: number;
  height: number;
  zoom?: boolean;
};

export type Mark = { x: number; y: number };

export type Screenshot = {
  itemId: string;
  turnId: string | null;
  attachmentId: string;
  width: number;
  height: number;
  marks: Mark[];
  label: string;
};

const MAX_TYPED_CHARS = 40;

const DIRECTIONS = {
  up: "desktop.directions.up",
  down: "desktop.directions.down",
  left: "desktop.directions.left",
  right: "desktop.directions.right",
} as const;

function direction(value: unknown) {
  return typeof value === "string" && value in DIRECTIONS
    ? DIRECTIONS[value as keyof typeof DIRECTIONS]
    : DIRECTIONS.down;
}

export function isDesktopTool(toolName: string) {
  return toolName.startsWith("desktop_");
}

export function screenshotsOf(item: Item): ScreenshotRef[] {
  return Array.isArray(item.images) ? (item.images as ScreenshotRef[]) : [];
}

export function marksOf(item: Item): Mark[] {
  return Array.isArray(item.marks) ? (item.marks as Mark[]) : [];
}

function number(value: unknown) {
  return typeof value === "number" ? value : Number(value ?? 0);
}

function text(value: unknown) {
  return typeof value === "string" ? value : "";
}

function point(args: Record<string, unknown>, x = "x", y = "y") {
  return `(${number(args[x])}, ${number(args[y])})`;
}

function shortened(value: string) {
  return value.length > MAX_TYPED_CHARS ? `${value.slice(0, MAX_TYPED_CHARS)}…` : value;
}

export function desktopSummary(t: TFunction, item: Item) {
  const toolName = text(item.toolName);
  const args = (item.arguments ?? {}) as Record<string, unknown>;
  switch (toolName) {
    case "desktop_click": {
      const button = text(args.button) || "left";
      const clicks = number(args.clicks ?? 1);
      const modifiers = Array.isArray(args.modifiers) ? (args.modifiers as string[]) : [];
      const prefix = modifiers.length > 0 ? `${modifiers.join("+")}+` : "";
      const variant =
        clicks === 2
          ? "desktop.doubleClick"
          : clicks === 3
            ? "desktop.tripleClick"
            : button === "right"
              ? "desktop.rightClick"
              : button === "middle"
                ? "desktop.middleClick"
                : "desktop.click";
      return prefix + t(variant, { point: point(args) });
    }
    case "desktop_move":
      return t("desktop.move", { point: point(args) });
    case "desktop_drag":
      return t("desktop.drag", {
        from: point(args, "fromX", "fromY"),
        to: point(args, "toX", "toY"),
      });
    case "desktop_scroll":
      return t("desktop.scroll", {
        direction: t(direction(args.direction)),
        amount: number(args.amount ?? 3),
        point: point(args),
      });
    case "desktop_type":
      return t("desktop.type", { text: shortened(text(args.text)) });
    case "desktop_key":
      return t("desktop.key", { keys: text(args.keys) });
    case "desktop_launch":
      return t("desktop.launch", { command: text(args.command) });
    case "desktop_stop":
      return t("desktop.stop", { process: text(args.processId) });
    case "desktop_wait": {
      const log = args.untilLog as { text?: string } | undefined;
      if (typeof args.untilWindow === "string") {
        return t("desktop.waitWindow", { title: args.untilWindow });
      }
      if (log?.text) {
        return t("desktop.waitLog", { text: log.text });
      }
      return t("desktop.wait", { seconds: number(args.ms ?? 0) / 1000 });
    }
    case "desktop_screenshot":
      return args.zoom ? t("desktop.zoom") : t("desktop.screenshot");
    case "desktop_start": {
      const width = args.width ? number(args.width) : null;
      const height = args.height ? number(args.height) : null;
      return width && height ? t("desktop.startSized", { width, height }) : t("desktop.start");
    }
    default:
      return t("tools.other", { name: toolName });
  }
}

export function desktopDetail(t: TFunction, item: Item) {
  const toolName = text(item.toolName);
  const result = (item.result ?? {}) as Record<string, unknown>;
  if (item.toolStatus !== "succeeded") {
    return null;
  }
  switch (toolName) {
    case "desktop_launch":
      return result.exited ? t("desktop.exited", { code: String(result.exitCode ?? "?") }) : null;
    case "desktop_wait":
      if (result.met === false) {
        return t("desktop.notMet", { seconds: Math.round(number(result.waitedMs) / 1000) });
      }
      return null;
    case "desktop_start":
      return result.width && result.height
        ? `${number(result.width)}×${number(result.height)}`
        : null;
    default:
      return null;
  }
}

export function collectScreenshots(t: TFunction, items: Item[]): Screenshot[] {
  return items
    .filter((item) => item.type === "tool_call" && !item.parentItemId)
    .flatMap((item) =>
      screenshotsOf(item).map((image) => ({
        itemId: item.id,
        turnId: item.turnId,
        attachmentId: image.attachmentId,
        width: image.width,
        height: image.height,
        marks: image.zoom ? [] : marksOf(item),
        label: desktopSummary(t, item),
      })),
    );
}
