import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

describe("T11C.2 student task boundaries", () => {
  it("uses the server-filtered currentLessonId contract on the learning page", () => {
    const source = readFileSync(new URL("./views/CourseLearningPage.vue", import.meta.url), "utf8");
    expect(source).toContain("listCourseTasks(detail.id, { page: 1, size: 100, currentLessonId: lessonId }");
    expect(source).not.toContain("studentAssessmentApi.list({ page: 1, size: 100, courseId: detail.id }");
    expect(source).toContain("/learn/${course.id}/tasks/${item.id}");
    expect(source).not.toContain("查看练习中心");
  });

  it("keeps result visibility controlled by the safe publication projection", () => {
    const source = readFileSync(new URL("./views/AttemptResultPage.vue", import.meta.url), "utf8");
    expect(source).toContain("courseTaskResult");
    expect(source).toContain("resultDisclosure(result.value)");
    expect(source).toContain("v-if=\"disclosure?.showReferenceAnswer\"");
  });

  it("does not render question content in the course task overview", () => {
    const source = readFileSync(new URL("./views/CourseTasksPage.vue", import.meta.url), "utf8");
    expect(source).toContain("listCourseTasks");
    expect(source).toContain("未开放任务只展示安全摘要");
    expect(source).not.toContain("task.questions");
  });
});
