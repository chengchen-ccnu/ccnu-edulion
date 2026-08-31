package com.eduplatform.course.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * course-service 的 HTTP 契约适配记录；字段必须与 docs/api/openapi.yaml 一致。
 * 自动生成的前端类型从 OpenAPI 获取，本类不作为跨服务共享 DTO。
 */
public final class CourseDtos {
    private CourseDtos() {}

    public enum CourseLevel { BEGINNER, INTERMEDIATE, ADVANCED }

    public enum CourseAccessType { PUBLIC, ENROLLMENT_REQUIRED }

    public enum LessonType { VIDEO, ARTICLE, ASSESSMENT }

    public enum LessonMediaPurpose { LESSON_VIDEO, VIDEO_POSTER, SUBTITLE, COURSEWARE, ARTICLE_ATTACHMENT, ARTICLE_IMAGE }

    public enum LessonResourcePresentation { INLINE, DOWNLOAD }

    public enum CourseResourceAction { PREVIEW, DOWNLOAD }

    public enum PublicationStatus { DRAFT, PUBLISHED, UNPUBLISHED }

    public record CreateCourseRequest(
            @NotNull UUID clientRequestId,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 300) String summary,
            @NotNull @Size(max = 20000) String description,
            UUID coverMediaId,
            @NotBlank @Size(max = 80) String instructorDisplayName,
            @NotNull UUID categoryId,
            @NotNull CourseLevel level,
            @NotNull CourseAccessType accessType) {}

    public record UpdateCourseRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 300) String summary,
            @NotNull @Size(max = 20000) String description,
            UUID coverMediaId,
            @NotBlank @Size(max = 80) String instructorDisplayName,
            @NotNull UUID categoryId,
            @NotNull CourseLevel level,
            @NotNull CourseAccessType accessType) {}

    public record ReplaceCurriculumRequest(
            @PositiveOrZero long expectedRevision,
            @NotNull @Size(max = 100) List<@Valid ChapterInput> chapters) {}

    public record ChapterInput(
            @NotBlank @Size(max = 120) String title,
            @Positive @Max(100) int sequence,
            @NotNull @Size(max = 200) List<@Valid LessonInput> lessons) {}

    public record LessonInput(
            @NotBlank @Size(max = 120) String title,
            @NotNull LessonType type,
            @Positive @Max(200) int sequence,
            boolean previewable,
            String articleMarkdown,
            @Size(max = 100) List<@Valid LessonMediaResourceInput> resources) {
        /** 兼容既有 Java 测试夹具；HTTP 契约仍包含显式可空 articleMarkdown。 */
        public LessonInput(String title, LessonType type, int sequence, boolean previewable,
                List<LessonMediaResourceInput> resources) {
            this(title, type, sequence, previewable, null, resources);
        }
    }

    public record LessonMediaResourceInput(
            @NotNull UUID mediaId,
            @NotNull LessonMediaPurpose purpose,
            @Positive @Max(100) int sequence,
            @Size(max = 120) String displayName,
            LessonResourcePresentation presentation) {
        /** 兼容既有 Java 调用；服务端从媒体快照推导展示名与默认动作。 */
        public LessonMediaResourceInput(UUID mediaId, LessonMediaPurpose purpose, int sequence) {
            this(mediaId, purpose, sequence, null, null);
        }
    }

    public record UpdatePublicationRequest(
            @NotNull PublishTarget status,
            @PositiveOrZero long expectedRevision) {}

    public enum PublishTarget { PUBLISHED, UNPUBLISHED }

    public record CoursePage(List<CourseSummary> items, int page, int size, long total) {}

    public record CourseSummary(
            String id,
            String title,
            String summary,
            String coverMediaId,
            String coverUrl,
            TeacherSummary teacher,
            CategorySummary category,
            CourseLevel level,
            CourseAccessType accessType) {}

    public record CourseDetail(
            String id,
            String title,
            String summary,
            String description,
            String coverMediaId,
            String coverUrl,
            TeacherSummary teacher,
            CategorySummary category,
            CourseLevel level,
            CourseAccessType accessType,
            String publishedVersion,
            List<Chapter> chapters) {}

    public record TeacherSummary(String id, String displayName) {}

    public record CategorySummary(String id, String name) {}

    public record Chapter(String id, String title, int sequence, List<LessonSummary> lessons) {}

    public record LessonSummary(String id, String title, LessonType type, int sequence, boolean previewable,
            String articleMarkdown, List<LessonMediaResource> resources) {
        /** 兼容既有 Java 调用；公开投影与旧测试不携带正文。 */
        public LessonSummary(String id, String title, LessonType type, int sequence, boolean previewable,
                List<LessonMediaResource> resources) {
            this(id, title, type, sequence, previewable, null, resources);
        }
    }

    public record LessonMediaResource(String id, String mediaId, LessonMediaPurpose purpose, int sequence,
            String displayName, LessonResourcePresentation presentation, String originalFileName,
            String mimeType, long size) {}

    /** 短期 HLS 播放投影；URL 及请求头不得持久化、写事件或输出到普通日志。 */
    public record CourseMediaPlaybackGrant(String method, String url, Map<String, String> headers, Instant expiresAt) {}

    /** 课程域完成可见性与资源动作判断后返回的短期安全媒体投影。 */
    public record CourseMediaAccessGrant(String method, String url, Map<String, String> headers, Instant expiresAt) {}

    public record CreateCourseResourceAccessGrantRequest(@NotNull CourseResourceAction action) {}

    /** 受保护课时内容；附件仅暴露课程域 resourceId，浏览器不能凭 mediaId 申请授权。 */
    public record LessonContent(String courseId, String lessonId, String title, LessonType type,
            boolean previewable, String articleMarkdown, String playbackResourceId,
            CourseMediaAccessGrant poster, List<LessonInlineImage> inlineImages,
            List<LessonContentResource> resources) {}

    public record LessonInlineImage(String resourceId, String mediaId, CourseMediaAccessGrant grant) {}

    public record LessonContentResource(String id, LessonMediaPurpose purpose, String displayName,
            LessonResourcePresentation presentation, String originalFileName, String mimeType, long size,
            List<CourseResourceAction> actions) {}

    public record TeacherCourse(
            String id,
            PublicationStatus publicationStatus,
            boolean hasUnpublishedChanges,
            String publishedVersion,
            String draftVersion,
            String version,
            long revision,
            String title,
            String summary,
            String description,
            String coverMediaId,
            String coverUrl,
            String instructorDisplayName,
            CategorySummary category,
            CourseLevel level,
            CourseAccessType accessType,
            List<Chapter> chapters,
            Instant createdAt,
            Instant updatedAt) {}

    public record TeacherCoursePage(List<TeacherCourse> items, int page, int size, long total) {}

    public record CoursePublication(
            String id,
            PublicationStatus status,
            String publishedVersion,
            String draftVersion,
            boolean hasUnpublishedChanges,
            long revision,
            Instant updatedAt) {}
}
