import { createGatewayClient } from "@edu/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createStudentCourseApi } from "./course-api";

afterEach(() => vi.unstubAllGlobals());

describe("student course API", () => {
  it("uses the generated filter and pagination query fields", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(input instanceof Request ? input.url : input.toString());
      expect(url.pathname).toBe("/api/v1/courses");
      expect(Object.fromEntries(url.searchParams)).toEqual({
        page: "2", size: "12", keyword: "代数", level: "BEGINNER", accessType: "PUBLIC"
      });
      return json({ items: [], page: 2, size: 12, total: 0 });
    }));

    const api = createStudentCourseApi(createGatewayClient("http://gateway.test"));
    await expect(api.list({ page: 2, size: 12, keyword: "代数", level: "BEGINNER", accessType: "PUBLIC" }))
      .resolves.toMatchObject({ page: 2, total: 0 });
  });

  it("loads category options and a course detail from contract paths", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(input instanceof Request ? input.url : input.toString()).pathname;
      calls.push(path);
      if (path.endsWith("/categories")) return json([{ id: "00000000-0000-4000-8000-000000000501", name: "初中数学" }]);
      return json({ id: "00000000-0000-4000-8000-000000000201", title: "代数", summary: "摘要",
        description: "介绍", coverUrl: "https://cdn.example.test/cover.jpg",
        teacher: { id: "00000000-0000-4000-8000-000000000101", displayName: "测试教师" },
        category: { id: "00000000-0000-4000-8000-000000000501", name: "初中数学" },
        level: "BEGINNER", accessType: "PUBLIC", publishedVersion: "v1", chapters: [] });
    }));
    const api = createStudentCourseApi(createGatewayClient("http://gateway.test"));

    await expect(api.categories()).resolves.toHaveLength(1);
    await expect(api.detail("00000000-0000-4000-8000-000000000201")).resolves.toMatchObject({ title: "代数" });
    expect(calls).toEqual(["/api/v1/courses/categories", "/api/v1/courses/00000000-0000-4000-8000-000000000201"]);
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
