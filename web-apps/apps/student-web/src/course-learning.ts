import type { CourseDetail } from "@edu/api-client";

type CourseLesson = CourseDetail["chapters"][number]["lessons"][number];

/**
 * 已发布课程中的学习目录投影。
 * 输入只来自公开 CourseDetail，输出不携带正文、mediaId 或资源绑定；这些内容必须另走受保护接口。
 */
export interface CourseLearningEntry {
  chapterId: string;
  chapterTitle: string;
  chapterSequence: number;
  lesson: CourseLesson;
  accessible: boolean;
}

/** 将发布目录按章节和课时顺序展开；访问性只依据公开课程规则、试看标记与报名事实。 */
export function courseLearningEntries(course: CourseDetail, enrolled = false): CourseLearningEntry[] {
  return [...course.chapters]
    .sort((left, right) => left.sequence - right.sequence)
    .flatMap(chapter => [...chapter.lessons]
      .sort((left, right) => left.sequence - right.sequence)
      .map(lesson => ({
          chapterId: chapter.id,
          chapterTitle: chapter.title,
          chapterSequence: chapter.sequence,
          lesson,
          accessible: course.accessType === "PUBLIC" || lesson.previewable || enrolled
        })));
}

/** 选择详情页“开始学习/试看”的首个真实入口；未传入已报名事实时，报名制课程只返回试看课时。 */
export function firstAccessibleLearningEntry(course: CourseDetail, enrolled = false): CourseLearningEntry | null {
  return courseLearningEntries(course, enrolled).find(item => item.accessible) ?? null;
}

/** 根据公开目录解析当前深链接；受保护内容是否存在仍由课时内容接口裁决。 */
export function findLearningEntry(course: CourseDetail, lessonId: string): CourseLearningEntry | null {
  return courseLearningEntries(course).find(item => item.lesson.id === lessonId) ?? null;
}
