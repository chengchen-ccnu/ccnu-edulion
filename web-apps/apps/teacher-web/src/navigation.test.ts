import type { CurrentUser } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { buildTeacherNavigation } from "./navigation";

const urls = {
  student: "https://learn.example.test",
  teacher: "https://teach.example.test",
  admin: "https://admin.example.test"
};

describe("teacher navigation", () => {
  it("shows both course center and teaching management for a teacher", () => {
    const items = buildTeacherNavigation(user(["TEACHER"], ["teaching:access"]), urls);

    expect(items.map(item => item.label)).toEqual(["工作台", "课程中心", "教学管理"]);
    expect(items.find(item => item.id === "course-center")?.href).toBe("https://learn.example.test/courses");
    expect(items.find(item => item.id === "teaching-management")?.href).toBe("/teacher/courses");
  });

  it("does not show teaching management to a student", () => {
    const items = buildTeacherNavigation(user(["STUDENT"], ["learning:access"]), urls);

    expect(items.map(item => item.label)).toEqual(["课程中心"]);
  });
});

function user(roles: CurrentUser["roles"], permissions: string[]): CurrentUser {
  return { id: "00000000-0000-4000-8000-000000000001", displayName: "测试用户", avatarUrl: null,
    roles, permissions };
}
