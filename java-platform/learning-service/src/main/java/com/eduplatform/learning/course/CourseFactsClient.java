package com.eduplatform.learning.course;

import java.util.Set;

/**
 * 读取 course-service 当前发布课程、课时和 owner 事实；实现只走版本化 HTTP，不查询课程 Schema。
 */
public interface CourseFactsClient {
    /** @return 当前公开发布版本及全部课时稳定 ID；课程不可见时抛 404，依赖失败时抛 503。 */
    CourseFact requirePublished(String courseId, String traceId);

    /** @return 当前教师拥有且仍有公开发布版本的课程事实；他人课程按 404 隐藏。 */
    CourseFact requireOwnedPublished(String courseId, String teacherId, String traceId);

    record CourseFact(String courseId, String publishedVersion, String accessType, String ownerTeacherId,
            Set<String> lessonIds) {
        public boolean containsLesson(String lessonId) { return lessonId == null || lessonIds.contains(lessonId); }
    }
}
