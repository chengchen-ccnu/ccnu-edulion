import { createGatewayClient } from "@edu/api-client";
import type { CurrentUser } from "@edu/api-client";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  availableWorkspaces,
  buildCourseCenterUrl,
  buildUnifiedLoginUrl,
  createAuthStore,
  installAuthGuard,
  resolveInternalRedirect,
  selectPostLoginDestination
} from "./index";
import type { AuthStore, PlatformRole, PlatformUrls } from "./index";

const urls: PlatformUrls = {
  student: "http://student.test",
  teacher: "http://teacher.test",
  admin: "http://admin.test"
};

afterEach(() => vi.unstubAllGlobals());

describe("shared authentication store", () => {
  it("logs in once and loads roles and permissions from current-user", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access-1", expiresIn: 900 });
      if (path.endsWith("/users/me")) return json(user(["TEACHER"], ["teaching:access"]));
      throw new Error(`unexpected request: ${path}`);
    }));
    const store = createAuthStore(createGatewayClient("http://gateway.test"));

    expect(await store.login("teacher_test_01", "correct-password")).toBe(true);
    expect(store.state.status).toBe("authenticated");
    expect(store.canAccess("TEACHER")).toBe(true);
    expect(store.canAccess("STUDENT")).toBe(false);
  });

  it("restores a cross-application session through the refresh cookie", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      calls.push(path);
      if (path.endsWith("/auth/refresh")) return json({ tokenType: "Bearer", accessToken: "access-restored", expiresIn: 900 });
      if (path.endsWith("/users/me")) return json(user(["ADMIN"], ["administration:access"]));
      throw new Error(`unexpected request: ${path}`);
    }));
    const targetApplicationStore = createAuthStore(createGatewayClient("http://gateway.test"));

    expect(await targetApplicationStore.ensureCurrentUser()).toBe(true);
    expect(calls).toEqual(["/api/v1/auth/refresh", "/api/v1/users/me"]);
    expect(targetApplicationStore.canAccess("ADMIN")).toBe(true);
  });

  it("refreshes a rejected access token only once", async () => {
    let meCalls = 0;
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access-1", expiresIn: 900 });
      if (path.endsWith("/auth/refresh")) { refreshCalls += 1; return json({ tokenType: "Bearer", accessToken: "access-2", expiresIn: 900 }); }
      if (path.endsWith("/users/me")) {
        meCalls += 1;
        if (meCalls === 2) return json(apiError("IAM.UNAUTHORIZED"), 401);
        return json(user(["STUDENT"], ["learning:access"]));
      }
      throw new Error(`unexpected request: ${path}`);
    }));
    const store = createAuthStore(createGatewayClient("http://gateway.test"));
    expect(await store.login("student_test_01", "correct-password")).toBe(true);

    expect(await store.ensureCurrentUser()).toBe(true);
    expect(refreshCalls).toBe(1);
    expect(meCalls).toBe(3);
  });

  it("retries an authorized domain request once after refreshing its access token", async () => {
    let courseCalls = 0;
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (path.endsWith("/auth/login")) return json({ tokenType: "Bearer", accessToken: "access-1", expiresIn: 900 });
      if (path.endsWith("/auth/refresh")) { refreshCalls += 1; return json({ tokenType: "Bearer", accessToken: "access-2", expiresIn: 900 }); }
      if (path.endsWith("/users/me")) return json(user(["TEACHER"], ["teaching:access"]));
      if (path.endsWith("/teacher/courses")) {
        courseCalls += 1;
        return courseCalls === 1 ? json(apiError("IAM.UNAUTHORIZED"), 401) : json({ items: [], page: 1, size: 20, total: 0 });
      }
      throw new Error(`unexpected request: ${path}`);
    }));
    const client = createGatewayClient("http://gateway.test");
    const store = createAuthStore(client);
    expect(await store.login("teacher_test_01", "correct-password")).toBe(true);

    const result = await store.authorizedRequest(authorization => client.GET("/api/v1/teacher/courses", {
      headers: { Authorization: authorization }, params: { query: { page: 1, size: 20 } }
    }));

    expect(result.response.status).toBe(200);
    expect(refreshCalls).toBe(1);
    expect(courseCalls).toBe(2);
  });

  it("surfaces validation and rate-limit failures", async () => {
    let limited = false;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = requestUrl(input).pathname;
      if (!path.endsWith("/auth/login")) throw new Error(`unexpected request: ${path}`);
      if (limited) return json(apiError("IAM.LOGIN_RATE_LIMITED"), 429, { "Retry-After": "30" });
      return json({ ...apiError("COMMON.VALIDATION_FAILED"),
        fieldErrors: [{ field: "account", reason: "账号不能为空" }] }, 422);
    }));
    const store = createAuthStore(createGatewayClient("http://gateway.test"));

    expect(await store.login("", "correct-password")).toBe(false);
    expect(store.state.failure?.fieldErrors.account).toBe("账号不能为空");
    limited = true;
    expect(await store.login("student_test_01", "correct-password")).toBe(false);
    expect(store.state.failure).toMatchObject({ kind: "rate-limited", retryAfter: "30" });
  });
});

describe("post-login routing", () => {
  it.each([
    ["STUDENT", "learning:access", "http://student.test/"],
    ["TEACHER", "teaching:access", "http://teacher.test/teacher/dashboard"],
    ["ADMIN", "administration:access", "http://admin.test/admin/dashboard"]
  ] as const)("sends a single %s role directly to its workspace", (role, permission, href) => {
    const destination = selectPostLoginDestination(user([role], [permission]), undefined, urls);
    expect(destination).toMatchObject({ kind: "target", target: { href } });
  });

  it("requires a multi-role user to choose a workspace", () => {
    const destination = selectPostLoginDestination(user(["STUDENT", "TEACHER"],
      ["learning:access", "teaching:access"]), undefined, urls);
    expect(destination.kind).toBe("choose");
    if (destination.kind === "choose") {
      expect(destination.workspaces.map(item => item.label)).toEqual(["学生学习中心", "教师工作台"]);
    }
  });

  it("prioritizes an authorized internal redirect", () => {
    const destination = selectPostLoginDestination(user(["STUDENT", "ADMIN"],
      ["learning:access", "administration:access"]), "/admin/reviews?status=pending", urls);
    expect(destination).toMatchObject({ kind: "target",
      target: { href: "http://admin.test/admin/reviews?status=pending" } });
  });

  it.each([
    ["STUDENT", "learning:access"],
    ["TEACHER", "teaching:access"],
    ["ADMIN", "administration:access"]
  ] as const)("allows %s to redirect to the public course center", (role, permission) => {
    const destination = selectPostLoginDestination(user([role], [permission]), "/courses", urls);

    expect(destination).toMatchObject({ kind: "target", target: {
      access: "public", href: "http://student.test/courses"
    } });
  });

  it("shows 403 for a valid workspace redirect without its role", () => {
    const currentUser = user(["STUDENT"], ["learning:access"]);
    const destination = selectPostLoginDestination(currentUser, "/teacher/dashboard", urls);
    expect(destination.kind).toBe("forbidden");
    expect(availableWorkspaces(currentUser, urls).map(item => item.href)).toEqual(["http://student.test/"]);
  });

  it("rejects external and non-whitelisted redirects", () => {
    expect(resolveInternalRedirect("https://evil.example/steal", urls)).toBeNull();
    expect(resolveInternalRedirect("//evil.example/steal", urls)).toBeNull();
    expect(resolveInternalRedirect("/\\evil.example/steal", urls)).toBeNull();
    expect(resolveInternalRedirect("/login?redirect=https://evil.example", urls)).toBeNull();
    expect(resolveInternalRedirect("/unknown", urls)).toBeNull();
    expect(buildUnifiedLoginUrl("//evil.example/steal", urls)).toBe("http://student.test/login");
    const destination = selectPostLoginDestination(user(["STUDENT"], ["learning:access"]),
      "//evil.example/steal", urls);
    expect(destination).toMatchObject({ kind: "target", target: { href: "http://student.test/" } });
  });

  it("builds the public course URL from shared platform configuration", () => {
    expect(buildCourseCenterUrl(urls)).toBe("http://student.test/courses");
    expect(resolveInternalRedirect("/courses/00000000-0000-4000-8000-000000000201?from=teacher", urls))
      .toMatchObject({ access: "public",
        href: "http://student.test/courses/00000000-0000-4000-8000-000000000201?from=teacher" });
  });

  it("keeps a learning deep link and authorizes it by learning capability instead of role name", () => {
    const target = "/learn/00000000-0000-4000-8000-000000000201/00000000-0000-4000-8000-000000000301";
    const destination = selectPostLoginDestination(user(["TEACHER"], ["learning:access"]), target, urls);

    expect(resolveInternalRedirect(target, urls)).toMatchObject({ access: "workspace", requestedPath: target });
    expect(destination).toMatchObject({ kind: "target", target: { href: `http://student.test${target}` } });
  });
});

describe("application route guard", () => {
  it("opens public course list and detail anonymously while restoring at most once in the background", async () => {
    let finishRestore: ((value: boolean) => void) | undefined;
    const restore = vi.fn().mockImplementation(() => new Promise<boolean>(resolve => { finishRestore = resolve; }));
    const store = publicGuardStore(null, "unknown", restore);
    const router = publicCourseRouter();
    installAuthGuard(router, store);

    await router.push("/courses");
    expect(router.currentRoute.value.name).toBe("courses");
    expect(restore).toHaveBeenCalledTimes(1);
    await router.push("/courses/00000000-0000-4000-8000-000000000201");
    expect(router.currentRoute.value.name).toBe("course-detail");
    expect(restore).toHaveBeenCalledTimes(1);
    finishRestore?.(false);
  });

  it.each([
    ["STUDENT", "learning:access"],
    ["TEACHER", "teaching:access"],
    ["ADMIN", "administration:access"]
  ] as const)("opens the public course center for an authenticated %s", async (role, permission) => {
    const store = publicGuardStore(user([role], [permission]), "authenticated");
    const router = publicCourseRouter();
    installAuthGuard(router, store);

    await router.push("/courses");

    expect(router.currentRoute.value.name).toBe("courses");
    expect(store.ensureCurrentUser).not.toHaveBeenCalled();
    expect(store.canAccess).not.toHaveBeenCalled();
  });

  it("redirects an anonymous teacher visit to the unified login with a safe redirect", async () => {
    const router = testRouter("/teacher/dashboard", "TEACHER");
    const onLoginRedirect = vi.fn();
    const store = guardStore(false, false);
    installAuthGuard(router, store, { onLoginRedirect });

    await router.push("/teacher/dashboard");

    expect(onLoginRedirect).toHaveBeenCalledTimes(1);
    const loginUrl = new URL(onLoginRedirect.mock.calls[0][0]);
    expect(loginUrl.pathname).toBe("/login");
    expect(loginUrl.searchParams.get("redirect")).toBe("/teacher/dashboard");
  });

  it("redirects an authenticated but unauthorized application visit to 403", async () => {
    const router = testRouter("/admin/dashboard", "ADMIN");
    const store = guardStore(true, false);
    installAuthGuard(router, store);

    await router.push("/admin/dashboard");

    expect(router.currentRoute.value.name).toBe("forbidden");
  });

  it("keeps teacher course management forbidden to a student", async () => {
    const router = testRouter("/teacher/courses", "TEACHER");
    const store = guardStore(true, false);
    installAuthGuard(router, store);

    await router.push("/teacher/courses");

    expect(router.currentRoute.value.name).toBe("forbidden");
  });

  it("allows an authenticated role and permission pair", async () => {
    const router = testRouter("/teacher/dashboard", "TEACHER");
    const store = guardStore(true, true);
    installAuthGuard(router, store);

    await router.push("/teacher/dashboard");

    expect(router.currentRoute.value.name).toBe("protected");
  });

  it("guards a learning route by capability without requiring a STUDENT role", async () => {
    const router = permissionRouter("/learn/course-1/lesson-1", "learning:access");
    const store = guardStore(true, true);
    installAuthGuard(router, store);

    await router.push("/learn/course-1/lesson-1");

    expect(router.currentRoute.value.name).toBe("protected-permission");
    expect(store.hasPermission).toHaveBeenCalledWith("learning:access");
    expect(store.canAccess).not.toHaveBeenCalled();
  });

  it("sends an authenticated account without learning capability to 403", async () => {
    const router = permissionRouter("/learn/course-1/lesson-1", "learning:access");
    const store = guardStore(true, false);
    installAuthGuard(router, store);

    await router.push("/learn/course-1/lesson-1");

    expect(router.currentRoute.value.name).toBe("forbidden");
  });
});

function testRouter(path: string, role: PlatformRole) {
  return createRouter({ history: createMemoryHistory(), routes: [
    { path: "/login", name: "login", component: {} },
    { path: "/forbidden", name: "forbidden", component: {} },
    { path, name: "protected", component: {}, meta: { requiresAuth: true, requiredRole: role } }
  ] });
}

function publicCourseRouter() {
  return createRouter({ history: createMemoryHistory(), routes: [
    { path: "/courses", name: "courses", component: {}, meta: { restoreSession: true } },
    { path: "/courses/:courseId", name: "course-detail", component: {}, meta: { restoreSession: true } }
  ] });
}

function permissionRouter(path: string, permission: string) {
  return createRouter({ history: createMemoryHistory(), routes: [
    { path: "/login", name: "login", component: {} },
    { path: "/forbidden", name: "forbidden", component: {} },
    { path, name: "protected-permission", component: {}, meta: { requiresAuth: true, requiredPermission: permission } }
  ] });
}

function publicGuardStore(currentUser: CurrentUser | null, status: "unknown" | "authenticated",
    restore = vi.fn().mockResolvedValue(true)): AuthStore {
  return {
    state: { status, loading: false, currentUser, failure: null },
    login: vi.fn(), restore, logout: vi.fn(), ensureCurrentUser: vi.fn(),
    hasRole: vi.fn(), hasPermission: vi.fn(), canAccess: vi.fn(), authorizedRequest: vi.fn()
  } as unknown as AuthStore;
}

function guardStore(authenticated: boolean, authorized: boolean): AuthStore {
  return {
    state: { status: authenticated ? "authenticated" : "anonymous", loading: false,
      currentUser: authenticated ? user(["STUDENT"], ["learning:access"]) : null, failure: null },
    login: vi.fn(), restore: vi.fn(), logout: vi.fn(),
    ensureCurrentUser: vi.fn().mockResolvedValue(authenticated),
    hasRole: vi.fn(), hasPermission: vi.fn().mockReturnValue(authorized),
    canAccess: vi.fn().mockReturnValue(authorized)
  } as unknown as AuthStore;
}

function requestUrl(input: RequestInfo | URL): URL {
  return new URL(input instanceof Request ? input.url : input.toString());
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function user(roles: PlatformRole[], permissions: string[]): CurrentUser {
  return { id: "00000000-0000-4000-8000-000000000001", displayName: "测试用户", avatarUrl: null,
    roles, permissions };
}

function apiError(code: string) {
  return { code, message: "请求失败", traceId: "trace1234", timestamp: "2026-07-17T00:00:00Z",
    path: "/api/v1/auth/login", fieldErrors: [] };
}
