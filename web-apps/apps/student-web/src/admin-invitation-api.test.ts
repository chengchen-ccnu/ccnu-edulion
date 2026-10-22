import { createGatewayClient } from "@edu/api-client";
import { afterEach, expect, it, vi } from "vitest";
import { createAdminInvitationAcceptanceApi } from "./admin-invitation-api";
afterEach(() => vi.unstubAllGlobals());
it("accepts once through the public generated route with no Cookie, Authorization or URL secrets", async () => {
  const requests: Request[] = [];
  vi.stubGlobal("fetch", vi.fn(async (request: Request) => { requests.push(request); return new Response('{"userId":"new"}', { status: 201, headers: { "Content-Type": "application/json" } }); }));
  const body = { invitationId: "known", email: "synthetic@example.invalid", secret: "s".repeat(43), password: "synthetic-password", displayName: "admin_test" };
  const controller = new AbortController(); await createAdminInvitationAcceptanceApi(createGatewayClient("http://gateway.test"))(body, controller.signal);
  expect(requests).toHaveLength(1); expect(requests[0].credentials).toBe("omit"); expect(requests[0].headers.has("Authorization")).toBe(false);
  expect(requests[0].url).toBe("http://gateway.test/api/v1/auth/admin-invitations/accept"); expect(await requests[0].json()).toEqual(body);
  controller.abort(); expect(requests[0].signal.aborted).toBe(true);
});
it("does not retry an unknown 503 response", async () => {
  const fetchMock = vi.fn(async () => new Response("{}", { status: 503, headers: { "Content-Type": "application/json" } })); vi.stubGlobal("fetch", fetchMock);
  await expect(createAdminInvitationAcceptanceApi(createGatewayClient("http://gateway.test"))({ invitationId: "known", email: "a@example.invalid", secret: "s".repeat(43), password: "synthetic-password", displayName: "admin_test" }, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
