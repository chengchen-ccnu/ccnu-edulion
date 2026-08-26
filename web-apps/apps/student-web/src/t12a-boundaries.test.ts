import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const detail = readFileSync(new URL("./views/CourseDetailPage.vue", import.meta.url), "utf8");
const learning = readFileSync(new URL("./views/CourseLearningPage.vue", import.meta.url), "utf8");
const myLearning = readFileSync(new URL("./views/MyLearningPage.vue", import.meta.url), "utf8");

describe("T12A page boundaries", () => {
  it("keeps media playback exclusively in the existing learning page", () => {
    expect(detail).not.toContain("HlsVideoPlayer");
    expect(detail).not.toContain("playbackGrant");
    expect(learning).toContain("HlsVideoPlayer");
    expect(learning).toContain("studentCourseApi.playbackGrant");
  });

  it("shows loading, empty, partial failure, HTTP boundaries and offline recovery without fake success", () => {
    expect(myLearning).toContain("partialFailureCount");
    expect(myLearning).toContain("empty-state");
    expect(myLearning).toContain("learning-card-skeleton");
    for (const status of [401, 403, 404, 409, 422, 429]) {
      expect(`${detail}\n${learning}\n${myLearning}`).toContain(`status === ${status}`);
    }
    expect(`${detail}\n${learning}\n${myLearning}`).toContain("instanceof TypeError");
  });

  it("cleans page requests and progress work while leaving later task areas disabled", () => {
    expect(learning).toContain("onBeforeUnmount(cancelPage)");
    expect(learning).toContain("progressSync?.dispose()");
    expect(learning).toContain("pageAbort?.abort()");
    expect(learning).toContain("测评与 AI 将在后续任务");
    expect(learning).not.toContain("assessmentApi");
    expect(learning).not.toContain("aiApi");
  });
});
