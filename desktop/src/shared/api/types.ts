import type { components } from "./schema";

type Schemas = components["schemas"];

export type Me = Required<Omit<Schemas["MeResponse"], "github">> & {
  github: Required<Schemas["GitHubAccount"]> | null;
};

export type Model = Required<
  Omit<
    Schemas["ModelResponse"],
    | "contextWindow"
    | "maxOutputTokens"
    | "inputPricePerMtok"
    | "cachedInputPricePerMtok"
    | "outputPricePerMtok"
    | "embeddingQueryPrefix"
    | "embeddingDocumentPrefix"
  >
> & {
  contextWindow: number | null;
  maxOutputTokens: number | null;
  inputPricePerMtok: number | null;
  cachedInputPricePerMtok: number | null;
  outputPricePerMtok: number | null;
  embeddingQueryPrefix: string | null;
  embeddingDocumentPrefix: string | null;
};

export type Provider = Required<Omit<Schemas["ProviderResponse"], "models" | "apiKeyHint">> & {
  apiKeyHint: string | null;
  models: Model[];
};

export type Project = Required<Omit<Schemas["ProjectResponse"], "archivedAt" | "repository">> & {
  archivedAt: string | null;
  repository: string | null;
};

export type RemoteStatus = Required<Schemas["RemoteStatus"]>;

export type RemoteWorkspaceStatus =
  "queued" | "starting" | "preparing" | "ready" | "stopped" | "failed";

export type RemoteWorkspace = {
  sessionId: string;
  repository: string;
  branch: string;
  baseBranch: string | null;
  status: RemoteWorkspaceStatus;
  error: string | null;
  pushedCommit: string | null;
  branchUrl: string | null;
  pullRequestUrl: string | null;
  lastActiveAt: string;
};

export type AutomationWebhook = "created" | "missing" | "no_public_url";

export type Automation = {
  configured: boolean;
  enabled: boolean;
  triggerLabel: string;
  baseBranch: string | null;
  modelId: string | null;
  checkCommand: string | null;
  maxAttempts: number;
  budgetUsd: number | null;
  webhook: AutomationWebhook;
  webhookError: string | null;
  updatedAt: string | null;
};

export type AutomationRunStatus =
  "queued" | "running" | "checking" | "succeeded" | "failed" | "cancelled";

export type AutomationFailureReason =
  | "needs_input"
  | "check_failed"
  | "budget_exceeded"
  | "agent_failed"
  | "start_failed"
  | "workspace_failed"
  | "pull_request_failed"
  | "error";

export type AutomationRun = {
  id: string;
  sessionId: string | null;
  issueNumber: number;
  issueTitle: string;
  issueUrl: string | null;
  trigger: "label" | "desktop" | "telegram";
  status: AutomationRunStatus;
  failureReason: AutomationFailureReason | null;
  error: string | null;
  attempts: number;
  maxAttempts: number;
  lastCheckOutput: string | null;
  pullRequestUrl: string | null;
  costUsd: number;
  startedAt: string;
  finishedAt: string | null;
};

export type GitHubRepository = Required<Schemas["RepositoryResponse"]>;

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
  prefixesChanged: boolean;
  searchStrategy: SearchStrategy;
};

export type SearchStrategy = "hybrid" | "vector";

export type SessionStatus =
  "idle" | "running" | "waiting_for_approval" | "waiting_for_executor" | "error";

export type SessionMode = "ask" | "plan" | "agent";

export type MemoryKind = "user" | "feedback" | "project" | "reference";

export type MemoryStatus = "active" | "suggested" | "dismissed" | "deleted";

export type Memory = {
  id: string;
  projectId: string | null;
  scope: "user" | "project";
  kind: MemoryKind;
  name: string;
  description: string;
  content: string;
  status: MemoryStatus;
  pinned: boolean;
  source: "agent" | "user" | "extracted" | "telegram";
  sourceSessionId: string | null;
  readCount: number;
  lastReadAt: string | null;
  createdAt: string;
  updatedAt: string;
};

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
    | "modelId"
    | "executorDeviceId"
    | "archivedAt"
    | "status"
    | "mode"
    | "worktree"
    | "estimatedCost"
    | "budgetUsd"
    | "routing"
  >
> & {
  estimatedCost: number | null;
  budgetUsd: number | null;
  routing: "off" | "smart";
  worktree: SessionWorktree | null;
  modelId: string | null;
  executorDeviceId: string | null;
  archivedAt: string | null;
  status: SessionStatus;
  mode: SessionMode;
};

export type ItemType =
  | "user_message"
  | "assistant_message"
  | "reasoning"
  | "tool_call"
  | "plan"
  | "compaction_summary"
  | "model_switched";

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

export type SessionUsageEntry = {
  modelId: string | null;
  modelLabel: string;
  role: string;
  purpose: "step" | "title" | "compaction" | "memory" | "explore";
  calls: number;
  promptTokens: number;
  completionTokens: number;
  cachedPromptTokens: number | null;
  cacheReportedPromptTokens: number | null;
  prefixTokens: number | null;
  prefixReuseTokens: number | null;
  costUsd: number | null;
};

export type SessionUsage = {
  estimatedCost: number | null;
  budgetUsd: number | null;
  entries: SessionUsageEntry[];
};
