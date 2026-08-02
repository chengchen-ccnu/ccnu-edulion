-- T08A media-service 向前迁移：记录 HLS 播放清单的私有切片组件，以及短期授权产生的临时清单对象。
-- 组件和临时对象都只保存精确 Object Key 以便幂等清理；任何签名 URL 均不得写入数据库。

CREATE TABLE media_variant_component (
    component_id CHAR(36) PRIMARY KEY,
    variant_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    component_name VARCHAR(120) NOT NULL,
    position INT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_variant_component_position UNIQUE (variant_id, position),
    CONSTRAINT uk_variant_component_name UNIQUE (variant_id, component_name),
    CONSTRAINT uk_variant_component_object_key UNIQUE (object_key),
    CONSTRAINT fk_variant_component_variant FOREIGN KEY (variant_id) REFERENCES media_variant(variant_id),
    CONSTRAINT fk_variant_component_media FOREIGN KEY (media_id) REFERENCES media_asset(media_id),
    CONSTRAINT chk_variant_component_position CHECK (position > 0)
);

CREATE INDEX idx_variant_component_media ON media_variant_component(media_id, variant_id, position);

CREATE TABLE temporary_access_object (
    grant_id CHAR(36) PRIMARY KEY,
    media_id CHAR(36) NOT NULL,
    variant_name VARCHAR(80) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_temporary_access_object_key UNIQUE (object_key),
    CONSTRAINT fk_temporary_access_grant FOREIGN KEY (grant_id) REFERENCES access_grant_audit(grant_id),
    CONSTRAINT fk_temporary_access_media FOREIGN KEY (media_id) REFERENCES media_asset(media_id)
);

CREATE INDEX idx_temporary_access_expiry ON temporary_access_object(deleted_at, expires_at);
