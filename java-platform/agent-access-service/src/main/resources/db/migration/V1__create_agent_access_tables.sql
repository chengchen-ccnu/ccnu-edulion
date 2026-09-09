-- T14.1：Agent 会话、消息、运行与可恢复公开事件均由独立 Schema 持久化。
-- active_slot 仅在活动 run 中写 1，终态写 NULL；唯一约束在多实例并发下保证同会话最多一个活动 run。
CREATE TABLE ai_conversation (
    id CHAR(36) PRIMARY KEY,
    owner_user_id CHAR(36) NOT NULL,
    create_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    entry_point VARCHAR(32) NOT NULL,
    title VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    next_message_sequence BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    last_message_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ai_conversation_create UNIQUE (owner_user_id, create_request_id)
);

CREATE INDEX idx_ai_conversation_owner_activity
    ON ai_conversation(owner_user_id, last_message_at, id);

-- context_json 只保存 Java 已授权的稳定 ID；严禁保存短期媒体 URL、Token 或 Python 私有运行状态。
CREATE TABLE ai_message (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    client_request_id CHAR(36),
    request_fingerprint CHAR(64),
    role VARCHAR(16) NOT NULL,
    content LONGTEXT NOT NULL,
    completeness VARCHAR(16) NOT NULL,
    context_json LONGTEXT,
    sequence_no BIGINT NOT NULL,
    run_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT uk_ai_message_request UNIQUE (conversation_id, client_request_id),
    CONSTRAINT uk_ai_message_sequence UNIQUE (conversation_id, sequence_no)
);

CREATE TABLE ai_run (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    user_message_id CHAR(36) NOT NULL,
    assistant_message_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    active_slot TINYINT,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    terminal_event_sequence BIGINT,
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_run_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT fk_ai_run_user_message FOREIGN KEY (user_message_id) REFERENCES ai_message(id),
    CONSTRAINT fk_ai_run_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES ai_message(id),
    CONSTRAINT uk_ai_run_user_message UNIQUE (user_message_id),
    CONSTRAINT uk_ai_run_active_conversation UNIQUE (conversation_id, active_slot)
);

CREATE INDEX idx_ai_run_owner ON ai_run(owner_user_id, created_at);

-- 只保存浏览器可见的已脱敏事件；运行恢复读取该表，不会重新启动模型或工具。
CREATE TABLE ai_run_event (
    run_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_json LONGTEXT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_ai_run_event_run FOREIGN KEY (run_id) REFERENCES ai_run(id)
);

CREATE TABLE ai_run_cancellation (
    run_id CHAR(36) NOT NULL,
    cancel_request_id CHAR(36) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    response_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_id, cancel_request_id),
    CONSTRAINT fk_ai_run_cancellation_run FOREIGN KEY (run_id) REFERENCES ai_run(id)
);

-- 工具审计只记录白名单工具、状态和安全摘要，不记录原始工具结果、提示词或临时签名 URL。
CREATE TABLE ai_tool_call (
    id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    safe_summary VARCHAR(500),
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT fk_ai_tool_call_run FOREIGN KEY (run_id) REFERENCES ai_run(id)
);
