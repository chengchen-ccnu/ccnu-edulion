package com.eduplatform.course.service;

import com.eduplatform.course.domain.ChapterEntity;
import com.eduplatform.course.domain.CourseEntity;
import com.eduplatform.course.domain.CourseListRow;
import com.eduplatform.course.domain.CourseMediaAccessRow;
import com.eduplatform.course.domain.CourseVersionEntity;
import com.eduplatform.course.domain.LessonEntity;
import com.eduplatform.course.domain.LessonMediaResourceEntity;
import com.eduplatform.course.media.MediaAccessClient;
import com.eduplatform.course.media.MediaBindingClient;
import com.eduplatform.course.media.MediaBindingClient.BindingSnapshot;
import com.eduplatform.course.media.MediaCoverClient;
import com.eduplatform.course.media.MediaPlaybackClient;
import com.eduplatform.course.messaging.CourseMediaEventFactory;
import com.eduplatform.course.messaging.CourseAgentIndexEventFactory;
import com.eduplatform.course.messaging.CourseLessonContentEventFactory;
import com.eduplatform.course.persistence.CourseMapper;
import com.eduplatform.course.persistence.EnrollmentAccessRepository;
import com.eduplatform.course.web.CourseDtos;
import com.eduplatform.shared.api.FieldError;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
    @org.springframework.beans.factory.annotation.Autowired
    private CourseSuspensionService suspension;
    private final CourseMapper mapper;
    private final ObjectMapper objectMapper;
    private final MediaBindingClient mediaBindingClient;
    private final MediaCoverClient mediaCoverClient;
    private final MediaPlaybackClient mediaPlaybackClient;
    private final MediaAccessClient mediaAccessClient;
    private final CourseMediaEventFactory mediaEvents;
    private final CourseAgentIndexEventFactory agentIndexEvents;
    private final CourseLessonContentEventFactory lessonContentEvents;
    private final EnrollmentAccessRepository enrollmentAccess;
    private final ControlledMarkdownValidator markdownValidator;

    public CourseCatalogService(CourseMapper mapper, ObjectMapper objectMapper,
            MediaBindingClient mediaBindingClient, MediaCoverClient mediaCoverClient,
            MediaPlaybackClient mediaPlaybackClient, MediaAccessClient mediaAccessClient,
            CourseMediaEventFactory mediaEvents, CourseAgentIndexEventFactory agentIndexEvents,
            CourseLessonContentEventFactory lessonContentEvents,
            EnrollmentAccessRepository enrollmentAccess,
            ControlledMarkdownValidator markdownValidator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.mediaBindingClient = mediaBindingClient;
        this.mediaCoverClient = mediaCoverClient;
        this.mediaPlaybackClient = mediaPlaybackClient;
        this.mediaAccessClient = mediaAccessClient;
        this.mediaEvents = mediaEvents;
        this.agentIndexEvents = agentIndexEvents;
        this.lessonContentEvents = lessonContentEvents;
        this.enrollmentAccess = enrollmentAccess;
        this.markdownValidator = markdownValidator;
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
        suspension.requireActive(courseId);
        CourseVersionEntity version = mapper.findPublicVersion(courseId);
        if (version == null) {
            throw notFound();
        }
        return new CourseDtos.CourseDetail(
                version.getCourseId(),
                version.getTitle(),
                version.getSummary(),
                version.getDescription(),
                null,
                coverUrl(version.getCoverMediaId(), version.getCoverUrl(), PUBLIC_COVER_VIEWER_ID, newTraceId()),
                new CourseDtos.TeacherSummary(version.getOwnerTeacherId(), version.getInstructorDisplayName()),
                new CourseDtos.CategorySummary(version.getCategoryId(), version.getCategoryName()),
                CourseDtos.CourseLevel.valueOf(version.getLevel()),
                CourseDtos.CourseAccessType.valueOf(version.getAccessType()),
                version.getVersionLabel(),
                version.getVersionNumber(),
                curriculum(version.getId(), false));
    }

    /**
     * 为当前发布版本中的视频资源申请播放授权。
     *
     * <p>PUBLIC 课程允许已登录用户播放；ENROLLMENT_REQUIRED 课程允许试看课时或本地报名投影为 ACTIVE 的学习者。
     * 课程、课时、资源和版本不匹配统一按不可见处理。通过后才调用 media-service，且不会
     * 把短期 URL 写入课程数据库、Outbox 或日志。</p>
     *
     * @param courseId 浏览器请求的课程 ID
     * @param lessonId 浏览器请求的课时 ID
     * @param resourceId 浏览器请求的课程域资源行 ID，而非 mediaId
     * @param visitorId Gateway 验证后的访问者 ID
     * @param traceId 当前请求追踪 ID
     * @return 优先使用 fMP4 v2、旧资产有限回退 v1 的短期 HLS 播放授权
     * @throws CourseException 资源不可见、尚需报名或媒体未就绪时抛出
     */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaPlaybackGrant createPublishedPlaybackGrant(
            String courseId, String lessonId, String resourceId, String visitorId, String traceId) {
        suspension.requireActive(courseId);
        CourseMediaAccessRow access = mapper.findPublishedMediaAccess(courseId, lessonId, resourceId);
        requireVideoResource(access);
        if (!"PUBLIC".equals(access.getAccessType()) && !access.isPreviewable()
                && !enrollmentAccess.hasActive(visitorId, courseId)) {
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
     * @return 优先使用 fMP4 v2、旧资产有限回退 v1 的短期 HLS owner 预览授权
     * @throws CourseException 非所有者、历史资源或非视频用途统一按不可见处理
     */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaPlaybackGrant createTeacherPlaybackGrant(
            String courseId, String lessonId, String resourceId, String teacherId, String traceId) {
        requireOwnedCourse(courseId, teacherId, false);
        CourseMediaAccessRow access = mapper.findOwnedEditableMediaAccess(
                courseId, lessonId, resourceId, teacherId);
        requireVideoResource(access);
        return mediaPlaybackClient.issuePlayback(
                access.getMediaId(), access.getPurpose(), teacherId, traceId);
    }

    /**
     * 返回发布课时的受保护内容投影。PUBLIC、有效报名或登录试看可访问；试看仍要求控制器提供登录身份。
     * 正文图片与海报直接返回短期授权，资料只返回课程域资源 ID 和允许动作。
     */
    @Transactional(readOnly = true)
    public CourseDtos.LessonContent getPublishedLessonContent(
            String courseId, String lessonId, String visitorId, String traceId) {
        suspension.requireActive(courseId);
        List<CourseMediaAccessRow> rows = mapper.listPublishedLessonContentRows(courseId, lessonId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        CourseMediaAccessRow lesson = rows.get(0);
        requirePublishedLessonAccess(lesson, visitorId);
        List<CourseMediaAccessRow> resources = rows.stream().filter(row -> row.getResourceId() != null).toList();
        String playbackResourceId = resources.stream()
                .filter(row -> "LESSON_VIDEO".equals(row.getPurpose()))
                .map(CourseMediaAccessRow::getResourceId).findFirst().orElse(null);
        CourseDtos.CourseMediaAccessGrant poster = effectivePoster(resources, visitorId, traceId);
        List<CourseDtos.LessonInlineImage> images = resources.stream()
                .filter(row -> "ARTICLE_IMAGE".equals(row.getPurpose()))
                .map(row -> new CourseDtos.LessonInlineImage(row.getResourceId(), row.getMediaId(),
                        mediaAccessClient.issue(row.getMediaId(), row.getPurpose(), visitorId,
                                "COURSE_ARTICLE_IMAGE", "ORIGINAL", "INLINE", traceId)))
                .toList();
        List<CourseDtos.LessonContentResource> attachments = resources.stream()
                .filter(row -> Set.of("COURSEWARE", "ARTICLE_ATTACHMENT").contains(row.getPurpose()))
                .map(this::contentResource).toList();
        return new CourseDtos.LessonContent(courseId, lesson.getVersionId(), lessonId,
                lesson.getContentRevisionId(), lesson.getLessonTitle(),
                CourseDtos.LessonType.valueOf(lesson.getLessonType()), lesson.isPreviewable(),
                "ARTICLE".equals(lesson.getLessonType()) ? lesson.getArticleMarkdown() : null,
                playbackResourceId, poster, images, attachments);
    }

    /**
     * 为课程 owner 重新进入编辑器时返回当前可编辑 ARTICLE 正文及实际引用图片的新短期授权。
     *
     * <p>输入只有课程与课时 ID；服务端先用 owner 条件解析当前草稿（无草稿时为当前发布编辑快照），
     * 再重新解析保存的 Markdown。每个引用必须在同课时全部资源中恰好出现一次且用途为
     * ARTICLE_IMAGE，未引用绑定不会进入响应。任一绑定缺失、重复或错用途时整次读取以安全冲突失败，
     * 不会把浏览器提供的任意 mediaId 交给 media-service。媒体域随后独立复核精确用途与 READY，
     * 并只返回 ORIGINAL/INLINE 短期授权。</p>
     *
     * @param courseId 当前教师拥有的课程 ID
     * @param lessonId 当前可编辑版本中的 ARTICLE 课时 ID
     * @param teacherId Gateway 验证后的教师 ID，同时用于 owner 查询与媒体访问审计
     * @param traceId 当前请求追踪 ID
     * @return 已保存 Markdown 和与其引用集合一一对应的新短期图片授权
     * @throws CourseException 非 owner、历史/错课时、非 ARTICLE 返回不可枚举 404；内容绑定不一致或媒体非 READY 拒绝返回
     */
    @Transactional(readOnly = true)
    public CourseDtos.TeacherArticleContent getTeacherArticleContent(
            String courseId, String lessonId, String teacherId, String traceId) {
        requireOwnedCourse(courseId, teacherId, false);
        List<CourseMediaAccessRow> rows = mapper.listOwnedEditableLessonContentRows(
                courseId, lessonId, teacherId);
        if (rows.isEmpty() || !"ARTICLE".equals(rows.get(0).getLessonType())) {
            throw notFound();
        }
        String markdown = rows.get(0).getArticleMarkdown();
        Set<String> references = parseSavedArticleReferences(markdown);
        List<CourseMediaAccessRow> resources = rows.stream()
                .filter(row -> row.getResourceId() != null)
                .toList();
        List<CourseMediaAccessRow> referencedBindings = references.stream()
                .sorted()
                .map(mediaId -> requireUniqueArticleImage(resources, mediaId))
                .toList();
        List<CourseDtos.TeacherArticleInlineImage> images = referencedBindings.stream()
                .map(row -> new CourseDtos.TeacherArticleInlineImage(row.getMediaId(),
                        mediaAccessClient.issue(row.getMediaId(), row.getPurpose(), teacherId,
                                "COURSE_ARTICLE_IMAGE", "ORIGINAL", "INLINE", traceId)))
                .toList();
        return new CourseDtos.TeacherArticleContent(courseId, lessonId,
                markdown == null ? "" : markdown, images);
    }

    /** 已保存正文理论上已通过写校验；若数据异常，读取使用安全冲突而非回显解析细节。 */
    private Set<String> parseSavedArticleReferences(String markdown) {
        try {
            return markdownValidator.validate(markdown);
        } catch (CourseException exception) {
            throw contentInconsistent();
        }
    }

    /** 在同课时全部用途中确认 mediaId 只有一条绑定且用途精确，防止错用途或重复行被授权。 */
    private CourseMediaAccessRow requireUniqueArticleImage(List<CourseMediaAccessRow> resources, String mediaId) {
        List<CourseMediaAccessRow> matches = resources.stream()
                .filter(row -> mediaId.equals(row.getMediaId()))
                .toList();
        if (matches.size() != 1 || !"ARTICLE_IMAGE".equals(matches.get(0).getPurpose())) {
            throw contentInconsistent();
        }
        return matches.get(0);
    }

    private static CourseException contentInconsistent() {
        return new CourseException(HttpStatus.CONFLICT, "COURSE.CONTENT_INCONSISTENT",
                "课程正文资源状态冲突，请刷新或重新保存后重试");
    }

    /** 按课程域 resourceId 和动作签发发布资源授权；浏览器请求体不能携带 mediaId、用途或 disposition。 */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaAccessGrant createPublishedResourceGrant(String courseId, String lessonId,
            String resourceId, CourseDtos.CourseResourceAction action, String visitorId, String traceId) {
        suspension.requireActive(courseId);
        CourseMediaAccessRow row = mapper.findPublishedMediaAccess(courseId, lessonId, resourceId);
        if (row == null) throw notFound();
        requirePublishedLessonAccess(row, visitorId);
        return issueResourceAction(row, action, visitorId, traceId);
    }

    /** 课程所有者可为当前可编辑版本的资料申请预览或下载授权。 */
    @Transactional(readOnly = true)
    public CourseDtos.CourseMediaAccessGrant createTeacherResourceGrant(String courseId, String lessonId,
            String resourceId, CourseDtos.CourseResourceAction action, String teacherId, String traceId) {
        requireOwnedCourse(courseId, teacherId, false);
        CourseMediaAccessRow row = mapper.findOwnedEditableMediaAccess(courseId, lessonId, resourceId, teacherId);
        if (row == null) throw notFound();
        return issueResourceAction(row, action, teacherId, traceId);
    }

    private void requirePublishedLessonAccess(CourseMediaAccessRow row, String visitorId) {
        if (!"PUBLIC".equals(row.getAccessType()) && !row.isPreviewable()
                && !enrollmentAccess.hasActive(visitorId, row.getCourseId())) {
            throw new CourseException(HttpStatus.FORBIDDEN, "COURSE.ENROLLMENT_REQUIRED", "该课时需要报名后访问");
        }
    }

    private CourseDtos.CourseMediaAccessGrant effectivePoster(
            List<CourseMediaAccessRow> resources, String visitorId, String traceId) {
        CourseMediaAccessRow custom = resources.stream().filter(row -> "VIDEO_POSTER".equals(row.getPurpose()))
                .findFirst().orElse(null);
        if (custom != null) {
            return mediaAccessClient.issue(custom.getMediaId(), custom.getPurpose(), visitorId,
                    "COURSE_POSTER", "ORIGINAL", "INLINE", traceId);
        }
        CourseMediaAccessRow video = resources.stream().filter(row -> "LESSON_VIDEO".equals(row.getPurpose()))
                .findFirst().orElse(null);
        if (video == null || video.getVariantNames() == null
                || !Set.of(video.getVariantNames().split(",")).contains("poster-v1")) {
            return null;
        }
        return mediaAccessClient.issue(video.getMediaId(), video.getPurpose(), visitorId,
                "COURSE_POSTER", "poster-v1", "INLINE", traceId);
    }

    private CourseDtos.LessonContentResource contentResource(CourseMediaAccessRow row) {
        return new CourseDtos.LessonContentResource(row.getResourceId(),
                CourseDtos.LessonMediaPurpose.valueOf(row.getPurpose()), row.getDisplayName(),
                CourseDtos.LessonResourcePresentation.valueOf(row.getPresentation()), row.getOriginalFileName(),
                row.getMimeType(), row.getSizeBytes(), allowedActions(row.getMimeType()));
    }

    private CourseDtos.CourseMediaAccessGrant issueResourceAction(CourseMediaAccessRow row,
            CourseDtos.CourseResourceAction action, String visitorId, String traceId) {
        if (!Set.of("COURSEWARE", "ARTICLE_ATTACHMENT").contains(row.getPurpose())
                || !allowedActions(row.getMimeType()).contains(action)) {
            throw notFound();
        }
        return mediaAccessClient.issue(row.getMediaId(), row.getPurpose(), visitorId,
                action == CourseDtos.CourseResourceAction.PREVIEW ? "COURSE_RESOURCE_PREVIEW" : "COURSE_RESOURCE_DOWNLOAD",
                "ORIGINAL", action == CourseDtos.CourseResourceAction.PREVIEW ? "INLINE" : "ATTACHMENT", traceId);
    }

    private static List<CourseDtos.CourseResourceAction> allowedActions(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        if (mime.equals("application/pdf") || mime.startsWith("image/")) {
            return List.of(CourseDtos.CourseResourceAction.PREVIEW, CourseDtos.CourseResourceAction.DOWNLOAD);
        }
        return List.of(CourseDtos.CourseResourceAction.DOWNLOAD);
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
        Map<String, BindingSnapshot> snapshots = validateLessonMedia(request.chapters(), teacherId, traceId);
        if (draft.getRowVersion() != request.expectedRevision()) {
            throw versionConflict();
        }
        if (mapper.reserveCurriculum(draft.getId(), hash, request.expectedRevision()) != 1) {
            throw versionConflict();
        }
        ExistingCurriculum existing = indexCurriculum(draft.getId());
        releaseRemovedLessonMedia(existing, request.chapters(), traceId);
        mapper.deleteLessonMediaByVersion(draft.getId());
        mapper.deleteLessonsByVersion(draft.getId());
        mapper.deleteChaptersByVersion(draft.getId());
        insertCurriculum(draft.getId(), request.chapters(), snapshots, existing, traceId,
                request.lessonIdentityMode() == CourseDtos.LessonIdentityMode.EXPLICIT);
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
        if (suspension.lockTeacher(teacherId).paused()) throw new CourseSuspendedException(suspension.availability(courseId, 0).supportEmail());
        String editableVersionId = course.getCurrentDraftVersionId() != null
                ? course.getCurrentDraftVersionId() : course.getPublishedVersionId();
        if (editableVersionId == null) {
            throw notFound();
        }
        CourseVersionEntity editable = mapper.findVersion(editableVersionId);
        if (editable.getRowVersion() != request.expectedRevision()) {
            throw versionConflict();
        }

        String traceId = newTraceId();
        if (request.status() == CourseDtos.PublishTarget.PUBLISHED) {
            publish(course, editable, teacherId, traceId);
        } else {
            unpublish(course, traceId);
        }
        CourseEntity current = requireOwnedCourse(courseId, teacherId, false);
        CourseVersionEntity currentVersion = mapper.findVersion(current.getCurrentDraftVersionId() != null
                ? current.getCurrentDraftVersionId() : current.getPublishedVersionId());
        return toPublication(current, currentVersion);
    }

    /** 校验目录后把当前草稿固化为发布版本，并归档先前发布版本；调用方事务负责整体回滚。 */
    private void publish(CourseEntity course, CourseVersionEntity editable, String teacherId, String traceId) {
        String previousPublishedVersionId = course.getPublishedVersionId();
        if (course.getCurrentDraftVersionId() != null) {
            if (mapper.countChapters(editable.getId()) == 0 || mapper.countEmptyChapters(editable.getId()) > 0) {
                throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "COURSE.PUBLISH_VALIDATION_FAILED", "每门发布课程至少需要一个非空章节",
                        List.of(new FieldError("chapters", "至少创建一个章节，且每章至少包含一个课时")));
            }
            validatePublishableLessons(editable.getId(), teacherId, traceId);
            if (course.getPublishedVersionId() != null
                    && !course.getPublishedVersionId().equals(editable.getId())) {
                mapper.archiveVersion(course.getPublishedVersionId());
            }
            mapper.publishVersion(editable.getId());
            mapper.publishDraft(course.getId(), editable.getId());
            emitLessonContentEvent(course.getId(), editable, traceId);
            emitPortalEvent(course.getId(), traceId);
            if (previousPublishedVersionId != null && !previousPublishedVersionId.equals(editable.getId())) {
                emitCourseTextEvents(course.getId(), previousPublishedVersionId, "DELETE", traceId);
            }
            emitCourseTextEvents(course.getId(), editable.getId(), "UPSERT", traceId);
        } else {
            if (CourseDtos.PublicationStatus.PUBLISHED.name().equals(course.getPublicationStatus())) {
                return;
            }
            mapper.updatePublicationStatus(course.getId(), CourseDtos.PublicationStatus.PUBLISHED.name());
            emitLessonContentEvent(course.getId(), editable, traceId);
            emitPortalEvent(course.getId(), traceId);
            emitCourseTextEvents(course.getId(), editable.getId(), "UPSERT", traceId);
        }
    }

    /** 将已发布课程切为不可公开读取；版本及目录仍保留，未发布课程重复下架返回冲突。 */
    private void unpublish(CourseEntity course, String traceId) {
        if (course.getPublishedVersionId() == null) {
            throw new CourseException(HttpStatus.CONFLICT, "COURSE.NOT_PUBLISHED", "课程尚未发布，无法下架");
        }
        mapper.updatePublicationStatus(course.getId(), CourseDtos.PublicationStatus.UNPUBLISHED.name());
        emitPortalEvent(course.getId(), traceId);
        emitCourseTextEvents(course.getId(), course.getPublishedVersionId(), "DELETE", traceId);
    }

    /** 发布/下架事务内只写稳定课程ID的失效Outbox；消费者必须重查当前可见性，不能用旧消息恢复卡片。 */
    private void emitPortalEvent(String courseId, String traceId) {
        Instant at=Instant.now(); String eventId=UUID.randomUUID().toString();
        String json=objectMapper.writeValueAsString(Map.of("eventId",eventId,"eventType","CoursePortalChanged","eventVersion",1,
                "aggregateId",courseId,"occurredAt",at,"traceId",traceId,"payload",Map.of("courseId",courseId)));
        mapper.insertOutbox(new com.eduplatform.course.messaging.CourseOutboxEvent(eventId,"CoursePortalChanged",1,courseId,
                "edu.course.portal.events.v1","course.portal.changed.v1",traceId,json,"PENDING",0,at,null,null,at,at));
    }

    /** 发布状态和每个 ARTICLE 通知在同一课程事务提交，消息仅携带 ID 与 revision。 */
    private void emitCourseTextEvents(String courseId, String versionId, String operation, String traceId) {
        java.time.Instant now = java.time.Instant.now();
        for (LessonEntity lesson : mapper.listVersionArticleLessons(versionId)) {
            mapper.bumpCourseTextIndexRevision(lesson.getId(), now);
            long revision = mapper.findCourseTextIndexRevision(lesson.getId());
            mapper.insertOutbox(agentIndexEvents.create(courseId, versionId, lesson.getId(), revision,
                    operation, traceId, now));
        }
    }

    /** 发布事务内写入完整当前映射；RabbitMQ 不可用只延后 Outbox 投递，不回滚已提交发布事实。 */
    private void emitLessonContentEvent(String courseId, CourseVersionEntity version, String traceId) {
        List<LessonEntity> lessons = mapper.listChapters(version.getId()).stream()
                .flatMap(chapter -> mapper.listLessons(chapter.getId()).stream()).toList();
        if (lessons.stream().anyMatch(lesson -> lesson.getContentRevisionId() == null)) {
            throw new IllegalStateException("published lesson content revision is missing");
        }
        Instant now = Instant.now();
        mapper.insertOutbox(lessonContentEvents.create(courseId, version.getId(), version.getVersionNumber(),
                lessons, traceId, now));
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
                lesson.setContentRevisionId(sourceLesson.getContentRevisionId());
                lesson.setChapterId(chapter.getId());
                lesson.setTitle(sourceLesson.getTitle());
                lesson.setLessonType(sourceLesson.getLessonType());
                lesson.setPosition(sourceLesson.getPosition());
                lesson.setPreviewable(sourceLesson.isPreviewable());
                lesson.setArticleMarkdown(sourceLesson.getArticleMarkdown());
                mapper.insertLesson(lesson);
                for (LessonMediaResourceEntity sourceResource : mapper.listLessonMediaResources(sourceLesson.getId())) {
                    LessonMediaResourceEntity resource = new LessonMediaResourceEntity();
                    resource.setId(UUID.randomUUID().toString());
                    resource.setLessonId(lesson.getId());
                    resource.setMediaId(sourceResource.getMediaId());
                    resource.setPurpose(sourceResource.getPurpose());
                    resource.setPosition(sourceResource.getPosition());
                    resource.setDisplayName(sourceResource.getDisplayName());
                    resource.setPresentation(sourceResource.getPresentation());
                    resource.setOriginalFileName(sourceResource.getOriginalFileName());
                    resource.setMimeType(sourceResource.getMimeType());
                    resource.setSizeBytes(sourceResource.getSizeBytes());
                    resource.setVariantNames(sourceResource.getVariantNames());
                    resource.setProcessingContractVersion(sourceResource.getProcessingContractVersion());
                    mapper.insertLessonMediaResource(resource);
                    reference(CourseMediaEventFactory.BOUND, resource.getMediaId(),
                            "LESSON_RESOURCE", resource.getId(), traceId);
                }
            }
        }
    }

    private void insertCurriculum(String versionId, List<CourseDtos.ChapterInput> inputs,
            Map<String, BindingSnapshot> snapshots, ExistingCurriculum existing, String traceId,
            boolean explicitIdentity) {
        Set<String> retainedLessonIds = new HashSet<>();
        for (CourseDtos.ChapterInput chapterInput : inputs) {
            ChapterEntity chapter = new ChapterEntity();
            chapter.setId(existing.chapterIds().getOrDefault(chapterInput.sequence(), UUID.randomUUID().toString()));
            chapter.setCourseVersionId(versionId);
            chapter.setTitle(chapterInput.title().trim());
            chapter.setPosition(chapterInput.sequence());
            mapper.insertChapter(chapter);
            for (CourseDtos.LessonInput lessonInput : chapterInput.lessons()) {
                LessonEntity lesson = new LessonEntity();
                String lessonKey = lessonKey(chapterInput.sequence(), lessonInput.sequence());
                LessonEntity retainedLesson = retainedLesson(existing, lessonInput, lessonKey, explicitIdentity);
                if (retainedLesson != null && !retainedLessonIds.add(retainedLesson.getId())) {
                    throw curriculumError("chapters.lessons.id", "同一课时 ID 不能在目录中重复使用");
                }
                lesson.setId(retainedLesson == null ? UUID.randomUUID().toString() : retainedLesson.getId());
                lesson.setContentRevisionId(contentRevision(retainedLesson, lessonInput, existing));
                lesson.setChapterId(chapter.getId());
                lesson.setTitle(lessonInput.title().trim());
                lesson.setLessonType(lessonInput.type().name());
                lesson.setPosition(lessonInput.sequence());
                lesson.setPreviewable(lessonInput.previewable());
                lesson.setArticleMarkdown(lessonInput.type() == CourseDtos.LessonType.ARTICLE
                        ? lessonInput.articleMarkdown() : null);
                mapper.insertLesson(lesson);
                for (CourseDtos.LessonMediaResourceInput input : resources(lessonInput)) {
                    LessonMediaResourceEntity resource = new LessonMediaResourceEntity();
                    String resourceKey = resourceKey(chapterInput.sequence(), lessonInput.sequence(),
                            input.purpose().name(), input.mediaId().toString());
                    LessonMediaResourceEntity retained = existing.resources().get(resourceKey);
                    resource.setId(retained == null ? UUID.randomUUID().toString() : retained.getId());
                    resource.setLessonId(lesson.getId());
                    resource.setMediaId(input.mediaId().toString());
                    resource.setPurpose(input.purpose().name());
                    resource.setPosition(input.sequence());
                    BindingSnapshot snapshot = snapshots.get(input.mediaId().toString());
                    resource.setDisplayName(normalizeDisplayName(input.displayName(), snapshot.originalFileName()));
                    resource.setPresentation(normalizePresentation(input, snapshot.mimeType()).name());
                    resource.setOriginalFileName(snapshot.originalFileName());
                    resource.setMimeType(snapshot.mimeType());
                    resource.setSizeBytes(snapshot.size());
                    resource.setVariantNames(String.join(",", snapshot.variants().stream().sorted().toList()));
                    resource.setProcessingContractVersion(snapshot.processingContractVersion());
                    mapper.insertLessonMediaResource(resource);
                    if (retained == null) {
                        reference(CourseMediaEventFactory.BOUND, resource.getMediaId(),
                                "LESSON_RESOURCE", resource.getId(), traceId);
                    }
                }
            }
        }
    }

    /**
     * 按章节/课时位置索引当前草稿，使整目录请求仍能保留最终请求中完全相同的资源引用 ID。
     * 类型改变时不兼容资源因校验无法出现在最终集合，必然释放；COURSEWARE 若仍存在则不产生释放/重绑抖动。
     */
    private ExistingCurriculum indexCurriculum(String versionId) {
        Map<Integer, String> chapters = new java.util.HashMap<>();
        Map<String, LessonEntity> lessonsByPosition = new java.util.HashMap<>();
        Map<String, LessonEntity> lessonsById = new java.util.HashMap<>();
        Map<String, LessonMediaResourceEntity> resources = new java.util.HashMap<>();
        Map<String, String> primaryVideosByLessonId = new java.util.HashMap<>();
        for (ChapterEntity chapter : mapper.listChapters(versionId)) {
            chapters.put(chapter.getPosition(), chapter.getId());
            for (LessonEntity lesson : mapper.listLessons(chapter.getId())) {
                lessonsByPosition.put(lessonKey(chapter.getPosition(), lesson.getPosition()), lesson);
                lessonsById.put(lesson.getId(), lesson);
                for (LessonMediaResourceEntity resource : mapper.listLessonMediaResources(lesson.getId())) {
                    resources.put(resourceKey(chapter.getPosition(), lesson.getPosition(),
                            resource.getPurpose(), resource.getMediaId()), resource);
                    if ("LESSON_VIDEO".equals(resource.getPurpose())) {
                        primaryVideosByLessonId.put(lesson.getId(), resource.getMediaId());
                    }
                }
            }
        }
        return new ExistingCurriculum(Map.copyOf(chapters), Map.copyOf(lessonsByPosition),
                Map.copyOf(lessonsById), Map.copyOf(resources), Map.copyOf(primaryVideosByLessonId));
    }

    /** 显式模式只按服务端 lessonId 保留身份；旧客户端仍按位置兼容，避免一次升级重置全部进度。 */
    private LessonEntity retainedLesson(ExistingCurriculum existing, CourseDtos.LessonInput input,
            String lessonKey, boolean explicitIdentity) {
        if (input.id() != null) {
            LessonEntity retained = existing.lessonsById().get(input.id().toString());
            if (retained == null) throw curriculumError("chapters.lessons.id", "课时 ID 不属于当前可编辑版本");
            return retained;
        }
        return explicitIdentity ? null : existing.lessonsByPosition().get(lessonKey);
    }

    /** 主视频绑定或课时类型变化生成新 UUID；正文、标题、顺序、海报和附件不会参与判断。 */
    private String contentRevision(LessonEntity retained, CourseDtos.LessonInput input,
            ExistingCurriculum existing) {
        if (retained == null || !retained.getLessonType().equals(input.type().name())) {
            return UUID.randomUUID().toString();
        }
        if (input.type() != CourseDtos.LessonType.VIDEO) return retained.getContentRevisionId();
        // 整目录替换在插入前已经删除旧资源，必须使用删除前快照判断，不能把普通标题修改误判成主视频解绑。
        String previousVideo = existing.primaryVideosByLessonId().get(retained.getId());
        String nextVideo = resources(input).stream()
                .filter(resource -> resource.purpose() == CourseDtos.LessonMediaPurpose.LESSON_VIDEO)
                .map(resource -> resource.mediaId().toString()).findFirst().orElse(null);
        return Objects.equals(previousVideo, nextVideo)
                ? retained.getContentRevisionId() : UUID.randomUUID().toString();
    }

    /**
     * 为 Learning 精确验证已打开播放器携带的身份。查询不枚举历史版本，也不返回正文或媒体；
     * 课程下架、从未发布的草稿以及不匹配的三元组统一 404。
     */
    @Transactional(readOnly = true)
    public CourseDtos.LessonContentIdentity verifyLessonContentIdentity(String courseId,
            String lessonId, String contentRevisionId) {
        suspension.requireActive(courseId);
        LessonEntity lesson = mapper.findPublishedContentIdentity(courseId, lessonId, contentRevisionId);
        if (lesson == null) throw notFound();
        return new CourseDtos.LessonContentIdentity(courseId, lessonId, contentRevisionId,
                CourseDtos.LessonType.valueOf(lesson.getLessonType()),
                mapper.countCurrentContentIdentity(courseId, lessonId, contentRevisionId) > 0);
    }

    /** 只为最终目录不再保留的精确引用写 Released Outbox；事件与目录替换处于同一事务。 */
    private void releaseRemovedLessonMedia(ExistingCurriculum existing,
            List<CourseDtos.ChapterInput> chapters, String traceId) {
        Set<String> retained = new HashSet<>();
        for (CourseDtos.ChapterInput chapter : chapters) {
            for (CourseDtos.LessonInput lesson : chapter.lessons()) {
                for (CourseDtos.LessonMediaResourceInput resource : resources(lesson)) {
                    retained.add(resourceKey(chapter.sequence(), lesson.sequence(),
                            resource.purpose().name(), resource.mediaId().toString()));
                }
            }
        }
        existing.resources().forEach((key, resource) -> {
            if (!retained.contains(key)) {
                reference(CourseMediaEventFactory.RELEASED, resource.getMediaId(),
                        "LESSON_RESOURCE", resource.getId(), traceId);
            }
        });
    }

    private static String lessonKey(int chapterSequence, int lessonSequence) {
        return chapterSequence + ":" + lessonSequence;
    }

    private static String resourceKey(int chapterSequence, int lessonSequence, String purpose, String mediaId) {
        return chapterSequence + ":" + lessonSequence + ":" + purpose + ":" + mediaId;
    }

    private CourseDtos.TeacherCourse toTeacherCourse(CourseEntity course) {
        return toTeacherCourse(course, newTraceId());
    }

    /** 将稳定封面 ID 投影为本次响应使用的短期 URL；签名结果不回写课程实体。 */
    private CourseDtos.TeacherCourse toTeacherCourse(CourseEntity course, String traceId) {
        suspension.requireActive(course.getId());
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
                curriculum(version.getId(), true),
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

    private List<CourseDtos.Chapter> curriculum(String versionId, boolean includeResources) {
        List<CourseDtos.Chapter> chapters = new ArrayList<>();
        for (ChapterEntity chapter : mapper.listChapters(versionId)) {
            List<CourseDtos.LessonSummary> lessons = mapper.listLessons(chapter.getId()).stream()
                    .map(lesson -> new CourseDtos.LessonSummary(
                            lesson.getId(),
                            lesson.getContentRevisionId(),
                            lesson.getTitle(),
                            CourseDtos.LessonType.valueOf(lesson.getLessonType()),
                            lesson.getPosition(),
                            lesson.isPreviewable(),
                            includeResources && "ARTICLE".equals(lesson.getLessonType())
                                    ? lesson.getArticleMarkdown() : null,
                            includeResources ? mapper.listLessonMediaResources(lesson.getId()).stream()
                                    .map(resource -> new CourseDtos.LessonMediaResource(
                                            resource.getId(), resource.getMediaId(),
                                            CourseDtos.LessonMediaPurpose.valueOf(resource.getPurpose()),
                                            resource.getPosition(), resource.getDisplayName(),
                                            CourseDtos.LessonResourcePresentation.valueOf(resource.getPresentation()),
                                            resource.getOriginalFileName(), resource.getMimeType(), resource.getSizeBytes()))
                                    .toList() : null))
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
        if (lock) suspension.lockTeacher(teacherId);
        CourseEntity course = lock
                ? mapper.lockOwnedCourse(courseId, teacherId)
                : mapper.findOwnedCourse(courseId, teacherId);
        if (course == null) {
            throw notFound();
        }
        suspension.requireActive(courseId);
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
        Set<String> articleImages = new HashSet<>();
        for (CourseDtos.LessonMediaResourceInput resource : resources(lesson)) {
            if (!allowed(lesson.type(), resource.purpose())) {
                throw curriculumSequenceError("chapters.lessons.resources", "课时类型与媒体用途不匹配");
            }
            if (!mediaIds.add(resource.mediaId())) {
                throw curriculumSequenceError("chapters.lessons.resources", "同一课时不能重复绑定同一媒体");
            }
            if (resource.purpose() == CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE) {
                articleImages.add(resource.mediaId().toString());
            }
            Set<Integer> purposePositions = positions.computeIfAbsent(resource.purpose(), key -> new HashSet<>());
            if (!purposePositions.add(resource.sequence())) {
                throw curriculumSequenceError("chapters.lessons.resources", "同一用途的资源序号不能重复");
            }
        }
        Set<String> suppliedMarkdownImages = markdownValidator.validate(lesson.articleMarkdown());
        Set<String> markdownImages = lesson.type() == CourseDtos.LessonType.ARTICLE
                ? suppliedMarkdownImages : Set.of();
        if (!markdownImages.equals(articleImages)) {
            throw curriculumSequenceError("chapters.lessons.articleMarkdown",
                    "正文 media:UUID 引用必须与 ARTICLE_IMAGE 绑定严格一致");
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
                    CourseDtos.LessonMediaPurpose.ARTICLE_ATTACHMENT,
                    CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE).contains(purpose);
            case ASSESSMENT -> false;
        };
    }

    /** 在任何课程目录写入前逐项调用 media-service，拒绝他人、错用途、未就绪或不存在资产。 */
    private Map<String, BindingSnapshot> validateLessonMedia(
            List<CourseDtos.ChapterInput> chapters, String teacherId, String traceId) {
        Map<String, BindingSnapshot> snapshots = new LinkedHashMap<>();
        for (CourseDtos.ChapterInput chapter : chapters) {
            for (CourseDtos.LessonInput lesson : chapter.lessons()) {
                for (CourseDtos.LessonMediaResourceInput resource : resources(lesson)) {
                    BindingSnapshot snapshot = mediaBindingClient.requireBindable(resource.mediaId().toString(),
                            resource.purpose().name(), teacherId, traceId);
                    validatePresentation(resource, snapshot.mimeType());
                    snapshots.put(resource.mediaId().toString(), snapshot);
                }
            }
        }
        return Map.copyOf(snapshots);
    }

    /** 发布前重新验证 READY，并按课时类型校验正文、视频和新处理契约的自动海报。 */
    private void validatePublishableLessons(String versionId, String teacherId, String traceId) {
        for (ChapterEntity chapter : mapper.listChapters(versionId)) {
            for (LessonEntity lesson : mapper.listLessons(chapter.getId())) {
                List<LessonMediaResourceEntity> resources = mapper.listLessonMediaResources(lesson.getId());
                Set<String> articleImages = new HashSet<>();
                boolean video = false;
                for (LessonMediaResourceEntity resource : resources) {
                    BindingSnapshot current = mediaBindingClient.requireBindable(resource.getMediaId(),
                            resource.getPurpose(), teacherId, traceId);
                    if ("ARTICLE_IMAGE".equals(resource.getPurpose())) articleImages.add(resource.getMediaId());
                    if ("LESSON_VIDEO".equals(resource.getPurpose())) {
                        video = true;
                        if ("LESSON_VIDEO_V5".equals(current.processingContractVersion())
                                && !(current.variants().contains("hls-fmp4-v2")
                                    && current.variants().contains("poster-v1"))) {
                            throw publishError("chapters.lessons.resources", "新视频必须同时具有 HLS 与 poster-v1");
                        }
                    }
                }
                if ("VIDEO".equals(lesson.getLessonType()) && !video) {
                    throw publishError("chapters.lessons.resources", "VIDEO 课时发布时必须绑定 READY 视频");
                }
                if ("ARTICLE".equals(lesson.getLessonType())) {
                    if (lesson.getArticleMarkdown() == null || lesson.getArticleMarkdown().isBlank()) {
                        throw publishError("chapters.lessons.articleMarkdown", "ARTICLE 课时发布时正文不能为空");
                    }
                    if (!markdownValidator.validate(lesson.getArticleMarkdown()).equals(articleImages)) {
                        throw publishError("chapters.lessons.articleMarkdown", "正文图片引用与绑定不一致");
                    }
                }
            }
        }
    }

    private static String normalizeDisplayName(String requested, String originalFileName) {
        String value = requested == null || requested.isBlank() ? originalFileName : requested.trim();
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 120) {
            throw curriculumError("chapters.lessons.resources.displayName", "displayName 必须为 1～120 字");
        }
        return value;
    }

    private static CourseDtos.LessonResourcePresentation normalizePresentation(
            CourseDtos.LessonMediaResourceInput input, String mimeType) {
        CourseDtos.LessonResourcePresentation requested = input.presentation();
        if (input.purpose() == CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE) {
            return CourseDtos.LessonResourcePresentation.INLINE;
        }
        if (requested != null) return requested;
        return isPreviewableMime(mimeType)
                ? CourseDtos.LessonResourcePresentation.INLINE : CourseDtos.LessonResourcePresentation.DOWNLOAD;
    }

    private static void validatePresentation(CourseDtos.LessonMediaResourceInput input, String mimeType) {
        if (input.purpose() == CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE
                && input.presentation() != null
                && input.presentation() != CourseDtos.LessonResourcePresentation.INLINE) {
            throw curriculumError("chapters.lessons.resources.presentation", "ARTICLE_IMAGE 固定为 INLINE");
        }
        CourseDtos.LessonResourcePresentation actual = normalizePresentation(input, mimeType);
        if (input.purpose() == CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE
                && actual != CourseDtos.LessonResourcePresentation.INLINE) {
            throw curriculumError("chapters.lessons.resources.presentation", "ARTICLE_IMAGE 固定为 INLINE");
        }
        if (!isPreviewableMime(mimeType) && actual == CourseDtos.LessonResourcePresentation.INLINE
                && Set.of(CourseDtos.LessonMediaPurpose.COURSEWARE,
                        CourseDtos.LessonMediaPurpose.ARTICLE_ATTACHMENT).contains(input.purpose())) {
            throw curriculumError("chapters.lessons.resources.presentation", "PPTX/TXT 资料只允许 DOWNLOAD");
        }
    }

    private static boolean isPreviewableMime(String mimeType) {
        String value = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        return value.equals("application/pdf") || value.startsWith("image/");
    }

    private CourseException publishError(String field, String reason) {
        return new CourseException(HttpStatus.UNPROCESSABLE_CONTENT, "COURSE.PUBLISH_VALIDATION_FAILED",
                "课程内容不满足发布条件", List.of(new FieldError(field, reason)));
    }

    private static CourseException curriculumError(String field, String reason) {
        return new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                "COMMON.VALIDATION_FAILED", "请求参数不合法", List.of(new FieldError(field, reason)));
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

    private record ExistingCurriculum(Map<Integer, String> chapterIds,
            Map<String, LessonEntity> lessonsByPosition, Map<String, LessonEntity> lessonsById,
            Map<String, LessonMediaResourceEntity> resources, Map<String, String> primaryVideosByLessonId) {}
}
