import type { CourseDetail } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { courseLearningEntries, findLearningEntry, firstAccessibleLearningEntry } from "./course-learning";

describe("course learning model", () => {
  it("orders every published lesson without reading public media bindings", () => {
    const course = fixture("PUBLIC");

    expect(courseLearningEntries(course).map(item => [item.lesson.id, item.lesson.type, item.accessible]))
      .toEqual([["lesson-1", "VIDEO", true], ["lesson-2", "VIDEO", true], ["lesson-article", "ARTICLE", true]]);
  });

  it("offers only previewable lessons before enrollment and unlocks the same stable entries afterwards", () => {
    const course = fixture("ENROLLMENT_REQUIRED");

    expect(firstAccessibleLearningEntry(course)?.lesson.id).toBe("lesson-2");
    expect(courseLearningEntries(course)[0]?.accessible).toBe(false);
    expect(courseLearningEntries(course, true).every(item => item.accessible)).toBe(true);
  });

  it("resolves article deep links and treats only unknown lessons as missing", () => {
    const course = fixture("PUBLIC");

    expect(findLearningEntry(course, "lesson-2")?.lesson.type).toBe("VIDEO");
    expect(findLearningEntry(course, "lesson-article")?.lesson.type).toBe("ARTICLE");
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
        resources: [resource("resource-2", "media-2", "LESSON_VIDEO", 1)] },
      { id: "lesson-article", title: "图文", type: "ARTICLE", sequence: 3, previewable: true, resources: [] },
      { id: "lesson-1", title: "正式视频", type: "VIDEO", sequence: 1, previewable: false,
        resources: [
          resource("resource-other", "media-other", "ARTICLE_ATTACHMENT", 1),
          resource("resource-1", "media-1", "LESSON_VIDEO", 2)
        ] }
    ] }]
  };
}

function resource(id: string, mediaId: string, purpose: "LESSON_VIDEO" | "ARTICLE_ATTACHMENT", sequence: number) {
  return { id, mediaId, purpose, sequence, displayName: "测试资源", presentation: "DOWNLOAD" as const,
    originalFileName: "resource.bin", mimeType: "application/octet-stream", size: 8 };
}
