import createClient, { type Client } from "openapi-fetch";
import { core } from "./core";
import type { paths } from "./schema";
import type { AppError } from "./types";

let client: Client<paths> | null = null;

export async function initApiClient() {
  const { apiUrl } = await core.appConfig();
  client = createClient<paths>({ baseUrl: apiUrl });
  client.use({
    async onRequest({ request }) {
      request.headers.set("Authorization", `Bearer ${await core.accessToken()}`);
      return request;
    },
  });
}

export function api(): Client<paths> {
  if (!client) {
    throw new Error("API client is not initialized");
  }
  return client;
}

export function problemToError(problem: unknown, status: number): AppError {
  const body = (problem ?? {}) as { code?: string; detail?: string };
  return { code: body.code ?? `http_${status}`, message: body.detail ?? "", status };
}
