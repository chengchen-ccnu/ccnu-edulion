import type { CurrentUser } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { buildTeacherNavigation } from "./navigation";

const urls = {
  student: "https://learn.example.test",
  teacher: "https://teach.example.test",
  admin: "https://admin.example.test"
};

describe("teacher navigation", () => {
  const labels = ["课程中心", "工作台", "课程管理", "题库", "测评管理", "学情分析", "媒体中心"];

  it("keeps the frozen order and enables course center plus course management for a teacher", () => {
    const items = buildTeacherNavigation("teacher-courses", user(["TEACHER"], ["teaching:access"]), urls);

    expect(items.map(item => item.label)).toEqual(labels);
    expect(items.filter(item => item.state === "enabled").map(item => item.label))
      .toEqual(["课程中心", "课程管理", "题库"]);
    expect(items.find(item => item.id === "course-center")?.href).toBe("https://learn.example.test/courses");
    expect(items.find(item => item.id === "course-management")?.href).toBe("/teacher/courses");
    expect(items.filter(item => item.current).map(item => item.label)).toEqual(["课程管理"]);
  });

  it.each([
    ["anonymous", null],
    ["student", user(["STUDENT"], ["learning:access"])],
    ["admin", user(["ADMIN"], ["administration:access"])],
    ["multi-workspace", user(["STUDENT", "TEACHER"], ["learning:access", "teaching:access"])]
  ] as const)("keeps the same main navigation collection for %s", (_label, currentUser) => {
    const items = buildTeacherNavigation("teacher-courses", currentUser, urls);

    expect(items.map(item => item.label)).toEqual(labels);
  });

  it("keeps course management visible but non-navigable without teaching permission", () => {
    const item = buildTeacherNavigation("teacher-courses", user(["STUDENT"], ["learning:access"]), urls)
      .find(candidate => candidate.id === "course-management");

    expect(item).toMatchObject({ state: "permission-required", href: undefined, current: true });
  });

  it("never gives coming-soon entries a placeholder href", () => {
    const items = buildTeacherNavigation("teacher-dashboard", user(["TEACHER"], ["teaching:access"]), urls);

    expect(items.filter(item => item.state === "coming-soon").every(item => item.href === undefined)).toBe(true);
  });

  it.each(["teacher-questions", "teacher-question-new", "teacher-question-detail", "teacher-question-edit",
    "teacher-question-imports", "teacher-question-import-detail"])("keeps all T10 routes in one question-bank highlight group", routeName => {
    const items = buildTeacherNavigation(routeName, user(["TEACHER"], ["teaching:access"]), urls);
    expect(items.filter(item => item.current).map(item => item.label)).toEqual(["题库"]);
    expect(items.find(item => item.id === "question-bank")).toMatchObject({
      state: "enabled", href: "/teacher/questions", kind: "router"
    });
  });
});

function user(roles: CurrentUser["roles"], permissions: string[]): CurrentUser {
  return { id: "00000000-0000-4000-8000-000000000001", displayName: "测试用户", avatarUrl: null,
    roles, permissions };
}
