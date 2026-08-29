import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createTeacherAssessmentApi } from "./assessment-api";

afterEach(() => vi.unstubAllGlobals());

describe("teacher assessment API", () => {
  it("binds CRUD, paper, lifecycle, result and grading actions to the generated Gateway paths", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      calls.push(`${request.method} ${new URL(request.url).pathname}`);
      expect(request.headers.get("Authorization")).toBe("Bearer teacher-token");
      return request.method === "DELETE" ? new Response(null, { status: 204 }) : json({});
    }));
    const api = createTeacherAssessmentApi(store(), createGatewayClient("http://gateway.test"));
    const createBody = { clientRequestId: "request-1", courseId: "course-1", lessonId: null, title: "演示",
      description: "", type: "PRACTICE", gradePublicationPolicy: "MANUAL",
      answerPublicationPolicy: "MANUAL", opensAt: null, closesAt: null, timeLimitSeconds: null } as never;

    await api.list({ page: 1, size: 20, courseId: "course-1", status: "DRAFT" });
    await api.create(createBody); await api.detail("assessment-1");
    await api.update("assessment-1", { expectedRevision: 1 } as never);
    await api.remove("assessment-1");
    await api.replaceQuestions("assessment-1", { expectedRevision: 1, questions: [] });
    await api.publication("assessment-1", { expectedRevision: 2, status: "PUBLISHED" });
    await api.close("assessment-1", { clientRequestId: "request-2", expectedRevision: 3 });
    await api.publishGrades("assessment-1", { clientRequestId: "request-3", expectedRevision: 4 });
    await api.publishAnswers("assessment-1", { clientRequestId: "request-4", expectedRevision: 5 });
    await api.results("assessment-1", { page: 2, size: 20 });
    await api.grade("attempt-1", { clientRequestId: "request-5", expectedRevision: 6, grades: [] });

    expect(calls).toEqual([
      "GET /api/v1/teacher/assessments", "POST /api/v1/teacher/assessments",
      "GET /api/v1/teacher/assessments/assessment-1", "PUT /api/v1/teacher/assessments/assessment-1",
      "DELETE /api/v1/teacher/assessments/assessment-1", "PUT /api/v1/teacher/assessments/assessment-1/questions",
      "PUT /api/v1/teacher/assessments/assessment-1/publication", "POST /api/v1/teacher/assessments/assessment-1/closure",
      "POST /api/v1/teacher/assessments/assessment-1/grade-publication",
      "POST /api/v1/teacher/assessments/assessment-1/answer-publication",
      "GET /api/v1/teacher/assessments/assessment-1/results", "POST /api/v1/teacher/attempts/attempt-1/grades"
    ]);
  });
});

function store(): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) =>
    request("Bearer teacher-token") } as unknown as AuthStore;
}
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
