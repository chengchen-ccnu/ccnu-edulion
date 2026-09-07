import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createStudentAssessmentApi } from "./assessment-api";

afterEach(() => vi.unstubAllGlobals());

describe("student assessment API", () => {
  it("uses all T11A/T11B learner operations only through Gateway and grants media by stable IDs", async () => {
    const calls: Array<{ method: string; url: URL; body: unknown }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      calls.push({ method: request.method, url: new URL(request.url),
        body: request.method === "GET" ? null : await request.clone().json() });
      expect(request.headers.get("Authorization")).toBe("Bearer learner-token");
      return json({});
    }));
    const api = createStudentAssessmentApi(store("Bearer learner-token"), createGatewayClient("http://gateway.test"));
    const body = { clientRequestId: "00000000-0000-4000-8000-000000000801" };
    const revisionBody = { ...body, expectedRevision: 2, answers: [] };

    await api.list({ page: 2, size: 12, courseId: "course-1" });
    await api.detail("assessment-1");
    await api.listCourseTasks("course-1", { page: 1, size: 20, currentLessonId: "lesson-1" });
    await api.courseTask("course-1", "assessment-1", { currentLessonId: "lesson-1" });
    await api.startCourseTask("course-1", "assessment-1", body);
    await api.courseTaskResult("course-1", "assessment-1");
    await api.start("assessment-1", body);
    await api.draft("attempt-1");
    await api.save("attempt-1", revisionBody);
    await api.submit("attempt-1", revisionBody);
    await api.result("attempt-1");
    await api.wrongQuestions({ page: 1, size: 20, courseId: "course-1" });
    await api.wrongQuestion("wrong-1");
    await api.updateMastery("wrong-1", { ...body, expectedRevision: 3, masteryStatus: "MASTERED" });
    await api.redo("wrong-1", { ...body, expectedRevision: 3, answer: ["A"] });
    await api.mediaGrant("assessment-1", "media-1", "INLINE");

    expect(calls.map(item => `${item.method} ${item.url.pathname}`)).toEqual([
      "GET /api/v1/learning/assessments", "GET /api/v1/learning/assessments/assessment-1",
      "GET /api/v1/learning/courses/course-1/tasks", "GET /api/v1/learning/courses/course-1/tasks/assessment-1",
      "POST /api/v1/learning/courses/course-1/tasks/assessment-1/attempts",
      "GET /api/v1/learning/courses/course-1/tasks/assessment-1/result",
      "POST /api/v1/learning/assessments/assessment-1/attempts", "GET /api/v1/learning/attempts/attempt-1/draft",
      "PUT /api/v1/learning/attempts/attempt-1/draft", "POST /api/v1/learning/attempts/attempt-1/submission",
      "GET /api/v1/learning/attempts/attempt-1/result", "GET /api/v1/learning/wrong-questions",
      "GET /api/v1/learning/wrong-questions/wrong-1", "PUT /api/v1/learning/wrong-questions/wrong-1/mastery",
      "POST /api/v1/learning/wrong-questions/wrong-1/redos",
      "POST /api/v1/learning/assessments/assessment-1/media/media-1/access-grants"
    ]);
    expect(Object.fromEntries(calls[0].url.searchParams)).toEqual({ page: "2", size: "12", courseId: "course-1" });
    expect(Object.fromEntries(calls[2].url.searchParams)).toEqual({ page: "1", size: "20", currentLessonId: "lesson-1" });
    expect(calls.at(-1)?.body).toEqual({ variant: "ORIGINAL", disposition: "INLINE" });
  });

  it("preserves status, field errors and Retry-After for page state handling", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ code: "COMMON.RATE_LIMITED", message: "稍后重试",
      traceId: "trace", timestamp: "2026-08-28T00:00:00Z", path: "/api/v1/learning/assessments",
      fieldErrors: [{ field: "page", reason: "页码错误" }] }, 429, { "Retry-After": "3" })));
    const api = createStudentAssessmentApi(store("Bearer learner-token"), createGatewayClient("http://gateway.test"));
    await expect(api.list({ page: 1, size: 12 })).rejects.toMatchObject({
      status: 429, retryAfterSeconds: 3, fieldErrors: { page: "页码错误" }
    });
  });
});

function store(token: string): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) => request(token) } as unknown as AuthStore;
}
function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}
