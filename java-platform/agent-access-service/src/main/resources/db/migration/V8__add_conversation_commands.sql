-- 会话重命名/归档命令的稳定幂等事实。现有 ai_conversation 只有 create_request_id，无法区分
-- 网络重试与复用 requestId 的不同写命令，因此不能仅靠 revision 或普通审计安全恢复响应。
CREATE TABLE ai_conversation_command (
    owner_user_id CHAR(36) NOT NULL,
    request_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_user_id, request_id),
    CONSTRAINT fk_ai_conversation_command_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT ck_ai_conversation_command_type CHECK (command_type IN ('RENAME','ARCHIVE'))
);
CREATE INDEX idx_ai_conversation_command_conversation
    ON ai_conversation_command(conversation_id, created_at);
