import { create } from "zustand";

type WorkspaceState = {
  activeProjectId: string | null;
  activeSessionId: string | null;
  terminalOpen: boolean;
  setTerminalOpen: (open: boolean) => void;
  openSession: (projectId: string, sessionId: string) => void;
  selectProject: (projectId: string | null) => void;
  closeSession: () => void;
};

export const useWorkspace = create<WorkspaceState>((set) => ({
  activeProjectId: null,
  activeSessionId: null,
  terminalOpen: false,
  setTerminalOpen: (open) => set({ terminalOpen: open }),
  openSession: (projectId, sessionId) =>
    set({ activeProjectId: projectId, activeSessionId: sessionId }),
  selectProject: (projectId) => set({ activeProjectId: projectId, activeSessionId: null }),
  closeSession: () => set({ activeSessionId: null }),
}));
