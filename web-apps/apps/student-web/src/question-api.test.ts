import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createStudentQuestionApi } from "./question-api";

afterEach(() => vi.unstubAllGlobals());

describe("student public question API", () => {
  it("uses learner SYSTEM endpoints and the domain media grant through Gateway", async () => {
    const calls: Array<{ method: string; url: URL; body: unknown }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      calls.push({ method: request.method, url: new URL(request.url),
        body: request.method === "GET" ? null : await request.clone().json() });
      expect(request.headers.get("Authorization")).toBe("Bearer learner-token");
      return json({});
    }));
    const api = createStudentQuestionApi(store("Bearer learner-token"), createGatewayClient("http://gateway.test"));

    await api.list({ page: 2, size: 12, stage: "JUNIOR", grade: "EIGHT", type: "COMPOSITE", difficulty: "HARD" });
    await api.detail("question-1");
    await api.mediaGrant("question-1", "media-1", "INLINE");

    expect(calls.map(item => `${item.method} ${item.url.pathname}`)).toEqual([
      "GET /api/v1/learning/system-questions",
      "GET /api/v1/learning/system-questions/question-1",
      "POST /api/v1/learning/system-questions/question-1/media/media-1/access-grants"
    ]);
    expect(Object.fromEntries(calls[0].url.searchParams)).toEqual({
      page: "2", size: "12", stage: "JUNIOR", grade: "EIGHT", type: "COMPOSITE", difficulty: "HARD"
    });
    expect(calls[2].body).toEqual({ variant: "ORIGINAL", disposition: "INLINE" });
  });
});

function store(token: string): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) => request(token) } as unknown as AuthStore;
}
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
