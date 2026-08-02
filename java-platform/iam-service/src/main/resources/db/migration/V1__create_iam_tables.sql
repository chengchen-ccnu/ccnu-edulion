-- T04 iam-service 首个向前迁移：创建账号、角色权限、刷新会话与登录审计表。
-- 迁移不可逆且只在 IAM Schema 内建外键；后续结构变化必须新增 Flyway 版本，不能回写本文件。

CREATE TABLE `user` (
    id CHAR(36) NOT NULL,
    account VARCHAR(128) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    avatar_url VARCHAR(512) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account (account),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED'))
);

CREATE TABLE `role` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
);

CREATE TABLE permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
);

CREATE TABLE user_role (
    user_id CHAR(36) NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES `role` (id)
);

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES `role` (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);

CREATE TABLE refresh_session (
    id CHAR(36) NOT NULL,
    family_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    parent_id CHAR(36) NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    rotated_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    -- 原始 Refresh Token 不落库；摘要唯一键同时支持查找与重放/并发轮换判定。
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    -- family_id 把连续轮换会话串成令牌族，发现旧令牌重放时可整体撤销。
    KEY idx_refresh_family (family_id),
    KEY idx_refresh_user (user_id),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_refresh_parent FOREIGN KEY (parent_id) REFERENCES refresh_session (id),
    CONSTRAINT chk_refresh_status CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))
);

CREATE TABLE login_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account VARCHAR(128) NOT NULL,
    user_id CHAR(36) NULL,
    outcome VARCHAR(24) NOT NULL,
    remote_address VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 登录限流按归一化账号、来源地址和时间窗统计失败次数。
    KEY idx_login_audit_rate_limit (account, remote_address, occurred_at),
    CONSTRAINT fk_login_audit_user FOREIGN KEY (user_id) REFERENCES `user` (id)
);
