-- T07 media-service 首个向前迁移：创建上传、资产、处理、引用、Outbox、消费幂等与审计表。
-- 所有外部领域关系只保存稳定 ID，不创建跨 Schema 外键；该迁移不可回写修改，后续只能新增版本向前演进。

CREATE TABLE upload_session (
    upload_id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    upload_mode VARCHAR(20) NOT NULL,
    status VARCHAR(24) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    extension VARCHAR(20) NOT NULL,
    declared_mime_type VARCHAR(127) NOT NULL,
    declared_size BIGINT NOT NULL,
    declared_sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    provider_upload_id VARCHAR(256),
    part_size BIGINT,
    completion_fingerprint CHAR(64),
    media_id CHAR(36),
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_upload_owner_request UNIQUE (owner_id, client_request_id),
    CONSTRAINT uk_upload_object_key UNIQUE (object_key),
    CONSTRAINT uk_upload_media UNIQUE (media_id)
);

CREATE INDEX idx_upload_expiry ON upload_session(status, expires_at);
CREATE INDEX idx_upload_owner_created ON upload_session(owner_id, created_at);

CREATE TABLE upload_part (
    upload_id CHAR(36) NOT NULL,
    part_number INT NOT NULL,
    content_length BIGINT NOT NULL,
    content_md5 VARCHAR(64),
    etag VARCHAR(128),
    authorized_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (upload_id, part_number),
    CONSTRAINT fk_upload_part_session FOREIGN KEY (upload_id) REFERENCES upload_session(upload_id)
);

CREATE TABLE media_asset (
    media_id CHAR(36) PRIMARY KEY,
    upload_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    processing_status VARCHAR(20) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    etag VARCHAR(128),
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    unbound_expires_at TIMESTAMP(6),
    deleted_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_media_upload UNIQUE (upload_id),
    CONSTRAINT uk_media_object_key UNIQUE (object_key)
);

CREATE INDEX idx_media_owner_created ON media_asset(owner_id, created_at);
CREATE INDEX idx_media_reclaim ON media_asset(status, unbound_expires_at);

CREATE TABLE media_variant (
    variant_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36) NOT NULL,
    variant_name VARCHAR(80) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_media_variant UNIQUE (media_id, variant_name),
    CONSTRAINT uk_variant_object_key UNIQUE (object_key),
    CONSTRAINT fk_variant_media FOREIGN KEY (media_id) REFERENCES media_asset(media_id)
);

CREATE TABLE processing_job (
    job_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36) NOT NULL,
    job_type VARCHAR(40) NOT NULL,
    preset_version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    worker_key VARCHAR(120),
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_processing_job UNIQUE (media_id, job_type, preset_version),
    CONSTRAINT fk_job_media FOREIGN KEY (media_id) REFERENCES media_asset(media_id)
);

CREATE TABLE media_reference (
    reference_record_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36) NOT NULL,
    owner_service VARCHAR(40) NOT NULL,
    reference_type VARCHAR(80) NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    bound_event_id CHAR(36) NOT NULL,
    released_event_id CHAR(36),
    bound_at TIMESTAMP(6) NOT NULL,
    released_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_media_reference UNIQUE (media_id, owner_service, reference_type, reference_id)
);

CREATE INDEX idx_reference_media_status ON media_reference(media_id, status);

CREATE TABLE outbox_event (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    event_version INT NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    last_error VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_outbox_pending ON outbox_event(status, next_attempt_at, created_at);

CREATE TABLE consumed_event (
    event_id CHAR(36) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE media_audit_record (
    audit_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36),
    upload_id CHAR(36),
    actor_id CHAR(36),
    action VARCHAR(80) NOT NULL,
    from_status VARCHAR(24),
    to_status VARCHAR(24),
    trace_id VARCHAR(64) NOT NULL,
    details VARCHAR(500),
    occurred_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_media_audit_media ON media_audit_record(media_id, occurred_at);
CREATE INDEX idx_media_audit_upload ON media_audit_record(upload_id, occurred_at);

CREATE TABLE access_grant_audit (
    grant_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36) NOT NULL,
    actor_id CHAR(36) NOT NULL,
    variant_name VARCHAR(80) NOT NULL,
    disposition VARCHAR(20) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_access_grant_media ON access_grant_audit(media_id, created_at);
