import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

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

  it("protects the dedicated learning page by capability without embedding it in course detail", () => {
    const learning = studentRoutes.find(item => item.path === "/learn/:courseId/:lessonId");

    expect(learning?.name).toBe("course-learning");
    expect(learning?.meta).toMatchObject({ requiresAuth: true, requiredPermission: "learning:access" });
    expect(learning?.meta?.requiredRole).toBeUndefined();
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
