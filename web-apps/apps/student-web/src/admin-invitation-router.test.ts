// @vitest-environment jsdom
import { expect, it, vi } from "vitest";
const restore = vi.hoisted(() => vi.fn());
vi.mock("./auth", () => ({ authStore: { state: { status: "unknown", currentUser: null }, restore } }));
import { router } from "./router";

it("sanitizes direct and same-route acceptance navigation without login or restoration", async () => {
  await router.push("/accept-admin-invitation?email=synthetic&secret=synthetic&invitationId=synthetic#synthetic");
  expect(router.currentRoute.value.fullPath).toBe("/accept-admin-invitation");
  await router.push("/accept-admin-invitation?secret=a&secret=b#another");
  expect(router.currentRoute.value.fullPath).toBe("/accept-admin-invitation"); expect(restore).not.toHaveBeenCalled();
});
