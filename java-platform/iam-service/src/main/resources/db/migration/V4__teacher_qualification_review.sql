-- T16.2 向前迁移：每次提交一条轮次事实；既有 teacher_application 保持当前投影以兼容本人查询。
-- 决定和请求 ID 永久留存。retention_until 只控制 IAM 引用释放，Media 在确认无其他引用及自身宽限期后才删 OSS 对象。
CREATE TABLE teacher_application_round (
    id CHAR(36) NOT NULL PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    round_number INT NOT NULL,
    qualification_media_id CHAR(36) NOT NULL,
    statement VARCHAR(2000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    submitted_at TIMESTAMP(6) NOT NULL,
    first_viewed_by CHAR(36) NULL,
    first_viewed_at TIMESTAMP(6) NULL,
    retention_until TIMESTAMP(6) NULL,
    bound_enqueued_at TIMESTAMP(6) NULL,
    release_enqueued_at TIMESTAMP(6) NULL,
    UNIQUE KEY uk_teacher_round_number (application_id, round_number),
    KEY idx_teacher_round_retention (retention_until, release_enqueued_at, id),
    KEY idx_teacher_round_backfill (bound_enqueued_at, id),
    CONSTRAINT fk_teacher_round_application FOREIGN KEY (application_id) REFERENCES teacher_application (id),
    CONSTRAINT chk_teacher_round_status CHECK (status IN ('SUBMITTED','UNDER_REVIEW','APPROVED','REJECTED'))
);

-- 已存在的 T12D 申请回填为首轮；对旧驳回数据以原更新时间近似决定时间并开始保留期。
INSERT INTO teacher_application_round (id, application_id, round_number, qualification_media_id,
    statement, status, submitted_at, retention_until)
SELECT UUID(), id, 1, qualification_media_id, statement, status, submitted_at,
    CASE WHEN status = 'REJECTED' THEN TIMESTAMPADD(DAY, 7, updated_at) ELSE NULL END
FROM teacher_application;

CREATE TABLE teacher_review_decision (
    id CHAR(36) NOT NULL PRIMARY KEY,
    round_id CHAR(36) NOT NULL,
    application_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    decision VARCHAR(12) NOT NULL,
    rejection_reason VARCHAR(1000) NULL,
    decided_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_teacher_decision_round (round_id),
    KEY idx_teacher_decision_application (application_id, decided_at),
    CONSTRAINT fk_teacher_decision_round FOREIGN KEY (round_id) REFERENCES teacher_application_round (id),
    CONSTRAINT chk_teacher_decision CHECK (decision IN ('APPROVED','REJECTED'))
);

CREATE TABLE teacher_review_command (
    client_request_id CHAR(36) NOT NULL PRIMARY KEY,
    application_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    decision_id CHAR(36) NOT NULL,
    result_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_teacher_review_command_decision FOREIGN KEY (decision_id) REFERENCES teacher_review_decision (id)
);

-- 同一事务写入资质引用及最小审核结果事件；FAILED 留痕人工修复，不删除已提交事实。
CREATE TABLE iam_outbox_event (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    event_version INT NOT NULL,
    exchange_name VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(100) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    KEY idx_iam_outbox_due (status, next_attempt_at, created_at),
    CONSTRAINT chk_iam_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

CREATE INDEX idx_teacher_application_review_list ON teacher_application (status, submitted_at, id);
