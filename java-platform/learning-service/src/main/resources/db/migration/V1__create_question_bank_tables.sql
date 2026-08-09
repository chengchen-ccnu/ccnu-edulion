-- T09 learning-service 初中数学题库。所有外键均留在 learning Schema 内，不引用 IAM、课程或媒体表。
CREATE TABLE knowledge_point (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    parent_id VARCHAR(36) NULL,
    stage VARCHAR(20) NOT NULL,
    grade VARCHAR(20) NOT NULL,
    subject VARCHAR(30) NOT NULL,
    sort_order INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_knowledge_parent FOREIGN KEY (parent_id) REFERENCES knowledge_point(id)
);
CREATE INDEX idx_knowledge_tree ON knowledge_point(subject, stage, grade, enabled, parent_id, sort_order, code);

CREATE TABLE question_aggregate (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_version_id VARCHAR(36) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_question_owner_page ON question_aggregate(owner_id, updated_at, id);
CREATE INDEX idx_question_owner_status ON question_aggregate(owner_id, status, updated_at, id);

-- 版本行只允许 INSERT；应用和迁移均不提供 UPDATE 历史版本的语句。
CREATE TABLE question_version (
    id VARCHAR(36) PRIMARY KEY,
    question_id VARCHAR(36) NOT NULL,
    version_number INT NOT NULL,
    question_type VARCHAR(30) NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    stage VARCHAR(20) NOT NULL,
    grade VARCHAR(20) NOT NULL,
    subject VARCHAR(30) NOT NULL,
    stem_md TEXT NOT NULL,
    options_json TEXT NOT NULL,
    answers_json TEXT NOT NULL,
    analysis_md TEXT NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_question_version_aggregate FOREIGN KEY (question_id) REFERENCES question_aggregate(id),
    CONSTRAINT uk_question_version_number UNIQUE (question_id, version_number)
);
CREATE INDEX idx_question_filter ON question_version(question_type, difficulty, grade, subject, question_id);

CREATE TABLE question_version_knowledge_point (
    question_version_id VARCHAR(36) NOT NULL,
    knowledge_point_id VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    PRIMARY KEY (question_version_id, knowledge_point_id),
    CONSTRAINT fk_qvkp_version FOREIGN KEY (question_version_id) REFERENCES question_version(id),
    CONSTRAINT fk_qvkp_knowledge FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point(id)
);
CREATE INDEX idx_question_knowledge_filter ON question_version_knowledge_point(knowledge_point_id, question_version_id);

CREATE TABLE question_version_media (
    id VARCHAR(36) PRIMARY KEY,
    question_version_id VARCHAR(36) NOT NULL,
    media_id VARCHAR(36) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    sequence_no INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_qvm_version FOREIGN KEY (question_version_id) REFERENCES question_version(id),
    CONSTRAINT uk_qvm_order UNIQUE (question_version_id, purpose, sequence_no),
    CONSTRAINT uk_qvm_media UNIQUE (question_version_id, media_id, purpose)
);

CREATE TABLE question_idempotency_request (
    owner_id VARCHAR(36) NOT NULL,
    client_request_id VARCHAR(36) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_id, client_request_id)
);

CREATE TABLE question_import_task (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    client_request_id VARCHAR(36) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    source_media_id VARCHAR(36) NOT NULL,
    source_format VARCHAR(10) NOT NULL,
    status VARCHAR(30) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    total_rows INT NOT NULL DEFAULT 0,
    processed_rows INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    failure_code VARCHAR(80) NULL,
    failure_message VARCHAR(500) NULL,
    retain_until TIMESTAMP(6) NOT NULL,
    source_released_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_import_idempotency UNIQUE (owner_id, client_request_id)
);
CREATE INDEX idx_import_owner_page ON question_import_task(owner_id, created_at, id);
CREATE INDEX idx_import_retention ON question_import_task(status, retain_until, source_released_at);

CREATE TABLE question_import_row_error (
    id VARCHAR(36) PRIMARY KEY,
    import_task_id VARCHAR(36) NOT NULL,
    row_number INT NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_import_error_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id)
);
CREATE INDEX idx_import_error_row ON question_import_row_error(import_task_id, row_number, id);

CREATE TABLE learning_outbox_event (
    event_id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    event_version INT NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    exchange_name VARCHAR(120) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(200) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_learning_outbox_pending ON learning_outbox_event(status, next_attempt_at, occurred_at);

CREATE TABLE learning_consumed_event (
    event_id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL
);
