-- T09A 向前扩展：历史 JSON 任务、来源幂等、复核摘要及不可变版本的有序子题。
-- 不保存绝对目录、外部图片 URL、原始 JSON 或签名 URL；复核只记录安全问题码和来源哈希。

ALTER TABLE question_import_task MODIFY COLUMN source_media_id CHAR(36) NULL;
ALTER TABLE question_import_task MODIFY COLUMN source_format VARCHAR(20) NOT NULL;
ALTER TABLE question_import_task ADD COLUMN source_system VARCHAR(80);
ALTER TABLE question_import_task ADD COLUMN source_root_fingerprint CHAR(64);
ALTER TABLE question_import_task ADD COLUMN dry_run BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE question_import_task ADD COLUMN max_items INT;
ALTER TABLE question_import_task ADD COLUMN review_count INT NOT NULL DEFAULT 0;
ALTER TABLE question_import_task ADD COLUMN skipped_count INT NOT NULL DEFAULT 0;
ALTER TABLE question_import_task ADD COLUMN cleaned_at TIMESTAMP(6);

CREATE TABLE question_legacy_import_batch (
    import_task_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    final_batch BOOLEAN NOT NULL,
    processed_count INT NOT NULL,
    imported_count INT NOT NULL,
    valid_count INT NOT NULL,
    review_count INT NOT NULL,
    skipped_count INT NOT NULL,
    failed_count INT NOT NULL,
    result_json VARCHAR(10000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (import_task_id, batch_id),
    CONSTRAINT fk_legacy_batch_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id)
);

CREATE TABLE question_legacy_source_ref (
    id CHAR(36) PRIMARY KEY,
    import_task_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    source_system VARCHAR(80) NOT NULL,
    external_id VARCHAR(160) NOT NULL,
    dry_run BOOLEAN NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    relative_path_sha256 CHAR(64) NOT NULL,
    path_edition VARCHAR(100),
    json_edition VARCHAR(100),
    path_grade VARCHAR(100),
    json_grade VARCHAR(100),
    original_difficulty VARCHAR(40),
    outcome VARCHAR(24) NOT NULL,
    question_id CHAR(36),
    issue_codes_json VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_legacy_source UNIQUE (owner_id, source_system, external_id, dry_run),
    CONSTRAINT fk_legacy_source_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id),
    CONSTRAINT fk_legacy_source_question FOREIGN KEY (question_id) REFERENCES question_aggregate(id)
);

CREATE INDEX idx_legacy_source_task ON question_legacy_source_ref(import_task_id, outcome, created_at);

CREATE TABLE question_legacy_import_issue (
    id CHAR(36) PRIMARY KEY,
    import_task_id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    external_id VARCHAR(160) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    issue_code VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_legacy_issue UNIQUE (import_task_id, batch_id, external_id, issue_code),
    CONSTRAINT fk_legacy_issue_task FOREIGN KEY (import_task_id) REFERENCES question_import_task(id)
);

CREATE TABLE question_version_child (
    parent_question_version_id CHAR(36) NOT NULL,
    child_question_id CHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    PRIMARY KEY (parent_question_version_id, sequence_no),
    CONSTRAINT uk_question_version_child UNIQUE (parent_question_version_id, child_question_id),
    CONSTRAINT fk_question_child_parent_version FOREIGN KEY (parent_question_version_id) REFERENCES question_version(id),
    CONSTRAINT fk_question_child_aggregate FOREIGN KEY (child_question_id) REFERENCES question_aggregate(id)
);
