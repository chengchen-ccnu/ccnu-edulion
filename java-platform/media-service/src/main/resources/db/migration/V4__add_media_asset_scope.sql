-- T10B 向前扩展：系统题图片由管理员持有，但允许已登录用户只读；既有媒体仍为 TEACHER 私有资产。
ALTER TABLE media_asset
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEACHER' AFTER owner_id;
ALTER TABLE media_asset
    ADD CONSTRAINT chk_media_asset_scope CHECK (scope IN ('SYSTEM', 'TEACHER'));
CREATE INDEX idx_media_asset_scope_status ON media_asset(scope, status, media_id);
