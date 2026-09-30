package com.eduplatform.learning.course;

import java.util.Set;
import java.util.Map;

/**
 * 读取 course-service 当前发布课程、课时和 owner 事实；实现只走版本化 HTTP，不查询课程 Schema。
 */
public interface CourseFactsClient {
    /** 实时读取权威系统可用性和有界暂停区间；不能使用公开课程展示缓存替代。 */
    CourseAvailability availability(String courseId, long afterSequence, String traceId);
    /** @return 当前公开发布版本及全部课时稳定 ID；课程不可见时抛 404，系统暂停抛安全 409，依赖失败时抛 503。 */
    CourseFact requirePublished(String courseId, String traceId);

    /** @return 当前教师拥有且仍有公开发布版本的课程事实；他人课程按 404 隐藏。 */
    CourseFact requireOwnedPublished(String courseId, String teacherId, String traceId);

    /** 教师分析先校验 owner；本人草稿且尚无发布版本返回空，其他 owner 仍安全 404。 */
    default CourseFact ownedPublishedOrEmpty(String courseId, String teacherId, String traceId) {
        return requireOwnedPublished(courseId, teacherId, traceId);
    }

    /** 精确校验当前或历史已发布课时身份；供旧播放器继续写原修订，禁止枚举历史目录。 */
    LessonContentIdentity requireContentIdentity(String courseId, String lessonId,
            String contentRevisionId, String traceId);

    record CourseFact(String courseId, String publishedVersion, String accessType, String ownerTeacherId,
            int publicationRevision, Set<String> lessonIds, Map<String, String> lessonTypes,
            Map<String, String> contentRevisionIds) {
        /** 兼容既有测试夹具；未提供类型的旧调用不能用于 ARTICLE 完成命令。 */
        public CourseFact(String courseId, String publishedVersion, String accessType,
                String ownerTeacherId, Set<String> lessonIds) {
            this(courseId, publishedVersion, accessType, ownerTeacherId, 1, lessonIds, Map.of(),
                    lessonIds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> id)));
        }
        public CourseFact(String courseId, String publishedVersion, String accessType,
                String ownerTeacherId, Set<String> lessonIds, Map<String, String> lessonTypes) {
            this(courseId, publishedVersion, accessType, ownerTeacherId, 1, lessonIds, lessonTypes,
                    lessonIds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> id)));
        }
        public boolean containsLesson(String lessonId) { return lessonId == null || lessonIds.contains(lessonId); }
        public boolean isArticle(String lessonId) { return "ARTICLE".equals(lessonTypes.get(lessonId)); }
        public String contentRevisionId(String lessonId) { return contentRevisionIds.get(lessonId); }
    }

    record LessonContentIdentity(String courseId, String lessonId, String contentRevisionId,
            String lessonType, boolean currentPublished) {}
}
