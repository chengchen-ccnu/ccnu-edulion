import type { CoursePublicationStatus } from "@edu/api-client";

export type PublicationCourseState = {
  publicationStatus: CoursePublicationStatus;
  hasUnpublishedChanges: boolean;
};

export type PublicationControls = {
  showPublish: boolean;
  publishLabel: "发布当前版本" | "发布新版本" | "重新发布";
  showUnpublish: boolean;
};

export type PublishableCourseState = PublicationCourseState & {
  chapters: Array<{ lessons: unknown[] }>;
};

/**
 * 将课程线上状态和草稿差异转换为教师可执行操作。
 *
 * @param course 课程当前发布状态，以及是否存在尚未发布的新草稿。
 * @returns 页面应显示的发布、重新发布和下架操作；不发请求，也不改变课程状态。
 */
export function publicationControls(course: PublicationCourseState): PublicationControls {
  if (course.publicationStatus === "PUBLISHED") {
    return {
      showPublish: course.hasUnpublishedChanges,
      publishLabel: "发布新版本",
      showUnpublish: true
    };
  }
  if (course.publicationStatus === "UNPUBLISHED" && !course.hasUnpublishedChanges) {
    return { showPublish: true, publishLabel: "重新发布", showUnpublish: false };
  }
  return { showPublish: true, publishLabel: "发布当前版本", showUnpublish: false };
}

/**
 * 判断课程列表中的发布操作是否可执行。
 *
 * @param course 课程发布状态、草稿差异和目录摘要；未改动的已下架版本可直接恢复上线。
 * @returns 新草稿目录完整，或存在可重新上线的旧发布版本时返回 true；无副作用。
 */
export function canPublishCourse(course: PublishableCourseState): boolean {
  if (course.publicationStatus === "UNPUBLISHED" && !course.hasUnpublishedChanges) return true;
  return course.hasUnpublishedChanges && course.chapters.length > 0
    && course.chapters.every(chapter => chapter.lessons.length > 0);
}
