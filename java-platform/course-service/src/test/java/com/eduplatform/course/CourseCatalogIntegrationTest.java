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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(CourseMediaTestConfiguration.class)
class CourseCatalogIntegrationTest {
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000009101";
    private static final String OTHER_TEACHER_ID = "00000000-0000-4000-8000-000000009102";
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-4000-8000-000000009501");

    @Autowired CourseCatalogService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired CourseMediaTestConfiguration.FakeMediaBindingClient mediaClient;

    @BeforeEach
    void resetData() {
        mediaClient.clear();
        jdbc.update("UPDATE course SET current_draft_version_id = NULL, published_version_id = NULL");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM lesson_media_resource");
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
    /** Given 教师自己的 READY 封面；When 绑定、替换和解除；Then 课程只保存 mediaId 并可靠记录对应引用事件。 */
    void coverBindingReplacementReleaseAndLegacyReadCompatibility() {
        UUID firstCover = UUID.randomUUID();
        UUID secondCover = UUID.randomUUID();
        mediaClient.register(firstCover.toString(), TEACHER_ID, "COURSE_COVER", "READY");
        mediaClient.register(secondCover.toString(), TEACHER_ID, "COURSE_COVER", "READY");

        CourseDtos.TeacherCourse created = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "媒体课程", firstCover)).course();
        assertThat(created.coverMediaId()).isEqualTo(firstCover.toString());
        assertThat(created.coverUrl()).isNull();
        assertOutboxCount("MediaReferenceBound", firstCover, 1);

        CourseDtos.TeacherCourse replaced = service.updateMetadata(
                created.id(), TEACHER_ID, updateRequest(created.revision(), "替换封面", secondCover));
        assertThat(replaced.coverMediaId()).isEqualTo(secondCover.toString());
        assertOutboxCount("MediaReferenceReleased", firstCover, 1);
        assertOutboxCount("MediaReferenceBound", secondCover, 1);

        CourseDtos.TeacherCourse cleared = service.updateMetadata(
                created.id(), TEACHER_ID, updateRequest(replaced.revision(), "解除封面", null));
        assertThat(cleared.coverMediaId()).isNull();
        assertOutboxCount("MediaReferenceReleased", secondCover, 1);

        jdbc.update("UPDATE course_version SET cover_url = ? WHERE id = ?",
                "https://legacy.example.test/cover.jpg", currentDraftVersionId(created.id()));
        assertThat(service.getTeacherCourse(created.id(), TEACHER_ID).coverUrl())
                .isEqualTo("https://legacy.example.test/cover.jpg");
    }

    @Test
    /** Given 非本人、用途错误、未就绪或不存在的资产；Then 课程写入在事务开始前统一拒绝。 */
    void rejectsForeignWrongPurposePendingAndMissingMedia() {
        UUID foreign = UUID.randomUUID();
        UUID wrongPurpose = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        mediaClient.register(foreign.toString(), OTHER_TEACHER_ID, "COURSE_COVER", "READY");
        mediaClient.register(wrongPurpose.toString(), TEACHER_ID, "LESSON_VIDEO", "READY");
        mediaClient.register(pending.toString(), TEACHER_ID, "COURSE_COVER", "PROCESSING");

        for (UUID mediaId : List.of(foreign, wrongPurpose, pending, UUID.randomUUID())) {
            assertThatThrownBy(() -> service.create(
                    TEACHER_ID, createRequest(UUID.randomUUID(), "拒绝媒体", mediaId)))
                    .isInstanceOfSatisfying(CourseException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(422);
                        assertThat(error.code()).isEqualTo("COURSE.MEDIA_NOT_BINDABLE");
                    });
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course", Integer.class)).isZero();
    }

    @Test
    /** Given 含视频资源的目录；When 首次保存并重放相同 revision；Then 引用只绑定一次且资源行不重复。 */
    void lessonVideoBindingAndRepeatedRequestAreIdempotent() {
        UUID video = UUID.randomUUID();
        mediaClient.register(video.toString(), TEACHER_ID, "LESSON_VIDEO", "READY");
        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "视频课程")).course();
        CourseDtos.ReplaceCurriculumRequest request = curriculum(draft.revision(), video);

        CourseDtos.TeacherCourse first = service.replaceCurriculum(draft.id(), TEACHER_ID, request);
        mediaClient.clear();
        CourseDtos.TeacherCourse repeated = service.replaceCurriculum(draft.id(), TEACHER_ID, request);

        assertThat(repeated.revision()).isEqualTo(first.revision());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_media_resource", Integer.class)).isEqualTo(1);
        assertOutboxCount("MediaReferenceBound", video, 1);
    }

    @Test
    /** Given v1 已发布；When v2 草稿替换封面；Then 公共课程仍引用 v1 封面，草稿引用独立演进。 */
    void publishedVersionCoverIsNotPollutedByDraftReplacement() {
        UUID publishedCover = UUID.randomUUID();
        UUID draftCover = UUID.randomUUID();
        mediaClient.register(publishedCover.toString(), TEACHER_ID, "COURSE_COVER", "READY");
        mediaClient.register(draftCover.toString(), TEACHER_ID, "COURSE_COVER", "READY");
        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "封面版本", publishedCover)).course();
        CourseDtos.TeacherCourse curriculum = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(draft.revision()));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, curriculum.revision()));

        CourseDtos.TeacherCourse edited = service.updateMetadata(
                draft.id(), TEACHER_ID, updateRequest(curriculum.revision(), "草稿封面", draftCover));

        assertThat(edited.coverMediaId()).isEqualTo(draftCover.toString());
        assertThat(service.getPublished(draft.id()).coverMediaId()).isEqualTo(publishedCover.toString());
        assertOutboxCount("MediaReferenceBound", publishedCover, 2);
        assertOutboxCount("MediaReferenceReleased", publishedCover, 1);
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
    /** Given 已发布 v1；When 教师编辑形成草稿；Then 公共目录仍读 v1，直到新草稿显式发布。 */
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
    /** Given 已保存目录与发布状态；When 重放相同写请求；Then ID/revision 稳定且不产生重复章节。 */
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
    /** Given 过期 revision 或不连续目录序号；Then 事务拒绝写入并保留当前课程快照。 */
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
                        "第二课", CourseDtos.LessonType.ARTICLE, 2, false, List.of())));
        assertThatThrownBy(() -> service.replaceCurriculum(
                draft.id(), TEACHER_ID,
                new CourseDtos.ReplaceCurriculumRequest(draft.revision(), List.of(invalid))))
                .isInstanceOfSatisfying(CourseException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));
    }

    private CourseDtos.CreateCourseRequest createRequest(UUID requestId, String title) {
        return createRequest(requestId, title, null);
    }

    private CourseDtos.CreateCourseRequest createRequest(UUID requestId, String title, UUID coverMediaId) {
        return new CourseDtos.CreateCourseRequest(
                requestId,
                title,
                "测试摘要",
                "## 测试课程",
                coverMediaId,
                "测试教师",
                CATEGORY_ID,
                CourseDtos.CourseLevel.BEGINNER,
                CourseDtos.CourseAccessType.PUBLIC);
    }

    private CourseDtos.UpdateCourseRequest updateRequest(long revision, String title) {
        return updateRequest(revision, title, null);
    }

    private CourseDtos.UpdateCourseRequest updateRequest(long revision, String title, UUID coverMediaId) {
        return new CourseDtos.UpdateCourseRequest(
                revision,
                title,
                "更新后的摘要",
                "## 更新后的课程",
                coverMediaId,
                "测试教师",
                CATEGORY_ID,
                CourseDtos.CourseLevel.INTERMEDIATE,
                CourseDtos.CourseAccessType.PUBLIC);
    }

    private CourseDtos.ReplaceCurriculumRequest curriculum(long revision) {
        return curriculum(revision, null);
    }

    private CourseDtos.ReplaceCurriculumRequest curriculum(long revision, UUID videoMediaId) {
        return new CourseDtos.ReplaceCurriculumRequest(
                revision,
                List.of(new CourseDtos.ChapterInput(
                        "第一章",
                        1,
                        List.of(new CourseDtos.LessonInput(
                                "第一课", CourseDtos.LessonType.VIDEO, 1, true,
                                videoMediaId == null ? List.of() : List.of(new CourseDtos.LessonMediaResourceInput(
                                        videoMediaId, CourseDtos.LessonMediaPurpose.LESSON_VIDEO, 1)))))));
    }

    private void assertOutboxCount(String eventType, UUID mediaId, int expected) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, eventType, mediaId.toString());
        assertThat(count).isEqualTo(expected);
    }

    private String currentDraftVersionId(String courseId) {
        return jdbc.queryForObject("SELECT current_draft_version_id FROM course WHERE id = ?",
                String.class, courseId);
    }
}
