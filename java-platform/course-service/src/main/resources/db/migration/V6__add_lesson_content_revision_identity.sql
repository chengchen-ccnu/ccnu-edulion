-- T12C.0 Course 是发布课时内容身份的事实源；初始回填沿用既有课时 UUID，后续修订由应用生成新 UUID。
-- 该字段不是内容 Hash，也不替代 lesson.id 或任何 mediaId。
ALTER TABLE lesson
    ADD COLUMN content_revision_id CHAR(36) NULL;

UPDATE lesson
SET content_revision_id = id
WHERE content_revision_id IS NULL;

ALTER TABLE lesson
    MODIFY COLUMN content_revision_id CHAR(36) NOT NULL;

CREATE INDEX idx_lesson_content_revision ON lesson(content_revision_id);
