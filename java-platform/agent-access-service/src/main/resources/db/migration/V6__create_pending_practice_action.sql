-- T14.3 待确认动作以 MySQL 为事实源。candidate_json 只保存题目/版本/类型/难度，不保存题干、答案或模型输出。
CREATE TABLE ai_pending_action (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    source_run_id CHAR(36) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    criteria_json VARCHAR(1000) NOT NULL,
    candidate_json VARCHAR(8000) NOT NULL,
    requested_count INT NOT NULL,
    actual_count INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    pending_slot TINYINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    confirmation_request_id CHAR(36),
    cancellation_request_id CHAR(36),
    autonomous_practice_id CHAR(36),
    assessment_id CHAR(36),
    start_route VARCHAR(120),
    failure_code VARCHAR(80),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_pending_action_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id),
    CONSTRAINT fk_ai_pending_action_run FOREIGN KEY (source_run_id) REFERENCES ai_run(id),
    CONSTRAINT uk_ai_pending_action_run UNIQUE (source_run_id),
    CONSTRAINT uk_ai_pending_action_conversation_slot UNIQUE (conversation_id, pending_slot),
    CONSTRAINT chk_ai_pending_action_type CHECK (action_type = 'CREATE_AUTONOMOUS_PRACTICE'),
    CONSTRAINT chk_ai_pending_action_status CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED','EXECUTING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_ai_pending_action_counts CHECK (requested_count BETWEEN 1 AND 20 AND actual_count BETWEEN 0 AND requested_count),
    CONSTRAINT chk_ai_pending_action_slot CHECK ((status='PENDING' AND pending_slot=1) OR (status<>'PENDING' AND pending_slot IS NULL))
);
CREATE INDEX idx_ai_pending_action_owner ON ai_pending_action(owner_user_id, updated_at, id);
CREATE INDEX idx_ai_pending_action_expiry ON ai_pending_action(status, expires_at, id);

-- 审计只记录动作状态和稳定 ID，不记录候选正文、答案、提示词、Token 或完整模型输出。
CREATE TABLE ai_pending_action_audit (
    id CHAR(36) PRIMARY KEY,
    action_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    request_id CHAR(36),
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_ai_pending_action_audit_action FOREIGN KEY (action_id) REFERENCES ai_pending_action(id)
);
CREATE INDEX idx_ai_pending_action_audit_action ON ai_pending_action_audit(action_id, occurred_at, id);
