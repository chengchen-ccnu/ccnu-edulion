import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createStudentAgentApi } from "./agent-api";

afterEach(() => vi.unstubAllGlobals());

describe("student agent action API", () => {
  it("uses the four generated OpenAPI action operations with requestId and revision", async () => {
    const calls: Array<{ method: string; url: URL; body: unknown }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      calls.push({ method: request.method, url: new URL(request.url),
        body: request.method === "GET" ? null : await request.clone().json() });
      expect(request.headers.get("Authorization")).toBe("Bearer learner-token");
      return json({ actionId: "action-1" });
    }));
    const api = createStudentAgentApi(createGatewayClient("http://gateway.test"), store("Bearer learner-token"));
    const body = { requestId: "00000000-0000-4000-8000-000000000901", expectedRevision: 4 };

    await api.pendingPracticeActionForConversation("conversation-1");
    await api.pendingPracticeAction("action-1");
    await api.confirmPendingPracticeAction("action-1", body);
    await api.cancelPendingPracticeAction("action-1", body);

    expect(calls.map(item => `${item.method} ${item.url.pathname}`)).toEqual([
      "GET /api/v1/ai/conversations/conversation-1/pending-practice-action",
      "GET /api/v1/ai/pending-practice-actions/action-1",
      "POST /api/v1/ai/pending-practice-actions/action-1:confirm",
      "POST /api/v1/ai/pending-practice-actions/action-1:cancel"
    ]);
    expect(calls[0]?.url.searchParams.get("requestId")).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls[1]?.url.searchParams.get("requestId")).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls[2]?.body).toEqual(body); expect(calls[3]?.body).toEqual(body);
  });
});

function store(token: string): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) => request(token) } as unknown as AuthStore;
}
function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}
