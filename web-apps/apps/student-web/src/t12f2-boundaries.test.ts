import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const learning = readFileSync(new URL("./views/CourseLearningPage.vue", import.meta.url), "utf8");
const courseApi = readFileSync(new URL("./course-api.ts", import.meta.url), "utf8");
const learningApi = readFileSync(new URL("./learning-api.ts", import.meta.url), "utf8");

describe("T12F.2 learner content boundaries", () => {
  it("loads all protected content and resource actions through generated course paths", () => {
    expect(courseApi).toContain('"/api/v1/courses/{courseId}/lessons/{lessonId}/content"');
    expect(courseApi).toContain('"/api/v1/courses/{courseId}/lessons/{lessonId}/resources/{resourceId}/access-grants"');
    expect(learning).toContain("SafeArticleContent");
    expect(learning).toContain("content.inlineImages");
    expect(learning).not.toContain("current.value.resourceId");
  });

  it("completes article only after render with a stable request id and bounded API retries", () => {
    expect(learning).toContain("articleClientRequestId = crypto.randomUUID()");
    expect(learning).toContain("async function handleArticleRendered");
    expect(learning).toContain("learningFactsAllowed.value");
    expect(learning).toContain("正文可继续阅读，完成状态尚未同步");
    expect(learningApi).toContain("for (let attempt = 0; attempt < 3; attempt += 1)");
    expect(learningApi).toContain("body: CompleteArticleLessonRequest");
  });

  it("cleans requests and temporary resources and exposes no subtitle controls", () => {
    expect(learning).toContain("pageAbort?.abort()");
    expect(learning).toContain("resourceActions.dispose()");
    expect(learning).not.toMatch(/字幕按钮|字幕菜单|上传字幕|<track/);
    expect(learning).toContain("status === 403");
    expect(learning).toContain("status === 429");
    expect(learning).toContain("instanceof TypeError");
  });
});
