import { describe, expect, it } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { readFileSync } from "node:fs";

import { studentRoutes } from "./routes";

describe("student application routes", () => {
  it.each([
    ["/login", false],
    ["/register", false],
    ["/forgot-password", false],
    ["/reset-password", false],
    ["/terms", false],
    ["/privacy", false],
    ["/help", false],
    ["/profile", true],
    ["/profile/security", true],
    ["/apply-teacher", true]
  ])("registers the T12E route %s with the expected access boundary", (path, requiresAuth) => {
    const route = studentRoutes.find(item => item.path === path);

    expect(route).toBeDefined();
    expect(route?.meta?.requiresAuth).toBe(requiresAuth ? true : undefined);
  });

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

  it("protects the dedicated learning page by capability without embedding it in course detail", () => {
    const learning = studentRoutes.find(item => item.path === "/learn/:courseId/:lessonId");

    expect(learning?.name).toBe("course-learning");
    expect(learning?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
    expect(learning?.meta?.requiredRole).toBeUndefined();
  });

  it("enables my learning on the same capability boundary for teacher-shaped learner accounts", () => {
    const myLearning = studentRoutes.find(item => item.path === "/my-learning");

    expect(myLearning?.name).toBe("my-learning");
    expect(myLearning?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
    expect(myLearning?.meta?.requiredRole).toBeUndefined();
  });

  it.each([
    ["/practice", "practice"],
    ["/practice/questions/:questionId", "practice-question"],
    ["/practice/:assessmentId", "practice-legacy-entry"],
    ["/attempt/:attemptId/result", "attempt-result"],
    ["/wrong-questions", "wrong-questions"]
  ])("protects T12B route %s with learning:access", (path, name) => {
    const route = studentRoutes.find(item => item.path === path);
    expect(route?.name).toBe(name);
    expect(route?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
    expect(route?.meta?.requiredRole).toBeUndefined();
  });

  it.each([["/ai", "ai"], ["/ai/conversations/:conversationId", "ai-conversation"]])(
    "protects T15A route %s by learning capability", (path, name) => {
      const route = studentRoutes.find(item => item.path === path);
      expect(route?.name).toBe(name);
      expect(route?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
      expect(route?.meta?.requiredRole).toBeUndefined();
    });

  it.each([
    ["/learn/:courseId/tasks", "course-tasks"],
    ["/learn/:courseId/tasks/:assessmentId", "course-task-attempt"],
    ["/learn/:courseId/tasks/:assessmentId/result", "course-task-result"]
  ])("protects the T11C.2 course task route %s without a parallel role boundary", (path, name) => {
    const route = studentRoutes.find(item => item.path === path);
    expect(route?.name).toBe(name);
    expect(route?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
    expect(route?.meta?.requiredRole).toBeUndefined();
  });

  it.each([
    ["/learn/course-1/tasks", "course-tasks"],
    ["/learn/course-1/tasks/assessment-1", "course-task-attempt"],
    ["/learn/course-1/tasks/assessment-1/result", "course-task-result"],
    ["/learn/course-1/lesson-1", "course-learning"]
  ])("resolves the concrete T11C.2 path %s before the generic lesson route", (path, name) => {
    const router = createRouter({ history: createMemoryHistory(), routes: studentRoutes });

    expect(router.resolve(path).name).toBe(name);
  });

  it("resolves the public question detail before the legacy assessment migration route", () => {
    const router = createRouter({ history: createMemoryHistory(), routes: studentRoutes });

    expect(router.resolve("/practice/questions/question-1").name).toBe("practice-question");
    expect(router.resolve("/practice/assessment-1").name).toBe("practice-legacy-entry");
  });

  it("uses the public question projection instead of a cross-course assessment list", () => {
    const source = readFileSync(new URL("./views/QuestionBankPage.vue", import.meta.url), "utf8");
    expect(source).toContain("studentQuestionApi.list");
    expect(source).not.toContain("studentAssessmentApi.list");
    expect(source).not.toContain("courseId");
  });

  it("keeps playback implementation out of the public course detail component", () => {
    const detailSource = readFileSync(new URL("./views/CourseDetailPage.vue", import.meta.url), "utf8");
    const learningSource = readFileSync(new URL("./views/CourseLearningPage.vue", import.meta.url), "utf8");

    expect(detailSource).not.toContain("HlsVideoPlayer");
    expect(detailSource).not.toContain("playbackGrant");
    expect(learningSource).toContain("HlsVideoPlayer");
    expect(learningSource).toContain("playbackGrant");
  });
});
