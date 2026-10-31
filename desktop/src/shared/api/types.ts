import type { components } from "./schema";

type Schemas = components["schemas"];

export type Me = Required<Omit<Schemas["MeResponse"], "github">> & {
  github: Required<Schemas["GitHubAccount"]> | null;
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
