import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createTeacherReviewApi } from "./teacher-review-api";
const store = { authorizedRequest: (send: (authorization: string) => Promise<unknown>) => send("Bearer test-token") } as unknown as AuthStore;
afterEach(() => vi.unstubAllGlobals());
describe("teacher review generated Gateway adapter", () => {
  it("uses the four generated routes, exact query and original decision payload, forwards cancellation", async () => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: Request) => { requests.push(input); return new Response("{}", { headers: { "Content-Type": "application/json" } }); }));
    const api = createTeacherReviewApi(store, createGatewayClient("http://gateway.test")); const controller = new AbortController();
    const body = { clientRequestId: "stable-request", expectedRevision: 7, decision: "APPROVED" as const, materialConfirmed: true };
    await api.list({ page: 2, size: 20, status: "SUBMITTED", order: "OLDEST", exact: "student_test@example.invalid" }, controller.signal);
    await api.detail("application", controller.signal); await api.material("application", { roundId: "round", action: "PREVIEW" }, controller.signal);
    await api.decide("application", body, controller.signal);
    expect(requests.map(request => [request.method, new URL(request.url).pathname])).toEqual([
      ["GET", "/api/v1/admin/teacher-applications"], ["GET", "/api/v1/admin/teacher-applications/application"],
      ["POST", "/api/v1/admin/teacher-applications/application/material-access"], ["POST", "/api/v1/admin/teacher-applications/application/decision"]
    ]);
    expect(await requests[3].json()).toEqual(body); expect(await requests[2].json()).toEqual({ roundId: "round", action: "PREVIEW" });
    expect(requests.every(request => request.headers.get("Authorization") === "Bearer test-token")).toBe(true);
    controller.abort(); expect(requests.every(request => request.signal.aborted)).toBe(true);
  });
  it("preserves 409/fields and does not retry inside the adapter", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "IAM.CONFLICT", fieldErrors: [{ field: "request", reason: "冲突" }] }), { status: 409, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock); const api = createTeacherReviewApi(store, createGatewayClient("http://gateway.test"));
    await expect(api.detail("application", new AbortController().signal)).rejects.toMatchObject({ status: 409, fieldErrors: { request: "冲突" } }); expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
