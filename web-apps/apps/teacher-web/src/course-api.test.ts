import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createTeacherCourseApi } from "./course-api";

afterEach(() => vi.unstubAllGlobals());

describe("teacher course API", () => {
  it("lists only through the shared authorized request flow", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      expect(new URL(request.url).pathname).toBe("/api/v1/teacher/courses");
      expect(request.headers.get("Authorization")).toBe("Bearer shared-token");
      return json({ items: [], page: 1, size: 20, total: 0 });
    }));
    const store = { authorizedRequest: (request: (authorization: string) => Promise<unknown>) =>
      request("Bearer shared-token") } as unknown as AuthStore;
    const api = createTeacherCourseApi(store, createGatewayClient("http://gateway.test"));

    await expect(api.list({ page: 1, size: 20 })).resolves.toMatchObject({ total: 0 });
  });

  it("sends generated revision and publication fields", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      expect(new URL(request.url).pathname).toContain("/publication");
      await expect(request.json()).resolves.toEqual({ status: "UNPUBLISHED", expectedRevision: 3 });
      return json({ id: "00000000-0000-4000-8000-000000000201", status: "UNPUBLISHED",
        publishedVersion: "v1", draftVersion: null, hasUnpublishedChanges: false, revision: 3,
        updatedAt: "2026-08-01T00:00:00Z" });
    }));
    const store = { authorizedRequest: (request: (authorization: string) => Promise<unknown>) =>
      request("Bearer shared-token") } as unknown as AuthStore;
    const api = createTeacherCourseApi(store, createGatewayClient("http://gateway.test"));

    await expect(api.updatePublication("00000000-0000-4000-8000-000000000201",
      { status: "UNPUBLISHED", expectedRevision: 3 })).resolves.toMatchObject({ status: "UNPUBLISHED" });
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
