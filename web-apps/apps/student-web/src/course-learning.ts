import type { CourseDetail } from "@edu/api-client";

type CourseLesson = CourseDetail["chapters"][number]["lessons"][number];

/**
 * 已发布课程中的可播放课时投影。
 * 输入只来自 OpenAPI CourseDetail，输出只携带课程域资源行 ID；不会暴露或推导 mediaId、OSS 地址。
 */
export interface CourseLearningEntry {
  chapterId: string;
  chapterTitle: string;
  chapterSequence: number;
  lesson: CourseLesson;
  resourceId: string;
  accessible: boolean;
}

/** 将发布目录按章节、课时和资源顺序展开为首期支持的 LESSON_VIDEO 学习项。 */
export function courseLearningEntries(course: CourseDetail): CourseLearningEntry[] {
  return [...course.chapters]
    .sort((left, right) => left.sequence - right.sequence)
    .flatMap(chapter => [...chapter.lessons]
      .sort((left, right) => left.sequence - right.sequence)
      .flatMap(lesson => {
        const resource = [...lesson.resources]
          .sort((left, right) => left.sequence - right.sequence)
          .find(item => item.purpose === "LESSON_VIDEO");
        if (lesson.type !== "VIDEO" || !resource) return [];
        return [{
          chapterId: chapter.id,
          chapterTitle: chapter.title,
          chapterSequence: chapter.sequence,
          lesson,
          resourceId: resource.id,
          accessible: course.accessType === "PUBLIC" || lesson.previewable
        }];
      }));
}

/** 选择详情页“开始学习/试看”的首个真实入口；报名制课程在 T11 前只返回试看课时。 */
export function firstAccessibleLearningEntry(course: CourseDetail): CourseLearningEntry | null {
  return courseLearningEntries(course).find(item => item.accessible) ?? null;
}

/** 根据深链接解析当前课时；找不到 VIDEO 资源时返回 null，由学习页展示稳定 404。 */
export function findLearningEntry(course: CourseDetail, lessonId: string): CourseLearningEntry | null {
  return courseLearningEntries(course).find(item => item.lesson.id === lessonId) ?? null;
}
