// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
const identity = vi.hoisted(() => ({ admin: true }));
vi.mock("./auth", () => ({ authStore: {
  state: { status: "authenticated", currentUser: { id: "test" } },
  hasPermission: (permission: string) => identity.admin && permission === "administration:access",
  canAccess: () => identity.admin, ensureCurrentUser: async () => true
} }));
import { router } from "./router";
afterEach(() => { identity.admin = true; });
describe("admin canonical entry and permission guard", () => {
  it.each(["/", "/admin/dashboard", "/admin/reviews"])("redirects %s directly to the implemented review page", async path => {
    await router.push(path); expect(router.currentRoute.value.fullPath).toBe("/admin/teacher-review");
    expect(router.currentRoute.value.name).toBe("admin-teacher-review");
  });
  it("preserves a real review deep link and rejects non administrator navigation", async () => {
    await router.push("/admin/teacher-review?page=2&status=REJECTED&panel=history"); expect(router.currentRoute.value.query.page).toBe("2");
    identity.admin = false; await router.push("/admin/teacher-review?page=1"); expect(router.currentRoute.value.name).toBe("forbidden");
  });
  it("protects the account permission deep link with the same administrator guard", async () => {
    await router.push("/admin/users?page=2&status=DISABLED&panel=detail");
    expect(router.currentRoute.value.name).toBe("admin-users"); expect(router.currentRoute.value.query.page).toBe("2");
    identity.admin = false; await router.push("/admin/users?page=1"); expect(router.currentRoute.value.name).toBe("forbidden");
  });
});
