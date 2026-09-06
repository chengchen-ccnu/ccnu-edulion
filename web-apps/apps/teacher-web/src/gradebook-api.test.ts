import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createTeacherGradebookApi } from "./gradebook-api";

afterEach(() => vi.unstubAllGlobals());

describe("teacher gradebook API", () => {
  it("uses generated gradebook and streaming export paths with current filters", async () => {
    const calls: URL[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      const url = new URL(request.url); calls.push(url);
      expect(request.headers.get("Authorization")).toBe("Bearer teacher-token");
      if (url.pathname.endsWith("/export")) {
        return new Response("\uFEFF课程学生编号,成绩\n0001,90\n", {
          status: 200, headers: { "Content-Type": "text/csv", "Content-Disposition": "attachment; filename=gradebook.csv" }
        });
      }
      return new Response(JSON.stringify({ courseId: "course-1", aggregateStatus: "READY", assessments: [], rows: [],
        homeworkAverage: null, examAverage: null, nicknameDependencyFailed: false, page: 1, size: 20, total: 0 }), {
        status: 200, headers: { "Content-Type": "application/json" }
      });
    }));
    const api = createTeacherGradebookApi(store(), createGatewayClient("http://gateway.test"));
    await api.get({ courseId: "course-1", type: "HOMEWORK", page: 2, size: 20, sort: "STUDENT_NUMBER", direction: "asc" });
    const csv = await api.exportCsv({ courseId: "course-1", clientRequestId: "request-1", type: "HOMEWORK", keyword: "student" });
    expect(calls.map(url => `${url.pathname}${url.search}`)).toEqual([
      "/api/v1/teacher/gradebook?courseId=course-1&type=HOMEWORK&page=2&size=20&sort=STUDENT_NUMBER&direction=asc",
      "/api/v1/teacher/gradebook/export?courseId=course-1&clientRequestId=request-1&type=HOMEWORK&keyword=student"
    ]);
    expect(csv.filename).toBe("gradebook.csv");
    expect(await csv.blob.text()).toContain("0001,90");
  });
});

function store(): AuthStore {
  return { authorizedRequest: (request: (authorization: string) => Promise<unknown>) =>
    request("Bearer teacher-token") } as unknown as AuthStore;
}
