import createClient from "openapi-fetch";

import type { paths } from "./generated/schema";

export const DEFAULT_MOCK_BASE_URL = "http://127.0.0.1:4010";

export function createGatewayClient(baseUrl = DEFAULT_MOCK_BASE_URL) {
  return createClient<paths>({ baseUrl });
}

export type GatewayPaths = paths;
