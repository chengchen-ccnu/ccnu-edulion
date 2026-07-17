import createClient from "openapi-fetch";

import type { components, paths } from "./generated/schema";

export const DEFAULT_MOCK_BASE_URL = "http://127.0.0.1:4010";
const runtimeEnv = (import.meta as unknown as { env?: Record<string, string | undefined> }).env;
export const DEFAULT_GATEWAY_BASE_URL = runtimeEnv?.VITE_GATEWAY_BASE_URL ?? "http://localhost:8080";

export function createGatewayClient(baseUrl = DEFAULT_GATEWAY_BASE_URL) {
  return createClient<paths>({ baseUrl, credentials: "include" });
}

export type GatewayPaths = paths;
export type GatewayClient = ReturnType<typeof createGatewayClient>;
export type CurrentUser = components["schemas"]["CurrentUser"];
export type ApiError = components["schemas"]["ApiError"];
