import { createGatewayClient } from "@edu/api-client";
import type { CurrentUser } from "@edu/api-client";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createAuthStore, installAuthGuard } from "./index";
import type { AuthStore } from "./index";

afterEach(() => vi.unstubAllGlobals());

describe("three application authentication stores", () => {
  it.each([
    ["STUDENT", "student_test_01"],
    ["TEACHER", "teacher_test_01"],
    ["ADMIN", "admin_test_01"]
  ] as const)("logs in and accepts the %s role", async (role, account) => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access-1", expiresIn: 900 });
      if (path.endsWith("/users/me")) return json(user(role, account));
      throw new Error(`unexpected request: ${path}`);
    }));
    const store = createAuthStore(role, createGatewayClient("http://gateway.test"));

    expect(await store.login(account, "correct-password")).toBe(true);
    expect(store.state.status).toBe("authenticated");
    expect(store.hasRequiredRole()).toBe(true);
  });

  it("refreshes a 401 only once and replays current-user once", async () => {
    let meCalls = 0;
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access-1", expiresIn: 900 });
      if (path.endsWith("/auth/refresh")) { refreshCalls += 1; return json({ tokenType: "Bearer", accessToken: "access-2", expiresIn: 900 }); }
      if (path.endsWith("/users/me")) {
        meCalls += 1;
        if (meCalls === 2) return json(apiError("IAM.UNAUTHORIZED"), 401);
        return json(user("STUDENT", "student_test_01"));
      }
      throw new Error(`unexpected request: ${path}`);
    }));
    const store = createAuthStore("STUDENT", createGatewayClient("http://gateway.test"));
    expect(await store.login("student_test_01", "correct-password")).toBe(true);

    expect(await store.ensureCurrentUser()).toBe(true);
    expect(refreshCalls).toBe(1);
    expect(meCalls).toBe(3);
  });

  it("surfaces 422 field errors, 429 retry state and wrong-role 403 state", async () => {
    let mode: "validation" | "limited" | "wrong-role" = "validation";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login") && mode === "validation") {
        return json({ ...apiError("COMMON.VALIDATION_FAILED"), fieldErrors: [{ field: "account", reason: "账号不能为空" }] }, 422);
      }
      if (path.endsWith("/auth/login") && mode === "limited") {
        return json(apiError("IAM.LOGIN_RATE_LIMITED"), 429, { "Retry-After": "30" });
      }
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access", expiresIn: 900 });
      return json(user("TEACHER", "teacher_test_01"));
    }));
    const store = createAuthStore("STUDENT", createGatewayClient("http://gateway.test"));
    expect(await store.login("", "correct-password")).toBe(false);
    expect(store.state.failure?.fieldErrors.account).toBe("账号不能为空");
    mode = "limited";
    expect(await store.login("student_test_01", "correct-password")).toBe(false);
    expect(store.state.failure).toMatchObject({ kind: "rate-limited", retryAfter: "30" });
    mode = "wrong-role";
    expect(await store.login("teacher_test_01", "correct-password")).toBe(false);
    expect(store.state.failure?.kind).toBe("forbidden");
  });
});

describe("role route guard", () => {
  it.each(["STUDENT", "TEACHER", "ADMIN"])("allows authenticated %s application navigation", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: "/login", name: "login", component: {} },
      { path: "/forbidden", name: "forbidden", component: {} },
      { path: "/protected", name: "protected", component: {}, meta: { requiresAuth: true } }
    ] });
    const store = { state: { status: "authenticated", loading: false, currentUser: null, failure: null },
      ensureCurrentUser: vi.fn().mockResolvedValue(true) } as unknown as AuthStore;
    installAuthGuard(router, store);
    await router.push("/protected");
    expect(router.currentRoute.value.name).toBe("protected");
  });

  it("redirects an anonymous user to login without a refresh loop", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: "/login", name: "login", component: {} },
      { path: "/forbidden", name: "forbidden", component: {} },
      { path: "/protected", component: {}, meta: { requiresAuth: true } }
    ] });
    const ensure = vi.fn().mockResolvedValue(false);
    const store = { state: { status: "anonymous", loading: false, currentUser: null, failure: { kind: "unauthorized" } },
      ensureCurrentUser: ensure } as unknown as AuthStore;
    installAuthGuard(router, store);
    await router.push("/protected");
    expect(router.currentRoute.value.name).toBe("login");
    expect(ensure).toHaveBeenCalledTimes(1);
  });
});

function requestUrl(input: RequestInfo | URL): URL {
  return new URL(input instanceof Request ? input.url : input.toString());
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function user(role: CurrentUser["roles"][number], account: string): CurrentUser {
  return { id: "00000000-0000-4000-8000-000000000001", displayName: account, avatarUrl: null,
    roles: [role], permissions: [`${role.toLowerCase()}:access`] };
}

function apiError(code: string) {
  return { code, message: "请求失败", traceId: "trace1234", timestamp: "2026-07-17T00:00:00Z",
    path: "/api/v1/auth/login", fieldErrors: [] };
}
