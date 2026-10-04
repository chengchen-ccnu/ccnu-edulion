import { describe, expect, it } from "vitest";

import { buildAdminNavigation } from "./navigation";

const labels = ["教师审核", "账号权限"];

describe("admin fixed navigation", () => {
  it("keeps the frozen sidebar order", () => {
    expect(buildAdminNavigation("admin-dashboard").map(item => item.label)).toEqual(labels);
  });

  it("enables only review and keeps permissions without href or keyboard target", () => {
    const items = buildAdminNavigation("admin-dashboard");

    expect(items[0]).toMatchObject({ state: "enabled", href: "/admin/teacher-review", kind: "router" });
    expect(items[1]).toMatchObject({ state: "coming-soon" });
    expect(items[1].href).toBeUndefined(); expect(items[1].kind).toBeUndefined();
  });

  it("does not vary the collection for anonymous, student, teacher, admin or multi-workspace accounts", () => {
    const identities = ["anonymous", "student", "teacher", "admin", "multi-workspace"];

    expect(identities.map(() => buildAdminNavigation("admin-course-review").map(item => item.id)))
      .toEqual(identities.map(() => ["teacher-review", "users"]));
  });
});
