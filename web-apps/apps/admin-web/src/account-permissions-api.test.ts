import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAccountPermissionsApi } from "./account-permissions-api";
const store = { authorizedRequest: (send: (authorization: string) => Promise<unknown>) => send("Bearer synthetic") } as unknown as AuthStore;
afterEach(() => vi.unstubAllGlobals());
describe("account permission generated Gateway adapter", () => {
  it("forwards all seven generated routes, original payloads, query and cancellation", async () => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", vi.fn(async (request: Request) => { requests.push(request); return new Response("{}", { headers: { "Content-Type": "application/json" } }); }));
    const api = createAccountPermissionsApi(store, createGatewayClient("http://gateway.test")); const controller = new AbortController();
    const body = { clientRequestId: "stable", previewId: "preview", expectedRevision: 7, action: "BAN" as const, reason: "合成测试权限变更理由", confirmed: true as const };
    await api.list({ page: 2, size: 20, status: "ACTIVE", keyword: "synthetic" }, controller.signal);
    await api.detail("user", controller.signal); await api.preview("user", "BAN", controller.signal);
    await api.change("user", body, controller.signal); await api.latest("user", controller.signal);
    await api.command("returned-id", controller.signal); await api.retry("returned-id", { clientRequestId: "retry-stable", expectedAttempts: 3 }, controller.signal);
    expect(requests.map(item => [item.method, new URL(item.url).pathname])).toEqual([
      ["GET", "/api/v1/admin/users"], ["GET", "/api/v1/admin/users/user"], ["POST", "/api/v1/admin/users/user/permission-previews"],
      ["POST", "/api/v1/admin/users/user/permission-commands"], ["GET", "/api/v1/admin/users/user/permission-commands/latest"],
      ["GET", "/api/v1/admin/permission-commands/returned-id"], ["POST", "/api/v1/admin/permission-commands/returned-id/retry"]
    ]);
    expect(new URL(requests[0].url).searchParams.get("keyword")).toBe("synthetic"); expect(await requests[3].json()).toEqual(body);
    expect(await requests[6].json()).toEqual({ clientRequestId: "retry-stable", expectedAttempts: 3 });
    controller.abort(); expect(requests.every(request => request.signal.aborted && request.headers.get("Authorization") === "Bearer synthetic")).toBe(true);
  });
  it("handles 204 without parsing empty JSON and preserves 404/409/fields without retries", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValue(new Response(JSON.stringify({ code: "IAM.CONFLICT", fieldErrors: [{ field: "reason", reason: "不合法" }] }), { status: 409, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock); const api = createAccountPermissionsApi(store, createGatewayClient("http://gateway.test"));
    expect(await api.latest("user", new AbortController().signal)).toBeNull();
    await expect(api.command("returned-id", new AbortController().signal)).rejects.toMatchObject({ status: 409, fieldErrors: { reason: "不合法" } });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
