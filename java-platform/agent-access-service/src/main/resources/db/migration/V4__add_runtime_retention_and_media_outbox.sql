-- T14.2.3：正文保留状态与稳定媒体引用；不修改 V1～V3，也不保存 URL/Object Key/Bucket。
ALTER TABLE ai_message ADD COLUMN content_state VARCHAR(16) NOT NULL DEFAULT 'RETAINED';
ALTER TABLE ai_message ADD COLUMN content_purged_at TIMESTAMP(6) NULL;
CREATE INDEX idx_ai_message_retention ON ai_message(content_state, created_at, id);

-- 一条消息对一个 AI_QUESTION_IMAGE 只保留一个稳定引用；状态用于避免重复释放。
CREATE TABLE ai_message_media_reference (
    message_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    released_at TIMESTAMP(6) NULL,
    PRIMARY KEY (message_id, media_id),
    CONSTRAINT fk_ai_message_media_message FOREIGN KEY (message_id) REFERENCES ai_message(id)
);

-- MediaReferenceBound/Released 与 Agent 本地事务同提交；失败有限重试并保留，eventId 供 Media 幂等。
CREATE TABLE ai_media_outbox (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(120) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_ai_media_outbox_pending ON ai_media_outbox(status, next_attempt_at, occurred_at);

-- 单例游标避免每轮从表头扫描；每批按 (created_at,id) 稳定向前，跑完一轮后回到起点等待下次周期。
CREATE TABLE ai_message_retention_cursor (
    singleton_id TINYINT PRIMARY KEY,
    last_created_at TIMESTAMP(6) NULL,
    last_message_id CHAR(36) NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
INSERT INTO ai_message_retention_cursor(singleton_id,updated_at) VALUES(1,CURRENT_TIMESTAMP(6));
