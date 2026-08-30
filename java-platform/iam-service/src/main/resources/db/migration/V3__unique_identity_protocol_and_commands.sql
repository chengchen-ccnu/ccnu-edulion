-- T12D.1 向前迁移：补齐每账号每类型唯一身份、不可变协议正文和身份变更幂等事实。
-- 既有 V1/V2 不得改写；Redis 仍只保存 CAPTCHA、一次性验证码、失败计数、锁定和会话版本短期状态。

ALTER TABLE user_identity
    ADD CONSTRAINT uk_user_identity_user_type UNIQUE (user_id, identity_type);

ALTER TABLE registration_request
    ADD COLUMN request_fingerprint CHAR(64) NULL;

CREATE TABLE protocol_document (
    id CHAR(36) NOT NULL,
    document_type VARCHAR(20) NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    body TEXT NOT NULL,
    effective_at TIMESTAMP(6) NOT NULL,
    is_current BOOLEAN NOT NULL,
    requires_reconsent BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_protocol_document_version (document_type, document_version),
    KEY idx_protocol_document_current (document_type, is_current, effective_at),
    CONSTRAINT chk_protocol_document_type CHECK (document_type IN ('TERMS', 'PRIVACY'))
);

CREATE TABLE identity_change_command (
    client_request_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    command_type VARCHAR(24) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    resulting_revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (client_request_id),
    KEY idx_identity_change_user (user_id, created_at),
    CONSTRAINT fk_identity_change_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT chk_identity_change_type CHECK (command_type IN ('BIND', 'REPLACE', 'UNBIND'))
);
