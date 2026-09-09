package com.eduplatform.course.persistence;

import com.eduplatform.course.domain.ChapterEntity;
import com.eduplatform.course.domain.CategoryEntity;
import com.eduplatform.course.domain.CourseEntity;
import com.eduplatform.course.domain.CourseListRow;
import com.eduplatform.course.domain.CourseMediaAccessRow;
import com.eduplatform.course.domain.CourseVersionEntity;
import com.eduplatform.course.domain.LessonEntity;
import com.eduplatform.course.domain.LessonMediaResourceEntity;
import com.eduplatform.course.messaging.CourseOutboxEvent;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
/**
 * course-service Schema 的课程目录持久化边界。
 *
 * <p>公开查询只选择可见发布版本；教师查询始终带 owner ID。锁定、revision 条件更新和内容
 * 哈希预占由服务层组合成事务，用于防止并发覆盖与重复目录写入。</p>
 */
public interface CourseMapper {
    long countPublished(@Param("keyword") String keyword, @Param("categoryId") String categoryId,
            @Param("level") String level, @Param("accessType") String accessType);

    List<CourseListRow> listPublished(@Param("keyword") String keyword, @Param("categoryId") String categoryId,
            @Param("level") String level, @Param("accessType") String accessType,
            @Param("offset") long offset, @Param("limit") int limit);

    CourseVersionEntity findPublicVersion(String courseId);

    CourseVersionEntity findVersion(String versionId);

    /** 查询当前发布版本中的精确课时资源；草稿、归档和历史版本不会命中。 */
    CourseMediaAccessRow findPublishedMediaAccess(@Param("courseId") String courseId,
            @Param("lessonId") String lessonId, @Param("resourceId") String resourceId);

    /** 查询当前教师拥有课程的可编辑版本资源；优先草稿，没有草稿时使用已发布版本。 */
    CourseMediaAccessRow findOwnedEditableMediaAccess(@Param("courseId") String courseId,
            @Param("lessonId") String lessonId, @Param("resourceId") String resourceId,
            @Param("teacherId") String teacherId);

    /** 查询发布版本的精确课时及其全部资源，空资源课时也返回一行。 */
    List<CourseMediaAccessRow> listPublishedLessonContentRows(@Param("courseId") String courseId,
            @Param("lessonId") String lessonId);

    /** owner 范围内查询当前可编辑版本的精确课时及全部资源；空资源 ARTICLE 也返回一行。 */
    List<CourseMediaAccessRow> listOwnedEditableLessonContentRows(@Param("courseId") String courseId,
            @Param("lessonId") String lessonId, @Param("teacherId") String teacherId);

    List<CategoryEntity> listActiveCategories();

    CourseEntity findOwnedCourse(@Param("courseId") String courseId, @Param("teacherId") String teacherId);

    long countOwnedCourses(String teacherId);

    List<CourseEntity> listOwnedCourses(@Param("teacherId") String teacherId,
            @Param("offset") long offset, @Param("limit") int limit);

    CourseEntity lockOwnedCourse(@Param("courseId") String courseId, @Param("teacherId") String teacherId);

    CourseEntity findByOwnerAndRequest(@Param("teacherId") String teacherId,
            @Param("createRequestId") String createRequestId);

    int countActiveCategory(String categoryId);

    int insertCourse(CourseEntity course);

    int insertCourseVersion(CourseVersionEntity version);

    int setDraftVersion(@Param("courseId") String courseId, @Param("versionId") String versionId);

    int updateMetadata(@Param("version") CourseVersionEntity version, @Param("expectedRevision") long expectedRevision);

    int reserveCurriculum(@Param("versionId") String versionId, @Param("curriculumHash") String curriculumHash,
            @Param("expectedRevision") long expectedRevision);

    int deleteLessonsByVersion(String versionId);

    int deleteLessonMediaByVersion(String versionId);

    int deleteChaptersByVersion(String versionId);

    int insertChapter(ChapterEntity chapter);

    int insertLesson(LessonEntity lesson);

    int insertLessonMediaResource(LessonMediaResourceEntity resource);

    List<ChapterEntity> listChapters(String versionId);

    List<LessonEntity> listLessons(String chapterId);

    List<LessonMediaResourceEntity> listLessonMediaResources(String lessonId);

    List<LessonMediaResourceEntity> listVersionMediaResources(String versionId);

    int countChapters(String versionId);

    int countEmptyChapters(String versionId);

    int archiveVersion(String versionId);

    int publishVersion(String versionId);

    int publishDraft(@Param("courseId") String courseId, @Param("versionId") String versionId);

    int updatePublicationStatus(@Param("courseId") String courseId, @Param("status") String status);

    List<LessonEntity> listVersionArticleLessons(String versionId);

    int bumpCourseTextIndexRevision(@Param("lessonId") String lessonId, @Param("now") Instant now);

    long findCourseTextIndexRevision(String lessonId);

    int insertOutbox(CourseOutboxEvent event);

    List<CourseOutboxEvent> pendingOutbox(@Param("now") Instant now, @Param("limit") int limit);

    int markOutboxPublished(@Param("eventId") String eventId, @Param("now") Instant now);

    int markOutboxFailure(@Param("eventId") String eventId, @Param("attempts") int attempts,
            @Param("terminal") boolean terminal, @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError, @Param("now") Instant now);
}
