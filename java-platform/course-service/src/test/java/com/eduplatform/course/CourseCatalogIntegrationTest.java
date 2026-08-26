package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.persistence.EnrollmentAccessRepository;
import com.eduplatform.course.web.CourseDtos;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
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
    @Autowired EnrollmentAccessRepository enrollmentAccess;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired CourseMediaTestConfiguration.FakeMediaBindingClient mediaClient;
    @Autowired CourseMediaTestConfiguration.FakeMediaCoverClient coverClient;
    @Autowired CourseMediaTestConfiguration.FakeMediaPlaybackClient playbackClient;

    @BeforeEach
    void resetData() {
        mediaClient.clear();
        coverClient.clear();
        playbackClient.clear();
        jdbc.update("DELETE FROM consumed_learning_event");
        jdbc.update("DELETE FROM course_enrollment_access");
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
    /** Given 当前发布 PUBLIC 视频；When 学生按课程资源申请；Then 服务端解析绑定并把真实访问者交给媒体域。 */
    void publicPublishedVideoAllowsPlaybackButDraftHistoricalAndMismatchedResourcesDoNot() {
        String studentId = "00000000-0000-4000-8000-000000009201";
        UUID publishedVideo = UUID.randomUUID();
        UUID draftVideo = UUID.randomUUID();
        mediaClient.register(publishedVideo.toString(), TEACHER_ID, "LESSON_VIDEO", "READY");
        mediaClient.register(draftVideo.toString(), TEACHER_ID, "LESSON_VIDEO", "READY");

        CourseDtos.TeacherCourse draft = service.create(
                TEACHER_ID, createRequest(UUID.randomUUID(), "公开视频", null)).course();
        CourseDtos.TeacherCourse withVideo = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(draft.revision(), publishedVideo));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, withVideo.revision()));
        CourseDtos.CourseDetail published = service.getPublished(draft.id());
        CourseDtos.LessonSummary publishedLesson = published.chapters().get(0).lessons().get(0);
        CourseDtos.LessonMediaResource publishedResource = publishedLesson.resources().get(0);

        CourseDtos.CourseMediaPlaybackGrant grant = service.createPublishedPlaybackGrant(
                draft.id(), publishedLesson.id(), publishedResource.id(), studentId, "trace-public");
        assertThat(grant.method()).isEqualTo("GET");
        assertThat(playbackClient.lastRequest()).satisfies(request -> {
            assertThat(request.mediaId()).isEqualTo(publishedVideo.toString());
            assertThat(request.purpose()).isEqualTo("LESSON_VIDEO");
            assertThat(request.visitorId()).isEqualTo(studentId);
        });

        CourseDtos.TeacherCourse editable = service.getTeacherCourse(draft.id(), TEACHER_ID);
        CourseDtos.TeacherCourse changed = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(editable.revision(), draftVideo));
        CourseDtos.LessonSummary draftLesson = changed.chapters().get(0).lessons().get(0);
        CourseDtos.LessonMediaResource draftResource = draftLesson.resources().get(0);

        assertThatThrownBy(() -> service.createPublishedPlaybackGrant(
                draft.id(), draftLesson.id(), draftResource.id(), studentId, "trace-draft"))
                .isInstanceOfSatisfying(CourseException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE.NOT_FOUND"));
        assertThatThrownBy(() -> service.createTeacherPlaybackGrant(
                draft.id(), draftLesson.id(), draftResource.id(), OTHER_TEACHER_ID, "trace-other"))
                .isInstanceOfSatisfying(CourseException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE.NOT_FOUND"));
        assertThat(service.createTeacherPlaybackGrant(
                draft.id(), draftLesson.id(), draftResource.id(), TEACHER_ID, "trace-owner").method())
                .isEqualTo("GET");
        assertThat(service.createPublishedPlaybackGrant(
                draft.id(), publishedLesson.id(), publishedResource.id(), studentId, "trace-current-live").method())
                .isEqualTo("GET");
        assertThatThrownBy(() -> service.createPublishedPlaybackGrant(
                draft.id(), publishedLesson.id(), draftResource.id(), studentId, "trace-mismatch"))
                .isInstanceOfSatisfying(CourseException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE.NOT_FOUND"));

        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, changed.revision()));
        assertThatThrownBy(() -> service.createPublishedPlaybackGrant(
                draft.id(), publishedLesson.id(), publishedResource.id(), studentId, "trace-historical"))
                .isInstanceOfSatisfying(CourseException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE.NOT_FOUND"));
        assertThat(service.createPublishedPlaybackGrant(
                draft.id(), draftLesson.id(), draftResource.id(), studentId, "trace-new-live").method())
                .isEqualTo("GET");
    }

    @Test
    /** Given 报名制课程；Then 非试看课时只允许 ACTIVE 本地报名投影，取消与乱序事件不能误授权。 */
    void enrollmentRequiredCourseUsesIdempotentLocalEnrollmentProjection() {
        String studentId = "00000000-0000-4000-8000-000000009201";
        UUID video = UUID.randomUUID();
        mediaClient.register(video.toString(), TEACHER_ID, "LESSON_VIDEO", "READY");
        CourseDtos.TeacherCourse draft = service.create(TEACHER_ID,
                createRequest(UUID.randomUUID(), "报名课程", null,
                        CourseDtos.CourseAccessType.ENROLLMENT_REQUIRED)).course();
        CourseDtos.TeacherCourse nonPreview = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(draft.revision(), video, false));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, nonPreview.revision()));
        CourseDtos.LessonSummary lesson = service.getPublished(draft.id()).chapters().get(0).lessons().get(0);
        CourseDtos.LessonMediaResource resource = lesson.resources().get(0);

        assertThatThrownBy(() -> service.createPublishedPlaybackGrant(
                draft.id(), lesson.id(), resource.id(), studentId, "trace-enrollment"))
                .isInstanceOfSatisfying(CourseException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("COURSE.ENROLLMENT_REQUIRED");
                });

        Instant activeAt = Instant.now();
        enrollmentAccess.apply("00000000-0000-4000-8000-000000001001",
                "00000000-0000-4000-8000-000000001101", studentId, draft.id(), "ACTIVE", activeAt, activeAt);
        // 重放相同 eventId 以及更旧的取消事件都不能覆盖 ACTIVE 投影。
        enrollmentAccess.apply("00000000-0000-4000-8000-000000001001",
                "00000000-0000-4000-8000-000000001101", studentId, draft.id(), "CANCELED",
                activeAt.plusSeconds(30), activeAt.plusSeconds(30));
        enrollmentAccess.apply("00000000-0000-4000-8000-000000001002",
                "00000000-0000-4000-8000-000000001101", studentId, draft.id(), "CANCELED",
                activeAt.minusSeconds(1), activeAt.plusSeconds(30));
        assertThat(service.createPublishedPlaybackGrant(
                draft.id(), lesson.id(), resource.id(), studentId, "trace-active-enrollment").method()).isEqualTo("GET");

        enrollmentAccess.apply("00000000-0000-4000-8000-000000001003",
                "00000000-0000-4000-8000-000000001101", studentId, draft.id(), "CANCELED",
                activeAt.plusSeconds(1), activeAt.plusSeconds(31));
        assertThatThrownBy(() -> service.createPublishedPlaybackGrant(
                draft.id(), lesson.id(), resource.id(), studentId, "trace-canceled-enrollment"))
                .isInstanceOfSatisfying(CourseException.class,
                        exception -> assertThat(exception.code()).isEqualTo("COURSE.ENROLLMENT_REQUIRED"));

        CourseDtos.TeacherCourse editable = service.getTeacherCourse(draft.id(), TEACHER_ID);
        CourseDtos.TeacherCourse previewDraft = service.replaceCurriculum(
                draft.id(), TEACHER_ID, curriculum(editable.revision(), video, true));
        service.updatePublication(draft.id(), TEACHER_ID,
                new CourseDtos.UpdatePublicationRequest(CourseDtos.PublishTarget.PUBLISHED, previewDraft.revision()));
        CourseDtos.LessonSummary previewLesson = service.getPublished(draft.id()).chapters().get(0).lessons().get(0);
        assertThat(service.createPublishedPlaybackGrant(draft.id(), previewLesson.id(),
                previewLesson.resources().get(0).id(), studentId, "trace-preview").method()).isEqualTo("GET");
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
        assertThat(created.coverUrl()).isEqualTo("https://oss.example.test/temporary-cover/" + firstCover);
        assertThat(coverClient.lastRequest().viewerId()).isEqualTo(TEACHER_ID);
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
        CourseDtos.CourseDetail published = service.getPublished(draft.id());
        assertThat(published.coverMediaId()).isEqualTo(publishedCover.toString());
        assertThat(published.coverUrl()).isEqualTo("https://oss.example.test/temporary-cover/" + publishedCover);
        assertThat(coverClient.lastRequest().viewerId()).isEqualTo("00000000-0000-0000-0000-000000000000");
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
        return createRequest(requestId, title, coverMediaId, CourseDtos.CourseAccessType.PUBLIC);
    }

    private CourseDtos.CreateCourseRequest createRequest(UUID requestId, String title, UUID coverMediaId,
            CourseDtos.CourseAccessType accessType) {
        return new CourseDtos.CreateCourseRequest(
                requestId,
                title,
                "测试摘要",
                "## 测试课程",
                coverMediaId,
                "测试教师",
                CATEGORY_ID,
                CourseDtos.CourseLevel.BEGINNER,
                accessType);
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
        return curriculum(revision, videoMediaId, true);
    }

    private CourseDtos.ReplaceCurriculumRequest curriculum(long revision, UUID videoMediaId, boolean previewable) {
        return new CourseDtos.ReplaceCurriculumRequest(
                revision,
                List.of(new CourseDtos.ChapterInput(
                        "第一章",
                        1,
                        List.of(new CourseDtos.LessonInput(
                                "第一课", CourseDtos.LessonType.VIDEO, 1, previewable,
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
