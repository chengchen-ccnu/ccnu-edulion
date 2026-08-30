-- T12D 向前迁移：MySQL 保存账号生命周期、唯一身份、协议、会话版本、教师申请与安全审计事实。
-- Redis 仅保存带 TTL 的验证码/冷却/锁定/限流和 sessionVersion 缓存，不能替代以下约束与事务。

ALTER TABLE `user` ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE `user` ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

-- T12D 起短期锁定由 Redis TTL 统一管理；存量 LOCKED 是旧的非 TTL 状态，迁移为可由新计数器重新管理的 ACTIVE。
UPDATE `user` SET status = 'ACTIVE' WHERE status = 'LOCKED';

ALTER TABLE refresh_session ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE refresh_session ADD COLUMN device_id CHAR(36) NULL;
ALTER TABLE refresh_session ADD COLUMN device_name VARCHAR(120) NOT NULL DEFAULT '未知设备';
ALTER TABLE refresh_session ADD COLUMN last_used_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

CREATE TABLE user_identity (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    identity_type VARCHAR(16) NOT NULL,
    normalized_value VARCHAR(254) NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identity_value (identity_type, normalized_value),
    KEY idx_user_identity_user (user_id),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_user_identity_type CHECK (identity_type IN ('PHONE', 'EMAIL'))
);

CREATE TABLE registration_request (
    client_request_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    account VARCHAR(128) NOT NULL,
    identity_type VARCHAR(16) NOT NULL,
    identity_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (client_request_id),
    UNIQUE KEY uk_registration_user (user_id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES `user` (id)
);

CREATE TABLE protocol_agreement (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    document_type VARCHAR(20) NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    agreed_at TIMESTAMP(6) NOT NULL,
    remote_address VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_protocol_user_document_version (user_id, document_type, document_version),
    CONSTRAINT fk_protocol_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_protocol_document CHECK (document_type IN ('TERMS', 'PRIVACY'))
);

CREATE TABLE teacher_application (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    qualification_media_id CHAR(36) NOT NULL,
    statement VARCHAR(2000) NOT NULL,
    status VARCHAR(24) NOT NULL,
    rejection_reason VARCHAR(1000) NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teacher_application_user (user_id),
    CONSTRAINT fk_teacher_application_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_teacher_application_status CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED'))
);

CREATE TABLE teacher_application_command (
    client_request_id CHAR(36) NOT NULL,
    application_id CHAR(36) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (client_request_id),
    CONSTRAINT fk_teacher_command_application FOREIGN KEY (application_id) REFERENCES teacher_application (id)
);

CREATE TABLE account_security_audit (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NULL,
    actor_id CHAR(36) NULL,
    action VARCHAR(80) NOT NULL,
    reason VARCHAR(500) NULL,
    remote_address VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_security_audit_user_time (user_id, occurred_at),
    KEY idx_security_audit_actor_time (actor_id, occurred_at),
    CONSTRAINT fk_security_audit_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_security_audit_actor FOREIGN KEY (actor_id) REFERENCES `user` (id)
);

CREATE TABLE admin_account_command (
    actor_id CHAR(36) NOT NULL,
    client_request_id CHAR(36) NOT NULL,
    target_user_id CHAR(36) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resulting_revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (actor_id, client_request_id),
    KEY idx_admin_command_target (target_user_id, created_at),
    CONSTRAINT fk_admin_command_actor FOREIGN KEY (actor_id) REFERENCES `user` (id),
    CONSTRAINT fk_admin_command_target FOREIGN KEY (target_user_id) REFERENCES `user` (id)
);
