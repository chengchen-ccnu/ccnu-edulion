-- T12C.0：旧进度事实保留，首次迁移把既有 lesson_id 作为初始内容修订身份；后续修订由 Course UUID 决定。
ALTER TABLE lesson_progress ADD COLUMN content_revision_id CHAR(36) NULL;
UPDATE lesson_progress SET content_revision_id=lesson_id WHERE content_revision_id IS NULL;
ALTER TABLE lesson_progress MODIFY COLUMN content_revision_id CHAR(36) NOT NULL;
ALTER TABLE lesson_progress DROP INDEX uk_lesson_progress_owner;
ALTER TABLE lesson_progress ADD CONSTRAINT uk_lesson_progress_content
    UNIQUE (learner_id, course_id, content_revision_id);
CREATE INDEX idx_lesson_progress_current ON lesson_progress(learner_id, course_id, content_revision_id, updated_at);

ALTER TABLE lesson_progress_event ADD COLUMN content_revision_id CHAR(36) NULL;
UPDATE lesson_progress_event SET content_revision_id=lesson_id WHERE content_revision_id IS NULL;
ALTER TABLE lesson_progress_event MODIFY COLUMN content_revision_id CHAR(36) NOT NULL;
CREATE INDEX idx_progress_event_content_time
    ON lesson_progress_event(learner_id, course_id, content_revision_id, occurred_at);

-- ARTICLE 正文、图片和附件变化沿用同一修订；类型互转后新修订保留旧完成事实但不再投影。
ALTER TABLE article_lesson_completion ADD COLUMN content_revision_id CHAR(36) NULL;
UPDATE article_lesson_completion SET content_revision_id=lesson_id WHERE content_revision_id IS NULL;
ALTER TABLE article_lesson_completion MODIFY COLUMN content_revision_id CHAR(36) NOT NULL;
ALTER TABLE article_lesson_completion DROP INDEX uk_article_completion_fact;
ALTER TABLE article_lesson_completion ADD CONSTRAINT uk_article_completion_content
    UNIQUE (learner_id, course_id, content_revision_id);

ALTER TABLE article_completion_request ADD COLUMN content_revision_id CHAR(36) NULL;
UPDATE article_completion_request SET content_revision_id=lesson_id WHERE content_revision_id IS NULL;
ALTER TABLE article_completion_request MODIFY COLUMN content_revision_id CHAR(36) NOT NULL;

-- Learning 只保存 Course 发布事件的最小当前投影，不复制课程正文、媒体或领域表。
CREATE TABLE published_course_content_projection (
    course_id CHAR(36) NOT NULL,
    course_version_id VARCHAR(64) NOT NULL,
    publication_revision INT NOT NULL,
    projection_source VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (course_id),
    CONSTRAINT chk_published_content_revision CHECK (publication_revision >= 1),
    CONSTRAINT chk_published_content_source CHECK (projection_source IN ('EVENT','COURSE_HTTP'))
);

CREATE TABLE published_lesson_content_projection (
    course_id CHAR(36) NOT NULL,
    lesson_id CHAR(36) NOT NULL,
    content_revision_id CHAR(36) NOT NULL,
    lesson_type VARCHAR(16) NOT NULL,
    publication_revision INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (course_id, lesson_id),
    CONSTRAINT uk_published_lesson_content_revision UNIQUE (course_id, content_revision_id),
    CONSTRAINT fk_published_lesson_content_course FOREIGN KEY (course_id)
        REFERENCES published_course_content_projection(course_id) ON DELETE CASCADE,
    CONSTRAINT chk_published_lesson_content_type CHECK (lesson_type IN ('VIDEO','ARTICLE','ASSESSMENT'))
);

CREATE TABLE consumed_course_content_event (
    event_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    publication_revision INT NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY idx_consumed_course_content (course_id, publication_revision)
);
