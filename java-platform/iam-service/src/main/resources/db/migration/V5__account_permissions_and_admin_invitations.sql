-- 只向前演进 IAM 本域。账号能力水位和暂停周期用于跨服务幂等，不保存课程/报名事实。
ALTER TABLE `user` ADD COLUMN access_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE `user` ADD COLUMN suspension_id CHAR(36) NULL;
ALTER TABLE `user` ADD COLUMN course_paused BOOLEAN NOT NULL DEFAULT FALSE;

-- 预览绑定操作者/目标/revision及五分钟有效期；课程ID只是内部API的确认快照，无跨域外键。
CREATE TABLE account_permission_preview (
    id CHAR(36) PRIMARY KEY, actor_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
    action VARCHAR(32) NOT NULL, expected_revision BIGINT NOT NULL,
    course_ids_json TEXT NOT NULL, affected_students BIGINT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL
);
-- 本地决定与协调状态同事务；网络尝试持久计数，崩溃也占一次预算，最多六次。
CREATE TABLE account_permission_command (
    id CHAR(36) PRIMARY KEY, actor_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
    request_json TEXT NOT NULL, action VARCHAR(32) NOT NULL, decided_revision BIGINT NOT NULL,
    access_revision BIGINT NOT NULL, suspension_id CHAR(36) NULL, desired_paused BOOLEAN NULL,
    coordination_status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
    retry_ceiling INT NOT NULL DEFAULT 3, affected_courses INT NOT NULL,
    last_error_code VARCHAR(80) NULL, next_attempt_at TIMESTAMP(6) NOT NULL,
    trace_id VARCHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_account_coordination_status CHECK (coordination_status IN ('NOT_REQUIRED','PENDING','FAILED','APPLIED')),
    CONSTRAINT chk_account_coordination_attempts CHECK (attempts BETWEEN 0 AND 6 AND retry_ceiling BETWEEN 3 AND 6)
);
CREATE INDEX idx_account_coordination_due ON account_permission_command(coordination_status,next_attempt_at);
CREATE INDEX idx_account_coordination_user ON account_permission_command(user_id,coordination_status);
-- 手工补偿请求幂等，不能重复增加预算；预算最终仍上限六次。
CREATE TABLE account_permission_retry (
    id CHAR(36) PRIMARY KEY, actor_id CHAR(36) NOT NULL, command_id CHAR(36) NOT NULL,
    expected_attempts INT NOT NULL
);

-- 邮箱邀请只保存32字节随机秘密的HMAC；数据库泄露不能直接接受邀请，原秘密只发送于邮件正文。
CREATE TABLE administrator_invitation (
    id CHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL, secret_digest CHAR(64) NOT NULL,
    invited_by CHAR(36) NULL, bootstrap BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status VARCHAR(16) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
    send_attempts INT NOT NULL DEFAULT 1, expires_at TIMESTAMP(6) NOT NULL,
    resend_after TIMESTAMP(6) NOT NULL, accepted_by CHAR(36) NULL, accepted_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_admin_invitation_delivery CHECK (delivery_status IN ('PENDING','SENT','FAILED')),
    CONSTRAINT chk_admin_invitation_attempts CHECK (send_attempts BETWEEN 1 AND 6)
);
-- 请求ID全局唯一；规范参数支持精确重放，不包含密码或邮件原秘密。
CREATE TABLE administrator_invitation_command (
    id CHAR(36) PRIMARY KEY, actor_id CHAR(36) NULL, invitation_id CHAR(36) NOT NULL,
    request_json TEXT NOT NULL
);
-- ADMIN角色行作为并发锁，必须在所有管理员创建/停用/解封路径中先锁定；不新增管理员等级。
INSERT INTO `role`(code,name) VALUES('ADMIN','ADMIN') ON DUPLICATE KEY UPDATE name=name;
INSERT INTO permission(code,name) VALUES('administration:access','administration:access') ON DUPLICATE KEY UPDATE name=name;
INSERT IGNORE INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM `role` r CROSS JOIN permission p WHERE r.code='ADMIN' AND p.code='administration:access';
