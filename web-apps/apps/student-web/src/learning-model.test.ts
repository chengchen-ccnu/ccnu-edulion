import type { CourseDetail, CourseProgress, LessonProgress } from "@edu/api-client";
import { describe, expect, it } from "vitest";

import { latestLessonProgress, mergeCourseProgress, resumeLessonId, videoProgressPercent } from "./learning-model";

describe("learning aggregation model", () => {
  it("resumes the most recently touched current-version lesson and falls back after a version change", () => {
    const progress = courseProgress([
      lessonProgress("lesson-1", 40, false, "2026-08-26T01:00:00Z"),
      lessonProgress("lesson-2", 10, false, "2026-08-26T02:00:00Z")
    ]);

    expect(latestLessonProgress(progress)?.lessonId).toBe("lesson-2");
    expect(resumeLessonId(course(), progress, true)).toBe("lesson-2");
    expect(resumeLessonId(course(), courseProgress([lessonProgress("old-lesson", 90, true,
      "2026-08-26T03:00:00Z")]), true)).toBe("lesson-1");
  });

  it("never lets duplicate or out-of-order responses reduce local monotonic progress", () => {
    const initial = courseProgress([lessonProgress("lesson-1", 80, true, "2026-08-26T02:00:00Z", 3, 120)]);
    const stale = lessonProgress("lesson-1", 20, false, "2026-08-26T01:00:00Z", 2, 30);
    const merged = mergeCourseProgress(initial, stale);

    expect(merged.lessons[0]).toMatchObject({ positionSeconds: 80, watchedSeconds: 120,
      completed: true, revision: 3 });
    expect(merged.completionPercent).toBe(50);
  });

  it("shows current-video percentage independently from course lesson completion", () => {
    expect(videoProgressPercent(28, 92.04, false)).toBe(30.4);
    expect(videoProgressPercent(999, 92.04, false)).toBe(100);
    expect(videoProgressPercent(0, 0, false)).toBe(0);
    expect(videoProgressPercent(0, 0, true)).toBe(100);
  });
});

function course(): CourseDetail {
  return { id: "course-1", title: "数学", summary: "", description: "", coverMediaId: null, coverUrl: null,
    teacher: { id: "teacher-1", displayName: "测试教师" }, category: { id: "category-1", name: "数学" },
    level: "BEGINNER", accessType: "ENROLLMENT_REQUIRED", publishedVersion: "v2", chapters: [{
      id: "chapter-1", title: "第一章", sequence: 1, lessons: [
        { id: "lesson-1", title: "第一课", type: "VIDEO", sequence: 1, previewable: false,
          resources: [resource("resource-1", "media-1")] },
        { id: "lesson-2", title: "第二课", type: "VIDEO", sequence: 2, previewable: false,
          resources: [resource("resource-2", "media-2")] }
      ]
    }] };
}

function resource(id: string, mediaId: string) {
  return { id, mediaId, purpose: "LESSON_VIDEO" as const, sequence: 1, displayName: "测试视频",
    presentation: "INLINE" as const, originalFileName: "lesson.mp4", mimeType: "video/mp4", size: 8 };
}

function courseProgress(lessons: LessonProgress[]): CourseProgress {
  return { courseId: "course-1", completedLessonCount: lessons.filter(item => item.completed).length,
    lessonCount: 2, completionPercent: 0, lessons };
}

function lessonProgress(lessonId: string, positionSeconds: number, completed: boolean, lastEventAt: string,
    revision = 1, watchedSeconds = positionSeconds): LessonProgress {
  return { courseId: "course-1", lessonId, positionSeconds, watchedSeconds, completed,
    lastEventAt, revision, replayed: false, persistenceStatus: "PERSISTED" };
}
