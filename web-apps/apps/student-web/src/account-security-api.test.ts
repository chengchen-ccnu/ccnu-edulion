import { createGatewayClient } from "@edu/api-client";
import type { AuthStore } from "@edu/auth";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createAccountSecurityApi } from "./account-security-api";

afterEach(() => vi.unstubAllGlobals());

describe("account security API adapter", () => {
  it("sends registration challenges through the generated Gateway client", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(input);
      expect(new URL(request.url).pathname).toBe("/api/v1/auth/registration-challenges");
      expect(await request.json()).toEqual({
        identityType: "EMAIL",
        target: "student_t12e@example.invalid",
        captcha: { captchaId: "00000000-0000-4000-8000-000000000111", answer: "8" }
      });
      return json({ challengeId: "00000000-0000-4000-8000-000000000112", expiresInSeconds: 300 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const api = createAccountSecurityApi(createGatewayClient("http://gateway.test"), anonymousStore());

    const challenge = await api.registrationChallenge("EMAIL", "student_t12e@example.invalid", {
      captchaId: "00000000-0000-4000-8000-000000000111", answer: "8"
    });

    expect(challenge.challengeId).toBe("00000000-0000-4000-8000-000000000112");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("loads sessions and identities only through the authenticated request boundary", async () => {
    const paths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(input);
      paths.push(new URL(request.url).pathname);
      expect(request.headers.get("Authorization")).toBe("Bearer access-t12e");
      if (request.url.endsWith("/users/me/sessions")) return json([]);
      if (request.url.endsWith("/users/me/identities")) return json({ identities: [] });
      throw new Error(`unexpected request: ${request.url}`);
    }));
    const authorizedRequest = vi.fn(async request => request("Bearer access-t12e"));
    const api = createAccountSecurityApi(createGatewayClient("http://gateway.test"), {
      ...anonymousStore(), authorizedRequest
    } as unknown as AuthStore);

    await expect(api.sessions()).resolves.toEqual([]);
    await expect(api.identities()).resolves.toEqual({ identities: [] });

    expect(paths).toEqual(["/api/v1/users/me/sessions", "/api/v1/users/me/identities"]);
    expect(authorizedRequest).toHaveBeenCalledTimes(2);
  });
});

function anonymousStore(): AuthStore {
  return {
    state: { status: "anonymous", loading: false, currentUser: null, failure: null },
    authorizedRequest: vi.fn()
  } as unknown as AuthStore;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
