import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Terminal, type ITheme } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { core, type TerminalEvent } from "@/shared/api/core";
import { toAppError } from "@/shared/api/types";
import { errorMessage } from "@/shared/i18n/errors";
import { useThemeStore } from "@/shared/lib/theme";

const themes: Record<"light" | "dark", ITheme> = {
  light: {
    background: "#ffffff",
    foreground: "#1f2328",
    cursor: "#1f2328",
    selectionBackground: "#0969da33",
  },
  dark: {
    background: "#0a0a0a",
    foreground: "#e6e6e6",
    cursor: "#e6e6e6",
    selectionBackground: "#ffffff33",
  },
};

type TerminalViewProps = { projectId: string; visible: boolean };

export function TerminalView({ projectId, visible }: TerminalViewProps) {
  const { t } = useTranslation();
  const container = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const fit = useRef<FitAddon | null>(null);
  const resolvedTheme = useThemeStore((state) => state.resolved);

  useEffect(() => {
    const element = container.current;
    if (!element) {
      return;
    }
    const xterm = new Terminal({
      fontFamily: "Cascadia Mono, Consolas, monospace",
      fontSize: 13,
      cursorBlink: true,
      scrollback: 5000,
      theme: themes[useThemeStore.getState().resolved],
    });
    const fitAddon = new FitAddon();
    xterm.loadAddon(fitAddon);
    xterm.open(element);
    fitAddon.fit();
    terminal.current = xterm;
    fit.current = fitAddon;

    let disposed = false;
    let terminalId: string | null = null;
    let exited = false;

    const start = async () => {
      exited = false;
      try {
        const id = await core.terminalOpen(
          projectId,
          xterm.cols,
          xterm.rows,
          (event: TerminalEvent) => {
            if (event.kind === "output") {
              xterm.write(event.data);
            } else {
              exited = true;
              xterm.write(`\r\n\x1b[2m${t("terminal.exited")}\x1b[0m\r\n`);
            }
          },
        );
        if (disposed) {
          void core.terminalClose(id);
          return;
        }
        terminalId = id;
      } catch (error) {
        xterm.write(`\x1b[31m${errorMessage(t, toAppError(error))}\x1b[0m\r\n`);
      }
    };

    const input = xterm.onData((data) => {
      if (exited) {
        if (data === "\r") {
          if (terminalId) {
            void core.terminalClose(terminalId);
          }
          terminalId = null;
          xterm.reset();
          void start();
        }
        return;
      }
      if (terminalId) {
        void core.terminalWrite(terminalId, data);
      }
    });
    const resize = xterm.onResize(({ cols, rows }) => {
      if (terminalId) {
        void core.terminalResize(terminalId, cols, rows);
      }
    });
    const observer = new ResizeObserver(() => {
      if (element.offsetParent !== null) {
        fitAddon.fit();
      }
    });
    observer.observe(element);
    void start();

    return () => {
      disposed = true;
      observer.disconnect();
      input.dispose();
      resize.dispose();
      if (terminalId) {
        void core.terminalClose(terminalId);
      }
      xterm.dispose();
      terminal.current = null;
      fit.current = null;
    };
  }, [projectId, t]);

  useEffect(() => {
    if (terminal.current) {
      terminal.current.options.theme = themes[resolvedTheme];
    }
  }, [resolvedTheme]);

  useEffect(() => {
    if (visible) {
      requestAnimationFrame(() => {
        fit.current?.fit();
        terminal.current?.focus();
      });
    }
  }, [visible]);

  return (
    <div
      ref={container}
      className="h-full w-full px-2 py-1"
      style={{ display: visible ? "block" : "none" }}
    />
  );
}
