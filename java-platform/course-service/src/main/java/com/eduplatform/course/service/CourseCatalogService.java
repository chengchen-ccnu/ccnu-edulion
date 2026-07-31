package com.eduplatform.course.service;

import com.eduplatform.course.domain.ChapterEntity;
import com.eduplatform.course.domain.CourseEntity;
import com.eduplatform.course.domain.CourseListRow;
import com.eduplatform.course.domain.CourseVersionEntity;
import com.eduplatform.course.domain.LessonEntity;
import com.eduplatform.course.persistence.CourseMapper;
import com.eduplatform.course.web.CourseDtos;
import com.eduplatform.shared.api.FieldError;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class CourseCatalogService {
    private final CourseMapper mapper;
    private final ObjectMapper objectMapper;

    public CourseCatalogService(CourseMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
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
                version.getCoverUrl(),
                new CourseDtos.TeacherSummary(version.getOwnerTeacherId(), version.getInstructorDisplayName()),
                new CourseDtos.CategorySummary(version.getCategoryId(), version.getCategoryName()),
                CourseDtos.CourseLevel.valueOf(version.getLevel()),
                CourseDtos.CourseAccessType.valueOf(version.getAccessType()),
                version.getVersionLabel(),
                curriculum(version.getId()));
    }

    @Transactional
    public CreateResult create(String teacherId, CourseDtos.CreateCourseRequest request) {
        CourseEntity existing = mapper.findByOwnerAndRequest(teacherId, request.clientRequestId().toString());
        if (existing != null) {
            return new CreateResult(toTeacherCourse(existing), false);
        }
        requireActiveCategory(request.categoryId().toString());

        CourseEntity course = new CourseEntity();
        course.setId(UUID.randomUUID().toString());
        course.setCreateRequestId(request.clientRequestId().toString());
        course.setOwnerTeacherId(teacherId);
        course.setPublicationStatus(CourseDtos.PublicationStatus.DRAFT.name());
        course.setRowVersion(0);
        mapper.insertCourse(course);
        CourseEntity winner = mapper.findByOwnerAndRequest(teacherId, request.clientRequestId().toString());
        if (!course.getId().equals(winner.getId())) {
            return new CreateResult(toTeacherCourse(winner), false);
        }

        CourseVersionEntity version = new CourseVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setCourseId(course.getId());
        version.setVersionNumber(1);
        version.setVersionLabel("v1");
        version.setLifecycleStatus("DRAFT");
        applyMetadata(version, request.title(), request.summary(), request.description(), request.coverUrl(),
                request.instructorDisplayName(), request.categoryId().toString(),
                request.level().name(), request.accessType().name());
        version.setRowVersion(0);
        mapper.insertCourseVersion(version);
        mapper.setDraftVersion(course.getId(), version.getId());

        CourseEntity created = requireOwnedCourse(course.getId(), teacherId, false);
        return new CreateResult(toTeacherCourse(created), true);
    }

    @Transactional(readOnly = true)
    public CourseDtos.TeacherCourse getTeacherCourse(String courseId, String teacherId) {
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
    }

    @Transactional
    public CourseDtos.TeacherCourse updateMetadata(String courseId, String teacherId,
            CourseDtos.UpdateCourseRequest request) {
        requireActiveCategory(request.categoryId().toString());
        CourseEntity course = requireOwnedCourse(courseId, teacherId, true);
        CourseVersionEntity draft = ensureDraft(course);
        if (metadataEquals(draft, request)) {
            return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
        }
        CourseVersionEntity updated = new CourseVersionEntity();
        updated.setId(draft.getId());
        applyMetadata(updated, request.title(), request.summary(), request.description(), request.coverUrl(),
                request.instructorDisplayName(), request.categoryId().toString(),
                request.level().name(), request.accessType().name());
        if (mapper.updateMetadata(updated, request.expectedRevision()) != 1) {
            throw versionConflict();
        }
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
    }

    @Transactional
    public CourseDtos.TeacherCourse replaceCurriculum(String courseId, String teacherId,
            CourseDtos.ReplaceCurriculumRequest request) {
        validateSequences(request.chapters());
        CourseEntity course = requireOwnedCourse(courseId, teacherId, true);
        CourseVersionEntity draft = ensureDraft(course);
        String hash = curriculumHash(request.chapters());
        if (Objects.equals(hash, draft.getCurriculumHash())) {
            return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
        }
        if (draft.getRowVersion() != request.expectedRevision()) {
            throw versionConflict();
        }
        if (mapper.reserveCurriculum(draft.getId(), hash, request.expectedRevision()) != 1) {
            throw versionConflict();
        }
        mapper.deleteLessonsByVersion(draft.getId());
        mapper.deleteChaptersByVersion(draft.getId());
        insertCurriculum(draft.getId(), request.chapters());
        return toTeacherCourse(requireOwnedCourse(courseId, teacherId, false));
    }

    @Transactional
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

    private void unpublish(CourseEntity course) {
        if (course.getPublishedVersionId() == null) {
            throw new CourseException(HttpStatus.CONFLICT, "COURSE.NOT_PUBLISHED", "课程尚未发布，无法下架");
        }
        mapper.updatePublicationStatus(course.getId(), CourseDtos.PublicationStatus.UNPUBLISHED.name());
    }

    private CourseVersionEntity ensureDraft(CourseEntity course) {
        if (course.getCurrentDraftVersionId() != null) {
            return mapper.findVersion(course.getCurrentDraftVersionId());
        }
        if (course.getPublishedVersionId() == null) {
            throw notFound();
        }
        CourseVersionEntity source = mapper.findVersion(course.getPublishedVersionId());
        CourseVersionEntity draft = copyVersion(source);
        mapper.insertCourseVersion(draft);
        copyCurriculum(source.getId(), draft.getId());
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
        applyMetadata(copy, source.getTitle(), source.getSummary(), source.getDescription(), source.getCoverUrl(),
                source.getInstructorDisplayName(), source.getCategoryId(), source.getLevel(), source.getAccessType());
        copy.setCurriculumHash(source.getCurriculumHash());
        copy.setRowVersion(source.getRowVersion());
        return copy;
    }

    private void copyCurriculum(String sourceVersionId, String targetVersionId) {
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
            }
        }
    }

    private void insertCurriculum(String versionId, List<CourseDtos.ChapterInput> inputs) {
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
            }
        }
    }

    private CourseDtos.TeacherCourse toTeacherCourse(CourseEntity course) {
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
                version.getCoverUrl(),
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
                            lesson.isPreviewable()))
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
                row.getCoverUrl(),
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

    private void requireActiveCategory(String categoryId) {
        if (mapper.countActiveCategory(categoryId) == 0) {
            throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "COURSE.CATEGORY_NOT_FOUND", "课程分类不存在或已停用",
                    List.of(new FieldError("categoryId", "课程分类不存在或已停用")));
        }
    }

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
            }
            requireContiguous(lessonSequences, chapter.lessons().size(), "chapters.lessons", "课时序号必须从 1 连续排列");
        }
        requireContiguous(chapterSequences, chapters.size(), "chapters", "章节序号必须从 1 连续排列");
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

    private String curriculumHash(List<CourseDtos.ChapterInput> chapters) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(chapters);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void applyMetadata(CourseVersionEntity version, String title, String summary, String description,
            String coverUrl, String instructorDisplayName, String categoryId, String level, String accessType) {
        version.setTitle(title.trim());
        version.setSummary(summary.trim());
        version.setDescription(description);
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
                && Objects.equals(version.getCoverUrl(), request.coverUrl())
                && Objects.equals(version.getInstructorDisplayName(), request.instructorDisplayName().trim())
                && Objects.equals(version.getCategoryId(), request.categoryId().toString())
                && Objects.equals(version.getLevel(), request.level().name())
                && Objects.equals(version.getAccessType(), request.accessType().name());
    }

    private CourseException notFound() {
        return new CourseException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在或不可见");
    }

    private CourseException versionConflict() {
        return new CourseException(HttpStatus.CONFLICT, "COURSE.VERSION_CONFLICT", "课程已被更新，请刷新后重试");
    }

    public record CreateResult(CourseDtos.TeacherCourse course, boolean created) {}
}
