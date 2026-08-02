-- T08A course-service 向前迁移：把课程封面与课时资源写事实收口到稳定 mediaId，并增加本域可靠 Outbox。
-- 历史 cover_url 仅保留可空读取兼容；不建立跨 Schema 外键，也不保存 Bucket、Object Key 或签名 URL。

ALTER TABLE course_version
    ADD COLUMN cover_media_id CHAR(36) NULL;

ALTER TABLE course_version
    MODIFY COLUMN cover_url VARCHAR(512) NULL;

CREATE INDEX idx_course_version_cover_media ON course_version(cover_media_id);

CREATE TABLE lesson_media_resource (
    id CHAR(36) PRIMARY KEY,
    lesson_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    position INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 同一课时同一用途按连续位置稳定排序；媒体对象仍由 media-service 独立拥有。
    CONSTRAINT uk_lesson_media_position UNIQUE (lesson_id, purpose, position),
    CONSTRAINT uk_lesson_media_asset UNIQUE (lesson_id, purpose, media_id),
    CONSTRAINT fk_lesson_media_lesson FOREIGN KEY (lesson_id) REFERENCES lesson(id),
    CONSTRAINT chk_lesson_media_purpose CHECK (purpose IN ('LESSON_VIDEO', 'VIDEO_POSTER', 'SUBTITLE', 'COURSEWARE', 'ARTICLE_ATTACHMENT')),
    CONSTRAINT chk_lesson_media_position CHECK (position > 0)
);

CREATE INDEX idx_lesson_media_media_id ON lesson_media_resource(media_id);

CREATE TABLE outbox_event (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    event_version INT NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    last_error VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- PENDING 事件按到期时间补投；达到有限上限后转 FAILED，业务事实不回滚。
    CONSTRAINT chk_course_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_course_outbox_pending ON outbox_event(status, next_attempt_at, created_at);
