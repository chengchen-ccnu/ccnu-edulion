package com.eduplatform.course.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CourseDtos {
    private static final String HTTP_URL = "https?://[^\\s]+";

    private CourseDtos() {}

    public enum CourseLevel { BEGINNER, INTERMEDIATE, ADVANCED }

    public enum CourseAccessType { PUBLIC, ENROLLMENT_REQUIRED }

    public enum LessonType { VIDEO, ARTICLE, ASSESSMENT }

    public enum PublicationStatus { DRAFT, PUBLISHED, UNPUBLISHED }

    public record CreateCourseRequest(
            @NotNull UUID clientRequestId,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 300) String summary,
            @NotNull @Size(max = 20000) String description,
            @NotBlank @Size(max = 512) @Pattern(regexp = HTTP_URL) String coverUrl,
            @NotBlank @Size(max = 80) String instructorDisplayName,
            @NotNull UUID categoryId,
            @NotNull CourseLevel level,
            @NotNull CourseAccessType accessType) {}

    public record UpdateCourseRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 300) String summary,
            @NotNull @Size(max = 20000) String description,
            @NotBlank @Size(max = 512) @Pattern(regexp = HTTP_URL) String coverUrl,
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
            boolean previewable) {}

    public record UpdatePublicationRequest(
            @NotNull PublishTarget status,
            @PositiveOrZero long expectedRevision) {}

    public enum PublishTarget { PUBLISHED, UNPUBLISHED }

    public record CoursePage(List<CourseSummary> items, int page, int size, long total) {}

    public record CourseSummary(
            String id,
            String title,
            String summary,
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

    public record LessonSummary(String id, String title, LessonType type, int sequence, boolean previewable) {}

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
            String coverUrl,
            String instructorDisplayName,
            CategorySummary category,
            CourseLevel level,
            CourseAccessType accessType,
            List<Chapter> chapters,
            Instant createdAt,
            Instant updatedAt) {}

    public record CoursePublication(
            String id,
            PublicationStatus status,
            String publishedVersion,
            String draftVersion,
            boolean hasUnpublishedChanges,
            long revision,
            Instant updatedAt) {}
}
