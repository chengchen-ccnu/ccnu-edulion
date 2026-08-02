import { describe, expect, it } from "vitest";

import { studentRoutes } from "./routes";

describe("student application routes", () => {
  it.each(["/courses", "/courses/:courseId"])("keeps %s public with optional session recovery", path => {
    const route = studentRoutes.find(item => item.path === path);

    expect(route?.meta).toMatchObject({ restoreSession: true });
    expect(route?.meta?.requiresAuth).not.toBe(true);
    expect(route?.meta?.requiredRole).toBeUndefined();
  });

  it("keeps the student learning home protected", () => {
    const home = studentRoutes.find(item => item.path === "/");

    expect(home?.meta).toMatchObject({ requiresAuth: true, requiredRole: "STUDENT" });
  });
});
