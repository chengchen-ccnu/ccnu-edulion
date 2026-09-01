import { createGatewayClient } from "@edu/api-client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createStudentLearningApi, isAuthenticationRequired } from "./learning-api";

afterEach(() => vi.unstubAllGlobals());

describe("student learning API", () => {
  it("recognizes the shared authentication refresh failure", () => {
    expect(isAuthenticationRequired(new Error("AUTHENTICATION_REQUIRED"))).toBe(true);
    expect(isAuthenticationRequired(new Error("OTHER"))).toBe(false);
  });

  it("uses only generated Gateway paths for enrollment, progress and owned notes", async () => {
    const calls: Array<{ method: string; path: string; body: unknown }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const path = new URL(request.url).pathname;
      const body = request.method === "GET" || request.method === "DELETE" ? null : await request.json();
      calls.push({ method: request.method, path, body });
      if (path.endsWith("/enrollments") && request.method === "GET") {
        return json({ items: [enrollment()], page: 1, size: 100, total: 1 });
      }
      if (path.endsWith("/enrollments")) return json(enrollment(), 201);
      if (path.endsWith("/progress")) return json({ courseId: "course-1", completedLessonCount: 0,
        lessonCount: 1, completionPercent: 0, lessons: [] });
      if (path.endsWith("/progress-events")) return json(progress());
      if (path.endsWith("/article-completion")) return json({ courseId: "course-1", lessonId: "lesson-1",
        completionPercent: 100, completedAt: "2026-08-26T00:00:00Z", revision: 1, replayed: false });
      if (path.endsWith("/notes") && request.method === "GET") return json([note()]);
      if (path.endsWith("/notes")) return json(note(), 201);
      if (request.method === "PUT") return json({ ...note(), content: "修改", revision: 1 });
      if (request.method === "DELETE") return new Response(null, { status: 204 });
      throw new Error(`unexpected ${request.method} ${path}`);
    }));
    const api = createStudentLearningApi(createGatewayClient("http://gateway.test"), authStore());

    await expect(api.findActiveEnrollment("course-1")).resolves.toMatchObject({ status: "ACTIVE" });
    await api.enroll("course-1");
    await api.progress("course-1");
    await api.reportProgress("course-1", "lesson-1", { clientEventId: uuid(1),
      occurredAt: "2026-08-26T00:00:00Z", positionSeconds: 20, watchedSeconds: 15, completed: false });
    await api.completeArticle("course-1", "lesson-1", { clientRequestId: uuid(2) });
    await api.notes("course-1", "lesson-1");
    await api.createNote("course-1", "lesson-1", "要点", 20);
    await api.updateNote("note-1", { expectedRevision: 0, content: "修改", positionSeconds: 20 });
    await api.deleteNote("note-1");

    expect(calls.map(item => `${item.method} ${item.path}`)).toEqual([
      "GET /api/v1/learning/enrollments", "POST /api/v1/learning/enrollments",
      "GET /api/v1/learning/courses/course-1/progress",
      "POST /api/v1/learning/courses/course-1/lessons/lesson-1/progress-events",
      "POST /api/v1/learning/courses/course-1/lessons/lesson-1/article-completion",
      "GET /api/v1/learning/courses/course-1/lessons/lesson-1/notes",
      "POST /api/v1/learning/courses/course-1/lessons/lesson-1/notes",
      "PUT /api/v1/learning/notes/note-1", "DELETE /api/v1/learning/notes/note-1"
    ]);
    expect(calls[3]?.body).toMatchObject({ clientEventId: uuid(1), positionSeconds: 20 });
    expect(calls[4]?.body).toEqual({ clientRequestId: uuid(2) });
  });

  it("reuses the exact progress body across two bounded 429 retries", async () => {
    const bodies: string[] = [];
    const waits: number[] = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      bodies.push(await request.text());
      attempts += 1;
      return attempts < 3 ? json(apiError("COMMON.RATE_LIMITED"), 429, { "Retry-After": "0" }) : json(progress());
    }));
    const api = createStudentLearningApi(createGatewayClient("http://gateway.test"), authStore(),
      async milliseconds => { waits.push(milliseconds); });
    const body = { clientEventId: uuid(9), occurredAt: "2026-08-26T00:00:00Z",
      positionSeconds: 60, watchedSeconds: 15, completed: false };

    await expect(api.reportProgress("course-1", "lesson-1", body)).resolves.toMatchObject({ positionSeconds: 20 });
    expect(bodies).toEqual([JSON.stringify(body), JSON.stringify(body), JSON.stringify(body)]);
    expect(waits).toEqual([0, 0]);
  });

  it("reuses one stable article clientRequestId for at most three attempts", async () => {
    const bodies: string[] = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      bodies.push(await request.text()); attempts += 1;
      return attempts < 3 ? json(apiError("COMMON.RATE_LIMITED"), 429, { "Retry-After": "0" })
        : json({ courseId: "course-1", lessonId: "lesson-1", completionPercent: 100,
          completedAt: "2026-08-26T00:00:00Z", revision: 1, replayed: false });
    }));
    const api = createStudentLearningApi(createGatewayClient("http://gateway.test"), authStore(), async () => undefined);
    const body = { clientRequestId: uuid(12) };

    await expect(api.completeArticle("course-1", "lesson-1", body)).resolves.toMatchObject({ completionPercent: 100 });
    expect(bodies).toEqual([JSON.stringify(body), JSON.stringify(body), JSON.stringify(body)]);
  });
});

function authStore() {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) => request("Bearer learner-token") } as never;
}

function enrollment() {
  return { id: "enrollment-1", learnerId: "learner-1", courseId: "course-1", courseVersionId: "v1",
    status: "ACTIVE", enrolledAt: "2026-08-26T00:00:00Z", canceledAt: null, updatedAt: "2026-08-26T00:00:00Z" };
}

function progress() {
  return { courseId: "course-1", lessonId: "lesson-1", positionSeconds: 20, watchedSeconds: 15,
    completed: false, lastEventAt: "2026-08-26T00:00:00Z", revision: 1, replayed: false };
}

function note() {
  return { id: "note-1", courseId: "course-1", lessonId: "lesson-1", content: "要点", positionSeconds: 20,
    revision: 0, createdAt: "2026-08-26T00:00:00Z", updatedAt: "2026-08-26T00:00:00Z" };
}

function uuid(index: number): string {
  return `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`;
}

function apiError(code: string) {
  return { code, message: "请求失败", traceId: "trace1234", timestamp: "2026-08-26T00:00:00Z",
    path: "/api/v1/learning", fieldErrors: [] };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}
