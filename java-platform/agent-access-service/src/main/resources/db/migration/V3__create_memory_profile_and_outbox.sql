-- T14.2.2：把 ai_run 中的私有报告向前归一化为 Agent Access 自有事实。
-- 所有 owner_user_id 都是 IAM 全局 userId；本迁移不跨 Schema 建外键，也不修改 V1/V2。

ALTER TABLE ai_run ADD COLUMN result_normalization_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE ai_run ADD COLUMN result_normalization_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE ai_run ADD COLUMN result_normalization_error VARCHAR(120) NULL;
ALTER TABLE ai_run ADD COLUMN result_normalized_at TIMESTAMP(6) NULL;

-- 投影分页游标需要全局单调 revision；否则晚创建的 revision=1 记忆会被已越过的游标漏掉。
CREATE TABLE ai_memory_revision_sequence (
    singleton_id TINYINT PRIMARY KEY,
    current_revision BIGINT NOT NULL
);
INSERT INTO ai_memory_revision_sequence(singleton_id, current_revision) VALUES(1, 0);

CREATE TABLE ai_conversation_summary (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    start_sequence BIGINT NOT NULL,
    end_sequence BIGINT NOT NULL,
    summary_text VARCHAR(4000) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_summary_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT uk_ai_summary_revision UNIQUE (conversation_id, revision)
);
CREATE INDEX idx_ai_summary_owner ON ai_conversation_summary(owner_user_id, updated_at, id);

-- safe_text 是可进入 USER_PRIVATE 索引的短文本；证据明细和隐藏画像绝不复制到该列。
CREATE TABLE ai_memory (
    id CHAR(36) PRIMARY KEY,
    owner_user_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NULL,
    memory_type VARCHAR(32) NOT NULL,
    safe_text VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_revision BIGINT NOT NULL,
    confidence_level VARCHAR(16) NOT NULL,
    access_scope VARCHAR(24) NOT NULL,
    course_id CHAR(36) NULL,
    course_version_id VARCHAR(64) NULL,
    lesson_id CHAR(36) NULL,
    decay_policy VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    last_verified_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_memory_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT uk_ai_memory_owner_text UNIQUE (owner_user_id, memory_type, safe_text)
);
CREATE INDEX idx_ai_memory_projection ON ai_memory(status, source_revision, id);
CREATE INDEX idx_ai_memory_owner_active ON ai_memory(owner_user_id, status, updated_at, id);

-- 证据只保留稳定来源身份、版本和强弱/修正关系，不保存完整作答、成绩、解析或课程正文。
CREATE TABLE ai_memory_evidence (
    id CHAR(36) PRIMARY KEY,
    memory_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id CHAR(36) NOT NULL,
    source_revision BIGINT NOT NULL,
    evidence_strength VARCHAR(16) NOT NULL,
    relation_type VARCHAR(16) NOT NULL,
    effective TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_memory_evidence_memory FOREIGN KEY (memory_id) REFERENCES ai_memory(id),
    CONSTRAINT uk_ai_memory_evidence_source UNIQUE (memory_id, source_type, source_id, source_revision)
);

CREATE TABLE ai_profile_topic (
    id CHAR(36) PRIMARY KEY,
    owner_user_id CHAR(36) NOT NULL,
    topic_key VARCHAR(120) NOT NULL,
    current_level VARCHAR(24) NOT NULL,
    confidence_level VARCHAR(16) NOT NULL,
    latest_evidence_revision BIGINT NOT NULL,
    last_verified_at TIMESTAMP(6) NOT NULL,
    historical_mastery TINYINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ai_profile_topic UNIQUE (owner_user_id, topic_key)
);

CREATE TABLE ai_preference (
    id CHAR(36) PRIMARY KEY,
    owner_user_id CHAR(36) NOT NULL,
    preference_kind VARCHAR(32) NOT NULL,
    preference_value VARCHAR(80) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ai_preference_kind UNIQUE (owner_user_id, preference_kind)
);

-- run_id 与 report_id 双重唯一，保证重放不重复计量；阶段耗时仍是有界私有 JSON。
CREATE TABLE ai_usage_record (
    id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    report_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    model_calls INT NOT NULL,
    vision_calls INT NOT NULL,
    tool_attempts INT NOT NULL,
    prompt_tokens BIGINT NOT NULL,
    completion_tokens BIGINT NOT NULL,
    phase_durations_json LONGTEXT NOT NULL,
    termination_reason VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_usage_run FOREIGN KEY (run_id) REFERENCES ai_run(id),
    CONSTRAINT uk_ai_usage_run UNIQUE (run_id),
    CONSTRAINT uk_ai_usage_report UNIQUE (report_id)
);

CREATE TABLE ai_safety_event (
    id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NULL,
    owner_user_id CHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    safe_reason VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_safety_run FOREIGN KEY (run_id) REFERENCES ai_run(id)
);
CREATE INDEX idx_ai_safety_owner ON ai_safety_event(owner_user_id, created_at, id);

-- 事件正文仅含冻结信封的稳定 ID/revision/最小授权元数据；与记忆状态变更同事务提交。
CREATE TABLE ai_agent_index_outbox (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    source_revision BIGINT NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(120) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ai_outbox_entity_revision UNIQUE (event_type, aggregate_id, source_revision)
);
CREATE INDEX idx_ai_outbox_pending ON ai_agent_index_outbox(status, next_attempt_at, occurred_at);
