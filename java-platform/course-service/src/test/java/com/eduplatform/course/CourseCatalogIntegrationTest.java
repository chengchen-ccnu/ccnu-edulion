package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.web.CourseDtos;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CourseCatalogIntegrationTest {
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000009101";
    private static final String OTHER_TEACHER_ID = "00000000-0000-4000-8000-000000009102";
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-4000-8000-000000009501");

    @Autowired CourseCatalogService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @BeforeEach
    void resetData() {
        jdbc.update("UPDATE course SET current_draft_version_id = NULL, published_version_id = NULL");
        jdbc.update("DELETE FROM lesson");
        jdbc.update("DELETE FROM chapter");
        jdbc.update("DELETE FROM course_version");
        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM category");
        jdbc.update("""
                INSERT INTO category (id, code, name, status)
                VALUES (?, 'MATH_TEST', '数学测试分类', 'ACTIVE')
                """, CATEGORY_ID.toString());
    }

    @Test
    void flywayMyBatisPublicCatalogSupportsFiltersPaginationAndDetail() {
        flyway.validate();
        assertThat(service.listActiveCategories()).singleElement()
                .satisfies(category -> assertThat(category.id()).isEqualTo(CATEGORY_ID.toString()));
        CourseDtos.TeacherCourse draft = service.create(TEACHER_ID, createRequest(UUID.randomUUID(), "代数基础"))
                .course();
        CourseDtos.TeacherCourse withCurriculum = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(draft.revision()));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(
                        CourseDtos.PublishTarget.PUBLISHED, withCurriculum.revision()));

        CourseDtos.CoursePage matching = service.listPublished(
                "代数", CATEGORY_ID.toString(), "BEGINNER", "PUBLIC", 1, 10);
        assertThat(matching.total()).isEqualTo(1);
        assertThat(matching.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(draft.id());
                    assertThat(item.teacher().id()).isEqualTo(TEACHER_ID);
                    assertThat(item.category().name()).isEqualTo("数学测试分类");
                });
        assertThat(service.listPublished(null, null, "ADVANCED", null, 1, 10).total()).isZero();

        CourseDtos.CourseDetail detail = service.getPublished(draft.id());
        assertThat(detail.publishedVersion()).isEqualTo("v1");
        assertThat(detail.chapters()).singleElement()
                .satisfies(chapter -> assertThat(chapter.lessons()).singleElement()
                        .satisfies(lesson -> assertThat(lesson.previewable()).isTrue()));
    }

    @Test
    void teacherCreateIsIdempotentAndOwnershipIsEnforced() {
        UUID requestId = UUID.randomUUID();
        CourseCatalogService.CreateResult created = service.create(
                TEACHER_ID, createRequest(requestId, "幂等课程"));
        CourseCatalogService.CreateResult repeated = service.create(
                TEACHER_ID, createRequest(requestId, "客户端重试时忽略的新标题"));

        assertThat(created.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.course().id()).isEqualTo(created.course().id());
        assertThat(repeated.course().title()).isEqualTo("幂等课程");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> service.getTeacherCourse(created.course().id(), OTHER_TEACHER_ID))
                .isInstanceOfSatisfying(CourseException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("COURSE.NOT_FOUND");
                });

        service.create(OTHER_TEACHER_ID, createRequest(UUID.randomUUID(), "其他教师课程"));
        CourseDtos.TeacherCoursePage page = service.listTeacherCourses(TEACHER_ID, 1, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(course -> assertThat(course.id()).isEqualTo(created.course().id()));
    }

    @Test
    void publishedVersionRemainsImmutableUntilNewDraftIsPublished() {
        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "线上标题")).course();
        CourseDtos.TeacherCourse catalog = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(draft.revision()));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, catalog.revision()));

        CourseDtos.UpdateCourseRequest metadataUpdate = updateRequest(catalog.revision(), "草稿新标题");
        CourseDtos.TeacherCourse edited = service.updateMetadata(draft.id(), TEACHER_ID, metadataUpdate);
        CourseDtos.TeacherCourse editRetry = service.updateMetadata(draft.id(), TEACHER_ID, metadataUpdate);
        assertThat(edited.version()).isEqualTo("v2");
        assertThat(edited.hasUnpublishedChanges()).isTrue();
        assertThat(editRetry.revision()).isEqualTo(edited.revision());
        assertThat(service.getPublished(draft.id()).title()).isEqualTo("线上标题");

        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, edited.revision()));
        assertThat(service.getPublished(draft.id()).title()).isEqualTo("草稿新标题");
        assertThat(jdbc.queryForList(
                "SELECT version_label, lifecycle_status FROM course_version ORDER BY version_number"))
                .extracting(row -> row.get("VERSION_LABEL"), row -> row.get("LIFECYCLE_STATUS"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("v1", "ARCHIVED"),
                        org.assertj.core.groups.Tuple.tuple("v2", "PUBLISHED"));
    }

    @Test
    void curriculumReplacementAndPublicationStatusAreIdempotent() {
        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "状态课程")).course();
        CourseDtos.ReplaceCurriculumRequest request = curriculum(draft.revision());
        CourseDtos.TeacherCourse first = service.replaceCurriculum(draft.id(), TEACHER_ID, request);
        List<String> lessonIds = first.chapters().stream()
                .flatMap(chapter -> chapter.lessons().stream())
                .map(CourseDtos.LessonSummary::id)
                .toList();

        CourseDtos.TeacherCourse repeated = service.replaceCurriculum(
                draft.id(), TEACHER_ID, request);
        assertThat(repeated.revision()).isEqualTo(first.revision());
        assertThat(repeated.chapters().stream()
                .flatMap(chapter -> chapter.lessons().stream())
                .map(CourseDtos.LessonSummary::id)).containsExactlyElementsOf(lessonIds);

        CourseDtos.CoursePublication published = service.updatePublication(
                draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, first.revision()));
        CourseDtos.CoursePublication publishRetry = service.updatePublication(
                draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, first.revision()));
        assertThat(publishRetry.status()).isEqualTo(CourseDtos.PublicationStatus.PUBLISHED);
        assertThat(publishRetry.publishedVersion()).isEqualTo(published.publishedVersion());

        CourseDtos.CoursePublication unpublished = service.updatePublication(
                draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.UNPUBLISHED, first.revision()));
        assertThat(unpublished.status()).isEqualTo(CourseDtos.PublicationStatus.UNPUBLISHED);
        assertThatThrownBy(() -> service.getPublished(draft.id()))
                .isInstanceOfSatisfying(CourseException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void rejectsStaleRevisionAndInvalidCurriculumSequence() {
        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "并发课程")).course();
        assertThatThrownBy(() -> service.updatePublication(
                draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, draft.revision())))
                .isInstanceOfSatisfying(CourseException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("COURSE.PUBLISH_VALIDATION_FAILED");
                });
        assertThatThrownBy(() -> service.updateMetadata(
                draft.id(), TEACHER_ID, updateRequest(99, "过期写入")))
                .isInstanceOfSatisfying(CourseException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("COURSE.VERSION_CONFLICT");
                });

        CourseDtos.ChapterInput invalid = new CourseDtos.ChapterInput(
                "第二章", 2, List.of(new CourseDtos.LessonInput(
                        "第二课", CourseDtos.LessonType.ARTICLE, 2, false)));
        assertThatThrownBy(() -> service.replaceCurriculum(
                draft.id(), TEACHER_ID,
                new CourseDtos.ReplaceCurriculumRequest(draft.revision(), List.of(invalid))))
                .isInstanceOfSatisfying(CourseException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));
    }

    private CourseDtos.CreateCourseRequest createRequest(UUID requestId, String title) {
        return new CourseDtos.CreateCourseRequest(
                requestId,
                title,
                "测试摘要",
                "## 测试课程",
                "https://cdn.example.test/courses/test-cover.jpg",
                "测试教师",
                CATEGORY_ID,
                CourseDtos.CourseLevel.BEGINNER,
                CourseDtos.CourseAccessType.PUBLIC);
    }

    private CourseDtos.UpdateCourseRequest updateRequest(long revision, String title) {
        return new CourseDtos.UpdateCourseRequest(
                revision,
                title,
                "更新后的摘要",
                "## 更新后的课程",
                "https://cdn.example.test/courses/test-cover-v2.jpg",
                "测试教师",
                CATEGORY_ID,
                CourseDtos.CourseLevel.INTERMEDIATE,
                CourseDtos.CourseAccessType.PUBLIC);
    }

    private CourseDtos.ReplaceCurriculumRequest curriculum(long revision) {
        return new CourseDtos.ReplaceCurriculumRequest(
                revision,
                List.of(new CourseDtos.ChapterInput(
                        "第一章",
                        1,
                        List.of(new CourseDtos.LessonInput(
                                "第一课", CourseDtos.LessonType.VIDEO, 1, true)))));
    }
}
