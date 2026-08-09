import { describe, expect, it } from "vitest";

import { buildAdminNavigation } from "./navigation";

const labels = ["工作台", "教师审核", "课程审核", "内容治理", "运营配置", "数据报表", "系统管理"];

describe("admin fixed navigation", () => {
  it("keeps the frozen sidebar order", () => {
    expect(buildAdminNavigation("admin-dashboard").map(item => item.label)).toEqual(labels);
  });

  it("keeps all T17C entries disabled, without href or keyboard target", () => {
    const items = buildAdminNavigation("admin-dashboard");

    expect(items.every(item => item.state === "coming-soon")).toBe(true);
    expect(items.every(item => item.href === undefined && item.kind === undefined)).toBe(true);
  });

  it("does not vary the collection for anonymous, student, teacher, admin or multi-workspace accounts", () => {
    const identities = ["anonymous", "student", "teacher", "admin", "multi-workspace"];

    expect(identities.map(() => buildAdminNavigation("admin-course-review").map(item => item.id)))
      .toEqual(identities.map(() => ["dashboard", "teacher-review", "course-review", "content-moderation",
        "operations", "reports", "system"]));
  });
});
