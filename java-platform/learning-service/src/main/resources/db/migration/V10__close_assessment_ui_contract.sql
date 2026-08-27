-- T11A 只向前补齐考试时间、错题掌握与有限重做事实；不改写 V9，不建立跨 Schema 外键。
-- deadline_at 在开始作答时由服务端冻结，浏览器倒计时只是展示，不能延长它。

ALTER TABLE assessment ADD COLUMN opens_at TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN closes_at TIMESTAMP(6) NULL;
ALTER TABLE assessment ADD COLUMN time_limit_seconds INT NULL;
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_window CHECK (opens_at IS NULL OR closes_at IS NULL OR opens_at < closes_at);
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_time_limit CHECK (time_limit_seconds IS NULL OR time_limit_seconds BETWEEN 60 AND 28800);
ALTER TABLE assessment
    ADD CONSTRAINT chk_assessment_timing_type CHECK (
        assessment_type = 'EXAM'
        OR (opens_at IS NULL AND closes_at IS NULL AND time_limit_seconds IS NULL)
    );

ALTER TABLE assessment_attempt ADD COLUMN started_at TIMESTAMP(6) NULL;
ALTER TABLE assessment_attempt ADD COLUMN deadline_at TIMESTAMP(6) NULL;

UPDATE assessment_attempt SET started_at = created_at WHERE started_at IS NULL;

-- 历史行已回填；保持列可空可兼容 H2/MySQL 的同一份迁移，所有新 attempt 由仓储强制写入 started_at。

ALTER TABLE wrong_question ADD COLUMN mastery_status VARCHAR(20) NOT NULL DEFAULT 'LEARNING';
ALTER TABLE wrong_question ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE wrong_question ADD COLUMN redo_attempts_used INT NOT NULL DEFAULT 0;
ALTER TABLE wrong_question ADD COLUMN updated_at TIMESTAMP(6) NULL;
ALTER TABLE wrong_question
    ADD CONSTRAINT chk_wrong_mastery_status CHECK (mastery_status IN ('LEARNING', 'MASTERED'));
ALTER TABLE wrong_question
    ADD CONSTRAINT chk_wrong_redo_attempts CHECK (redo_attempts_used BETWEEN 0 AND 3);

UPDATE wrong_question SET updated_at = last_wrong_at WHERE updated_at IS NULL;

-- 历史行已回填；所有后续错题写入路径都会维护 updated_at。

-- 重做是独立的最多三次记录，不修改、重开或复制原不可变 assessment_attempt。
CREATE TABLE wrong_question_redo (
    id CHAR(36) PRIMARY KEY,
    wrong_question_id CHAR(36) NOT NULL,
    learner_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    answer_json LONGTEXT NOT NULL,
    score DECIMAL(10,2),
    correct BOOLEAN,
    submitted_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_wrong_redo_request UNIQUE (learner_id, client_request_id),
    CONSTRAINT uk_wrong_redo_sequence UNIQUE (wrong_question_id, sequence_no),
    CONSTRAINT fk_wrong_redo_question FOREIGN KEY (wrong_question_id) REFERENCES wrong_question(id),
    CONSTRAINT chk_wrong_redo_sequence CHECK (sequence_no BETWEEN 1 AND 3),
    CONSTRAINT chk_wrong_redo_score CHECK (score IS NULL OR score >= 0)
);
