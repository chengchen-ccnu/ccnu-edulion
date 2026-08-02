-- T05 course-service 首个向前迁移：创建分类、课程聚合、不可变版本、章节和课时目录表。
-- 迁移不可逆且不跨 Schema 建外键；后续结构变化必须新增 Flyway 版本，不能回写本文件。

CREATE TABLE category (
    id CHAR(36) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code),
    CONSTRAINT chk_category_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE course (
    id CHAR(36) NOT NULL,
    create_request_id CHAR(36) NOT NULL,
    owner_teacher_id CHAR(36) NOT NULL,
    current_draft_version_id CHAR(36) NULL,
    published_version_id CHAR(36) NULL,
    publication_status VARCHAR(16) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 教师与客户端请求 ID 共同保证创建重放只产生一门课程。
    UNIQUE KEY uk_course_owner_request (owner_teacher_id, create_request_id),
    KEY idx_course_publication (publication_status, published_at),
    KEY idx_course_owner (owner_teacher_id, updated_at),
    CONSTRAINT chk_course_publication_status CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'UNPUBLISHED'))
);

CREATE TABLE course_version (
    id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    version_number INT NOT NULL,
    version_label VARCHAR(20) NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    description TEXT NOT NULL,
    cover_url VARCHAR(512) NOT NULL,
    instructor_display_name VARCHAR(80) NOT NULL,
    category_id CHAR(36) NOT NULL,
    level VARCHAR(16) NOT NULL,
    access_type VARCHAR(32) NOT NULL,
    curriculum_hash CHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 每门课程的版本号单调且唯一，已发布版本不会被后续编辑覆盖。
    UNIQUE KEY uk_course_version_number (course_id, version_number),
    KEY idx_course_version_category (category_id),
    CONSTRAINT fk_course_version_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_course_version_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT chk_course_version_lifecycle CHECK (lifecycle_status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_course_level CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT chk_course_access_type CHECK (access_type IN ('PUBLIC', 'ENROLLMENT_REQUIRED'))
);

CREATE TABLE chapter (
    id CHAR(36) NOT NULL,
    course_version_id CHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    position INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一版本内章节位置唯一，服务层另行校验必须从 1 连续排列。
    UNIQUE KEY uk_chapter_position (course_version_id, position),
    CONSTRAINT fk_chapter_course_version FOREIGN KEY (course_version_id) REFERENCES course_version (id)
);

CREATE TABLE lesson (
    id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    title VARCHAR(120) NOT NULL,
    lesson_type VARCHAR(16) NOT NULL,
    position INT NOT NULL,
    previewable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 同一章节内课时位置唯一，目录整体替换在一个事务内完成。
    UNIQUE KEY uk_lesson_position (chapter_id, position),
    CONSTRAINT fk_lesson_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id),
    CONSTRAINT chk_lesson_type CHECK (lesson_type IN ('VIDEO', 'ARTICLE', 'ASSESSMENT'))
);

ALTER TABLE course
    -- 聚合只保存当前草稿/发布版本指针，避免公开查询误读其他历史版本。
    ADD CONSTRAINT fk_course_draft_version FOREIGN KEY (current_draft_version_id) REFERENCES course_version (id);

ALTER TABLE course
    ADD CONSTRAINT fk_course_published_version FOREIGN KEY (published_version_id) REFERENCES course_version (id);
