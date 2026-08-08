package com.eduplatform.course.service;

import com.eduplatform.course.domain.ChapterEntity;
import com.eduplatform.course.domain.CourseEntity;
import com.eduplatform.course.domain.CourseListRow;
import com.eduplatform.course.domain.CourseMediaAccessRow;
import com.eduplatform.course.domain.CourseVersionEntity;
import com.eduplatform.course.domain.LessonEntity;
import com.eduplatform.course.domain.LessonMediaResourceEntity;
import com.eduplatform.course.media.MediaBindingClient;
import com.eduplatform.course.media.MediaCoverClient;
import com.eduplatform.course.media.MediaPlaybackClient;
import com.eduplatform.course.messaging.CourseMediaEventFactory;
import com.eduplatform.course.persistence.CourseMapper;
import com.eduplatform.course.web.CourseDtos;
import com.eduplatform.shared.api.FieldError;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
/**
 * 提供公开课程目录读取和教师课程草稿、版本、目录及发布状态管理。
 *
 * <p>所有写操作限定在 course-service Schema 内；教师身份由控制器传入并在查询条件中再次
 * 约束。已发布版本保持只读，后续编辑通过复制新草稿完成，不拥有封面媒体对象。</p>
 */
public class CourseCatalogService {
    private static final String PUBLIC_COVER_VIEWER_ID = "00000000-0000-0000-0000-000000000000";
    private final CourseMapper mapper;
    private final ObjectMapper objectMapper;
    private final MediaBindingClient mediaBindingClient;
    private final MediaCoverClient mediaCoverClient;
    private final MediaPlaybackClient mediaPlaybackClient;
    private final CourseMediaEventFactory mediaEvents;

    public CourseCatalogService(CourseMapper mapper, ObjectMapper objectMapper,
            MediaBindingClient mediaBindingClient, MediaCoverClient mediaCoverClient,
            MediaPlaybackClient mediaPlaybackClient,
            CourseMediaEventFactory mediaEvents) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.mediaBindingClient = mediaBindingClient;
        this.mediaCoverClient = mediaCoverClient;
        this.mediaPlaybackClient = mediaPlaybackClient;
        this.mediaEvents = mediaEvents;
    }

    @Transactional(readOnly = true)
    /** 按可选筛选条件分页读取已发布课程，不返回草稿或已下架版本。 */
    public CourseDtos.CoursePage listPublished(String keyword, String categoryId, String level,
            String accessType, int page, int size) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        long total = mapper.countPublished(normalizedKeyword, categoryId, level, accessType);
        List<CourseDtos.CourseSummary> items = mapper.listPublished(normalizedKeyword, categoryId, level,
                        accessType, ((long) page - 1L) * size, size).stream()
                .map(this::toSummary)
                .toList();
        return new CourseDtos.CoursePage(items, page, size, total);
    }

    @Transactional(readOnly = true)
    /**
     * 读取单门课程当前公开版本及目录。
     * @throws CourseException 课程不存在、未发布或已下架时统一按不可见处理
     */
    public CourseDtos.CourseDetail getPublished(String courseId) {
        CourseVersionEntity version = mapper.findPublicVersion(courseId);
        if (version == null) {
            throw notFound();
        }
        return new CourseDtos.CourseDetail(
                version.getCourseId(),
                version.getTitle(),
                version.getSummary(),
                version.getDescription(),
                version.getCoverMediaId(),
                coverUrl(version.getCoverMediaId(), version.getCoverUrl(), PUBLIC_COVER_VIEWER_ID, newTraceId()),
                new CourseDtos.TeacherSummary(version.getOwnerTeacherId(), version.getInstructorDisplayName()),
                new CourseDtos.CategorySummary(version.getCategoryId(), version.getCategoryName()),
                CourseDtos.CourseLevel.valueOf(version.getLevel()),
                CourseDtos.CourseAccessType.valueOf(version.getAccessType()),
                version.getVersionLabel(),
                curriculum(version.getId()));
    }

    /**
     * 为当前发布版本中的视频资源申请播放授权。
     *
     * <p>PUBLIC 课程允许已登录用户播放；ENROLLMENT_REQUIRED 课程在 T11 前只允许试看课时。
     * 课程、课时、资源和版本不匹配统一按不可见处理。通过后才调用 media-service，且不会
     * 把短期 URL 写入课程数据库、Outbox 或日志。</p>
     *
     * @param courseId 浏览器请求的课程 ID
     * @param lessonId 浏览器请求的课时 ID
     * @param resourceId 浏览器请求的课程域资源行 ID，而非 mediaId
     * @param visitorId Gateway 验证后的访问者 ID
     * @param traceId 当前请求追踪 ID
     * @return 短期 hls-v1 播放授权
     * @throws CourseException 资源不可见、尚需报名或媒体未就绪时抛出
     */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaPlaybackGrant createPublishedPlaybackGrant(
            String courseId, String lessonId, String resourceId, String visitorId, String traceId) {
        CourseMediaAccessRow access = mapper.findPublishedMediaAccess(courseId, lessonId, resourceId);
        requireVideoResource(access);
        if (!"PUBLIC".equals(access.getAccessType()) && !access.isPreviewable()) {
            throw new CourseException(HttpStatus.FORBIDDEN, "COURSE.ENROLLMENT_REQUIRED",
                    "该课时需要报名后观看");
        }
        return mediaPlaybackClient.issuePlayback(
                access.getMediaId(), access.getPurpose(), visitorId, traceId);
    }

    /**
     * 为课程所有者预览当前草稿或未发布版本中的视频资源申请授权。
     *
     * @param courseId 教师拥有的课程 ID
     * @param lessonId 当前可编辑版本中的课时 ID
     * @param resourceId 当前可编辑版本中的课程域资源行 ID
     * @param teacherId Gateway 验证且具备 TEACHER 角色的用户 ID
     * @param traceId 当前请求追踪 ID
     * @return 短期 hls-v1 owner 预览授权
     * @throws CourseException 非所有者、历史资源或非视频用途统一按不可见处理
     */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaPlaybackGrant createTeacherPlaybackGrant(
            String courseId, String lessonId, String resourceId, String teacherId, String traceId) {
        CourseMediaAccessRow access = mapper.findOwnedEditableMediaAccess(
                courseId, lessonId, resourceId, teacherId);
        requireVideoResource(access);
        return mediaPlaybackClient.issuePlayback(
                access.getMediaId(), access.getPurpose(), teacherId, traceId);
    }

    @Transactional(readOnly = true)
    /** @return 可用于课程编辑与筛选的启用分类，不包含已停用分类。 */
    public List<CourseDtos.CategorySummary> listActiveCategories() {
        return mapper.listActiveCategories().stream()
                .map(category -> new CourseDtos.CategorySummary(category.getId(), category.getName()))
                .toList();
    }

    @Transactional
    /**
     * 创建课程聚合及首个 v1 草稿。
     *
     * @param teacherId 当前教师用户 ID，同时成为课程所有者
     * @param request 带客户端请求 ID 的课程元数据
     * @return 课程快照和是否本次新建；同一教师重复请求返回既有课程
     */
    public CreateResult create(String teacherId, CourseDtos.CreateCourseRequest request) {
        return create(teacherId, request, newTraceId());
    }

    /** 与 HTTP Trace 关联地创建课程；媒体校验成功后才写课程版本与同事务 Outbox。 */
    @Transactional
    public CreateResult create(String teacherId, CourseDtos.CreateCourseRequest request, String traceId) {
        CourseEntity existing = mapper.findByOwnerAndRequest(teacherId, request.clientRequestId().toString());
        if (existing != null) {
            return new CreateResult(toTeacherCourse(existing, traceId), false);
        }
        requireActiveCategory(request.categoryId().toString());
        String coverMediaId = id(request.coverMediaId());
        requireBindable(coverMediaId, "COURSE_COVER", teacherId, traceId);

        CourseEntity course = new CourseEntity();
        course.setId(UUID.randomUUID().toString());
        course.setCreateRequestId(request.clientRequestId().toString());
        course.setOwnerTeacherId(teacherId);
        course.setPublicationStatus(CourseDtos.PublicationStatus.DRAFT.name());
        course.setRowVersion(0);
        mapper.insertCourse(course);
        CourseEntity winner = mapper.findByOwnerAndRequest(teacherId, request.clientRequestId().toString());
        if (!course.getId().equals(winner.getId())) {
            return new CreateResult(toTeacherCourse(winner, traceId), false);
        }

        CourseVersionEntity version = new CourseVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setCourseId(course.getId());
        version.setVersionNumber(1);
        version.setVersionLabel("v1");
        version.setLifecycleStatus("DRAFT");
        applyMetadata(version, request.title(), request.summary(), request.description(), coverMediaId, null,
                request.instructorDisplayName(), request.categoryId().toString(),
                request.level().name(), request.accessType().name());
        version.setRowVersion(0);
        mapper.insertCourseVersion(version);
        mapper.setDraftVersion(course.getId(), version.getId());
        bindCover(version, traceId);

        CourseEntity created = requireOwnedCourse(course.getId(), teacherId, false);
        return new CreateResult(toTeacherCourse(created, traceId), true);
    }

    @Transactional(readOnly = true)
    /** 读取当前教师拥有的课程编辑快照；他人课程按不存在处理。 */
    public CourseDtos.TeacherCourse getTeacherCourse(String courseId, String teacherId) {
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
    }

    @Transactional(readOnly = true)
    /** 分页读取当前教师自己的课程，包含草稿和发布状态但不跨教师泄露数据。 */
    public CourseDtos.TeacherCoursePage listTeacherCourses(String teacherId, int page, int size) {
        long total = mapper.countOwnedCourses(teacherId);
        List<CourseDtos.TeacherCourse> items = mapper.listOwnedCourses(
                        teacherId, ((long) page - 1L) * size, size).stream()
                .map(this::toTeacherCourse)
                .toList();
        return new CourseDtos.TeacherCoursePage(items, page, size, total);
    }

    @Transactional
    /**
     * 以乐观版本号更新课程元数据；已发布课程会先复制为新草稿，原公开版本不变。
     *
     * <p>内容完全相同视为幂等成功；版本号落后时抛出冲突并回滚事务。</p>
     */
    public CourseDtos.TeacherCourse updateMetadata(String courseId, String teacherId,
            CourseDtos.UpdateCourseRequest request) {
        return updateMetadata(courseId, teacherId, request, newTraceId());
    }

    /** 更新课程元数据与封面引用；替换/解除事件和版本修改在同一课程事务中提交。 */
    @Transactional
    public CourseDtos.TeacherCourse updateMetadata(String courseId, String teacherId,
            CourseDtos.UpdateCourseRequest request, String traceId) {
        requireActiveCategory(request.categoryId().toString());
        CourseEntity course = requireOwnedCourse(courseId, teacherId, true);
        CourseVersionEntity draft = ensureDraft(course, traceId);
        if (metadataEquals(draft, request)) {
            return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false), traceId);
        }
        String nextCoverMediaId = id(request.coverMediaId());
        if (!Objects.equals(draft.getCoverMediaId(), nextCoverMediaId)) {
            requireBindable(nextCoverMediaId, "COURSE_COVER", teacherId, traceId);
        }
        CourseVersionEntity updated = new CourseVersionEntity();
        updated.setId(draft.getId());
        String compatibleCoverUrl = nextCoverMediaId == null ? draft.getCoverUrl() : null;
        applyMetadata(updated, request.title(), request.summary(), request.description(),
                nextCoverMediaId, compatibleCoverUrl,
                request.instructorDisplayName(), request.categoryId().toString(),
                request.level().name(), request.accessType().name());
        if (mapper.updateMetadata(updated, request.expectedRevision()) != 1) {
            throw versionConflict();
        }
        if (!Objects.equals(draft.getCoverMediaId(), nextCoverMediaId)) {
            releaseCover(draft, traceId);
            bindCover(updated, traceId);
        }
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false), traceId);
    }

    @Transactional
    /**
     * 原子替换草稿的整套章节课时，并用规范化内容哈希识别重复请求。
     *
     * <p>先校验序号连续性，再锁定所属课程并预占乐观版本；任一插入失败会回滚删除与新增。</p>
     */
    public CourseDtos.TeacherCourse replaceCurriculum(String courseId, String teacherId,
            CourseDtos.ReplaceCurriculumRequest request) {
        return replaceCurriculum(courseId, teacherId, request, newTraceId());
    }

    /** 在写课程目录前验证全部媒体，并把资源替换及引用 Outbox 原子提交。 */
    @Transactional
    public CourseDtos.TeacherCourse replaceCurriculum(String courseId, String teacherId,
            CourseDtos.ReplaceCurriculumRequest request, String traceId) {
        validateSequences(request.chapters());
        CourseEntity course = requireOwnedCourse(courseId, teacherId, true);
        CourseVersionEntity draft = ensureDraft(course, traceId);
        String hash = curriculumHash(request.chapters());
        if (Objects.equals(hash, draft.getCurriculumHash())) {
            return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false), traceId);
        }
        validateLessonMedia(request.chapters(), teacherId, traceId);
        if (draft.getRowVersion() != request.expectedRevision()) {
            throw versionConflict();
        }
        if (mapper.reserveCurriculum(draft.getId(), hash, request.expectedRevision()) != 1) {
            throw versionConflict();
        }
        releaseLessonMedia(draft.getId(), traceId);
        mapper.deleteLessonMediaByVersion(draft.getId());
        mapper.deleteLessonsByVersion(draft.getId());
        mapper.deleteChaptersByVersion(draft.getId());
        insertCurriculum(draft.getId(), request.chapters(), traceId);
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false), traceId);
    }

    @Transactional
    /**
     * 在发布或下架目标间转换课程状态。
     *
     * <p>发布要求非空目录并把草稿固化为公开版本；下架保留版本数据但停止公开读取。请求携带
     * 的 revision 过期时不改变状态并返回冲突。</p>
     */
    public CourseDtos.CoursePublication updatePublication(String courseId, String teacherId,
            CourseDtos.UpdatePublicationRequest request) {
        CourseEntity course = requireOwnedCourse(courseId, teacherId, true);
        String editableVersionId = course.getCurrentDraftVersionId() != null
                ? course.getCurrentDraftVersionId() : course.getPublishedVersionId();
        if (editableVersionId == null) {
            throw notFound();
        }
        CourseVersionEntity editable = mapper.findVersion(editableVersionId);
        if (editable.getRowVersion() != request.expectedRevision()) {
            throw versionConflict();
        }

        if (request.status() == CourseDtos.PublishTarget.PUBLISHED) {
            publish(course, editable);
        } else {
            unpublish(course);
        }
        CourseEntity current = requireOwnedCourse(courseId, teacherId, false);
        CourseVersionEntity currentVersion = mapper.findVersion(current.getCurrentDraftVersionId() != null
                ? current.getCurrentDraftVersionId() : current.getPublishedVersionId());
        return toPublication(current, currentVersion);
    }

    /** 校验目录后把当前草稿固化为发布版本，并归档先前发布版本；调用方事务负责整体回滚。 */
    private void publish(CourseEntity course, CourseVersionEntity editable) {
        if (course.getCurrentDraftVersionId() != null) {
            if (mapper.countChapters(editable.getId()) == 0 || mapper.countEmptyChapters(editable.getId()) > 0) {
                throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "COURSE.PUBLISH_VALIDATION_FAILED", "每门发布课程至少需要一个非空章节",
                        List.of(new FieldError("chapters", "至少创建一个章节，且每章至少包含一个课时")));
            }
            if (course.getPublishedVersionId() != null
                    && !course.getPublishedVersionId().equals(editable.getId())) {
                mapper.archiveVersion(course.getPublishedVersionId());
            }
            mapper.publishVersion(editable.getId());
            mapper.publishDraft(course.getId(), editable.getId());
        } else {
            mapper.updatePublicationStatus(course.getId(), CourseDtos.PublicationStatus.PUBLISHED.name());
        }
    }

    /** 将已发布课程切为不可公开读取；版本及目录仍保留，未发布课程重复下架返回冲突。 */
    private void unpublish(CourseEntity course) {
        if (course.getPublishedVersionId() == null) {
            throw new CourseException(HttpStatus.CONFLICT, "COURSE.NOT_PUBLISHED", "课程尚未发布，无法下架");
        }
        mapper.updatePublicationStatus(course.getId(), CourseDtos.PublicationStatus.UNPUBLISHED.name());
    }

    /** 返回现有草稿，或从已发布版本完整复制新草稿；没有任何可复制版本时按不存在处理。 */
    private CourseVersionEntity ensureDraft(CourseEntity course, String traceId) {
        if (course.getCurrentDraftVersionId() != null) {
            return mapper.findVersion(course.getCurrentDraftVersionId());
        }
        if (course.getPublishedVersionId() == null) {
            throw notFound();
        }
        CourseVersionEntity source = mapper.findVersion(course.getPublishedVersionId());
        CourseVersionEntity draft = copyVersion(source);
        mapper.insertCourseVersion(draft);
        bindCover(draft, traceId);
        copyCurriculum(source.getId(), draft.getId(), traceId);
        mapper.setDraftVersion(course.getId(), draft.getId());
        return mapper.findVersion(draft.getId());
    }

    private CourseVersionEntity copyVersion(CourseVersionEntity source) {
        CourseVersionEntity copy = new CourseVersionEntity();
        copy.setId(UUID.randomUUID().toString());
        copy.setCourseId(source.getCourseId());
        copy.setVersionNumber(source.getVersionNumber() + 1);
        copy.setVersionLabel("v" + copy.getVersionNumber());
        copy.setLifecycleStatus("DRAFT");
        applyMetadata(copy, source.getTitle(), source.getSummary(), source.getDescription(),
                source.getCoverMediaId(), source.getCoverUrl(),
                source.getInstructorDisplayName(), source.getCategoryId(), source.getLevel(), source.getAccessType());
        copy.setCurriculumHash(source.getCurriculumHash());
        copy.setRowVersion(source.getRowVersion());
        return copy;
    }

    /** 复制章节课时并生成新 ID，使后续草稿编辑不会修改已发布目录。 */
    private void copyCurriculum(String sourceVersionId, String targetVersionId, String traceId) {
        for (ChapterEntity sourceChapter : mapper.listChapters(sourceVersionId)) {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(UUID.randomUUID().toString());
            chapter.setCourseVersionId(targetVersionId);
            chapter.setTitle(sourceChapter.getTitle());
            chapter.setPosition(sourceChapter.getPosition());
            mapper.insertChapter(chapter);
            for (LessonEntity sourceLesson : mapper.listLessons(sourceChapter.getId())) {
                LessonEntity lesson = new LessonEntity();
                lesson.setId(UUID.randomUUID().toString());
                lesson.setChapterId(chapter.getId());
                lesson.setTitle(sourceLesson.getTitle());
                lesson.setLessonType(sourceLesson.getLessonType());
                lesson.setPosition(sourceLesson.getPosition());
                lesson.setPreviewable(sourceLesson.isPreviewable());
                mapper.insertLesson(lesson);
                for (LessonMediaResourceEntity sourceResource : mapper.listLessonMediaResources(sourceLesson.getId())) {
                    LessonMediaResourceEntity resource = new LessonMediaResourceEntity();
                    resource.setId(UUID.randomUUID().toString());
                    resource.setLessonId(lesson.getId());
                    resource.setMediaId(sourceResource.getMediaId());
                    resource.setPurpose(sourceResource.getPurpose());
                    resource.setPosition(sourceResource.getPosition());
                    mapper.insertLessonMediaResource(resource);
                    reference(CourseMediaEventFactory.BOUND, resource.getMediaId(),
                            "LESSON_RESOURCE", resource.getId(), traceId);
                }
            }
        }
    }

    private void insertCurriculum(String versionId, List<CourseDtos.ChapterInput> inputs, String traceId) {
        for (CourseDtos.ChapterInput chapterInput : inputs) {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(UUID.randomUUID().toString());
            chapter.setCourseVersionId(versionId);
            chapter.setTitle(chapterInput.title().trim());
            chapter.setPosition(chapterInput.sequence());
            mapper.insertChapter(chapter);
            for (CourseDtos.LessonInput lessonInput : chapterInput.lessons()) {
                LessonEntity lesson = new LessonEntity();
                lesson.setId(UUID.randomUUID().toString());
                lesson.setChapterId(chapter.getId());
                lesson.setTitle(lessonInput.title().trim());
                lesson.setLessonType(lessonInput.type().name());
                lesson.setPosition(lessonInput.sequence());
                lesson.setPreviewable(lessonInput.previewable());
                mapper.insertLesson(lesson);
                for (CourseDtos.LessonMediaResourceInput input : resources(lessonInput)) {
                    LessonMediaResourceEntity resource = new LessonMediaResourceEntity();
                    resource.setId(UUID.randomUUID().toString());
                    resource.setLessonId(lesson.getId());
                    resource.setMediaId(input.mediaId().toString());
                    resource.setPurpose(input.purpose().name());
                    resource.setPosition(input.sequence());
                    mapper.insertLessonMediaResource(resource);
                    reference(CourseMediaEventFactory.BOUND, resource.getMediaId(),
                            "LESSON_RESOURCE", resource.getId(), traceId);
                }
            }
        }
    }

    private CourseDtos.TeacherCourse toTeacherCourse(CourseEntity course) {
        return toTeacherCourse(course, newTraceId());
    }

    /** 将稳定封面 ID 投影为本次响应使用的短期 URL；签名结果不回写课程实体。 */
    private CourseDtos.TeacherCourse toTeacherCourse(CourseEntity course, String traceId) {
        String versionId = course.getCurrentDraftVersionId() != null
                ? course.getCurrentDraftVersionId() : course.getPublishedVersionId();
        if (versionId == null) {
            throw notFound();
        }
        CourseVersionEntity version = mapper.findVersion(versionId);
        return new CourseDtos.TeacherCourse(
                course.getId(),
                CourseDtos.PublicationStatus.valueOf(course.getPublicationStatus()),
                course.getCurrentDraftVersionId() != null,
                label(course.getPublishedVersionId()),
                label(course.getCurrentDraftVersionId()),
                version.getVersionLabel(),
                version.getRowVersion(),
                version.getTitle(),
                version.getSummary(),
                version.getDescription(),
                version.getCoverMediaId(),
                coverUrl(version.getCoverMediaId(), version.getCoverUrl(), course.getOwnerTeacherId(), traceId),
                version.getInstructorDisplayName(),
                new CourseDtos.CategorySummary(version.getCategoryId(), version.getCategoryName()),
                CourseDtos.CourseLevel.valueOf(version.getLevel()),
                CourseDtos.CourseAccessType.valueOf(version.getAccessType()),
                curriculum(version.getId()),
                course.getCreatedAt(),
                version.getUpdatedAt());
    }

    private CourseDtos.CoursePublication toPublication(CourseEntity course, CourseVersionEntity version) {
        return new CourseDtos.CoursePublication(
                course.getId(),
                CourseDtos.PublicationStatus.valueOf(course.getPublicationStatus()),
                label(course.getPublishedVersionId()),
                label(course.getCurrentDraftVersionId()),
                course.getCurrentDraftVersionId() != null,
                version.getRowVersion(),
                course.getUpdatedAt());
    }

    private String label(String versionId) {
        return versionId == null ? null : mapper.findVersion(versionId).getVersionLabel();
    }

    private List<CourseDtos.Chapter> curriculum(String versionId) {
        List<CourseDtos.Chapter> chapters = new ArrayList<>();
        for (ChapterEntity chapter : mapper.listChapters(versionId)) {
            List<CourseDtos.LessonSummary> lessons = mapper.listLessons(chapter.getId()).stream()
                    .map(lesson -> new CourseDtos.LessonSummary(
                            lesson.getId(),
                            lesson.getTitle(),
                            CourseDtos.LessonType.valueOf(lesson.getLessonType()),
                            lesson.getPosition(),
                            lesson.isPreviewable(),
                            mapper.listLessonMediaResources(lesson.getId()).stream()
                                    .map(resource -> new CourseDtos.LessonMediaResource(
                                            resource.getId(), resource.getMediaId(),
                                            CourseDtos.LessonMediaPurpose.valueOf(resource.getPurpose()),
                                            resource.getPosition()))
                                    .toList()))
                    .toList();
            chapters.add(new CourseDtos.Chapter(
                    chapter.getId(), chapter.getTitle(), chapter.getPosition(), lessons));
        }
        return List.copyOf(chapters);
    }

    private CourseDtos.CourseSummary toSummary(CourseListRow row) {
        return new CourseDtos.CourseSummary(
                row.getId(),
                row.getTitle(),
                row.getSummary(),
                row.getCoverMediaId(),
                coverUrl(row.getCoverMediaId(), row.getCoverUrl(), PUBLIC_COVER_VIEWER_ID, newTraceId()),
                new CourseDtos.TeacherSummary(row.getTeacherId(), row.getTeacherDisplayName()),
                new CourseDtos.CategorySummary(row.getCategoryId(), row.getCategoryName()),
                CourseDtos.CourseLevel.valueOf(row.getLevel()),
                CourseDtos.CourseAccessType.valueOf(row.getAccessType()));
    }

    private CourseEntity requireOwnedCourse(String courseId, String teacherId, boolean lock) {
        CourseEntity course = lock
                ? mapper.lockOwnedCourse(courseId, teacherId)
                : mapper.findOwnedCourse(courseId, teacherId);
        if (course == null) {
            throw notFound();
        }
        return course;
    }

    /** 新媒体引用优先生成授权投影；没有 mediaId 时才读取迁移期历史 URL。 */
    private String coverUrl(String mediaId, String legacyUrl, String viewerId, String traceId) {
        return mediaId == null ? legacyUrl : mediaCoverClient.issueCover(mediaId, viewerId, traceId);
    }

    /** 只允许绑定用途为 LESSON_VIDEO 的精确资源进入播放链路，其他情况统一为不可见。 */
    private void requireVideoResource(CourseMediaAccessRow access) {
        if (access == null || !"LESSON_VIDEO".equals(access.getPurpose())) {
            throw notFound();
        }
    }

    private void requireActiveCategory(String categoryId) {
        if (mapper.countActiveCategory(categoryId) == 0) {
            throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "COURSE.CATEGORY_NOT_FOUND", "课程分类不存在或已停用",
                    List.of(new FieldError("categoryId", "课程分类不存在或已停用")));
        }
    }

    /** 校验章节和各章课时序号唯一且从 1 连续，失败时在任何数据库写入前终止。 */
    private void validateSequences(List<CourseDtos.ChapterInput> chapters) {
        Set<Integer> chapterSequences = new HashSet<>();
        for (CourseDtos.ChapterInput chapter : chapters) {
            if (!chapterSequences.add(chapter.sequence())) {
                throw curriculumSequenceError("chapters", "章节序号不能重复");
            }
            Set<Integer> lessonSequences = new HashSet<>();
            for (CourseDtos.LessonInput lesson : chapter.lessons()) {
                if (!lessonSequences.add(lesson.sequence())) {
                    throw curriculumSequenceError("chapters.lessons", "同一章节内的课时序号不能重复");
                }
                validateLessonResourceSequences(lesson);
            }
            requireContiguous(lessonSequences, chapter.lessons().size(), "chapters.lessons", "课时序号必须从 1 连续排列");
        }
        requireContiguous(chapterSequences, chapters.size(), "chapters", "章节序号必须从 1 连续排列");
    }

    /** 校验课时类型、资源用途、重复 mediaId 与同用途连续序号，避免把未定义策略写入课程表。 */
    private void validateLessonResourceSequences(CourseDtos.LessonInput lesson) {
        Map<CourseDtos.LessonMediaPurpose, Set<Integer>> positions = new java.util.EnumMap<>(
                CourseDtos.LessonMediaPurpose.class);
        Set<UUID> mediaIds = new HashSet<>();
        for (CourseDtos.LessonMediaResourceInput resource : resources(lesson)) {
            if (!allowed(lesson.type(), resource.purpose())) {
                throw curriculumSequenceError("chapters.lessons.resources", "课时类型与媒体用途不匹配");
            }
            if (!mediaIds.add(resource.mediaId())) {
                throw curriculumSequenceError("chapters.lessons.resources", "同一课时不能重复绑定同一媒体");
            }
            Set<Integer> purposePositions = positions.computeIfAbsent(resource.purpose(), key -> new HashSet<>());
            if (!purposePositions.add(resource.sequence())) {
                throw curriculumSequenceError("chapters.lessons.resources", "同一用途的资源序号不能重复");
            }
        }
        for (Map.Entry<CourseDtos.LessonMediaPurpose, Set<Integer>> entry : positions.entrySet()) {
            requireContiguous(entry.getValue(), entry.getValue().size(), "chapters.lessons.resources",
                    "同一用途的资源序号必须从 1 连续排列");
            if (Set.of(CourseDtos.LessonMediaPurpose.LESSON_VIDEO, CourseDtos.LessonMediaPurpose.VIDEO_POSTER)
                    .contains(entry.getKey()) && entry.getValue().size() > 1) {
                throw curriculumSequenceError("chapters.lessons.resources", "每个课时最多绑定一个视频或海报");
            }
        }
    }

    private boolean allowed(CourseDtos.LessonType lessonType, CourseDtos.LessonMediaPurpose purpose) {
        return switch (lessonType) {
            case VIDEO -> Set.of(CourseDtos.LessonMediaPurpose.LESSON_VIDEO,
                    CourseDtos.LessonMediaPurpose.VIDEO_POSTER,
                    CourseDtos.LessonMediaPurpose.SUBTITLE,
                    CourseDtos.LessonMediaPurpose.COURSEWARE).contains(purpose);
            case ARTICLE -> Set.of(CourseDtos.LessonMediaPurpose.COURSEWARE,
                    CourseDtos.LessonMediaPurpose.ARTICLE_ATTACHMENT).contains(purpose);
            case ASSESSMENT -> false;
        };
    }

    /** 在任何课程目录写入前逐项调用 media-service，拒绝他人、错用途、未就绪或不存在资产。 */
    private void validateLessonMedia(List<CourseDtos.ChapterInput> chapters, String teacherId, String traceId) {
        for (CourseDtos.ChapterInput chapter : chapters) {
            for (CourseDtos.LessonInput lesson : chapter.lessons()) {
                for (CourseDtos.LessonMediaResourceInput resource : resources(lesson)) {
                    mediaBindingClient.requireBindable(resource.mediaId().toString(), resource.purpose().name(),
                            teacherId, traceId);
                }
            }
        }
    }

    private void requireContiguous(Set<Integer> values, int size, String field, String reason) {
        for (int sequence = 1; sequence <= size; sequence++) {
            if (!values.contains(sequence)) {
                throw curriculumSequenceError(field, reason);
            }
        }
    }

    private CourseException curriculumSequenceError(String field, String reason) {
        return new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                "COMMON.VALIDATION_FAILED", "请求参数不合法", List.of(new FieldError(field, reason)));
    }

    /** 对 OpenAPI 顺序化目录生成稳定摘要，用于识别完整目录替换的重复请求。 */
    private String curriculumHash(List<CourseDtos.ChapterInput> chapters) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(chapters);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void applyMetadata(CourseVersionEntity version, String title, String summary, String description,
            String coverMediaId, String coverUrl, String instructorDisplayName, String categoryId,
            String level, String accessType) {
        version.setTitle(title.trim());
        version.setSummary(summary.trim());
        version.setDescription(description);
        version.setCoverMediaId(coverMediaId);
        version.setCoverUrl(coverUrl);
        version.setInstructorDisplayName(instructorDisplayName.trim());
        version.setCategoryId(categoryId);
        version.setLevel(level);
        version.setAccessType(accessType);
    }

    private boolean metadataEquals(CourseVersionEntity version, CourseDtos.UpdateCourseRequest request) {
        return Objects.equals(version.getTitle(), request.title().trim())
                && Objects.equals(version.getSummary(), request.summary().trim())
                && Objects.equals(version.getDescription(), request.description())
                && Objects.equals(version.getCoverMediaId(), id(request.coverMediaId()))
                && Objects.equals(version.getInstructorDisplayName(), request.instructorDisplayName().trim())
                && Objects.equals(version.getCategoryId(), request.categoryId().toString())
                && Objects.equals(version.getLevel(), request.level().name())
                && Objects.equals(version.getAccessType(), request.accessType().name());
    }

    private void requireBindable(String mediaId, String purpose, String teacherId, String traceId) {
        if (mediaId != null) mediaBindingClient.requireBindable(mediaId, purpose, teacherId, traceId);
    }

    private void bindCover(CourseVersionEntity version, String traceId) {
        if (version.getCoverMediaId() != null) {
            reference(CourseMediaEventFactory.BOUND, version.getCoverMediaId(),
                    "COURSE_VERSION_COVER", version.getId(), traceId);
        }
    }

    private void releaseCover(CourseVersionEntity version, String traceId) {
        if (version.getCoverMediaId() != null) {
            reference(CourseMediaEventFactory.RELEASED, version.getCoverMediaId(),
                    "COURSE_VERSION_COVER", version.getId(), traceId);
        }
    }

    private void releaseLessonMedia(String versionId, String traceId) {
        for (LessonMediaResourceEntity resource : mapper.listVersionMediaResources(versionId)) {
            reference(CourseMediaEventFactory.RELEASED, resource.getMediaId(),
                    "LESSON_RESOURCE", resource.getId(), traceId);
        }
    }

    private void reference(String eventType, String mediaId, String referenceType,
            String referenceId, String traceId) {
        mapper.insertOutbox(mediaEvents.create(eventType, mediaId, referenceType, referenceId, traceId));
    }

    private static List<CourseDtos.LessonMediaResourceInput> resources(CourseDtos.LessonInput lesson) {
        return lesson.resources() == null ? List.of() : lesson.resources();
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private CourseException notFound() {
        return new CourseException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在或不可见");
    }

    private CourseException versionConflict() {
        return new CourseException(HttpStatus.CONFLICT, "COURSE.VERSION_CONFLICT", "课程已被更新，请刷新后重试");
    }

    /** 课程创建结果；created=false 表示命中同一教师/clientRequestId 的既有幂等结果。 */
    public record CreateResult(CourseDtos.TeacherCourse course, boolean created) {}
}
