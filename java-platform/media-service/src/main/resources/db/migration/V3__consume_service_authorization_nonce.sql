-- T08A.1 media-service 向前迁移：持久化已消费的服务授权 jti，以原子唯一约束拒绝重放。
-- 仅保存随机令牌标识、调用方、访问者和到期时间；不保存 JWT、签名 URL、课程事实或 OSS 元数据。

CREATE TABLE consumed_service_authorization (
    token_id CHAR(36) PRIMARY KEY,
    issuer VARCHAR(120) NOT NULL,
    audience VARCHAR(120) NOT NULL,
    visitor_id CHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_service_authorization_expiry
    ON consumed_service_authorization(expires_at);
