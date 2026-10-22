import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, expect, it, vi } from "vitest";
import { createAdminInvitationsApi } from "./admin-invitations-api";

afterEach(() => vi.unstubAllGlobals());
it("uses the three generated Gateway routes, stable bodies, auth and cancellation", async () => {
  const requests: Request[] = [];
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => { requests.push(request); return new Response("{}", { headers: { "Content-Type": "application/json" } }); }));
  const store = { authorizedRequest: (send: (auth: string) => Promise<unknown>) => send("Bearer synthetic") } as unknown as AuthStore;
  const api = createAdminInvitationsApi(store, createGatewayClient("http://gateway.test")); const controller = new AbortController();
  const body = { clientRequestId: "stable", email: "synthetic@example.invalid", reason: "合成邀请理由，已经确认" };
  await api.create(body, controller.signal); await api.get("known", controller.signal);
  await api.resend("known", { clientRequestId: "stable-resend", expectedRevision: 7, reason: body.reason }, controller.signal);
  expect(requests.map(request => [request.method, new URL(request.url).pathname])).toEqual([
    ["POST", "/api/v1/admin/invitations"], ["GET", "/api/v1/admin/invitations/known"], ["POST", "/api/v1/admin/invitations/known/resend"]]);
  expect(await requests[0].json()).toEqual(body); expect(await requests[2].json()).toMatchObject({ expectedRevision: 7 });
  controller.abort(); expect(requests.every(request => request.signal.aborted && request.headers.get("Authorization") === "Bearer synthetic")).toBe(true);
});
