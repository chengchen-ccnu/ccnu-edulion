-- T14.2.4 Agent Access V5：允许同一用户消息保留多次生成，并保存公开偏好命令的幂等结果。
-- 本迁移只向前调整 run 唯一约束并增加命令事实；旧消息、旧 run、事件和审计均原样保留。
-- MySQL 需要先有另一个 user_message_id 索引承载外键，才能删除原唯一索引。
CREATE INDEX idx_ai_run_user_message ON ai_run(user_message_id, created_at, id);
ALTER TABLE ai_run DROP INDEX uk_ai_run_user_message;
ALTER TABLE ai_run ADD COLUMN regeneration_request_id CHAR(36) NULL AFTER user_message_id;
ALTER TABLE ai_run ADD CONSTRAINT uk_ai_run_regeneration_request
    UNIQUE (conversation_id, regeneration_request_id);

-- 偏好本体仍只存于 ai_preference；此表仅记录 PUT 的稳定请求和当次结果，防止网络重试重复推进 revision。
CREATE TABLE ai_preference_command (
    owner_user_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    preference_value VARCHAR(80) NOT NULL,
    result_revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_user_id, client_request_id)
);
