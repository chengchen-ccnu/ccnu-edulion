import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createTeacherQuestionApi } from "./question-api";

afterEach(() => vi.unstubAllGlobals());

describe("teacher question API", () => {
  it("sends URL-persisted list filters only through the generated owner endpoint", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url);
      expect(url.pathname).toBe("/api/v1/teacher/questions");
      expect(url.searchParams.get("type")).toBe("COMPOSITE");
      expect(url.searchParams.get("page")).toBe("2");
      expect(request.headers.get("Authorization")).toBe("Bearer teacher-token");
      return json({ items: [], page: 2, size: 20, total: 0 });
    }));
    const api = createTeacherQuestionApi(store(), createGatewayClient("http://gateway.test"));

    await expect(api.list({ page: 2, size: 20, type: "COMPOSITE" })).resolves.toMatchObject({ total: 0 });
  });

  it("sends expectedRevision and a structured child graph when creating a new version", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      expect(new URL(request.url).pathname).toBe("/api/v1/teacher/questions/q-1");
      const body = await request.json() as { expectedRevision: number; version: { children: unknown[] } };
      expect(body.expectedRevision).toBe(4);
      expect(body.version.children).toHaveLength(1);
      return json(questionResponse());
    }));
    const api = createTeacherQuestionApi(store(), createGatewayClient("http://gateway.test"));
    const version = questionResponse().currentVersion;

    await expect(api.update("q-1", { expectedRevision: 4, version: {
      type: version.type, difficulty: version.difficulty, stage: version.stage, grade: version.grade,
      subject: version.subject, stem: version.stem, options: [], answers: version.answers, analysis: version.analysis,
      knowledgePointIds: [], media: [], children: [{ type: "SHORT_ANSWER", difficulty: "MEDIUM", stage: "JUNIOR",
        grade: "SEVEN", subject: "MATHEMATICS", stem: "子题", options: [], answers: ["答"], analysis: "", knowledgePointIds: [], media: [] }]
    } })).resolves.toMatchObject({ revision: 4 });
  });

  it("uses the item resolution endpoint and preserves revision/idempotency fields", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      expect(new URL(request.url).pathname).toBe("/api/v1/teacher/question-imports/task-1/items/item-1/resolution");
      await expect(request.json()).resolves.toMatchObject({ clientRequestId: "request-1", expectedRevision: 2,
        action: "CONFIRM_SKIP", correctedVersion: null });
      return json({ id: "item-1", revision: 3, externalId: "fixture", outcome: "SKIPPED", questionId: null,
        sourceSystem: "LEGACY_BROWSER_V1", originalDifficulty: null, pathEdition: null, jsonEdition: null,
        pathGrade: null, jsonGrade: null, issues: [], resolution: { action: "CONFIRM_SKIP", resolvedAt: "2026-08-12T00:00:00Z" },
        createdAt: "2026-08-12T00:00:00Z", updatedAt: "2026-08-12T00:00:00Z" });
    }));
    const api = createTeacherQuestionApi(store(), createGatewayClient("http://gateway.test"));

    await expect(api.resolveLegacyItem("task-1", "item-1", { clientRequestId: "request-1", expectedRevision: 2,
      action: "CONFIRM_SKIP", correctedVersion: null })).resolves.toMatchObject({ outcome: "SKIPPED" });
  });
});

function store(): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) =>
    request("Bearer teacher-token") } as unknown as AuthStore;
}
function json(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status,
  headers: { "Content-Type": "application/json" } }); }
function questionResponse() {
  return { id: "q-1", status: "DRAFT" as const, revision: 4, createdAt: "2026-08-12T00:00:00Z", updatedAt: "2026-08-12T00:00:00Z",
    currentVersion: { id: "v-1", versionNumber: 1, type: "COMPOSITE" as const, difficulty: "MEDIUM" as const,
      stage: "JUNIOR" as const, grade: "SEVEN" as const, subject: "MATHEMATICS" as const, stem: "父题", options: [], answers: ["见子题"],
      analysis: "", knowledgePoints: [], media: [], legacySource: null, children: [], createdAt: "2026-08-12T00:00:00Z" } };
}
