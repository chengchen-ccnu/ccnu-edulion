-- T14.1：课程 Outbox 从固定媒体交换机扩展为显式交换机；历史记录保持原媒体路由。
ALTER TABLE outbox_event
    ADD COLUMN exchange_name VARCHAR(120) NOT NULL DEFAULT 'edu.media.events.v1' AFTER aggregate_id;

-- 每个课时的索引通知 revision 独立递增，使下架后重发不会被旧 tombstone 压住。
CREATE TABLE course_text_index_revision (
    lesson_id CHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
