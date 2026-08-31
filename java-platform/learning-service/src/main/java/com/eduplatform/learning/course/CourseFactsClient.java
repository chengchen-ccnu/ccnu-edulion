package com.eduplatform.learning.course;

import java.util.Set;
import java.util.Map;

/**
 * 读取 course-service 当前发布课程、课时和 owner 事实；实现只走版本化 HTTP，不查询课程 Schema。
 */
public interface CourseFactsClient {
    /** @return 当前公开发布版本及全部课时稳定 ID；课程不可见时抛 404，依赖失败时抛 503。 */
    CourseFact requirePublished(String courseId, String traceId);

    /** @return 当前教师拥有且仍有公开发布版本的课程事实；他人课程按 404 隐藏。 */
    CourseFact requireOwnedPublished(String courseId, String teacherId, String traceId);

    record CourseFact(String courseId, String publishedVersion, String accessType, String ownerTeacherId,
            Set<String> lessonIds, Map<String, String> lessonTypes) {
        /** 兼容既有测试夹具；未提供类型的旧调用不能用于 ARTICLE 完成命令。 */
        public CourseFact(String courseId, String publishedVersion, String accessType,
                String ownerTeacherId, Set<String> lessonIds) {
            this(courseId, publishedVersion, accessType, ownerTeacherId, lessonIds, Map.of());
        }
        public boolean containsLesson(String lessonId) { return lessonId == null || lessonIds.contains(lessonId); }
        public boolean isArticle(String lessonId) { return "ARTICLE".equals(lessonTypes.get(lessonId)); }
    }
}
