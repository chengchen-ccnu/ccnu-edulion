package com.eduplatform.course.persistence;

import com.eduplatform.course.domain.ChapterEntity;
import com.eduplatform.course.domain.CourseEntity;
import com.eduplatform.course.domain.CourseListRow;
import com.eduplatform.course.domain.CourseVersionEntity;
import com.eduplatform.course.domain.LessonEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CourseMapper {
    long countPublished(@Param("keyword") String keyword, @Param("categoryId") String categoryId,
            @Param("level") String level, @Param("accessType") String accessType);

    List<CourseListRow> listPublished(@Param("keyword") String keyword, @Param("categoryId") String categoryId,
            @Param("level") String level, @Param("accessType") String accessType,
            @Param("offset") long offset, @Param("limit") int limit);

    CourseVersionEntity findPublicVersion(String courseId);

    CourseVersionEntity findVersion(String versionId);

    CourseEntity findOwnedCourse(@Param("courseId") String courseId, @Param("teacherId") String teacherId);

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

    int deleteChaptersByVersion(String versionId);

    int insertChapter(ChapterEntity chapter);

    int insertLesson(LessonEntity lesson);

    List<ChapterEntity> listChapters(String versionId);

    List<LessonEntity> listLessons(String chapterId);

    int countChapters(String versionId);

    int countEmptyChapters(String versionId);

    int archiveVersion(String versionId);

    int publishVersion(String versionId);

    int publishDraft(@Param("courseId") String courseId, @Param("versionId") String versionId);

    int updatePublicationStatus(@Param("courseId") String courseId, @Param("status") String status);
}
