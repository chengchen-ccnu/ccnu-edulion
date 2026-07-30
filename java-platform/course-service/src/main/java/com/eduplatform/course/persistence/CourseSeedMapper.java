package com.eduplatform.course.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CourseSeedMapper {
    @Insert("""
            INSERT INTO category (id, code, name, status, row_version)
            VALUES ('00000000-0000-4000-8000-000000000501', 'MIDDLE_SCHOOL_MATH', '初中数学', 'ACTIVE', 0)
            ON DUPLICATE KEY UPDATE name = VALUES(name), status = 'ACTIVE', row_version = 0
            """)
    int upsertCategory();

    @Insert("""
            INSERT INTO course (
                id, create_request_id, owner_teacher_id, current_draft_version_id,
                published_version_id, publication_status, row_version
            ) VALUES (
                '00000000-0000-4000-8000-000000000201',
                '00000000-0000-4000-8000-000000000291',
                '00000000-0000-4000-8000-000000000102',
                NULL, NULL, 'DRAFT', 0
            )
            ON DUPLICATE KEY UPDATE
                owner_teacher_id = VALUES(owner_teacher_id),
                row_version = 0
            """)
    int upsertCourse();

    @Insert("""
            INSERT INTO course_version (
                id, course_id, version_number, version_label, lifecycle_status,
                title, summary, description, cover_url, instructor_display_name,
                category_id, level, access_type, curriculum_hash, row_version
            ) VALUES (
                '00000000-0000-4000-8000-000000000211',
                '00000000-0000-4000-8000-000000000201',
                1, 'v1', 'PUBLISHED',
                '七年级代数基础', '从有理数到一元一次方程',
                '## 课程介绍\n循序渐进掌握代数基础。',
                'https://cdn.example.test/courses/algebra-cover.jpg',
                '测试教师',
                '00000000-0000-4000-8000-000000000501',
                'BEGINNER', 'PUBLIC',
                '5e3895e61b4c2f98c0233a560aa1d2963a80173cf119174e952a832419808a6f', 0
            )
            ON DUPLICATE KEY UPDATE
                lifecycle_status = 'PUBLISHED',
                title = VALUES(title),
                summary = VALUES(summary),
                description = VALUES(description),
                cover_url = VALUES(cover_url),
                instructor_display_name = VALUES(instructor_display_name),
                category_id = VALUES(category_id),
                level = VALUES(level),
                access_type = VALUES(access_type),
                curriculum_hash = VALUES(curriculum_hash),
                row_version = 0
            """)
    int upsertVersion();

    @Insert("""
            INSERT INTO chapter (id, course_version_id, title, position, row_version)
            VALUES (
                '00000000-0000-4000-8000-000000000221',
                '00000000-0000-4000-8000-000000000211',
                '有理数', 1, 0
            )
            ON DUPLICATE KEY UPDATE title = VALUES(title), position = 1, row_version = 0
            """)
    int upsertChapter();

    @Insert("""
            INSERT INTO lesson (id, chapter_id, title, lesson_type, position, previewable, row_version)
            VALUES (
                '00000000-0000-4000-8000-000000000231',
                '00000000-0000-4000-8000-000000000221',
                '正数与负数', 'VIDEO', 1, TRUE, 0
            )
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                lesson_type = VALUES(lesson_type),
                position = 1,
                previewable = TRUE,
                row_version = 0
            """)
    int upsertLesson();

    @Update("""
            UPDATE course
            SET current_draft_version_id = NULL,
                published_version_id = '00000000-0000-4000-8000-000000000211',
                publication_status = 'PUBLISHED',
                published_at = COALESCE(published_at, CURRENT_TIMESTAMP(6)),
                row_version = 0
            WHERE id = '00000000-0000-4000-8000-000000000201'
            """)
    int publishCourse();
}
