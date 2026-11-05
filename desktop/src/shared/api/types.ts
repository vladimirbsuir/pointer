import type { components } from "./schema";

type Schemas = components["schemas"];

export type Me = Required<Omit<Schemas["MeResponse"], "github">> & {
  github: Required<Schemas["GitHubAccount"]> | null;
};

export type Model = Required<
  Omit<
    Schemas["ModelResponse"],
    "contextWindow" | "maxOutputTokens" | "inputPricePerMtok" | "outputPricePerMtok"
  >
> & {
  contextWindow: number | null;
  maxOutputTokens: number | null;
  inputPricePerMtok: number | null;
  outputPricePerMtok: number | null;
};

export type Provider = Required<Omit<Schemas["ProviderResponse"], "models" | "apiKeyHint">> & {
  apiKeyHint: string | null;
  models: Model[];
};

export type Project = Required<Omit<Schemas["ProjectResponse"], "archivedAt">> & {
  archivedAt: string | null;
};

export type ProjectIndex = {
  status: "none" | "indexing" | "ready" | "failed";
  embeddingModelId: string | null;
  embeddingModelKey: string | null;
  filesTotal: number;
  filesDone: number;
  fileCount: number;
  chunkCount: number;
  error: string | null;
  indexedAt: string | null;
};

export type SessionStatus =
  "idle" | "running" | "waiting_for_approval" | "waiting_for_executor" | "error";

export type SessionMode = "ask" | "plan" | "agent";

export type SlashCommand = {
  id: string;
  name: string;
  description: string;
  prompt: string;
  updatedAt: string;
};

export type CustomTool = {
  id: string;
  name: string;
  description: string;
  scope: "user" | "project";
  projectId: string | null;
  interpreter: string;
  parameters: Record<string, unknown>;
  version: number;
  defaultPolicy: "allow" | "ask" | "deny";
  modes: SessionMode[];
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
};

export type CustomToolVersion = {
  version: number;
  description: string;
  parameters: Record<string, unknown>;
  interpreter: string;
  body: string;
  sessionId: string | null;
  createdAt: string;
  current: boolean;
};

export type SessionWorktree = {
  branch: string;
  baseBranch: string;
  state: "active" | "merged" | "discarded";
};

export type WorktreeStatus = {
  path: string;
  branch: string;
  baseBranch: string;
  commitsAhead: number;
  uncommittedFiles: string[];
  baseCheckedOut: boolean;
};

export type Session = Required<
  Omit<
    Schemas["SessionResponse"],
    "modelId" | "executorDeviceId" | "archivedAt" | "status" | "mode" | "worktree" | "estimatedCost"
  >
> & {
  estimatedCost: number | null;
  worktree: SessionWorktree | null;
  modelId: string | null;
  executorDeviceId: string | null;
  archivedAt: string | null;
  status: SessionStatus;
  mode: SessionMode;
};

export type ItemType =
  "user_message" | "assistant_message" | "reasoning" | "tool_call" | "plan" | "compaction_summary";

export type Item = {
  id: string;
  type: ItemType;
  turnId: string | null;
  status: "in_progress" | "completed" | "failed" | "cancelled";
  createdAt: string;
  completedAt: string | null;
  text?: string;
  [key: string]: unknown;
};

export type SessionEvent = {
  sessionId: string;
  seq: number;
  occurredAt: string;
  event: { kind: string; [key: string]: unknown };
};

export type AppError = {
  code: string;
  message: string;
  status?: number | null;
};

export function isAppError(value: unknown): value is AppError {
  return typeof value === "object" && value !== null && "code" in value && "message" in value;
}

export function toAppError(value: unknown): AppError {
  if (isAppError(value)) {
    return value;
  }
  return { code: "unknown", message: value instanceof Error ? value.message : String(value) };
}
