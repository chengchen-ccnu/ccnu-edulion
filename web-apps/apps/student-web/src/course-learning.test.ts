import type { CourseDetail } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { courseLearningEntries, findLearningEntry, firstAccessibleLearningEntry } from "./course-learning";

describe("course learning model", () => {
  it("orders published video lessons and keeps only the first LESSON_VIDEO resource", () => {
    const course = fixture("PUBLIC");

    expect(courseLearningEntries(course).map(item => [item.lesson.id, item.resourceId, item.accessible]))
      .toEqual([["lesson-1", "resource-1", true], ["lesson-2", "resource-2", true]]);
  });

  it("offers only previewable lessons before enrollment and unlocks the same stable entries afterwards", () => {
    const course = fixture("ENROLLMENT_REQUIRED");

    expect(firstAccessibleLearningEntry(course)?.lesson.id).toBe("lesson-2");
    expect(courseLearningEntries(course)[0]?.accessible).toBe(false);
    expect(courseLearningEntries(course, true).every(item => item.accessible)).toBe(true);
  });

  it("treats an unknown or non-video lesson deep link as missing", () => {
    const course = fixture("PUBLIC");

    expect(findLearningEntry(course, "lesson-2")?.resourceId).toBe("resource-2");
    expect(findLearningEntry(course, "lesson-article")).toBeNull();
    expect(findLearningEntry(course, "missing")).toBeNull();
  });
});

function fixture(accessType: CourseDetail["accessType"]): CourseDetail {
  return {
    id: "course-1", title: "线性代数", summary: "摘要", description: "介绍", coverMediaId: null,
    coverUrl: null, teacher: { id: "teacher-1", displayName: "测试教师" },
    category: { id: "category-1", name: "数学" }, level: "BEGINNER", accessType,
    publishedVersion: "v1", chapters: [{ id: "chapter-1", title: "第一章", sequence: 1, lessons: [
      { id: "lesson-2", title: "试看视频", type: "VIDEO", sequence: 2, previewable: true,
        resources: [{ id: "resource-2", mediaId: "media-2", purpose: "LESSON_VIDEO", sequence: 1 }] },
      { id: "lesson-article", title: "图文", type: "ARTICLE", sequence: 3, previewable: true, resources: [] },
      { id: "lesson-1", title: "正式视频", type: "VIDEO", sequence: 1, previewable: false,
        resources: [
          { id: "resource-other", mediaId: "media-other", purpose: "ARTICLE_ATTACHMENT", sequence: 1 },
          { id: "resource-1", mediaId: "media-1", purpose: "LESSON_VIDEO", sequence: 2 }
        ] }
    ] }]
  };
}
