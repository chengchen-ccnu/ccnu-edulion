import { describe, expect, it } from "vitest";

import { buildStudentNavigation } from "./navigation";

const labels = ["首页", "课程中心", "我的学习", "练习中心", "AI 答疑"];

describe("student fixed navigation", () => {
  it.each(["login", "courses", "course-detail", "course-learning"])(
      "keeps the same brand-level navigation collection on %s", routeName => {
        expect(buildStudentNavigation(routeName).map(item => item.label)).toEqual(labels);
      });

  it("enables course center, my learning and the frozen practice slot while keeping AI inert", () => {
    const items = buildStudentNavigation("courses");

    expect(items.filter(item => item.state === "enabled").map(item => item.id))
      .toEqual(["course-center", "my-learning", "practice-center"]);
    expect(items.filter(item => item.state !== "enabled").every(item => item.href === undefined)).toBe(true);
  });

  it.each(["practice", "practice-attempt", "attempt-result", "wrong-questions"])(
    "keeps %s in the practice-center highlight group", routeName => {
      const items = buildStudentNavigation(routeName);
      expect(items.filter(item => item.current).map(item => item.id)).toEqual(["practice-center"]);
    });

  it("highlights only my learning on the dedicated course learning route", () => {
    const items = buildStudentNavigation("course-learning");

    expect(items.filter(item => item.current).map(item => item.label)).toEqual(["我的学习"]);
    expect(items.map(item => item.label).filter(label => label === "学生学习中心")).toEqual([]);
  });

  it.each(["courses", "course-detail"])("highlights only course center on %s", routeName => {
    expect(buildStudentNavigation(routeName).filter(item => item.current).map(item => item.label))
      .toEqual(["课程中心"]);
  });

  it("does not accept identity input, so anonymous and multi-workspace sessions cannot change slots", () => {
    const identityScenarios = ["anonymous", "student", "teacher", "admin", "multi-workspace"];

    expect(identityScenarios.map(() => buildStudentNavigation("courses").map(item => item.id)))
      .toEqual(identityScenarios.map(() => ["home", "course-center", "my-learning", "practice-center", "ai-tutor"]));
  });
});
